package com.example.datasync.outbox;

import static com.example.datasync.HostRecords.hostRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.datasync.DatasyncProperties;
import com.example.datasync.DatasyncTest;
import com.example.datasync.HostRecords;
import com.example.datasync.HostStub;
import com.example.datasync.TaskboardDatabase;
import com.example.datasync.etl.EtlJob;
import com.example.datasync.host.CicsHostClient;
import com.example.datasync.host.HostKey;
import com.example.datasync.host.TskRecord;
import com.example.datasync.host.TskRecord.Origin;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.host.TskRecordCodec;
import com.example.datasync.task.TaskData;
import com.example.datasync.task.TaskRepository;
import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@DatasyncTest
class OutboxRelayTest {

  private static final String REGION_DOWN = "TSKUPDT answered HTTP 503: CICS region unavailable";

  private final OutboxRelay relay;
  private final OutboxRepository outbox;
  private final EtlJob etl;
  private final HostStub host;
  private final TaskboardDatabase db;

  OutboxRelayTest(
      OutboxRelay relay, OutboxRepository outbox, EtlJob etl, HostStub host, TaskboardDatabase db) {
    this.relay = relay;
    this.outbox = outbox;
    this.etl = etl;
    this.host = host;
    this.db = db;
  }

  @BeforeEach
  void startEmpty() {
    db.clear();
    host.reset();
  }

