package com.example.datasync.host;

import com.example.datasync.DatasyncProperties;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the host's CICS programs over HTTP, the way CICS web services or a CICS Transaction Gateway
 * expose them: {@code POST /cics/<program>} with the COMMAREA as plain text. The real host speaks
 * EBCDIC; this model stays readable ASCII.
 */
public class CicsHostClient {

  private static final MediaType COMMAREA =
      new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

  private final DatasyncProperties.Host host;
  private final RestClient http;

  public CicsHostClient(RestClient.Builder restClient, DatasyncProperties.Host host) {
    this.host = host;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(host.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(host.readTimeout());
    this.http = restClient.baseUrl(host.baseUrl()).requestFactory(requestFactory).build();
  }

  /**
   * TSKBRWS: browses TSKMSTR and returns every record, one 300-character line each.
   *
   * @throws HostUnavailableException if the host cannot be reached or fails
   */
  public List<String> browse() {
    return call("TSKBRWS", "").lines().toList();
  }

  /**
   * TSKUPDT: inserts the record or replaces the one with the same TSK-ID. The program is
   * idempotent: writing the same record twice leaves the same file.
   *
   * @throws HostRejectedRecordException if the host rejects the record (RC=12)
   * @throws HostUnavailableException if the host cannot be reached, fails or answers anything but
   *     RC=00
   */
  public void update(String record) {
    String answer = call("TSKUPDT", record).strip();
    if (!answer.startsWith("RC=00")) {
      throw new HostUnavailableException("TSKUPDT answered '" + answer + "' instead of RC=00");
    }
  }

  private String call(String program, String commarea) {
    try {
      return http.post()
          .uri("/cics/{program}", program)
          .contentType(COMMAREA)
          .body(commarea)
          .exchangeForRequiredValue(
              (request, response) ->
                  answer(program, response.getStatusCode(), response.bodyTo(String.class)));
    } catch (ResourceAccessException e) {
      throw new HostUnavailableException(program + " failed: " + reason(e), e);
    }
  }

  private static String answer(String program, HttpStatusCode status, String body) {
    String answer = body == null ? "" : body;
    if (status.is2xxSuccessful()) {
      return answer;
    }
    if (status.isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT) && answer.startsWith("RC=12")) {
      throw new HostRejectedRecordException(program + " rejected the record: " + answer.strip());
    }
    throw new HostUnavailableException(
        program + " answered HTTP " + status.value() + ": " + answer.strip());
  }

  private String reason(ResourceAccessException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
        return "cannot connect to " + host.baseUrl();
      }
      if (cause instanceof HttpTimeoutException) {
        return "no answer within " + host.readTimeout().toMillis() + " ms";
      }
    }
    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
    return root.getMessage() != null ? root.getMessage() : root.toString();
  }
}
