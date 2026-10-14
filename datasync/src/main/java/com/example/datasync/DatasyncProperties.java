package com.example.datasync;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code datasync.*} settings. The defaults are in {@code application.properties}. */
@ConfigurationProperties("datasync")
public record DatasyncProperties(Host host, Etl etl, Outbox outbox) {

  /** The CICS host: where its programs are called and how long a call may take. */
  public record Host(URI baseUrl, Duration connectTimeout, Duration readTimeout) {}

  /** The pull ETL, host to Db2. */
  public record Etl(Duration interval) {}

  /** The outbox relay, Db2 to host. */
  public record Outbox(Duration interval, int batchSize, int maxAttempts) {

    public Outbox {
      if (batchSize < 1) {
        throw new IllegalArgumentException("datasync.outbox.batch-size must be at least 1");
      }
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("datasync.outbox.max-attempts must be at least 1");
      }
    }
  }
}
