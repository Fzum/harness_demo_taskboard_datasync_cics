package com.example.datasync.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The TASK columns that mirror a host record: all but ID and CREATED_AT.
 *
 * @param priority PRIORITY
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
    TaskPriority priority,
    Instant updatedAt) {

  public TaskData {
    Objects.requireNonNull(priority, "priority");
  }

  /** A task of priority MEDIUM, the column's default. */
  public TaskData(
      String hostId,
      String title,
      String description,
      TaskStatus status,
      String assignee,
      LocalDate dueDate,
      TaskSource source,
      Instant updatedAt) {
    this(
        hostId,
        title,
        description,
        status,
        assignee,
        dueDate,
        source,
        TaskPriority.MEDIUM,
        updatedAt);
  }
}
