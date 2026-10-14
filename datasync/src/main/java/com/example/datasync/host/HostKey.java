package com.example.datasync.host;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host keys (TSK-ID). Tasks that originate on the host have keys like H000001001. A task created in
 * the Taskboard gets "T" plus its TASK.ID zero-padded to 9 digits (task 42 is T000000042) when the
 * datasync first writes it to the host.
 */
public final class HostKey {

  private static final Pattern TASKBOARD_KEY = Pattern.compile("T(\\d{9})");

  private HostKey() {}

  /** The host key of a task created in the Taskboard. */
  public static String forTask(long taskId) {
    return "T%09d".formatted(taskId);
  }

  /** The TASK.ID in a key of the form T plus 9 digits; empty for every other key. */
  public static Optional<Long> taskIdOf(String key) {
    Matcher matcher = TASKBOARD_KEY.matcher(key);
    return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
  }
}
