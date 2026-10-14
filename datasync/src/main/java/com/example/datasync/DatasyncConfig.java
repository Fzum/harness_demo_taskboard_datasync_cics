package com.example.datasync;

import com.example.datasync.etl.EtlJob;
import com.example.datasync.etl.EtlReport;
import com.example.datasync.host.CicsHostClient;
import com.example.datasync.outbox.OutboxRelay;
import com.example.datasync.outbox.RelayReport;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class DatasyncConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  CicsHostClient cicsHostClient(RestClient.Builder restClient, DatasyncProperties properties) {
    return new CicsHostClient(restClient, properties.host());
  }

  @Bean
  RunTracker<EtlReport> etlRuns(EtlJob etl, Clock clock) {
    return new RunTracker<>("ETL", etl::run, clock);
  }

  @Bean
  RunTracker<RelayReport> outboxRuns(OutboxRelay relay, Clock clock) {
    return new RunTracker<>("Outbox relay", relay::run, clock);
  }
}
