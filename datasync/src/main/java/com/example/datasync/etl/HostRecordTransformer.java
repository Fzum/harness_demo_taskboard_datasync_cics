package com.example.datasync.etl;

import com.example.datasync.host.InvalidRecordException;
import com.example.datasync.host.TskRecord;
import com.example.datasync.host.TskRecord.Origin;
import com.example.datasync.host.TskRecord.Priority;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.task.TaskData;
import com.example.datasync.task.TaskPriority;
import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * E3, the ETL's transformation of a decoded host record into the Taskboard's terms: texts trimmed
 * and blank ones null; O, I and D become OPEN, IN_PROGRESS and DONE, X a deletion; TSK-USER is
 * lower-cased into the assignee; origin H becomes source HOST and T becomes TASKBOARD; TSK-LAST-UPD
 * becomes UPDATED_AT; TSK-PRIO H, M and L become HIGH, MEDIUM and LOW, blank MEDIUM. A record the
 * Taskboard cannot hold (no title, a user that is no Taskboard user id) is rejected.
 */
final class HostRecordTransformer {

  private static final Pattern USER_ID = Pattern.compile("[a-z][a-z0-9]{0,7}");

  private HostRecordTransformer() {}

  /**
   * The task a record describes.
   *
   * @throws InvalidRecordException if the Taskboard cannot hold the task
   */
  static HostTask transform(TskRecord record) {
    if (record.status() == Status.DELETED) {
      return new HostTask.Deleted(record.id());
    }
    String title = trimToNull(record.title());
    if (title == null) {
      throw new InvalidRecordException(record.id(), "TSK-TITLE is blank");
    }
    String assignee = trimToNull(record.user());
    if (assignee != null) {
      assignee = assignee.toLowerCase(Locale.ROOT);
      if (!USER_ID.matcher(assignee).matches()) {
        throw new InvalidRecordException(
            record.id(), "TSK-USER '" + record.user() + "' is not a Taskboard user id");
      }
    }
    return new HostTask.Existing(
        new TaskData(
            record.id(),
            title,
            trimToNull(record.description()),
            status(record.status()),
            assignee,
            record.dueDate(),
            record.origin() == Origin.FROM_HOST ? TaskSource.HOST : TaskSource.TASKBOARD,
            priority(record.priority()),
            record.lastUpdate()));
  }

  private static TaskStatus status(Status status) {
    return switch (status) {
      case OPEN -> TaskStatus.OPEN;
      case IN_PROGRESS -> TaskStatus.IN_PROGRESS;
      case DONE -> TaskStatus.DONE;
      case DELETED -> throw new IllegalArgumentException("a deleted record has no task status");
    };
  }

  private static TaskPriority priority(Priority priority) {
    if (priority == null) {
      return TaskPriority.MEDIUM;
    }
    return switch (priority) {
      case HIGH -> TaskPriority.HIGH;
      case MEDIUM -> TaskPriority.MEDIUM;
      case LOW -> TaskPriority.LOW;
    };
  }

  private static String trimToNull(String text) {
    String trimmed = text.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
