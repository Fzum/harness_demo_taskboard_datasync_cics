package com.example.datasync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.datasync.etl.EtlReport;
import com.example.datasync.outbox.RelayReport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

/** The application with its schedules on, as it runs outside the tests. */
@SpringBootTest(properties = "datasync.scheduling.enabled=true")
@ActiveProfiles("test")
@Import(DatasyncTestConfiguration.class)
class SchedulingTest {

  private final RunTracker<EtlReport> etlRuns;
  private final RunTracker<RelayReport> outboxRuns;
  private final ThreadPoolTaskScheduler scheduler;

  SchedulingTest(
      RunTracker<EtlReport> etlRuns,
      RunTracker<RelayReport> outboxRuns,
      ThreadPoolTaskScheduler scheduler) {
    this.etlRuns = etlRuns;
    this.outboxRuns = outboxRuns;
    this.scheduler = scheduler;
  }

  @Test
  void runsTheEtlAndTheRelayAtStartUp() {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> etlRuns.lastRun().isPresent() && outboxRuns.lastRun().isPresent());
  }

  @Test
  void givesTheSchedulerTwoThreadsSoTheJobsNeverDelayEachOther() {
    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
  }
}
