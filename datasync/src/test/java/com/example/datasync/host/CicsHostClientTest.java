package com.example.datasync.host;

import static com.example.datasync.HostRecords.hostRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.datasync.DatasyncProperties;
import com.example.datasync.HostStub;
import com.example.datasync.HostStub.Request;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CicsHostClientTest {

  private static final HostStub host = HostStub.start();

  private final CicsHostClient client = client(host.baseUrl(), Duration.ofSeconds(2));

  @AfterAll
  static void stopHost() {
    host.stop();
  }

  @BeforeEach
  void resetHost() {
    host.reset();
  }

  @Test
  void browseReturnsEveryRecordOfTskmstrInKeyOrder() {
    String first = hostRecord("H000001001").line();
    String second = hostRecord("H000001002").line();
    host.put(second, first);

    assertThat(client.browse()).containsExactly(first, second);
    assertThat(host.requests()).extracting(Request::path).containsExactly("/cics/TSKBRWS");
  }

  @Test
  void browseOfAnEmptyFileReturnsNoRecords() {
    assertThat(client.browse()).isEmpty();
  }

  @Test
  void updateSendsTheRecordAsPlainTextToTskupdt() {
    String record = hostRecord("H000001001").line();

    client.update(record);

    assertThat(host.requests())
        .containsExactly(new Request("/cics/TSKUPDT", "text/plain;charset=UTF-8", record));
    assertThat(host.records()).containsExactly(record);
  }

  @Test
  void rc12IsARejectionOfTheRecord() {
    host.rejectUpdatesOf("H000001001", "TSK-USER BKRAUSE is not authorised");

    assertThatThrownBy(() -> client.update(hostRecord("H000001001").line()))
        .isInstanceOf(HostRejectedRecordException.class)
        .hasMessage("TSKUPDT rejected the record: RC=12 TSK-USER BKRAUSE is not authorised");
  }

  @Test
  void a5xxMeansTheHostIsUnavailable() {
    host.down();

    assertThatThrownBy(client::browse)
        .isInstanceOf(HostUnavailableException.class)
        .hasMessage("TSKBRWS answered HTTP 503: CICS region unavailable");
    assertThatThrownBy(() -> client.update(hostRecord("H000001001").line()))
        .isInstanceOf(HostUnavailableException.class)
        .hasMessage("TSKUPDT answered HTTP 503: CICS region unavailable");
  }

  @Test
  void anyOtherAnswerMeansTheHostIsUnavailable() {
    host.answer(200, "RC=04 TSKMSTR is being reorganised");

    assertThatThrownBy(() -> client.update(hostRecord("H000001001").line()))
        .isInstanceOf(HostUnavailableException.class)
        .hasMessage("TSKUPDT answered 'RC=04 TSKMSTR is being reorganised' instead of RC=00");

    host.answer(404, "no such program");
    assertThatThrownBy(client::browse)
        .isInstanceOf(HostUnavailableException.class)
        .hasMessage("TSKBRWS answered HTTP 404: no such program");
  }

  @Test
  void anUnreachableHostIsUnavailable() {
    HostStub gone = HostStub.start();
    gone.stop();

    assertThatThrownBy(client(gone.baseUrl(), Duration.ofSeconds(2))::browse)
        .isInstanceOf(HostUnavailableException.class)
        .hasMessage("TSKBRWS failed: cannot connect to " + gone.baseUrl());
  }

  @Test
  void aHostThatAnswersTooLateIsUnavailable() {
    host.delayAnswers(Duration.ofSeconds(2));

    assertThatThrownBy(client(host.baseUrl(), Duration.ofMillis(200))::browse)
        .isInstanceOf(HostUnavailableException.class)
        .hasMessage("TSKBRWS failed: no answer within 200 ms");
  }

  private static CicsHostClient client(String baseUrl, Duration readTimeout) {
    return new CicsHostClient(
        RestClient.builder(),
        new DatasyncProperties.Host(URI.create(baseUrl), Duration.ofSeconds(1), readTimeout));
  }
}
