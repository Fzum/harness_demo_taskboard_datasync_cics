package com.example.datasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Keeps the IBM host (CICS, VSAM file TSKMSTR) and the Taskboard's Db2 database eventually
 * consistent: a pull ETL loads the host's records into TASK, and an outbox relay writes the
 * Taskboard's changes back to the host.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DatasyncApplication {

  public static void main(String[] args) {
    SpringApplication.run(DatasyncApplication.class, args);
  }
}