  @Test
  void writesANewTaskToTheHostUnderItsTKeyAndSetsTheHostId() {
    long id = db.createTask("Call back about the invoice");
    long eventId = db.eventIdsOf(id).getFirst();
    String key = HostKey.forTask(id);
    Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));
    Instant after = Instant.now();
    assertThat(host.records())
        .containsExactly(
            TskRecordCodec.format(
                new TskRecord(
                    key,
                    "Call back about the invoice",
                    "",
                    Status.OPEN,
                    "",
                    null,
                    Origin.FROM_TASKBOARD,
                    db.task(id).updatedAt())));
    OutboxEvent event = event(eventId);
    assertThat(event)
        .extracting(OutboxEvent::status, OutboxEvent::attempts, OutboxEvent::lastError)
        .containsExactly(OutboxStatus.PROCESSED, 1, null);
    assertThat(event.processedAt()).isBetween(before, after); // stored in UTC, read back as UTC
    assertThat(db.task(id).hostId()).isEqualTo(key);
  }

  @Test
  void writesBackAHostTaskChangedInTheTaskboardWithOnlyTheChange() {
    String original =
        hostRecord("H000001001")
            .title("Clarify returned direct debit")
            .description("The bank returned the October instalment")
            .user("AMUELLER")
            .dueDate(LocalDate.of(2026, 10, 31))
            .line();
    host.put(original);
    etl.run();
    long id = db.taskIdOf("H000001001");
    db.updateTask(id, task -> withStatus(task, TaskStatus.DONE));

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));
    TskRecord written = TskRecordCodec.parse(host.records().getFirst());
    TskRecord before = TskRecordCodec.parse(original);
    assertThat(written)
        .isEqualTo(
            new TskRecord(
                before.id(),
                before.title(),
                before.description(),
                Status.DONE,
                before.user(),
                before.dueDate(),
                before.origin(),
                db.task(id).updatedAt()));
    assertThat(db.task(id).hostId()).isEqualTo("H000001001");
  }

  @Test
  void aDeletionWritesTheTasksLastStateWithStatusX() {
    host.put(hostRecord("H000001001").title("Archive closed claim files").line());
    etl.run();
    db.deleteTask(db.taskIdOf("H000001001"));

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));
    assertThat(TskRecordCodec.parse(host.records().getFirst()))
        .extracting(TskRecord::id, TskRecord::title, TskRecord::status)
        .containsExactly("H000001001", "Archive closed claim files", Status.DELETED);
  }

  @Test
  void transientErrorsKeepTheEventPendingUntilTheLastAttemptFails() {
    long eventId = db.eventIdsOf(db.createTask("Call back")).getFirst();
    host.down();

    for (int attempt = 1; attempt < 5; attempt++) {
      assertThat(relay.run()).isEqualTo(new RelayReport(1, 0, 1, 0, 0));
      assertThat(event(eventId))
          .extracting(OutboxEvent::status, OutboxEvent::attempts, OutboxEvent::lastError)
          .containsExactly(OutboxStatus.PENDING, attempt, REGION_DOWN);
    }
    assertThat(relay.run()).isEqualTo(new RelayReport(1, 0, 0, 1, 0));
    assertThat(event(eventId))
        .extracting(OutboxEvent::status, OutboxEvent::attempts, OutboxEvent::lastError)
        .containsExactly(OutboxStatus.FAILED, 5, REGION_DOWN);
    assertThat(relay.run()).isEqualTo(new RelayReport(0, 0, 0, 0, 0));
  }

  @Test
  void cutsALongErrorToTheLengthOfLastError() {
    long eventId = db.eventIdsOf(db.createTask("Call back")).getFirst();
    host.answer(500, "x".repeat(1500));

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 0, 1, 0, 0));
    assertThat(event(eventId).lastError())
        .startsWith("TSKUPDT answered HTTP 500: xxx")
        .hasSize(1000);
  }

  @Test
  void anEventGoesThroughOnceTheHostIsBack() {
    long eventId = db.eventIdsOf(db.createTask("Call back")).getFirst();
    host.down();
    relay.run();
    relay.run();
    host.up();

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));
    assertThat(event(eventId))
        .extracting(OutboxEvent::status, OutboxEvent::attempts, OutboxEvent::lastError)
        .containsExactly(OutboxStatus.PROCESSED, 3, null);
  }

  @Test
  void permanentErrorsFailTheEventAtOnce() {
    long task = db.createTask("Call back");
    long created = db.eventIdsOf(task).getFirst();
    long unparseable = db.insertEvent(taskWithoutEvents(), "TASK_UPDATED", "{not json");
    long unknownType = eventWithTitle("TASK_ARCHIVED", "Archived");
    long tooLong = eventWithTitle("TASK_UPDATED", "x".repeat(41));
    host.rejectUpdatesOf(HostKey.forTask(task), "TSK-USER is not authorised");

    relay.run();

    assertThat(event(created))
        .extracting(OutboxEvent::status, OutboxEvent::attempts, OutboxEvent::lastError)
        .containsExactly(
            OutboxStatus.FAILED,
            1,
            "TSKUPDT rejected the record: RC=12 TSK-USER is not authorised");
    assertThat(event(unparseable).status()).isEqualTo(OutboxStatus.FAILED);
    assertThat(event(unparseable).lastError()).startsWith("unparseable payload: ");
    assertThat(event(unknownType).lastError()).isEqualTo("unknown event type TASK_ARCHIVED");
    assertThat(event(tooLong).lastError()).isEqualTo("TSK-TITLE has 41 characters, at most 40 fit");
  }

  @Test
  void keepsEachTasksChangesInOrderWhileOneOfThemIsDeferred() {
    long blocked = db.createTask("Blocked task");
    long first = db.eventIdsOf(blocked).getFirst();
    long second = db.updateTask(blocked, task -> retitled(task, "Blocked task, edited"));
    long other = db.eventIdsOf(db.createTask("Other task")).getFirst();
    host.failUpdatesOf(HostKey.forTask(blocked));

    assertThat(relay.run()).isEqualTo(new RelayReport(3, 1, 1, 0, 1));
    assertThat(event(first).status()).isEqualTo(OutboxStatus.PENDING);
    assertThat(event(second))
        .extracting(OutboxEvent::status, OutboxEvent::attempts)
        .containsExactly(OutboxStatus.PENDING, 0);
    assertThat(event(other).status()).isEqualTo(OutboxStatus.PROCESSED);
    assertThat(titlesSent()).containsExactly("Blocked task", "Other task");

    host.reset();
    assertThat(relay.run()).isEqualTo(new RelayReport(2, 2, 0, 0, 0));
    assertThat(titlesSent()).containsExactly("Blocked task", "Blocked task, edited");
  }

  @Test
  void aFailedEventDoesNotHoldUpItsTasksLaterChanges() {
    long id = db.createTask("Call back");
    long created = db.eventIdsOf(id).getFirst();
    db.setEventStatus(created, OutboxStatus.FAILED, 5, REGION_DOWN);
    long updated = db.updateTask(id, task -> retitled(task, "Call back about the invoice"));

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));
    assertThat(event(updated).status()).isEqualTo(OutboxStatus.PROCESSED);
    assertThat(titlesSent()).containsExactly("Call back about the invoice");
  }

  @Test
  void aProcessedSnapshotResolvesTheTasksOlderFailedEvents() {
    long id = db.createTask("Call back");
    long created = db.eventIdsOf(id).getFirst();
    db.setEventStatus(created, OutboxStatus.FAILED, 5, REGION_DOWN);
    long updated = db.updateTask(id, task -> retitled(task, "Call back about the invoice"));

    relay.run();

    assertThat(event(created))
        .extracting(OutboxEvent::status, OutboxEvent::attempts, OutboxEvent::lastError)
        .containsExactly(OutboxStatus.PROCESSED, 5, "superseded by event " + updated);
    assertThat(event(created).processedAt()).isNotNull();
  }

  @Test
  void readsAtMostOneBatchPerRun(
      @Autowired TaskRepository tasks,
      @Autowired CicsHostClient client,
      @Autowired TransactionTemplate transaction,
      @Autowired Clock clock,
      @Autowired DatasyncProperties properties) {
    db.createTask("First");
    db.createTask("Second");
    db.createTask("Third");
    var outboxOfTwo = new DatasyncProperties.Outbox(Duration.ofSeconds(30), 2, 5);
    var smallBatches =
        new OutboxRelay(
            outbox,
            tasks,
            client,
            transaction,
            clock,
            new DatasyncProperties(properties.host(), properties.etl(), outboxOfTwo));

    assertThat(smallBatches.run()).isEqualTo(new RelayReport(2, 2, 0, 0, 0));
    assertThat(smallBatches.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));
    assertThat(titlesSent()).containsExactly("First", "Second", "Third");
  }

  private OutboxEvent event(long id) {
    return outbox.findById(id).orElseThrow();
  }

  /** A task without outbox events, as if the relay had processed them all. */
  private long taskWithoutEvents() {
    return db.insertTask(
        new TaskData(
            null,
            "Placeholder",
            null,
            TaskStatus.OPEN,
            null,
            null,
            TaskSource.TASKBOARD,
            HostRecords.LAST_UPDATE));
  }

  /** A PENDING event of a task without other events, with this title in its payload. */
  private long eventWithTitle(String eventType, String title) {
    long id = taskWithoutEvents();
    TaskData task = db.task(id);
    return db.insertEvent(
        id, eventType, TaskboardDatabase.snapshot(id, retitled(task, title), task.updatedAt()));
  }

  private List<String> titlesSent() {
    return host.updates().stream().map(TskRecordCodec::parse).map(TskRecord::title).toList();
  }

  private static TaskData retitled(TaskData task, String title) {
    return new TaskData(
        task.hostId(),
        title,
        task.description(),
        task.status(),
        task.assignee(),
        task.dueDate(),
        task.source(),
        task.updatedAt());
  }

  private static TaskData withStatus(TaskData task, TaskStatus status) {
    return new TaskData(
        task.hostId(),
        task.title(),
        task.description(),
        status,
        task.assignee(),
        task.dueDate(),
        task.source(),
        task.updatedAt());
  }
}
