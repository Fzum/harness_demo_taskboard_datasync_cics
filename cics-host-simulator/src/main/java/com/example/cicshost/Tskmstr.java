package com.example.cicshost;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The VSAM KSDS TSKMSTR, in memory: records keyed and sorted by TSK-ID, loaded from a fixed-width
 * seed file at start and on reset.
 */
@Component
public final class Tskmstr {

  private static final Logger log = LoggerFactory.getLogger(Tskmstr.class);

  private final Resource seed;
  private final NavigableMap<String, String> records = new TreeMap<>();

  public Tskmstr(@Value("${simulator.seed}") Resource seed) {
    this.seed = seed;
    reset();
  }

  /** Every record in key order, as TSKBRWS returns them. */
  public synchronized List<String> browse() {
    return List.copyOf(records.values());
  }

  /** Inserts the record or replaces the one with its TSK-ID, as TSKUPDT does. */
  public synchronized TskRecord write(String record) {
    TskRecord parsed = TskRecord.parse(record);
    records.put(parsed.id(), record);
    return parsed;
  }

  /** Empties the file and loads the seed again. */
  public synchronized void reset() {
    records.clear();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(seed.getInputStream(), StandardCharsets.UTF_8))) {
      reader.lines().forEach(this::write);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the TSKMSTR seed " + seed, e);
    }
    log.info("TSKMSTR loaded with {} records from {}", records.size(), seed.getDescription());
  }

  /** Records as text, one per line, the way the CICS programs and the admin API send them. */
  public static String asText(List<String> records) {
    StringBuilder text = new StringBuilder();
    records.forEach(record -> text.append(record).append('\n'));
    return text.toString();
  }
}
