package com.example.datasync.outbox;

/**
 * An outbox event the relay cannot turn into a host record: an unknown event type or an unparseable
 * payload. A permanent error: processing it again gives the same result.
 */
public class InvalidEventException extends RuntimeException {

  public InvalidEventException(String message) {
    super(message);
  }
}
