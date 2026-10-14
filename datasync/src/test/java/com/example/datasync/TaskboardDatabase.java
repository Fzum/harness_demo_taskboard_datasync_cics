package com.example.datasync;

import com.example.datasync.outbox.OutboxStatus;
import com.example.datasync.task.TaskData;
import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Taskboard database in tests, changed the way the backend changes it: each change of a task
 * and its outbox event (PENDING, ATTEMPTS 0, a full JSON snapshot) together. Every change happens
 * one second after the previous one.
 */
public final class TaskboardDatabase {

  private static final JsonMapper JSON = JsonMapper.shared();

  private final JdbcClient jdbc;
  private Instant now = Instant.parse("2026-10-14T08:15:30.123Z");

  public TaskboardDatabase(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void clear() {
    jdbc.sql("DELETE FROM OUTBOX_EVENT").update();
    jdbc.sql("DELETE FROM TASK").update();
  }

  /** POST /api/tasks: a new OPEN task from the Taskboard and its TASK_CREATED event. */
  public long createTask(String title) {
    Instant at = tick();
    TaskData task =
        new TaskData(null, title, null, TaskStatus.OPEN, null, null, TaskSource.TASKBOARD, at);
    long id = insertTask(task);
    insertEvent(id, "TASK_CREATED", snapshot(id, task, at));
    return id;
  }

  /** PUT /api/tasks/{id}: changes the task and writes its TASK_UPDATED event. */
  public long updateTask(long id, UnaryOperator<TaskData> change) {
    Instant at = tick();
    TaskData before = task(id);
    TaskData after = change.apply(before);
    TaskData updated =
        new TaskData(
            before.hostId(),
            after.title(),
            after.description(),
            after.status(),
            after.assignee(),
            after.dueDate(),
            before.source(),
            at);
    jdbc.sql(
            """
            UPDATE TASK SET TITLE = :title, DESCRIPTION = :description, STATUS = :status,
                            ASSIGNEE = :assignee, DUE_DATE = :dueDate, UPDATED_AT = :updatedAt
            WHERE ID = :id
            """)
        .param("title", updated.title())
        .param("description", updated.description())
        .param("status", updated.status().name())
        .param("assignee", updated.assignee())
        .param("dueDate", updated.dueDate())
        .param("updatedAt", UtcTimestamps.toColumn(at))
        .param("id", id)
        .update();
    return insertEvent(id, "TASK_UPDATED", snapshot(id, updated, at));
  }

  /** DELETE /api/tasks/{id}: deletes the task and writes TASK_DELETED with its last state. */
  public long deleteTask(long id) {
    Instant at = tick();
    TaskData last = task(id);
    jdbc.sql("DELETE FROM TASK WHERE ID = :id").param("id", id).update();
    return insertEvent(id, "TASK_DELETED", snapshot(id, last, at));
  }

  /** Inserts a TASK row as it is, created at its updatedAt; returns its ID. */
  public long insertTask(TaskData task) {
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.sql(
            """
            INSERT INTO TASK (HOST_ID, TITLE, DESCRIPTION, STATUS, ASSIGNEE, DUE_DATE, SOURCE,
                              CREATED_AT, UPDATED_AT)
            VALUES (:hostId, :title, :description, :status, :assignee, :dueDate, :source,
                    :updatedAt, :updatedAt)
            """)
        .param("hostId", task.hostId())
        .param("title", task.title())
        .param("description", task.description())
        .param("status", task.status().name())
        .param("assignee", task.assignee())
        .param("dueDate", task.dueDate())
        .param("source", task.source().name())
        .param("updatedAt", UtcTimestamps.toColumn(task.updatedAt()))
        .update(key, "ID");
    return key.getKey().longValue();
  }

  /** Inserts a PENDING outbox event with ATTEMPTS 0; returns its ID. */
  public long insertEvent(long taskId, String eventType, String payload) {
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.sql(
            """
            INSERT INTO OUTBOX_EVENT (AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD, CREATED_AT)
            VALUES ('TASK', :taskId, :eventType, :payload, :createdAt)
            """)
        .param("taskId", taskId)
        .param("eventType", eventType)
        .param("payload", payload)
        .param("createdAt", UtcTimestamps.toColumn(tick()))
        .update(key, "ID");
    return key.getKey().longValue();
  }

  /** Puts an event into a status, as earlier relay runs would have. */
  public void setEventStatus(long eventId, OutboxStatus status, int attempts, String lastError) {
    jdbc.sql(
            "UPDATE OUTBOX_EVENT SET STATUS = :status, ATTEMPTS = :attempts, LAST_ERROR = :error"
                + " WHERE ID = :id")
        .param("status", status.name())
        .param("attempts", attempts)
        .param("error", lastError)
        .param("id", eventId)
        .update();
  }

  /** The payload the backend writes: every key present, null when empty. */
  public static String snapshot(long taskId, TaskData task, Instant occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", taskId);
    payload.put("hostId", task.hostId());
    payload.put("title", task.title());
    payload.put("description", task.description());
    payload.put("status", task.status().name());
    payload.put("assignee", task.assignee());
    payload.put("dueDate", task.dueDate() == null ? null : task.dueDate().toString());
    payload.put("source", task.source().name());
    payload.put("occurredAt", occurredAt.toString());
    return JSON.writeValueAsString(payload);
  }

  /** The task's columns that mirror a host record. */
  public TaskData task(long id) {
    return jdbc.sql(
            "SELECT HOST_ID, TITLE, DESCRIPTION, STATUS, ASSIGNEE, DUE_DATE, SOURCE, UPDATED_AT"
                + " FROM TASK WHERE ID = :id")
        .param("id", id)
        .query(
            (row, n) ->
                new TaskData(
                    row.getString("HOST_ID"),
                    row.getString("TITLE"),
                    row.getString("DESCRIPTION"),
                    TaskStatus.valueOf(row.getString("STATUS")),
                    row.getString("ASSIGNEE"),
                    row.getObject("DUE_DATE", LocalDate.class),
                    TaskSource.valueOf(row.getString("SOURCE")),
                    UtcTimestamps.fromColumn(row.getObject("UPDATED_AT", LocalDateTime.class))))
        .single();
  }

  /** Every column of the task as stored. */
  public Map<String, Object> taskRow(long id) {
    return jdbc.sql("SELECT * FROM TASK WHERE ID = :id").param("id", id).query().singleRow();
  }

  /** The ID of the task with this HOST_ID. */
  public long taskIdOf(String hostId) {
    return jdbc.sql("SELECT ID FROM TASK WHERE HOST_ID = :hostId")
        .param("hostId", hostId)
        .query(Long.class)
        .single();
  }

  /** The IDs of the task's outbox events, oldest first. */
  public List<Long> eventIdsOf(long taskId) {
    return jdbc.sql("SELECT ID FROM OUTBOX_EVENT WHERE AGGREGATE_ID = :taskId ORDER BY ID")
        .param("taskId", taskId)
        .query(Long.class)
        .list();
  }

  public int count(String table) {
    return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
  }

  private Instant tick() {
    now = now.plus(Duration.ofSeconds(1));
    return now;
  }
}
