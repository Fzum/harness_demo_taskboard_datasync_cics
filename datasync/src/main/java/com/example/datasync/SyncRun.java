package com.example.datasync;

import java.time.Instant;

/**
 * One run of the ETL or of the outbox relay: when it started and finished, and either its report
 * or, if it failed as a whole, the error.
 */
public record SyncRun<R>(
    Instant startedAt, Instant finishedAt, Outcome outcome, String error, R report) {

  public enum Outcome {
    SUCCEEDED,
    FAILED
  }

  static <R> SyncRun<R> succeeded(Instant startedAt, Instant finishedAt, R report) {
    return new SyncRun<>(startedAt, finishedAt, Outcome.SUCCEEDED, null, report);
  }

  static <R> SyncRun<R> failed(Instant startedAt, Instant finishedAt, String error) {
    return new SyncRun<>(startedAt, finishedAt, Outcome.FAILED, error, null);
  }
}
