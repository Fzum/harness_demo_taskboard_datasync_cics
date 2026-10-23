package com.example.datasync;

import static com.example.datasync.HostRecords.hostRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.datasync.etl.EtlJob;
import com.example.datasync.etl.EtlReport;
import com.example.datasync.outbox.OutboxRelay;
import com.example.datasync.outbox.RelayReport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PRIO-1 AC1 to AC4 at the datasync's seams: TSK-PRIO (host record, offset 294) to TASK.PRIORITY
 * (ETL) and OUTBOX_EVENT.PAYLOAD "priority" to TSK-PRIO (relay). Works on raw SQL, raw JSON and raw
 * 300-character records.
 */
@DatasyncTest
class TaskPriorityTest {

  private static final int PRIO_OFFSET = 294;
  private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 14, 8, 15, 31);

  private final EtlJob etl;
  private final OutboxRelay relay;
  private final HostStub host;
  private final TaskboardDatabase db;
  private final JdbcClient jdbc;

  TaskPriorityTest(
      EtlJob etl, OutboxRelay relay, HostStub host, TaskboardDatabase db, JdbcClient jdbc) {
    this.etl = etl;
    this.relay = relay;
    this.host = host;
    this.db = db;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void startEmpty() {
    db.clear();
    host.reset();
  }

  private static String withPrio(String line, String prio) {
    assertThat(line).hasSize(300);
    return line.substring(0, PRIO_OFFSET) + prio + line.substring(PRIO_OFFSET + 1);
  }

  private String priorityOf(String hostId) {
    return jdbc.sql("SELECT PRIORITY FROM TASK WHERE HOST_ID = :h")
        .param("h", hostId)
        .query(String.class)
        .single();
  }

  // ETL: host to database

  @ParameterizedTest
  @CsvSource({"H,HIGH", "M,MEDIUM", "L,LOW", "' ',MEDIUM"})
  void etlMapsTskPrioToThePriorityColumn(String prio, String expected) {
    host.put(withPrio(hostRecord("H000001001").line(), prio));

    assertThat(etl.run()).isEqualTo(new EtlReport(1, 1, 0, 0, 0, 0, 0, 0));
    assertThat(priorityOf("H000001001")).isEqualTo(expected);
  }

  @Test
  void etlLoadsABlankPriorityRecordFromBeforeTheChangeAsMedium() {
    // the record exactly as written before the change: FILLER of 6 spaces at offset 294
    host.put(hostRecord("H000001002").line().substring(0, 294) + " ".repeat(6));

    assertThat(etl.run().inserted()).isEqualTo(1);
    assertThat(priorityOf("H000001002")).isEqualTo("MEDIUM");
  }

  @ParameterizedTest
  @CsvSource({"X", "h", "1", "U"})
  void etlRejectsAnyOtherPriorityAndLoadsTheOthers(String prio) {
    host.put(hostRecord("H000001001").line(), withPrio(hostRecord("H000001002").line(), prio));

    assertThat(etl.run()).isEqualTo(new EtlReport(2, 1, 0, 0, 0, 0, 0, 1));
    assertThat(db.count("TASK")).isEqualTo(1);
  }

  @Test
  void etlReflectsAPriorityChangedOnTheHost() {
    host.put(withPrio(hostRecord("H000001001").line(), "H"));
    etl.run();
    assertThat(priorityOf("H000001001")).isEqualTo("HIGH");

    host.put(
        withPrio(
            hostRecord("H000001001").lastUpdate(HostRecords.LAST_UPDATE.plusSeconds(3600)).line(),
            "L"));

    assertThat(etl.run().updated()).isEqualTo(1);
    assertThat(priorityOf("H000001001")).isEqualTo("LOW");
  }

  // Relay: database to host

  private long taskWithEvent(
      String hostId, String priorityColumn, String eventType, String payloadPriorityJson) {
    jdbc.sql(
            """
            INSERT INTO TASK (HOST_ID, TITLE, STATUS, SOURCE, PRIORITY, CREATED_AT, UPDATED_AT)
            VALUES (:hostId, 'Call back about the invoice', 'OPEN', :source, :priority, :at, :at)
            """)
        .param("hostId", hostId)
        .param("source", hostId == null ? "TASKBOARD" : "HOST")
        .param("priority", priorityColumn)
        .param("at", AT)
        .update();
    long id = jdbc.sql("SELECT MAX(ID) FROM TASK").query(Long.class).single();
    String hostIdJson = hostId == null ? "null" : "\"" + hostId + "\"";
    String payload =
        """
        {"taskId": %d, "hostId": %s, "title": "Call back about the invoice", "description": null,
         "status": "OPEN", "assignee": null, "dueDate": null, "source": "%s"%s,
         "occurredAt": "2026-10-14T08:15:31Z"}"""
            .formatted(
                id,
                hostIdJson,
                hostId == null ? "TASKBOARD" : "HOST",
                payloadPriorityJson == null ? "" : ", \"priority\": " + payloadPriorityJson);
    db.insertEvent(id, eventType, payload);
    return id;
  }

  @ParameterizedTest
  @CsvSource({"HIGH,H", "MEDIUM,M", "LOW,L"})
  void relayWritesANewTasksPriorityAsHML(String priority, String expected) {
    taskWithEvent(null, priority, "TASK_CREATED", "\"" + priority + "\"");

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));

    assertThat(host.records())
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).hasSize(300);
              assertThat(record.charAt(PRIO_OFFSET)).isEqualTo(expected.charAt(0));
            });
  }

  @Test
  void relayRelaysAnEventFromBeforeTheChangeWithoutPriorityAsMedium() {
    taskWithEvent(null, "MEDIUM", "TASK_CREATED", null);

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));

    assertThat(host.records().getFirst().charAt(PRIO_OFFSET)).isEqualTo('M');
  }

  @Test
  void relayNeverWritesASpaceNorAnythingButHML() {
    taskWithEvent(null, "HIGH", "TASK_CREATED", "\"HIGH\"");
    taskWithEvent(null, "LOW", "TASK_CREATED", "\"LOW\"");
    taskWithEvent(null, "MEDIUM", "TASK_CREATED", null);

    relay.run();

    assertThat(host.records())
        .hasSize(3)
        .allSatisfy(r -> assertThat(r.charAt(PRIO_OFFSET)).isIn('H', 'M', 'L'));
  }

  @Test
  void relayWritesAChangedPriorityOfAHostTaskBack() {
    host.put(withPrio(hostRecord("H000001001").title("Call back about the invoice").line(), "H"));
    taskWithEvent("H000001001", "LOW", "TASK_UPDATED", "\"LOW\"");

    assertThat(relay.run()).isEqualTo(new RelayReport(1, 1, 0, 0, 0));

    assertThat(host.records())
        .singleElement()
        .satisfies(r -> assertThat(r.charAt(PRIO_OFFSET)).isEqualTo('L'));
  }

  @Test
  void relayKeepsThePriorityOfAHostTaskInAnUpdateOfAnotherField() {
    host.put(withPrio(hostRecord("H000001001").title("Call back about the invoice").line(), "H"));
    taskWithEvent("H000001001", "HIGH", "TASK_UPDATED", "\"HIGH\"");

    relay.run();

    assertThat(host.records().getFirst().charAt(PRIO_OFFSET)).isEqualTo('H');
  }
}
