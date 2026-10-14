package com.example.datasync;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** The host stub in place of the CICS host, and the backend's view of the database. */
@TestConfiguration(proxyBeanMethods = false)
public class DatasyncTestConfiguration {

  @Bean(destroyMethod = "stop")
  HostStub hostStub() {
    return HostStub.start();
  }

  @Bean
  DynamicPropertyRegistrar hostStubBaseUrl(HostStub host) {
    return registry -> registry.add("datasync.host.base-url", host::baseUrl);
  }

  @Bean
  TaskboardDatabase taskboardDatabase(JdbcClient jdbc) {
    return new TaskboardDatabase(jdbc);
  }
}
