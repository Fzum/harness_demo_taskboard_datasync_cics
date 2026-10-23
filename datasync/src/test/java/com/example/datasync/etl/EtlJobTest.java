package com.example.datasync.etl;

import static com.example.datasync.HostRecords.LAST_UPDATE;
import static com.example.datasync.HostRecords.hostRecord;
import static com.example.datasync.HostRecords.taskboardRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.datasync.DatasyncTest;
import com.example.datasync.HostStub;
import com.example.datasync.TaskboardDatabase;
import com.example.datasync.host.HostKey;
import com.example.datasync.host.HostUnavailableException;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.outbox.OutboxStatus;
import com.example.datasync.task.TaskData;
import com.example.datasync.task.TaskPriority;
import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@DatasyncTest
class EtlJobTest {

  private final EtlJob etl;
  private final HostStub host;
  private final TaskboardDatabase db;

  EtlJobTest(EtlJob etl, HostStub host, TaskboardDatabase db) {
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
  void insertsTheHostsTasks() {
    host.put(
        hostRecord("H000001001")
            .title("Clarify returned direct debit")
            .description("The bank returned the October instalment")
            .user("AMUELLER")
            .dueDate(LocalDate.of(2026, 10, 31))
            .line(),
        hostRecord("H000001002").title("Check address change request").line());

    assertThat(etl.run()).isEqualTo(new EtlReport(2, 2, 0, 0, 0, 0, 0, 0));
    long id = db.taskIdOf("H000001001");
    assertThat(db.task(id))
        .isEqualTo(
            new TaskData(
                "H000001001",
                "Clarify returned direct debit",
                "The bank returned the October instalment",
                TaskStatus.OPEN,
                "amueller",
                LocalDate.of(2026, 10, 31),
                TaskSource.HOST,
                TaskPriority.MEDIUM,
                LAST_UPDATE));
  }

  @Test
  void storesCreatedAndUpdatedAtFromTheLastUpdateInUtc() {
    host.put(hostRecord("H000001001").lastUpdate(Instant.parse("2026-10-01T07:42:10Z")).line());

    etl.run();

    var row = db.taskRow(db.taskIdOf("H000001001"));
    LocalDateTime utc = LocalDateTime.of(2026, 10, 1, 7, 42, 10);
    assertThat(((Timestamp) row.get("CREATED_AT")).toLocalDateTime()).isEqualTo(utc);
    assertThat(((Timestamp) row.get("UPDATED_AT")).toLocalDateTime()).isEqualTo(utc);
  }

  @Test
  void updatesATaskWhoseRecordChangedAndCountsTheRestUnchanged() {
    host.put(hostRecord("H000001001").line(), hostRecord("H000001002").line());
    etl.run();
    long id = db.taskIdOf("H000001001");
    Object createdAt = db.taskRow(id).get("CREATED_AT");
    Instant later = LAST_UPDATE.plusSeconds(3600);
    host.put(
        hostRecord("H000001001")
            .title("Clarify two returned direct debits")
            .description("The bank returned the November instalment as well")
            .status(Status.DONE)
            .user("BKRAUSE")
            .dueDate(LocalDate.of(2026, 11, 30))
            .lastUpdate(later)
            .line());

    assertThat(etl.run()).isEqualTo(new EtlReport(2, 0, 1, 1, 0, 0, 0, 0));
    assertThat(db.task(id))
        .isEqualTo(
            new TaskData(
                "H000001001",
                "Clarify two returned direct debits",
                "The bank returned the November instalment as well",
                TaskStatus.DONE,
                "bkrause",
                LocalDate.of(2026, 11, 30),
                TaskSource.HOST,
                TaskPriority.MEDIUM,
                later));
    assertThat(db.taskRow(id).get("CREATED_AT")).isEqualTo(createdAt);
  }

  @Test
  void anUpdateClearsWhatTheHostHasCleared() {
    host.put(
        hostRecord("H000001001")
            .description("The bank returned the October instalment")
            .user("AMUELLER")
            .dueDate(LocalDate.of(2026, 10, 31))
            .line());
    etl.run();
    host.put(hostRecord("H000001001").description("").user("").dueDate(null).line());

    assertThat(etl.run().updated()).isEqualTo(1);
    assertThat(db.task(db.taskIdOf("H000001001")))
        .extracting(TaskData::description, TaskData::assignee, TaskData::dueDate)
        .containsOnlyNulls();
  }

  @Test
  void aSecondRunWithoutHostChangesLeavesEveryTaskUnchanged() {
    host.put(hostRecord("H000001001").line(), hostRecord("H000001002").line());
    etl.run();

    assertThat(etl.run()).isEqualTo(new EtlReport(2, 0, 0, 2, 0, 0, 0, 0));
  }

  @Test
  void statusXDeletesTheTaskAndAnXRecordWithoutTaskChangesNothing() {
    host.put(hostRecord("H000001001").line());
    etl.run();
    host.put(
        hostRecord("H000001001").status(Status.DELETED).line(),
        hostRecord("H000001008").status(Status.DELETED).line());

    assertThat(etl.run()).isEqualTo(new EtlReport(2, 0, 0, 1, 1, 0, 0, 0));
    assertThat(db.count("TASK")).isZero();
  }

  @Test
  void localChangesWinUntilTheyHaveReachedTheHost() {
    host.put(
        hostRecord("H000001001").line(),
        hostRecord("H000001002").line(),
        hostRecord("H000001003").line());
    etl.run();
    long pending = db.taskIdOf("H000001001");
    long failed = db.taskIdOf("H000001002");
    long processed = db.taskIdOf("H000001003");
    db.updateTask(pending, task -> retitled(task, "Pending change"));
    long failedEvent = db.updateTask(failed, task -> retitled(task, "Failed change"));
    db.setEventStatus(failedEvent, OutboxStatus.FAILED, 5, "TSKUPDT failed");
    long processedEvent = db.updateTask(processed, task -> retitled(task, "Processed change"));
    db.setEventStatus(processedEvent, OutboxStatus.PROCESSED, 1, null);

    assertThat(etl.run()).isEqualTo(new EtlReport(3, 0, 1, 0, 0, 2, 0, 0));
    assertThat(db.task(pending).title()).isEqualTo("Pending change");
    assertThat(db.task(failed).title()).isEqualTo("Failed change");
    // once its latest event is PROCESSED, the host's record is the truth again
    assertThat(db.task(processed).title()).isEqualTo("Clarify returned direct debit");
  }

  @Test
  void aPendingDeleteKeepsTheTaskFromComingBack() {
    host.put(hostRecord("H000001001").line());
    etl.run();
    db.deleteTask(db.taskIdOf("H000001001"));

    assertThat(etl.run()).isEqualTo(new EtlReport(1, 0, 0, 0, 0, 1, 0, 0));
    assertThat(db.count("TASK")).isZero();
  }

  @Test
  void aChangeTheRelayDeliversDuringTheExtractIsNotOverwrittenWithTheOlderRecord() {
    host.put(hostRecord("H000001001").line());
    etl.run();
    long id = db.taskIdOf("H000001001");
    long event = db.updateTask(id, task -> retitled(task, "Changed in the Taskboard"));
    // TSKBRWS has read the record before the change when the relay delivers it
    host.whileBrowsing(() -> db.setEventStatus(event, OutboxStatus.PROCESSED, 1, null));

    assertThat(etl.run()).isEqualTo(new EtlReport(1, 0, 0, 0, 0, 1, 0, 0));
    assertThat(db.task(id).title()).isEqualTo("Changed in the Taskboard");
  }

  @Test
  void aTaskboardTaskIsSkippedByItsTaskIdWhileItsPayloadHasNoHostId() {
    long id = db.createTask("Call back");
    db.updateTask(id, task -> retitled(task, "Call back about the invoice"));
    host.put(taskboardRecord(HostKey.forTask(id)).title("Call back").line());

    assertThat(etl.run()).isEqualTo(new EtlReport(1, 0, 0, 0, 0, 1, 0, 0));
    assertThat(db.task(id).title()).isEqualTo("Call back about the invoice");
  }

  @Test
  void aTKeyMatchesTheTaskWithItsIdAndSetsTheHostId() {
    long id =
        db.insertTask(
            new TaskData(
                null,
                "Call back",
                null,
                TaskStatus.OPEN,
                null,
                null,
                TaskSource.TASKBOARD,
                TaskPriority.MEDIUM,
                LAST_UPDATE));
    host.put(taskboardRecord(HostKey.forTask(id)).title("Call back").line());

    assertThat(etl.run()).isEqualTo(new EtlReport(1, 0, 1, 0, 0, 0, 0, 0));
    assertThat(db.task(id).hostId()).isEqualTo(HostKey.forTask(id));
  }

  @Test
  void aTaskboardRecordWithoutTaskIsOrphanedAndCreatesNone() {
    host.put(taskboardRecord("T000000999").line());

    assertThat(etl.run()).isEqualTo(new EtlReport(1, 0, 0, 0, 0, 0, 1, 0));
    assertThat(db.count("TASK")).isZero();
  }

  @Test
  void rejectsInvalidRecordsAndLoadsTheOthers() {
    String valid = hostRecord("H000001001").line();
    host.put(
        valid,
        "H000001002 is far too short",
        overwrite(hostRecord("H000001003").line(), 250, "Q"),
        overwrite(hostRecord("H000001004").line(), 259, "20261332"),
        hostRecord("H000001005").title("").line(),
        hostRecord("H000001006").user("A.MUELLE").line());

    assertThat(etl.run()).isEqualTo(new EtlReport(6, 1, 0, 0, 0, 0, 0, 5));
    assertThat(db.count("TASK")).isEqualTo(1);
  }

  @Test
  void neverWritesTheOutbox() {
    host.put(hostRecord("H000001001").line());
    etl.run();
    host.put(hostRecord("H000001001").status(Status.DONE).line());
    etl.run();
    host.put(hostRecord("H000001001").status(Status.DELETED).line());
    etl.run();

    assertThat(db.count("OUTBOX_EVENT")).isZero();
  }

  @Test
  void failsAsAWholeWhileTheHostIsUnavailable() {
    host.down();

    assertThatThrownBy(etl::run)
        .isInstanceOf(HostUnavailableException.class)
        .hasMessage("TSKBRWS answered HTTP 503: CICS region unavailable");
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
        task.priority(),
        task.updatedAt());
  }

  private static String overwrite(String line, int offset, String value) {
    return line.substring(0, offset) + value + line.substring(offset + value.length());
  }
}
