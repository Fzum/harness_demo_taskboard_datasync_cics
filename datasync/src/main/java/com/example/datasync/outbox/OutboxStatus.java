package com.example.datasync.outbox;

/** OUTBOX_EVENT.STATUS. */
public enum OutboxStatus {
  /** Written by the backend, not yet on the host. */
  PENDING,
  /** On the host. */
  PROCESSED,
  /** The relay gave up on it: a host inconsistency until an operator retries it. */
  FAILED
}
