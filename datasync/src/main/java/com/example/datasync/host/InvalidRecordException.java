package com.example.datasync.host;

/**
 * A host record that does not fit copybook TSKREC, or the Taskboard, or a value that does not fit
 * its field. The message is the reason; {@link #key()} is the record's TSK-ID as far as it can be
 * read.
 */
public class InvalidRecordException extends RuntimeException {

  private final String key;

  public InvalidRecordException(String key, String reason) {
    super(reason);
    this.key = key;
  }

  public String key() {
    return key;
  }
}
