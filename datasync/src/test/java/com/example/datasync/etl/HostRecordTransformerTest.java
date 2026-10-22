package com.example.datasync.etl;

import static com.example.datasync.HostRecords.LAST_UPDATE;
import static com.example.datasync.HostRecords.hostRecord;
import static com.example.datasync.HostRecords.taskboardRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.datasync.host.InvalidRecordException;
import com.example.datasync.host.TskRecord;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.task.TaskData;
import com.example.datasync.task.TaskPriority;
import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HostRecordTransformerTest {

  @Test
  void transformsARecordIntoTheTaskItDescribes() {
    var record =
        hostRecord("H000001001")
            .title("Clarify returned direct debit")
            .description("The bank returned the October instalment")
            .user("AMUELLER")
            .dueDate(LocalDate.of(2026, 10, 31))
            .record();

    assertThat(HostRecordTransformer.transform(record))
        .isEqualTo(
            new HostTask.Existing(
                new TaskData(
                    "H000001001",
                    "Clarify returned direct debit",
                    "The bank returned the October instalment",
                    TaskStatus.OPEN,
                    "amueller",
                    LocalDate.of(2026, 10, 31),
                    TaskSource.HOST,
                    LAST_UPDATE)));
  }

  @ParameterizedTest
  @CsvSource({"HIGH, HIGH", "MEDIUM, MEDIUM", "LOW, LOW"})
  void mapsThePriority(com.example.datasync.host.TskRecord.Priority host, TaskPriority taskboard) {
    var record = hostRecord("H000001001").record();
    var withPriority =
        new com.example.datasync.host.TskRecord(
            record.id(),
            record.title(),
            record.description(),
            record.status(),
            record.user(),
            record.dueDate(),
            record.origin(),
            record.lastUpdate(),
            host);

    assertThat(data(withPriority).priority()).isEqualTo(taskboard);
  }

  @ParameterizedTest
  @CsvSource({"OPEN, OPEN", "IN_PROGRESS, IN_PROGRESS", "DONE, DONE"})
  void mapsTheStatus(Status host, TaskStatus taskboard) {
    assertThat(data(hostRecord("H000001001").status(host).record()).status()).isEqualTo(taskboard);
  }

  @Test
  void statusXIsADeletionEvenWithoutTitle() {
    var deleted = hostRecord("H000001008").status(Status.DELETED).title("").record();

    assertThat(HostRecordTransformer.transform(deleted))
        .isEqualTo(new HostTask.Deleted("H000001008"));
  }

  @Test
  void trimsTextsAndBlankOnesBecomeNull() {
    var record =
        hostRecord("H000001001").title("  Call back  ").description("   ").user("").record();

    assertThat(data(record))
        .extracting(TaskData::title, TaskData::description, TaskData::assignee)
        .containsExactly("Call back", null, null);
  }

  @Test
  void noDueDateStaysEmpty() {
    assertThat(data(hostRecord("H000001001").dueDate(null).record()).dueDate()).isNull();
  }

  @Test
  void originTMeansATaskFromTheTaskboard() {
    assertThat(data(taskboardRecord("T000000042").record()).source())
        .isEqualTo(TaskSource.TASKBOARD);
  }

  @Test
  void rejectsARecordWithoutTitle() {
    assertThatThrownBy(
            () -> HostRecordTransformer.transform(hostRecord("H000001001").title("").record()))
        .isInstanceOf(InvalidRecordException.class)
        .hasMessage("TSK-TITLE is blank");
  }

  @ParameterizedTest
  @ValueSource(strings = {"A.MUELLE", "1MUELLER", "A MUELLE"})
  void rejectsAUserThatIsNoTaskboardUserId(String user) {
    assertThatThrownBy(
            () -> HostRecordTransformer.transform(hostRecord("H000001001").user(user).record()))
        .isInstanceOf(InvalidRecordException.class)
        .hasMessage("TSK-USER '" + user + "' is not a Taskboard user id");
  }

  private static TaskData data(TskRecord record) {
    return ((HostTask.Existing) HostRecordTransformer.transform(record)).data();
  }
}
