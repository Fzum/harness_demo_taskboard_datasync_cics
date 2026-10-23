package com.example.datasync.outbox;

import com.example.datasync.host.HostKey;
import com.example.datasync.host.TskRecord;
import com.example.datasync.host.TskRecord.Origin;
import com.example.datasync.host.TskRecord.Priority;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.task.TaskPriority;
import com.example.datasync.task.TaskSource;
import com.example.datasync.task.TaskStatus;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R3, the relay's inverse transformation: the TSK-RECORD that TSKUPDT writes for an outbox event.
 * The key is the payload's hostId, or "T" plus the taskId padded to 9 digits; the user is
 * upper-cased; OPEN, IN_PROGRESS and DONE become O, I and D, and TASK_DELETED writes status X;
 * source HOST becomes origin H and TASKBOARD becomes T; occurredAt becomes TSK-LAST-UPD; the
 * priority becomes H, M or L (a missing one M, never a space). Control characters such as line
 * breaks become spaces, since a host record is one line. {@link
 * com.example.datasync.host.TskRecordCodec} pads the texts and writes the dates.
 */
final class InverseTransformer {

  private static final String TASK_DELETED = "TASK_DELETED";
  private static final Set<String> EVENT_TYPES =
      Set.of("TASK_CREATED", "TASK_UPDATED", TASK_DELETED);

  /**
   * Every character the codec refuses in a record: the Unicode controls, C1 ones such as NEL
   * included, not only the ASCII ones of {@code \p{Cntrl}}.
   */
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cc}");

  private InverseTransformer() {}

  /**
   * The host record for an event.
   *
   * @throws InvalidEventException for an unknown aggregate or event type or an unparseable payload
   */
  static TskRecord toRecord(OutboxEvent event) {
    if (!event.aggregateType().equals("TASK")) {
      throw new InvalidEventException("unknown aggregate type " + event.aggregateType());
    }
    if (!EVENT_TYPES.contains(event.eventType())) {
      throw new InvalidEventException("unknown event type " + event.eventType());
    }
    OutboxPayload task = OutboxPayload.parse(event.payload());
    if (task.taskId().longValue() != event.taskId()) {
      throw new InvalidEventException(
          "payload taskId " + task.taskId() + " is not the event's task " + event.taskId());
    }
    return new TskRecord(
        task.hostId() != null ? task.hostId() : HostKey.forTask(task.taskId()),
        hostText(task.title()),
        hostText(task.description()),
        event.eventType().equals(TASK_DELETED) ? Status.DELETED : status(task.status()),
        task.assignee() == null ? "" : task.assignee().toUpperCase(Locale.ROOT),
        task.dueDate(),
        task.source() == TaskSource.HOST ? Origin.FROM_HOST : Origin.FROM_TASKBOARD,
        task.occurredAt(),
        priority(task.priority()));
  }

  private static Status status(TaskStatus status) {
    return switch (status) {
      case OPEN -> Status.OPEN;
      case IN_PROGRESS -> Status.IN_PROGRESS;
      case DONE -> Status.DONE;
    };
  }

  private static Priority priority(TaskPriority priority) {
    if (priority == null) {
      return Priority.MEDIUM;
    }
    return switch (priority) {
      case HIGH -> Priority.HIGH;
      case MEDIUM -> Priority.MEDIUM;
      case LOW -> Priority.LOW;
    };
  }

  private static String hostText(String text) {
    return text == null ? "" : CONTROL_CHARACTERS.matcher(text).replaceAll(" ");
  }
}
