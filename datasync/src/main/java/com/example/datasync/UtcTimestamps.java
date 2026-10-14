package com.example.datasync;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The TIMESTAMP columns of the Taskboard schema hold UTC. These conversions keep it that way
 * whatever the time zone of the JVM or the database session.
 */
public final class UtcTimestamps {

  private UtcTimestamps() {}

  public static LocalDateTime toColumn(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public static Instant fromColumn(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
