package com.example.datasync.outbox;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A FAILED outbox event is a host inconsistency: a change in the Taskboard that the host does not
 * have. An operator resolves it by retrying the event once the cause is fixed (R6).
 */
@Service
public class HostInconsistencies {

  private static final Logger log = LoggerFactory.getLogger(HostInconsistencies.class);

  private final OutboxRepository outbox;

  public HostInconsistencies(OutboxRepository outbox) {
    this.outbox = outbox;
  }

  /** The FAILED events, oldest first. */
  public List<OutboxEvent> list() {
    return outbox.findFailed();
  }

  /**
   * Sets a FAILED event back to PENDING with ATTEMPTS 0, so the next relay run writes it again.
   *
   * @return the event, or empty if there is no FAILED event with this id
   */
  public Optional<OutboxEvent> retry(long eventId) {
    if (!outbox.retry(eventId)) {
      return Optional.empty();
    }
    log.info("Outbox event {} is PENDING again for a retry", eventId);
    return outbox.findById(eventId);
  }
}
