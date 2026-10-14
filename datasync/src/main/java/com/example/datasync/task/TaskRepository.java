package com.example.datasync.task;

import com.example.datasync.UtcTimestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * The TASK table, owned by the backend. The datasync writes it on ETL loads and sets TASK.HOST_ID
 * once it has written a Taskboard task to the host.
 */
@Repository
public class TaskRepository {

  private static final String SELECT =
      """
      SELECT ID, HOST_ID, TITLE, DESCRIPTION, STATUS, ASSIGNEE, DUE_DATE, SOURCE, UPDATED_AT
      FROM TASK
      """;

  private final JdbcClient jdbc;

  public TaskRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Task> findByHostId(String hostId) {
    return jdbc.sql(SELECT + "WHERE HOST_ID = :hostId")
        .param("hostId", hostId)
        .query(TaskRepository::task)
        .optional();
  }

  /** The task with this ID, if it has no HOST_ID yet. */
  public Optional<Task> findUnlinked(long id) {
    return jdbc.sql(SELECT + "WHERE ID = :id AND HOST_ID IS NULL")
        .param("id", id)
        .query(TaskRepository::task)
        .optional();
  }

  /** Inserts a task from the host: it was created and last updated at its TSK-LAST-UPD. */
  public void insert(TaskData task) {
    String insert =
        """
        INSERT INTO TASK (HOST_ID, TITLE, DESCRIPTION, STATUS, ASSIGNEE, DUE_DATE, SOURCE,
                          CREATED_AT, UPDATED_AT)
        VALUES (:hostId, :title, :description, :status, :assignee, :dueDate, :source,
                :updatedAt, :updatedAt)
        """;
    bind(jdbc.sql(insert), task).update();
  }

  public void update(long id, TaskData task) {
    String update =
        """
        UPDATE TASK
        SET HOST_ID = :hostId, TITLE = :title, DESCRIPTION = :description, STATUS = :status,
            ASSIGNEE = :assignee, DUE_DATE = :dueDate, SOURCE = :source, UPDATED_AT = :updatedAt
        WHERE ID = :id
        """;
    bind(jdbc.sql(update), task).param("id", id).update();
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM TASK WHERE ID = :id").param("id", id).update();
  }

  /** Sets TASK.HOST_ID if it is still null; a task that has one keeps it. */
  public void linkHostId(long id, String hostId) {
    jdbc.sql("UPDATE TASK SET HOST_ID = :hostId WHERE ID = :id AND HOST_ID IS NULL")
        .param("hostId", hostId)
        .param("id", id)
        .update();
  }

  private static StatementSpec bind(StatementSpec statement, TaskData task) {
    return statement
        .param("hostId", task.hostId())
        .param("title", task.title())
        .param("description", task.description())
        .param("status", task.status().name())
        .param("assignee", task.assignee())
        .param("dueDate", task.dueDate())
        .param("source", task.source().name())
        .param("updatedAt", UtcTimestamps.toColumn(task.updatedAt()));
  }

  private static Task task(ResultSet row, int rowNumber) throws SQLException {
    return new Task(
        row.getLong("ID"),
        new TaskData(
            row.getString("HOST_ID"),
            row.getString("TITLE"),
            row.getString("DESCRIPTION"),
            TaskStatus.valueOf(row.getString("STATUS")),
            row.getString("ASSIGNEE"),
            row.getObject("DUE_DATE", LocalDate.class),
            TaskSource.valueOf(row.getString("SOURCE")),
            UtcTimestamps.fromColumn(row.getObject("UPDATED_AT", LocalDateTime.class))));
  }
}
