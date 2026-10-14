package com.example.datasync.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.datasync.host.TskRecord;
import com.example.datasync.host.TskRecord.Origin;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.host.TskRecordCodec;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InverseTransformerTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T08:15:30.123Z");

  private static final String NEW_TASK =
      """
      {"taskId": 42, "hostId": null, "title": "Call back about the invoice",
       "description": "The customer asked twice", "status": "OPEN", "assignee": "amueller",
       "dueDate": "2026-10-31", "source": "TASKBOARD", "occurredAt": "2026-10-14T08:15:30.123Z"}
      """;

  @Test
  void writesANewTaskboardTaskUnderTPlusItsId() {
    assertThat(InverseTransformer.toRecord(event(42, "TASK_CREATED", NEW_TASK)))
        .isEqualTo(
            new TskRecord(
                "T000000042",
                "Call back about the invoice",
                "The customer asked twice",
                Status.OPEN,
                "AMUELLER",
                LocalDate.of(2026, 10, 31),
                Origin.FROM_TASKBOARD,
                OCCURRED_AT));
  }

  @Test
  void theRecordIsExactlyWhatTskupdtGets() {
    String record =
        TskRecordCodec.format(InverseTransformer.toRecord(event(42, "TASK_CREATED", NEW_TASK)));

    assertThat(record)
        .hasSize(300)
        .isEqualTo(
            "T000000042"
                + pad("Call back about the invoice", 40)
                + pad("The customer asked twice", 200)
                + "O"
                + "AMUELLER"
                + "20261031"
                + "T"
                + "2026-10-14-08.15.30.123000"
                + "      ");
  }

  @Test
  void writesATaskUnderItsHostIdOnceItHasOne() {
    String hostTask =
        """
        {"taskId": 7, "hostId": "H000001001", "title": "Clarify returned direct debit",
         "description": null, "status": "IN_PROGRESS", "assignee": null, "dueDate": null,
         "source": "HOST", "occurredAt": "2026-10-14T08:15:30.123Z"}
        """;

    assertThat(InverseTransformer.toRecord(event(7, "TASK_UPDATED", hostTask)))
        .isEqualTo(
            new TskRecord(
                "H000001001",
                "Clarify returned direct debit",
                "",
                Status.IN_PROGRESS,
                "",
                null,
                Origin.FROM_HOST,
                OCCURRED_AT));
  }

  @ParameterizedTest
  @CsvSource({"OPEN, OPEN", "IN_PROGRESS, IN_PROGRESS", "DONE, DONE"})
  void mapsTheStatus(String taskboard, Status host) {
    String payload = NEW_TASK.replace("\"OPEN\"", "\"" + taskboard + "\"");

    assertThat(InverseTransformer.toRecord(event(42, "TASK_UPDATED", payload)).status())
        .isEqualTo(host);
  }

  @Test
  void aDeletionWritesTheLastStateWithStatusX() {
    assertThat(InverseTransformer.toRecord(event(42, "TASK_DELETED", NEW_TASK)))
        .extracting(TskRecord::id, TskRecord::title, TskRecord::status)
        .containsExactly("T000000042", "Call back about the invoice", Status.DELETED);
  }

  @Test
  void lineBreaksBecomeSpacesBecauseAHostRecordIsOneLine() {
    String payload = NEW_TASK.replace("The customer asked twice", "First line\\nsecond line");

    assertThat(InverseTransformer.toRecord(event(42, "TASK_UPDATED", payload)).description())
        .isEqualTo("First line second line");
  }

  // Tab, carriage return, DEL, and the C1 controls NEL and APC, which text decoded with the wrong
  // code page carries: the codec refuses every one of them in a record.
  @ParameterizedTest
  @ValueSource(strings = {"\t", "\r", "\u007f", "\u0085", "\u009f"})
  void everyControlCharacterBecomesASpace(String control) {
    String payload =
        NEW_TASK.replace("Call back about", "Call back" + jsonEscape(control) + "about");

    TskRecord record = InverseTransformer.toRecord(event(42, "TASK_UPDATED", payload));

    assertThat(record.title()).isEqualTo("Call back about the invoice");
    assertThat(TskRecordCodec.format(record)).hasSize(300);
  }

  @Test
  void ignoresUnknownKeys() {
    String payload = NEW_TASK.replace("{", "{\"priority\": \"HIGH\", ");

    assertThat(InverseTransformer.toRecord(event(42, "TASK_UPDATED", payload)).id())
        .isEqualTo("T000000042");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          TASK     | TASK_ARCHIVED | 42 | unknown event type TASK_ARCHIVED
          CUSTOMER | TASK_UPDATED  | 42 | unknown aggregate type CUSTOMER
          TASK     | TASK_UPDATED  | 41 | payload taskId 42 is not the event's task 41
          """)
  void rejectsAnEventItCannotWrite(
      String aggregateType, String eventType, long taskId, String reason) {
    OutboxEvent event =
        new OutboxEvent(
            1,
            aggregateType,
            taskId,
            eventType,
            NEW_TASK,
            OutboxStatus.PENDING,
            0,
            null,
            OCCURRED_AT,
            null);

    assertThatThrownBy(() -> InverseTransformer.toRecord(event))
        .isInstanceOf(InvalidEventException.class)
        .hasMessage(reason);
  }

  @Test
  void rejectsAnUnparseablePayload() {
    assertThatThrownBy(() -> InverseTransformer.toRecord(event(42, "TASK_UPDATED", "{not json")))
        .isInstanceOf(InvalidEventException.class)
        .hasMessageStartingWith("unparseable payload: ");
    assertThatThrownBy(
            () ->
                InverseTransformer.toRecord(
                    event(42, "TASK_UPDATED", NEW_TASK.replace("\"OPEN\"", "\"BLOCKED\""))))
        .isInstanceOf(InvalidEventException.class)
        .hasMessageContaining("BLOCKED");
    assertThatThrownBy(
            () ->
                InverseTransformer.toRecord(
                    event(
                        42,
                        "TASK_UPDATED",
                        NEW_TASK.replace("\"Call back about the invoice\"", "null"))))
        .isInstanceOf(InvalidEventException.class)
        .hasMessage("unparseable payload: no title");
  }

  private static OutboxEvent event(long taskId, String eventType, String payload) {
    return new OutboxEvent(
        1, "TASK", taskId, eventType, payload, OutboxStatus.PENDING, 0, null, OCCURRED_AT, null);
  }

  private static String jsonEscape(String character) {
    return "\\u%04x".formatted((int) character.charAt(0));
  }

  private static String pad(String value, int length) {
    return value + " ".repeat(length - value.length());
  }
}
