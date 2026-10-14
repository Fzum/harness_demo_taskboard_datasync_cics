package com.example.datasync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.datasync.SyncRun.Outcome;
import com.example.datasync.host.HostUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RunTrackerTest {

  private static final Instant START = Instant.parse("2026-10-14T08:15:30Z");

  private final TestClock clock = new TestClock();

  @Test
  void hasNoLastRunBeforeTheFirst() {
    assertThat(new RunTracker<>("ETL", () -> "report", clock).lastRun()).isEmpty();
  }

  @Test
  void keepsASuccessfulRunWithItsReportAndDuration() {
    RunTracker<String> runs = new RunTracker<>("ETL", () -> takes(Duration.ofSeconds(3)), clock);

    SyncRun<String> run = runs.run();

    assertThat(run)
        .isEqualTo(new SyncRun<>(START, START.plusSeconds(3), Outcome.SUCCEEDED, null, "report"));
    assertThat(runs.lastRun()).contains(run);
  }

  @Test
  void keepsAFailedRunWithItsErrorAndRethrows() {
    RunTracker<String> runs =
        new RunTracker<>(
            "ETL",
            () -> {
              takes(Duration.ofSeconds(2));
              throw new HostUnavailableException("TSKBRWS failed: cannot connect");
            },
            clock);

    assertThatThrownBy(runs::run).isInstanceOf(HostUnavailableException.class);
    assertThat(runs.lastRun())
        .contains(
            new SyncRun<>(
                START,
                START.plusSeconds(2),
                Outcome.FAILED,
                "TSKBRWS failed: cannot connect",
                null));
  }

  @Test
  void aScheduledRunOnlyKeepsTheFailure() {
    RunTracker<String> runs =
        new RunTracker<>(
            "Outbox relay",
            () -> {
              throw new IllegalStateException();
            },
            clock);

    runs.runScheduled();

    assertThat(runs.lastRun())
        .hasValueSatisfying(run -> assertThat(run.error()).isEqualTo("IllegalStateException"));
  }

  /** A job that takes this long on the test clock. */
  private String takes(Duration duration) {
    clock.now = clock.now.plus(duration);
    return "report";
  }

  private static final class TestClock extends Clock {

    private Instant now = START;

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }
}
