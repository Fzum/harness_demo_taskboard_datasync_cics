package com.example.datasync.host;

/**
 * TSKUPDT rejected the record as invalid (RC=12). A permanent error: sending the same record again
 * gets the same answer.
 */
public class HostRejectedRecordException extends RuntimeException {

  public HostRejectedRecordException(String message) {
    super(message);
  }
}
