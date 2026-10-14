package com.example.datasync.api;

import com.example.datasync.RunTracker;
import com.example.datasync.SyncRun;
import com.example.datasync.etl.EtlReport;
import com.example.datasync.host.HostUnavailableException;
import com.example.datasync.outbox.HostInconsistencies;
import com.example.datasync.outbox.OutboxRepository;
import com.example.datasync.outbox.OutboxStatus;
import com.example.datasync.outbox.RelayReport;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The datasync's API: its status, runs on request, and the host inconsistencies. */
@RestController
@RequestMapping("/api/sync")
class SyncController {

  private final RunTracker<EtlReport> etlRuns;
  private final RunTracker<RelayReport> outboxRuns;
  private final OutboxRepository outbox;
  private final HostInconsistencies inconsistencies;

  SyncController(
      RunTracker<EtlReport> etlRuns,
      RunTracker<RelayReport> outboxRuns,
      OutboxRepository outbox,
      HostInconsistencies inconsistencies) {
    this.etlRuns = etlRuns;
    this.outboxRuns = outboxRuns;
    this.outbox = outbox;
    this.inconsistencies = inconsistencies;
  }

  @GetMapping("/status")
  SyncStatus status() {
    Map<OutboxStatus, Long> events = outbox.countByStatus();
    return new SyncStatus(
        new SyncStatus.Etl(etlRuns.lastRun().orElse(null)),
        new SyncStatus.Outbox(
            events.get(OutboxStatus.PENDING),
            events.get(OutboxStatus.PROCESSED),
            events.get(OutboxStatus.FAILED),
            outboxRuns.lastRun().orElse(null)),
        events.get(OutboxStatus.FAILED));
  }

  /** Runs the ETL now and returns the run with its report; 503 if the host is unavailable. */
  @PostMapping("/etl/run")
  SyncRun<EtlReport> runEtl() {
    return etlRuns.run();
  }

  /** Runs the outbox relay now and returns the run with its report. */
  @PostMapping("/outbox/run")
  SyncRun<RelayReport> runOutbox() {
    return outboxRuns.run();
  }

  /** The FAILED outbox events, oldest first. */
  @GetMapping("/inconsistencies")
  List<OutboxEventResponse> inconsistencies() {
    return inconsistencies.list().stream().map(OutboxEventResponse::of).toList();
  }

  /** Sets a FAILED event back to PENDING with ATTEMPTS 0; the next relay run writes it again. */
  @PostMapping("/inconsistencies/{eventId}/retry")
  OutboxEventResponse retry(@PathVariable long eventId) {
    return inconsistencies
        .retry(eventId)
        .map(OutboxEventResponse::of)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No host inconsistency with event id " + eventId));
  }

  @ExceptionHandler
  ProblemDetail hostUnavailable(HostUnavailableException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    problem.setTitle("CICS host unavailable");
    return problem;
  }
}
