package com.example.datasync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An in-process stand-in for the CICS host: TSKBRWS and TSKUPDT on a JDK HTTP server over an
 * in-memory TSKMSTR, with switches for the failures the datasync has to handle.
 */
public final class HostStub {

  /** A request the stub received. */
  public record Request(String path, String contentType, String body) {}

  private record Answer(int status, String body) {}

  private static final Answer REGION_DOWN = new Answer(503, "CICS region unavailable");

  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final NavigableMap<String, String> tskmstr = new ConcurrentSkipListMap<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Answer> updateAnswers = new ConcurrentHashMap<>();
  private volatile Answer answer;
  private volatile Duration delay = Duration.ZERO;

  private HostStub() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.setExecutor(executor);
  }

  public static HostStub start() {
    HostStub stub = new HostStub();
    stub.server.start();
    return stub;
  }

  public String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  /** Back to an empty TSKMSTR and a host that answers like CICS. */
  public void reset() {
    tskmstr.clear();
    requests.clear();
    updateAnswers.clear();
    answer = null;
    delay = Duration.ZERO;
  }

  /** Stores records in TSKMSTR as they are, valid or not, keyed by their first 10 characters. */
  public void put(String... records) {
    for (String record : records) {
      tskmstr.put(keyOf(record), record);
    }
  }

  /** The records of TSKMSTR in key order. */
  public List<String> records() {
    return List.copyOf(tskmstr.values());
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  /** The records TSKUPDT received, oldest first, whatever it answered. */
  public List<String> updates() {
    return requests.stream()
        .filter(request -> request.path().equals("/cics/TSKUPDT"))
        .map(Request::body)
        .toList();
  }

  /** Answers every call with 503, the way a gateway answers while its CICS region is down. */
  public void down() {
    answer = REGION_DOWN;
  }

  public void up() {
    answer = null;
  }

  /** Answers every call with this status and body. */
  public void answer(int status, String body) {
    answer = new Answer(status, body);
  }

  /** Answers TSKUPDT for records with this key with 503. */
  public void failUpdatesOf(String key) {
    updateAnswers.put(key, REGION_DOWN);
  }

  /** Answers TSKUPDT for records with this key with 422 and RC=12. */
  public void rejectUpdatesOf(String key, String reason) {
    updateAnswers.put(key, new Answer(422, "RC=12 " + reason));
  }

  /** Waits this long before every answer. */
  public void delayAnswers(Duration delay) {
    this.delay = delay;
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String path = exchange.getRequestURI().getPath();
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(new Request(path, exchange.getRequestHeaders().getFirst("Content-Type"), body));
      Thread.sleep(delay);
      Answer reply = answer(path, body);
      byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
      exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  private Answer answer(String path, String body) {
    if (answer != null) {
      return answer;
    }
    return switch (path) {
      case "/cics/TSKBRWS" -> new Answer(200, String.join("", lines(records())));
      case "/cics/TSKUPDT" -> update(body);
      default -> new Answer(404, "no such program");
    };
  }

  private Answer update(String record) {
    Answer failure = updateAnswers.get(keyOf(record));
    if (failure != null) {
      return failure;
    }
    if (record.length() != 300) {
      return new Answer(422, "RC=12 record length is " + record.length());
    }
    tskmstr.put(keyOf(record), record);
    return new Answer(200, "RC=00");
  }

  private static List<String> lines(List<String> records) {
    return records.stream().map(record -> record + "\n").toList();
  }

  private static String keyOf(String record) {
    return record.substring(0, Math.min(10, record.length())).strip();
  }
}
