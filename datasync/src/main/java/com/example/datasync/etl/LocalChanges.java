package com.example.datasync.etl;

import com.example.datasync.host.HostKey;
import com.example.datasync.outbox.OutboxEvent;
import com.example.datasync.outbox.OutboxPayload;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * E4: the tasks whose latest outbox event is PENDING or FAILED, so their local change has not
 * reached the host yet. Local changes win until they have: the ETL skips these tasks' records. Task
 * id and host key come from the events' payloads, because a task deleted in the Taskboard has no
 * TASK row left to look them up in.
 */
record LocalChanges(Set<Long> taskIds, Set<String> hostIds) {

  static LocalChanges of(List<OutboxEvent> latestUnsyncedEvents) {
    Set<Long> taskIds = new HashSet<>();
    Set<String> hostIds = new HashSet<>();
    for (OutboxEvent event : latestUnsyncedEvents) {
      taskIds.add(event.taskId());
      OutboxPayload.tryParse(event.payload())
          .ifPresent(
              payload -> {
                taskIds.add(payload.taskId());
                if (payload.hostId() != null) {
                  hostIds.add(payload.hostId());
                }
              });
    }
    return new LocalChanges(Set.copyOf(taskIds), Set.copyOf(hostIds));
  }

  /** Whether the record with this key belongs to one of these tasks. */
  boolean cover(String hostKey) {
    return hostIds.contains(hostKey)
        || HostKey.taskIdOf(hostKey).map(taskIds::contains).orElse(false);
  }
}
