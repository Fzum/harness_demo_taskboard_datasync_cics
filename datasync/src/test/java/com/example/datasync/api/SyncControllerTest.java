package com.example.datasync.api;

import static com.example.datasync.HostRecords.hostRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.datasync.DatasyncTest;
import com.example.datasync.HostStub;
import com.example.datasync.TaskboardDatabase;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.outbox.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@DatasyncTest
class SyncControllerTest {

  private static final String REGION_DOWN = "TSKBRWS answered HTTP 503: CICS region unavailable";

  private final MockMvcTester mvc;
  private final HostStub host;
  private final TaskboardDatabase db;

  SyncControllerTest(MockMvcTester mvc, HostStub host, TaskboardDatabase db) {
    this.mvc = mvc;
    this.host = host;
    this.db = db;
  }

  @BeforeEach
  void startEmpty() {
    db.clear();
    host.reset();
  }

  @Test
  void statusShowsTheLastRunsTheOutboxByStatusAndTheHostInconsistencies() {
    host.put(hostRecord("H000001001").line());
    assertThat(mvc.post().uri("/api/sync/etl/run")).hasStatusOk();
    assertThat(mvc.post().uri("/api/sync/outbox/run")).hasStatusOk();
    db.createTask("Call back");
    long failed = db.eventIdsOf(db.createTask("Never made it")).getFirst();
    db.setEventStatus(failed, OutboxStatus.FAILED, 5, REGION_DOWN);

    MvcTestResult status = mvc.get().uri("/api/sync/status").exchange();

    assertThat(status)
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "etl": {
                "lastRun": {
                  "outcome": "SUCCEEDED",
                  "error": null,
                  "report": {"read": 1, "inserted": 1, "updated": 0, "unchanged": 0, "deleted": 0,
                             "skipped": 0, "orphaned": 0, "rejected": 0}
                }
              },
              "outbox": {
                "pending": 1,
                "processed": 0,
                "failed": 1,
                "lastRun": {
                  "outcome": "SUCCEEDED",
                  "error": null,
                  "report": {"read": 0, "processed": 0, "deferred": 0, "failed": 0, "skipped": 0}
                }
              },
              "hostInconsistencies": 1
            }
            """);
    assertThat(status).bodyJson().extractingPath("$.etl.lastRun.startedAt").isNotNull();
    assertThat(status).bodyJson().extractingPath("$.etl.lastRun.finishedAt").isNotNull();
  }

  @Test
  void runningTheEtlReturnsTheRunWithItsReport() {
    host.put(
        hostRecord("H000001001").line(), hostRecord("H000001002").status(Status.DELETED).line());

    assertThat(mvc.post().uri("/api/sync/etl/run"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"outcome": "SUCCEEDED", "error": null,
             "report": {"read": 2, "inserted": 1, "unchanged": 1, "rejected": 0}}
            """);
  }

  @Test
  void runningTheEtlWhileTheHostIsDownAnswers503AndTheStatusShowsTheFailedRun() {
    host.down();

    assertThat(mvc.post().uri("/api/sync/etl/run"))
        .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"title": "CICS host unavailable", "status": 503, "detail": "%s"}
            """
                .formatted(REGION_DOWN));
    assertThat(mvc.get().uri("/api/sync/status"))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"etl": {"lastRun": {"outcome": "FAILED", "error": "%s", "report": null}}}
            """
                .formatted(REGION_DOWN));
  }

  @Test
  void runningTheRelayReturnsTheRunWithItsReport() {
    db.createTask("Call back");

    assertThat(mvc.post().uri("/api/sync/outbox/run"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"outcome": "SUCCEEDED",
             "report": {"read": 1, "processed": 1, "deferred": 0, "failed": 0, "skipped": 0}}
            """);
  }

  @Test
  void listsTheHostInconsistenciesAndRetriesOne() {
    long taskId = db.createTask("Call back");
    long eventId = db.eventIdsOf(taskId).getFirst();
    db.setEventStatus(eventId, OutboxStatus.FAILED, 5, REGION_DOWN);

    assertThat(mvc.get().uri("/api/sync/inconsistencies"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            [{"eventId": %d, "taskId": %d, "eventType": "TASK_CREATED", "status": "FAILED",
              "attempts": 5, "lastError": "%s", "processedAt": null}]
            """
                .formatted(eventId, taskId, REGION_DOWN));

    assertThat(mvc.post().uri("/api/sync/inconsistencies/{eventId}/retry", eventId))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"eventId": %d, "status": "PENDING", "attempts": 0}
            """
                .formatted(eventId));
    assertThat(mvc.get().uri("/api/sync/inconsistencies")).bodyJson().isStrictlyEqualTo("[]");

    assertThat(mvc.post().uri("/api/sync/outbox/run"))
        .bodyJson()
        .extractingPath("$.report.processed")
        .isEqualTo(1);
    assertThat(host.records()).hasSize(1);
  }

  @Test
  void retryingAnEventThatIsNoHostInconsistencyAnswers404() {
    long pending = db.eventIdsOf(db.createTask("Call back")).getFirst();

    assertThat(mvc.post().uri("/api/sync/inconsistencies/{eventId}/retry", pending))
        .hasStatus(HttpStatus.NOT_FOUND)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": 404, "detail": "No host inconsistency with event id %d"}
            """
                .formatted(pending));
    assertThat(mvc.post().uri("/api/sync/inconsistencies/999999/retry"))
        .hasStatus(HttpStatus.NOT_FOUND);
  }

  @Test
  void healthCoversTheAppAndItsDatabaseAndStaysUpWhileTheHostIsDown() {
    host.down();

    assertThat(mvc.get().uri("/actuator/health"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "UP", "components": {"db": {"status": "UP"}, "ping": {"status": "UP"}}}
            """);
  }
}
