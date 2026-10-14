package com.example.datasync.outbox;

/**
 * What a relay run did with the PENDING events it read. Each event read is counted once more:
 *
 * @param processed written to the host, now PROCESSED
 * @param deferred a transient error; the event stays PENDING for the next run
 * @param failed now FAILED, a host inconsistency
 * @param skipped not tried, because an earlier event of the same task was deferred in this run
 */
public record RelayReport(int read, int processed, int deferred, int failed, int skipped) {}
