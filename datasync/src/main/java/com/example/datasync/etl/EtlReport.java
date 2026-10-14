package com.example.datasync.etl;

/**
 * What an ETL run did with the records it read (E7). Each record read is counted once more:
 *
 * @param inserted a new task from the host
 * @param updated a task that differed from its record
 * @param unchanged a task that already matched its record, or a deleted record without a task
 * @param deleted a task deleted because its record has status X
 * @param skipped a record whose task has a local change that has not reached the host yet
 * @param orphaned a record from the Taskboard without a task: it never creates one
 * @param rejected a record that is invalid or that the Taskboard cannot hold
 */
public record EtlReport(
    int read,
    int inserted,
    int updated,
    int unchanged,
    int deleted,
    int skipped,
    int orphaned,
    int rejected) {}
