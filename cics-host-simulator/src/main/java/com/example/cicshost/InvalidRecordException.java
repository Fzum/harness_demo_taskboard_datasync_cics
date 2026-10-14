package com.example.cicshost;

/** A COMMAREA that is not a valid TSK-RECORD; the message is the reason TSKUPDT reports. */
public class InvalidRecordException extends RuntimeException {

  public InvalidRecordException(String reason) {
    super(reason);
  }
}
