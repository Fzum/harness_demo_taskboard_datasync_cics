package com.example.datasync.task;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The TASK columns that mirror a host record: all but ID and CREATED_AT.
 *
 * @param hostId HOST_ID, the task's key on the host; null until a Taskboard task has been written
 *     to the host
 */
public record TaskData(
    String hostId,
    String title,
    String description,
    TaskStatus status,
    String assignee,
    LocalDate dueDate,
    TaskSource source,
    Instant updatedAt) {}
