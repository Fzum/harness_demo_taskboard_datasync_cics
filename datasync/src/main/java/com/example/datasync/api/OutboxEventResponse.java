package com.example.datasync.api;

import com.example.datasync.outbox.OutboxEvent;
import com.example.datasync.outbox.OutboxStatus;
import java.time.Instant;

/** An outbox event as the API shows it: a host inconsistency, or one after its retry. */
record OutboxEventResponse(
    long eventId,
    long taskId,
    String eventType,
    OutboxStatus status,
    int attempts,
    String lastError,
    Instant createdAt,
    Instant processedAt) {

  static OutboxEventResponse of(OutboxEvent event) {
    return new OutboxEventResponse(
        event.id(),
        event.taskId(),
        event.eventType(),
        event.status(),
        event.attempts(),
        event.lastError(),
        event.createdAt(),
        event.processedAt());
  }
}
