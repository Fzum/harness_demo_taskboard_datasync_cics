package com.example.datasync.outbox;

import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * OUTBOX_EVENT.PAYLOAD: a full snapshot of the task after the change (for TASK_DELETED, its last
 * state before the deletion). Empty values are null; unknown keys are ignored.
 *
 * @param hostId the TASK.HOST_ID at the time of the change
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboxPayload(
    Long taskId,
    String hostId,
    String title,
    String description,
    TaskStatus status,
    String assignee,
    LocalDate dueDate,
    TaskSource source,
    Instant occurredAt) {

  private static final JsonMapper JSON = JsonMapper.shared();

  /**
   * Reads a payload.
   *
   * @throws InvalidEventException if it is not JSON of this shape or lacks taskId, title, status,
   *     source or occurredAt
   */
  public static OutboxPayload parse(String json) {
    OutboxPayload payload;
    try {
      payload = JSON.readValue(json, OutboxPayload.class);
    } catch (JacksonException e) {
      throw new InvalidEventException("unparseable payload: " + e.getOriginalMessage());
    }
    if (payload == null) {
      throw new InvalidEventException("unparseable payload: null");
    }
    require(payload.taskId(), "taskId");
    require(payload.title(), "title");
    require(payload.status(), "status");
    require(payload.source(), "source");
    require(payload.occurredAt(), "occurredAt");
    return payload;
  }

  /** The payload, or empty if it cannot be read. */
  public static Optional<OutboxPayload> tryParse(String json) {
    try {
      return Optional.of(parse(json));
    } catch (InvalidEventException e) {
      return Optional.empty();
    }
  }

  private static void require(Object value, String key) {
    if (value == null) {
      throw new InvalidEventException("unparseable payload: no " + key);
    }
  }
}
