package com.example.datasync.api;

import com.example.datasync.SyncRun;
import com.example.datasync.etl.EtlReport;
import com.example.datasync.outbox.RelayReport;

/**
 * {@code GET /api/sync/status}: the last run of the ETL and of the relay (null before the first),
 * the outbox events by status, and the number of host inconsistencies (FAILED events).
 */
record SyncStatus(Etl etl, Outbox outbox, long hostInconsistencies) {

  record Etl(SyncRun<EtlReport> lastRun) {}

  record Outbox(long pending, long processed, long failed, SyncRun<RelayReport> lastRun) {}
}
