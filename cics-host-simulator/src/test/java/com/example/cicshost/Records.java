package com.example.cicshost;

/** Builds fixed-width TSK-RECORDs for tests. */
final class Records {

  private Records() {}

  static String record(
      String id,
      String title,
      String description,
      String status,
      String user,
      String dueDate,
      String origin,
      String lastUpdate) {
    return pad(id, 10)
        + pad(title, 40)
        + pad(description, 200)
        + pad(status, 1)
        + pad(user, 8)
        + pad(dueDate, 8)
        + pad(origin, 1)
        + pad(lastUpdate, 26)
        + pad("", 6);
  }

  /** A record the datasync writes for a Taskboard task. */
  static String taskboardRecord(String id, String title, String status) {
    return record(id, title, "", status, "AMUELLER", "20261031", "T", "2026-10-14-08.15.30.123000");
  }

  /** The record with one field overwritten (and padded to its length). */
  static String with(String record, TskRecord.Field field, String value) {
    return record.substring(0, field.offset)
        + pad(value, field.length)
        + record.substring(field.offset + field.length);
  }

  private static String pad(String value, int length) {
    return value + " ".repeat(length - value.length());
  }
}
