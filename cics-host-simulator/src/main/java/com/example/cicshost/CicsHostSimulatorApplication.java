package com.example.cicshost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stands in for the CICS region that owns the VSAM file TSKMSTR, for local runs and demos: the CICS
 * programs TSKBRWS and TSKUPDT over HTTP, plus an admin API to look into the file and simulate an
 * outage.
 */
@SpringBootApplication
public class CicsHostSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(CicsHostSimulatorApplication.class, args);
  }
}
