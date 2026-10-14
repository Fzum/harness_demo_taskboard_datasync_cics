package com.example.datasync.etl;

import com.example.datasync.task.TaskData;

/** What a host record says about its task, in the Taskboard's terms (E3). */
sealed interface HostTask {

  /** The record's TSK-ID. */
  String hostId();

  /** A record with status O, I or D: the task as the host has it. */
  record Existing(TaskData data) implements HostTask {

    @Override
    public String hostId() {
      return data.hostId();
    }
  }

  /** A record with status X: the host has deleted the task. */
  record Deleted(String hostId) implements HostTask {}
}
