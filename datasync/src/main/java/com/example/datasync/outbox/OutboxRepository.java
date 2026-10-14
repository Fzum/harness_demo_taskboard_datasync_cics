package com.example.datasync.outbox;

import com.example.datasync.UtcTimestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The OUTBOX_EVENT table. The backend inserts the events; the datasync never does, it only moves
 * them on: STATUS, ATTEMPTS, LAST_ERROR and PROCESSED_AT.
 */
@Repository
public class OutboxRepository {

  private static final String SELECT =
      """
      SELECT ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD, STATUS, ATTEMPTS, LAST_ERROR,
             CREATED_AT, PROCESSED_AT
      FROM OUTBOX_EVENT
      """;
  private static final int LAST_ERROR_LENGTH = 1000;

  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The oldest PENDING events in ID order, at most {@code limit} of them. */
  public List<OutboxEvent> findPending(int limit) {
    return jdbc.sql(SELECT + "WHERE STATUS = 'PENDING' ORDER BY ID")
        .withMaxRows(limit)
        .query(OutboxRepository::event)
        .list();
  }

  public Optional<OutboxEvent> findById(long id) {
    return jdbc.sql(SELECT + "WHERE ID = :id")
        .param("id", id)
        .query(OutboxRepository::event)
        .optional();
  }

  /** The FAILED events in ID order: the host inconsistencies. */
  public List<OutboxEvent> findFailed() {
    return jdbc.sql(SELECT + "WHERE STATUS = 'FAILED' ORDER BY ID")
        .query(OutboxRepository::event)
        .list();
  }

  /** Each task's latest event (highest ID), where that event is PENDING or FAILED. */
  public List<OutboxEvent> findLatestUnsynced() {
    String latestUnsynced =
        """
        WHERE STATUS IN ('PENDING', 'FAILED')
          AND ID = (SELECT MAX(LATEST.ID) FROM OUTBOX_EVENT LATEST
                    WHERE LATEST.AGGREGATE_ID = OUTBOX_EVENT.AGGREGATE_ID)
        ORDER BY ID
        """;
    return jdbc.sql(SELECT + latestUnsynced).query(OutboxRepository::event).list();
  }

  /** The number of events in each status, 0 for a status without events. */
  public Map<OutboxStatus, Long> countByStatus() {
    Map<OutboxStatus, Long> counts = new EnumMap<>(OutboxStatus.class);
    for (OutboxStatus status : OutboxStatus.values()) {
      counts.put(status, 0L);
    }
    jdbc.sql("SELECT STATUS, COUNT(*) AS EVENTS FROM OUTBOX_EVENT GROUP BY STATUS")
        .query(
            row -> {
              counts.put(OutboxStatus.valueOf(row.getString("STATUS")), row.getLong("EVENTS"));
            });
    return counts;
  }

  /** The event has reached the host. */
  public void markProcessed(long id, int attempts, Instant processedAt) {
    String processed =
        """
        UPDATE OUTBOX_EVENT
        SET STATUS = 'PROCESSED', ATTEMPTS = :attempts, LAST_ERROR = NULL,
            PROCESSED_AT = :processedAt
        WHERE ID = :id
        """;
    jdbc.sql(processed)
        .param("attempts", attempts)
        .param("processedAt", UtcTimestamps.toColumn(processedAt))
        .param("id", id)
        .update();
  }

  /** An attempt failed: the event stays PENDING for another attempt, or it is FAILED. */
  public void markAttemptFailed(long id, int attempts, OutboxStatus status, String error) {
    String failed =
        """
        UPDATE OUTBOX_EVENT
        SET STATUS = :status, ATTEMPTS = :attempts, LAST_ERROR = :error
        WHERE ID = :id
        """;
    jdbc.sql(failed)
        .param("status", status.name())
        .param("attempts", attempts)
        .param("error", fitLastError(error))
        .param("id", id)
        .update();
  }

  /**
   * Marks the task's FAILED events older than {@code eventId} as PROCESSED: that event's full
   * snapshot has just brought their changes to the host as well.
   */
  public int resolveSuperseded(long taskId, long eventId, Instant processedAt) {
    String superseded =
        """
        UPDATE OUTBOX_EVENT
        SET STATUS = 'PROCESSED', PROCESSED_AT = :processedAt, LAST_ERROR = :note
        WHERE AGGREGATE_ID = :taskId AND ID < :eventId AND STATUS = 'FAILED'
        """;
    return jdbc.sql(superseded)
        .param("processedAt", UtcTimestamps.toColumn(processedAt))
        .param("note", "superseded by event " + eventId)
        .param("taskId", taskId)
        .param("eventId", eventId)
        .update();
  }

  /** Sets a FAILED event back to PENDING with ATTEMPTS 0; false if it is not FAILED. */
  public boolean retry(long id) {
    String retry =
        "UPDATE OUTBOX_EVENT SET STATUS = 'PENDING', ATTEMPTS = 0 WHERE ID = :id AND STATUS = 'FAILED'";
    return jdbc.sql(retry).param("id", id).update() == 1;
  }

  /** LAST_ERROR is VARCHAR(1000): a longer error, such as a long host answer, is cut to fit. */
  private static String fitLastError(String error) {
    return error.length() <= LAST_ERROR_LENGTH ? error : error.substring(0, LAST_ERROR_LENGTH);
  }

  private static OutboxEvent event(ResultSet row, int rowNumber) throws SQLException {
    return new OutboxEvent(
        row.getLong("ID"),
        row.getString("AGGREGATE_TYPE"),
        row.getLong("AGGREGATE_ID"),
        row.getString("EVENT_TYPE"),
        row.getString("PAYLOAD"),
        OutboxStatus.valueOf(row.getString("STATUS")),
        row.getInt("ATTEMPTS"),
        row.getString("LAST_ERROR"),
        UtcTimestamps.fromColumn(row.getObject("CREATED_AT", LocalDateTime.class)),
        UtcTimestamps.fromColumn(row.getObject("PROCESSED_AT", LocalDateTime.class)));
  }
}
