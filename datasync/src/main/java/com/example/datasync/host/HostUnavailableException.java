package com.example.datasync.host;

/**
 * The host could not be reached, did not answer in time, or did not answer the way its CICS
 * programs do (a 5xx, for example). A transient error: a later attempt may succeed.
 */
public class HostUnavailableException extends RuntimeException {

  public HostUnavailableException(String message) {
    super(message);
  }

  public HostUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
