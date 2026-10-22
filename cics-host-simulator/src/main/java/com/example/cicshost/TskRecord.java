package com.example.cicshost;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Set;

/**
 * A TSK-RECORD as copybook TSKREC ({@code copybooks/TSKREC.cpy}) lays it out: 300 characters,
 * alphanumeric fields left-aligned and padded with spaces. The admin API shows the fields under
 * their copybook names, without the padding.
 */
@JsonPropertyOrder({
  "TSK-ID",
  "TSK-TITLE",
  "TSK-DESC",
  "TSK-STATUS",
  "TSK-USER",
  "TSK-DUE-DATE",
  "TSK-ORIGIN",
  "TSK-LAST-UPD",
  "TSK-PRIO"
})
public record TskRecord(
    @JsonProperty("TSK-ID") String id,
    @JsonProperty("TSK-TITLE") String title,
    @JsonProperty("TSK-DESC") String description,
    @JsonProperty("TSK-STATUS") String status,
    @JsonProperty("TSK-USER") String user,
    @JsonProperty("TSK-DUE-DATE") String dueDate,
    @JsonProperty("TSK-ORIGIN") String origin,
    @JsonProperty("TSK-LAST-UPD") String lastUpdate,
    @JsonProperty("TSK-PRIO") String priority) {

  public static final int LENGTH = 300;

  /** The fields of TSK-RECORD in copybook order. */
  enum Field {
    TSK_ID("TSK-ID", 0, 10),
    TSK_TITLE("TSK-TITLE", 10, 40),
    TSK_DESC("TSK-DESC", 50, 200),
    TSK_STATUS("TSK-STATUS", 250, 1),
    TSK_USER("TSK-USER", 251, 8),
    TSK_DUE_DATE("TSK-DUE-DATE", 259, 8),
    TSK_ORIGIN("TSK-ORIGIN", 267, 1),
    TSK_LAST_UPD("TSK-LAST-UPD", 268, 26),
    TSK_PRIO("TSK-PRIO", 294, 1),
    FILLER("FILLER", 295, 5);

    final String copybookName;
    final int offset;
    final int length;

    Field(String copybookName, int offset, int length) {
      this.copybookName = copybookName;
      this.offset = offset;
      this.length = length;
    }

    String read(String record) {
      return record.substring(offset, offset + length).stripTrailing();
    }
  }

  private static final Set<String> STATUSES = Set.of("O", "I", "D", "X");
  private static final Set<String> ORIGINS = Set.of("H", "T");
  private static final Set<String> PRIORITIES = Set.of("", "H", "M", "L");
  private static final DateTimeFormatter DUE_DATE =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter LAST_UPD =
      DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SSSSSS")
          .withResolverStyle(ResolverStyle.STRICT);

  /** Reads a record and checks it the way TSKUPDT does before it writes to TSKMSTR. */
  public static TskRecord parse(String record) {
    if (record.length() != LENGTH) {
      throw new InvalidRecordException(
          "record length is " + record.length() + ", TSK-RECORD has " + LENGTH);
    }
    TskRecord parsed =
        new TskRecord(
            Field.TSK_ID.read(record),
            Field.TSK_TITLE.read(record),
            Field.TSK_DESC.read(record),
            Field.TSK_STATUS.read(record),
            Field.TSK_USER.read(record),
            Field.TSK_DUE_DATE.read(record),
            Field.TSK_ORIGIN.read(record),
            Field.TSK_LAST_UPD.read(record),
            Field.TSK_PRIO.read(record));
    parsed.check();
    return parsed;
  }

  private void check() {
    if (id.isEmpty()) {
      throw new InvalidRecordException("TSK-ID is blank");
    }
    if (title.isEmpty()) {
      throw new InvalidRecordException("TSK-TITLE is blank");
    }
    if (!STATUSES.contains(status)) {
      throw new InvalidRecordException("TSK-STATUS '" + status + "' is not O, I, D or X");
    }
    if (!isDueDate(dueDate)) {
      throw new InvalidRecordException(
          "TSK-DUE-DATE '" + dueDate + "' is not a date YYYYMMDD or 00000000");
    }
    if (!ORIGINS.contains(origin)) {
      throw new InvalidRecordException("TSK-ORIGIN '" + origin + "' is not H or T");
    }
    if (!isTimestamp(lastUpdate)) {
      throw new InvalidRecordException(
          "TSK-LAST-UPD '" + lastUpdate + "' is not a timestamp YYYY-MM-DD-HH.MM.SS.NNNNNN");
    }
    if (!PRIORITIES.contains(priority)) {
      throw new InvalidRecordException("TSK-PRIO '" + priority + "' is not H, M, L or blank");
    }
  }

  private static boolean isDueDate(String value) {
    if (value.equals("00000000")) {
      return true;
    }
    if (!value.matches("\\d{8}")) {
      return false;
    }
    try {
      LocalDate.parse(value, DUE_DATE);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static boolean isTimestamp(String value) {
    try {
      LocalDateTime.parse(value, LAST_UPD);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
