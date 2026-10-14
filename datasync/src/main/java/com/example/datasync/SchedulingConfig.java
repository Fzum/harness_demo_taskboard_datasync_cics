package com.example.datasync;

import com.example.datasync.etl.EtlReport;
import com.example.datasync.outbox.RelayReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the ETL and the outbox relay with a fixed delay between runs, both for the first time at
 * start-up. The scheduler has two threads ({@code spring.task.scheduling.pool.size}), so the two
 * never delay each other. {@code datasync.scheduling.enabled=false} leaves only the runs on
 * request.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "datasync.scheduling.enabled", matchIfMissing = true)
class SchedulingConfig implements SchedulingConfigurer {

  private final RunTracker<EtlReport> etlRuns;
  private final RunTracker<RelayReport> outboxRuns;
  private final DatasyncProperties properties;

  SchedulingConfig(
      RunTracker<EtlReport> etlRuns,
      RunTracker<RelayReport> outboxRuns,
      DatasyncProperties properties) {
    this.etlRuns = etlRuns;
    this.outboxRuns = outboxRuns;
    this.properties = properties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(etlRuns::runScheduled, properties.etl().interval());
    registrar.addFixedDelayTask(outboxRuns::runScheduled, properties.outbox().interval());
  }
}
