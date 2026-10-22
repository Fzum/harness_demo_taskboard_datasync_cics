package com.example.datasync;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.datasync.task.TaskData;
import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * PRIO-1: TSK-PRIO (offset 294) of the host record against TASK.PRIORITY and the priority of the
 * outbox payloads, through the sync API and the host stub only.
 */
@DatasyncTest
class TaskPriorityTest {

  private static final int PRIO_OFFSET = 294;
  private static final Instant LAST_UPDATE = Instant.parse("2026-10-01T07:42:10Z");

  private final MockMvcTester mvc;
  private final HostStub host;
  private final TaskboardDatabase db;
  private final JdbcClient jdbc;

  TaskPriorityTest(MockMvcTester mvc, HostStub host, TaskboardDatabase db, JdbcClient jdbc) {
    this.mvc = mvc;
    this.host = host;
    this.db = db;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void startEmpty() {
    db.clear();
    host.reset();
  }

  /** A fixed-width host record of an OPEN task from the host with this byte at offset 294. */
  private static String hostLine(String id, String prio) {
    String record =
        pad(id, 10)
            + pad("Clarify returned direct debit", 40)
            + pad("", 200)
            + "O"
            + pad("", 8)
            + "00000000"
            + "H"
            + "2026-10-01-07.42.10.000000"
            + pad(prio, 1)
            + pad("", 5);
    assertThat(record).hasSize(300);
    return record;
  }

  private static String pad(String value, int length) {
    return value + " ".repeat(length - value.length());
  }

  private void runEtl() {
    assertThat(mvc.post().uri("/api/sync/etl/run")).hasStatus(HttpStatus.OK);
  }

  private void runRelay() {
    assertThat(mvc.post().uri("/api/sync/outbox/run")).hasStatus(HttpStatus.OK);
  }

  private String priorityOf(String hostId) {
    return jdbc.sql("SELECT PRIORITY FROM TASK WHERE HOST_ID = :hostId")
        .param("hostId", hostId)
        .query(String.class)
        .single();
  }

  private int tasks() {
    return db.count("TASK");
  }

  /** A TASKBOARD task as the backend stores it, with its event whose payload carries priority. */
  private long taskWithEvent(String eventType, String payloadPriorityJson) {
    long id =
        db.insertTask(
            new TaskData(
                null,
                "Call back about the invoice",
                null,
                TaskStatus.OPEN,
                null,
                null,
                TaskSource.TASKBOARD,
                Instant.parse("2026-10-14T08:15:30Z")));
    db.insertEvent(
        id,
        eventType,
        "{\"taskId\": %d, \"hostId\": null, \"title\": \"Call back about the invoice\","
                .formatted(id)
            + " \"description\": null, \"status\": \"OPEN\", \"assignee\": null,"
            + " \"dueDate\": null, \"source\": \"TASKBOARD\","
            + payloadPriorityJson
            + " \"occurredAt\": \"2026-10-14T08:15:31.123Z\"}");
    return id;
  }

  // ETL: host -> database

  @ParameterizedTest
  @CsvSource({"H,HIGH", "M,MEDIUM", "L,LOW", "' ',MEDIUM"})
  void etlMapsTskPrioToPriority(String prio, String expected) {
    host.put(hostLine("H000001001", prio));

    runEtl();

    assertThat(priorityOf("H000001001")).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"Q", "h", "1"})
  void etlRejectsARecordWithAnInvalidTskPrioAndLoadsTheOthers(String prio) {
    host.put(hostLine("H000001001", prio), hostLine("H000001002", "H"));

    runEtl();

    assertThat(tasks()).isEqualTo(1);
    assertThat(priorityOf("H000001002")).isEqualTo("HIGH");
  }

  @Test
  void etlPropagatesAHostSidePriorityChangeOfAnOtherwiseUnchangedRecord() {
    host.put(hostLine("H000001001", "M"));
    runEtl();
    assertThat(priorityOf("H000001001")).isEqualTo("MEDIUM");

    host.put(hostLine("H000001001", "L"));
    runEtl();
    assertThat(priorityOf("H000001001")).isEqualTo("LOW");

    host.put(hostLine("H000001001", "H"));
    runEtl();
    assertThat(priorityOf("H000001001")).isEqualTo("HIGH");
    assertThat(tasks()).isEqualTo(1);
  }

  @Test
  void etlReadsABlankedPriorityAsMedium() {
    host.put(hostLine("H000001001", "H"));
    runEtl();

    host.put(hostLine("H000001001", " "));
    runEtl();

    assertThat(priorityOf("H000001001")).isEqualTo("MEDIUM");
  }

  // Relay: database -> host

  @ParameterizedTest
  @CsvSource({"HIGH,H", "MEDIUM,M", "LOW,L"})
  void relayWritesThePayloadPriorityAsHMLAtOffset294(String priority, String expected) {
    taskWithEvent("TASK_CREATED", " \"priority\": \"" + priority + "\",");

    runRelay();

    List<String> records = host.records();
    assertThat(records).hasSize(1);
    String record = records.getFirst();
    assertThat(record).hasSize(300);
    assertThat(record.charAt(PRIO_OFFSET)).isEqualTo(expected.charAt(0));
    assertThat(record.substring(PRIO_OFFSET + 1)).isEqualTo("     ");
    assertThat(record.substring(10, 50).strip()).isEqualTo("Call back about the invoice");
    assertThat(record.charAt(250)).isEqualTo('O');
    assertThat(record.charAt(267)).isEqualTo('T');
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          ''                    | an event without the key
          ' "priority": null,'  | an event with a null priority
          """)
  void relayWritesMediumForAnOldEventWithoutPriorityAndNeverASpace(String key, String reason) {
    taskWithEvent("TASK_CREATED", key.isEmpty() ? "" : key);

    runRelay();

    assertThat(host.records()).hasSize(1);
    assertThat(host.records().getFirst().charAt(PRIO_OFFSET)).as(reason).isEqualTo('M');
  }

  @Test
  void relayWritesTheNewPriorityOfAnUpdateOverTheHostRecord() {
    long id = taskWithEvent("TASK_CREATED", " \"priority\": \"MEDIUM\",");
    runRelay();
    assertThat(host.records().getFirst().charAt(PRIO_OFFSET)).isEqualTo('M');
    String key = host.records().getFirst().substring(0, 10).strip();
    jdbc.sql("UPDATE TASK SET HOST_ID = :key, PRIORITY = 'LOW' WHERE ID = :id")
        .param("key", key)
        .param("id", id)
        .update();
    db.insertEvent(
        id,
        "TASK_UPDATED",
        "{\"taskId\": %d, \"hostId\": \"%s\", \"title\": \"Call back about the invoice\","
                .formatted(id, key)
            + " \"description\": null, \"status\": \"OPEN\", \"assignee\": null,"
            + " \"dueDate\": null, \"source\": \"TASKBOARD\", \"priority\": \"LOW\","
            + " \"occurredAt\": \"2026-10-14T08:15:40.123Z\"}");

    runRelay();

    assertThat(host.records()).hasSize(1);
    assertThat(host.records().getFirst().charAt(PRIO_OFFSET)).isEqualTo('L');
  }
}
