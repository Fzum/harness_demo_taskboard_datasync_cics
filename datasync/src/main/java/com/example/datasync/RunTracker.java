package com.example.datasync;

import com.example.datasync.host.HostUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one sync job, on schedule or on request, one run at a time, and keeps its last run for the
 * status API.
 */
public class RunTracker<R> {

  private static final Logger log = LoggerFactory.getLogger(RunTracker.class);

  private final String job;
  private final Supplier<R> body;
  private final Clock clock;
  private volatile SyncRun<R> lastRun;

  public RunTracker(String job, Supplier<R> body, Clock clock) {
    this.job = job;
    this.body = body;
    this.clock = clock;
  }

  /**
   * Runs the job now, once a run in progress has finished, and returns how it went. If the run
   * fails as a whole, it is kept as a failed run and the exception is rethrown.
   */
  public synchronized SyncRun<R> run() {
    Instant startedAt = clock.instant();
    try {
      R report = body.get();
      lastRun = SyncRun.succeeded(startedAt, clock.instant(), report);
      return lastRun;
    } catch (RuntimeException e) {
      lastRun = SyncRun.failed(startedAt, clock.instant(), describe(e));
      if (e instanceof HostUnavailableException) {
        log.warn("{} run failed: {}", job, e.getMessage());
      } else {
        log.error("{} run failed", job, e);
      }
      throw e;
    }
  }

  /** Runs the job for the scheduler, which only needs the failure logged and kept, not thrown. */
  void runScheduled() {
    try {
      run();
    } catch (RuntimeException e) {
      // already logged and kept as the last run
    }
  }

  public Optional<SyncRun<R>> lastRun() {
    return Optional.ofNullable(lastRun);
  }

  private static String describe(RuntimeException e) {
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }
}
