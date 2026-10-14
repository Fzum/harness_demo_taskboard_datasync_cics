package com.example.datasync.etl;

import com.example.datasync.host.CicsHostClient;
import com.example.datasync.host.HostKey;
import com.example.datasync.host.InvalidRecordException;
import com.example.datasync.host.TskRecordCodec;
import com.example.datasync.outbox.OutboxRepository;
import com.example.datasync.task.Task;
import com.example.datasync.task.TaskData;
import com.example.datasync.task.TaskRepository;
import com.example.datasync.task.TaskSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The pull ETL, host to Db2: a full sync of the TASK table from every record of TSKMSTR. See E1 to
 * E7 in the README. It writes TASK only, never OUTBOX_EVENT (E6), so its loads are never echoed
 * back to the host.
 */
@Service
public class EtlJob {

  private static final Logger log = LoggerFactory.getLogger(EtlJob.class);

  private enum Outcome {
    INSERTED,
    UPDATED,
    UNCHANGED,
    DELETED,
    SKIPPED,
    ORPHANED,
    REJECTED
  }

  private final CicsHostClient host;
  private final TaskRepository tasks;
  private final OutboxRepository outbox;

  public EtlJob(CicsHostClient host, TaskRepository tasks, OutboxRepository outbox) {
    this.host = host;
    this.tasks = tasks;
    this.outbox = outbox;
  }

  public EtlReport run() {
    Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
    // E4 before E1: a change the relay delivers while TSKBRWS runs is then skipped in this run
    // instead of being overwritten with the record that TSKBRWS read before the change.
    LocalChanges localChanges = LocalChanges.of(outbox.findLatestUnsynced());
    List<String> records = host.browse(); // E1 extract
    List<HostTask> hostTasks = new ArrayList<>();
    for (String record : records) {
      try {
        hostTasks.add(HostRecordTransformer.transform(TskRecordCodec.parse(record))); // E2, E3
      } catch (InvalidRecordException e) {
        log.warn("Rejected host record {}: {}", e.key(), e.getMessage());
        outcomes.merge(Outcome.REJECTED, 1, Integer::sum);
      }
    }
    for (HostTask hostTask : hostTasks) {
      outcomes.merge(load(hostTask, localChanges), 1, Integer::sum); // E5
    }
    EtlReport report =
        new EtlReport(
            records.size(),
            outcomes.getOrDefault(Outcome.INSERTED, 0),
            outcomes.getOrDefault(Outcome.UPDATED, 0),
            outcomes.getOrDefault(Outcome.UNCHANGED, 0),
            outcomes.getOrDefault(Outcome.DELETED, 0),
            outcomes.getOrDefault(Outcome.SKIPPED, 0),
            outcomes.getOrDefault(Outcome.ORPHANED, 0),
            outcomes.getOrDefault(Outcome.REJECTED, 0));
    log.info("ETL run: {}", report); // E7
    return report;
  }

  private Outcome load(HostTask hostTask, LocalChanges localChanges) {
    if (localChanges.cover(hostTask.hostId())) {
      return Outcome.SKIPPED;
    }
    Optional<Task> match = match(hostTask.hostId());
    return switch (hostTask) {
      case HostTask.Deleted deleted -> delete(match);
      case HostTask.Existing existing -> upsert(match, existing.data());
    };
  }

  /**
   * The task a record belongs to: the one with its key as HOST_ID or, for a T key, the task with
   * the TASK.ID in the key that has no HOST_ID yet.
   */
  private Optional<Task> match(String hostId) {
    return tasks
        .findByHostId(hostId)
        .or(() -> HostKey.taskIdOf(hostId).flatMap(tasks::findUnlinked));
  }

  private Outcome delete(Optional<Task> match) {
    if (match.isEmpty()) {
      return Outcome.UNCHANGED; // a deleted record without a task: nothing to do
    }
    tasks.delete(match.get().id());
    return Outcome.DELETED;
  }

  private Outcome upsert(Optional<Task> match, TaskData data) {
    if (match.isEmpty()) {
      if (data.source() == TaskSource.TASKBOARD) {
        return Outcome.ORPHANED; // only the Taskboard creates its tasks
      }
      tasks.insert(data);
      return Outcome.INSERTED;
    }
    Task task = match.get();
    if (task.data().equals(data)) {
      return Outcome.UNCHANGED;
    }
    tasks.update(task.id(), data);
    return Outcome.UPDATED;
  }
}
