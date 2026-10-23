package com.example.datasync;

import com.example.datasync.host.TskRecord;
import com.example.datasync.host.TskRecord.Origin;
import com.example.datasync.host.TskRecord.Priority;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.host.TskRecordCodec;
import java.time.Instant;
import java.time.LocalDate;

/** Builds host records for tests, by default an OPEN task from the host. */
public final class HostRecords {

  public static final Instant LAST_UPDATE = Instant.parse("2026-10-01T07:42:10Z");

  private final String id;
  private String title = "Clarify returned direct debit";
  private String description = "";
  private Status status = Status.OPEN;
  private String user = "";
  private LocalDate dueDate;
  private Origin origin = Origin.FROM_HOST;
  private Instant lastUpdate = LAST_UPDATE;
  private Priority priority;

  private HostRecords(String id) {
    this.id = id;
  }

  public static HostRecords hostRecord(String id) {
    return new HostRecords(id);
  }

  /** A record the datasync wrote for a Taskboard task. */
  public static HostRecords taskboardRecord(String id) {
    return new HostRecords(id).origin(Origin.FROM_TASKBOARD);
  }

  public HostRecords title(String title) {
    this.title = title;
    return this;
  }

  public HostRecords description(String description) {
    this.description = description;
    return this;
  }

  public HostRecords status(Status status) {
    this.status = status;
    return this;
  }

  public HostRecords user(String user) {
    this.user = user;
    return this;
  }

  public HostRecords dueDate(LocalDate dueDate) {
    this.dueDate = dueDate;
    return this;
  }

  public HostRecords origin(Origin origin) {
    this.origin = origin;
    return this;
  }

  public HostRecords lastUpdate(Instant lastUpdate) {
    this.lastUpdate = lastUpdate;
    return this;
  }

  public HostRecords priority(Priority priority) {
    this.priority = priority;
    return this;
  }

  public TskRecord record() {
    return new TskRecord(
        id, title, description, status, user, dueDate, origin, lastUpdate, priority);
  }

  /** The record in its fixed-width form. */
  public String line() {
    return TskRecordCodec.format(record());
  }
}
