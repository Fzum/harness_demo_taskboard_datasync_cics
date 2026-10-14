package com.example.datasync.outbox;

import com.example.datasync.DatasyncProperties;
import com.example.datasync.host.CicsHostClient;
import com.example.datasync.host.HostRejectedRecordException;
import com.example.datasync.host.HostUnavailableException;
import com.example.datasync.host.InvalidRecordException;
import com.example.datasync.host.TskRecord;
import com.example.datasync.host.TskRecordCodec;
import com.example.datasync.task.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox relay, Db2 to host: writes the changes the backend records in OUTBOX_EVENT to the host
 * through TSKUPDT. See R1 to R7 in the README.
 */
@Service
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private enum Outcome {
    PROCESSED,
    DEFERRED,
    FAILED,
    SKIPPED
  }

  private final OutboxRepository outbox;
  private final TaskRepository tasks;
  private final CicsHostClient host;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final int batchSize;
  private final int maxAttempts;

  public OutboxRelay(
      OutboxRepository outbox,
      TaskRepository tasks,
      CicsHostClient host,
      TransactionTemplate transaction,
      Clock clock,
      DatasyncProperties properties) {
    this.outbox = outbox;
    this.tasks = tasks;
    this.host = host;
    this.transaction = transaction;
    this.clock = clock;
    this.batchSize = properties.outbox().batchSize();
    this.maxAttempts = properties.outbox().maxAttempts();
  }

  /**
   * Relays the oldest PENDING events in ID order (R1). Once an event of a task stays PENDING after
   * a failed attempt, the task's later events wait for the next run, so each task's changes reach
   * the host in order (R2). FAILED events do not hold anything up: every payload is a full snapshot
   * of its task.
   */
  public RelayReport run() {
    List<OutboxEvent> events = outbox.findPending(batchSize);
    Set<Long> deferredTasks = new HashSet<>();
    Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
    for (OutboxEvent event : events) {
      Outcome outcome = deferredTasks.contains(event.taskId()) ? Outcome.SKIPPED : relay(event);
      if (outcome == Outcome.DEFERRED) {
        deferredTasks.add(event.taskId());
      }
      outcomes.merge(outcome, 1, Integer::sum);
    }
    RelayReport report =
        new RelayReport(
            events.size(),
            outcomes.getOrDefault(Outcome.PROCESSED, 0),
            outcomes.getOrDefault(Outcome.DEFERRED, 0),
            outcomes.getOrDefault(Outcome.FAILED, 0),
            outcomes.getOrDefault(Outcome.SKIPPED, 0));
    if (!events.isEmpty()) {
      log.info("Outbox relay run: {}", report);
    }
    return report;
  }

  private Outcome relay(OutboxEvent event) {
    int attempt = event.attempts() + 1;
    TskRecord record;
    try {
      record = InverseTransformer.toRecord(event); // R3
      host.update(TskRecordCodec.format(record)); // R4
    } catch (InvalidEventException | InvalidRecordException | HostRejectedRecordException e) {
      return failed(event, attempt, e.getMessage()); // R5, permanent
    } catch (HostUnavailableException e) {
      return attempt < maxAttempts // R5, transient
          ? deferred(event, attempt, e.getMessage())
          : failed(event, attempt, e.getMessage());
    }
    processed(event, attempt, record.id());
    return Outcome.PROCESSED;
  }

  /**
   * R4 in one transaction (R7): PROCESSED, the task's host key if it has none yet, and the task's
   * older FAILED events, whose changes this full snapshot has brought to the host as well.
   */
  private void processed(OutboxEvent event, int attempt, String hostId) {
    Instant now = clock.instant();
    transaction.executeWithoutResult(
        status -> {
          outbox.markProcessed(event.id(), attempt, now);
          tasks.linkHostId(event.taskId(), hostId);
          outbox.resolveSuperseded(event.taskId(), event.id(), now);
        });
    log.info(
        "Outbox event {} ({}) of task {} written to the host as {}",
        event.id(),
        event.eventType(),
        event.taskId(),
        hostId);
  }

  private Outcome deferred(OutboxEvent event, int attempt, String error) {
    outbox.markAttemptFailed(event.id(), attempt, OutboxStatus.PENDING, error);
    log.warn(
        "Outbox event {} of task {}: attempt {} of {} failed, will retry: {}",
        event.id(),
        event.taskId(),
        attempt,
        maxAttempts,
        error);
    return Outcome.DEFERRED;
  }

  /** R6: the event becomes FAILED, a host inconsistency. */
  private Outcome failed(OutboxEvent event, int attempt, String error) {
    outbox.markAttemptFailed(event.id(), attempt, OutboxStatus.FAILED, error);
    log.error(
        "Host inconsistency: outbox event {} of task {} failed: {}",
        event.id(),
        event.taskId(),
        error);
    return Outcome.FAILED;
  }
}
