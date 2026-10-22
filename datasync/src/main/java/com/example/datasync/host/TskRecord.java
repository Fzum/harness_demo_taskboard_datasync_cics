package com.example.datasync.host;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A record of the host's task master file TSKMSTR, as copybook TSKREC ({@code
 * copybooks/TSKREC.cpy}) lays it out, with the host's values: texts without their padding (empty
 * when blank), the user in upper case. {@link TskRecordCodec} reads and writes the fixed-width
 * form.
 *
 * @param id TSK-ID, the key: H... for a task from the host, T plus the TASK.ID for one from the
 *     Taskboard (see {@link HostKey})
 * @param title TSK-TITLE
 * @param description TSK-DESC
 * @param status TSK-STATUS
 * @param user TSK-USER
 * @param dueDate TSK-DUE-DATE, null for 00000000
 * @param origin TSK-ORIGIN
 * @param lastUpdate TSK-LAST-UPD
 * @param priority TSK-PRIO; a blank one (a record from before the field) is read as MEDIUM, and the
 *     datasync never writes a blank
 */
public record TskRecord(
    String id,
    String title,
    String description,
    Status status,
    String user,
    LocalDate dueDate,
    Origin origin,
    Instant lastUpdate,
    Priority priority) {

  /** A record of priority MEDIUM. */
  public TskRecord(
      String id,
      String title,
      String description,
      Status status,
      String user,
      LocalDate dueDate,
      Origin origin,
      Instant lastUpdate) {
    this(id, title, description, status, user, dueDate, origin, lastUpdate, Priority.MEDIUM);
  }

  public TskRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(lastUpdate, "lastUpdate");
    Objects.requireNonNull(priority, "priority");
  }

  /** TSK-STATUS with its 88-level values. */
  public enum Status {
    /** TSK-OPEN */
    OPEN("O"),
    /** TSK-IN-PROGRESS */
    IN_PROGRESS("I"),
    /** TSK-DONE */
    DONE("D"),
    /** TSK-DELETED: the host keeps deleted records, with this status. */
    DELETED("X");

    private final String code;

    Status(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    static Optional<Status> ofCode(String code) {
      return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
    }
  }

  /** TSK-ORIGIN with its 88-level values: where the task was created. */
  public enum Origin {
    /** TSK-FROM-HOST */
    FROM_HOST("H"),
    /** TSK-FROM-TASKBOARD */
    FROM_TASKBOARD("T");

    private final String code;

    Origin(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    static Optional<Origin> ofCode(String code) {
      return Arrays.stream(values()).filter(origin -> origin.code.equals(code)).findFirst();
    }
  }

  /** TSK-PRIO with its 88-level values. */
  public enum Priority {
    /** TSK-PRIO-HIGH */
    HIGH("H"),
    /** TSK-PRIO-MEDIUM */
    MEDIUM("M"),
    /** TSK-PRIO-LOW */
    LOW("L");

    private final String code;

    Priority(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    static Optional<Priority> ofCode(String code) {
      return Arrays.stream(values()).filter(priority -> priority.code.equals(code)).findFirst();
    }
  }
}
