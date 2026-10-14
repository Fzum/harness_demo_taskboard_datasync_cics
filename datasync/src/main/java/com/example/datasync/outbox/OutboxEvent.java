package com.example.datasync.outbox;

import java.time.Instant;

/**
 * An OUTBOX_EVENT row: a change of a task in the Taskboard, written by the backend in the same
 * transaction as the change.
 *
 * @param aggregateType AGGREGATE_TYPE, always TASK
 * @param taskId AGGREGATE_ID, the TASK.ID
 * @param eventType EVENT_TYPE: TASK_CREATED, TASK_UPDATED or TASK_DELETED
 * @param payload PAYLOAD, a JSON snapshot of the task (see {@link OutboxPayload})
 */
public record OutboxEvent(
    long id,
    String aggregateType,
    long taskId,
    String eventType,
    String payload,
    OutboxStatus status,
    int attempts,
    String lastError,
    Instant createdAt,
    Instant processedAt) {}
