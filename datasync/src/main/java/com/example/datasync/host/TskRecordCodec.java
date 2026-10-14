package com.example.datasync.host;

import com.example.datasync.host.TskRecord.Origin;
import com.example.datasync.host.TskRecord.Status;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Reads and writes TSK-RECORDs in their fixed-width form: 300 characters as laid out by copybook
 * TSKREC. Alphanumeric fields are left-aligned and padded with spaces, TSK-DUE-DATE is YYYYMMDD
 * (00000000 for none), TSK-LAST-UPD a host timestamp YYYY-MM-DD-HH.MM.SS.NNNNNN in UTC.
 */
public final class TskRecordCodec {

  public static final int RECORD_LENGTH = 300;

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
    FILLER("FILLER", 294, 6);

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

  private static final String NO_DUE_DATE = "00000000";
  private static final DateTimeFormatter DUE_DATE =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter LAST_UPD =
      DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SSSSSS")
          .withResolverStyle(ResolverStyle.STRICT);

  private TskRecordCodec() {}

  /**
   * Decodes one record.
   *
   * @throws InvalidRecordException if the record has the wrong length, no key, an unknown status or
   *     origin, or an invalid due date or timestamp
   */
  public static TskRecord parse(String record) {
    String key = record.substring(0, Math.min(record.length(), Field.TSK_ID.length)).strip();
    if (record.length() != RECORD_LENGTH) {
      throw new InvalidRecordException(
          key, "record length is " + record.length() + ", TSK-RECORD has " + RECORD_LENGTH);
    }
    if (key.isEmpty()) {
      throw new InvalidRecordException(key, "TSK-ID is blank");
    }
    return new TskRecord(
        Field.TSK_ID.read(record),
        Field.TSK_TITLE.read(record),
        Field.TSK_DESC.read(record),
        status(key, Field.TSK_STATUS.read(record)),
        Field.TSK_USER.read(record),
        dueDate(key, Field.TSK_DUE_DATE.read(record)),
        origin(key, Field.TSK_ORIGIN.read(record)),
        lastUpdate(key, Field.TSK_LAST_UPD.read(record)));
  }

  /**
   * Encodes one record, padding every field to its length.
   *
   * @throws InvalidRecordException if the key is blank or a value does not fit its field
   */
  public static String format(TskRecord record) {
    String key = record.id();
    if (key.isBlank()) {
      throw new InvalidRecordException(key, "TSK-ID is blank");
    }
    StringBuilder line = new StringBuilder(" ".repeat(RECORD_LENGTH));
    put(line, Field.TSK_ID, key, key);
    put(line, Field.TSK_TITLE, record.title(), key);
    put(line, Field.TSK_DESC, record.description(), key);
    put(line, Field.TSK_STATUS, record.status().code(), key);
    put(line, Field.TSK_USER, record.user(), key);
    put(
        line,
        Field.TSK_DUE_DATE,
        record.dueDate() == null ? NO_DUE_DATE : DUE_DATE.format(record.dueDate()),
        key);
    put(line, Field.TSK_ORIGIN, record.origin().code(), key);
    put(
        line,
        Field.TSK_LAST_UPD,
        LAST_UPD.format(LocalDateTime.ofInstant(record.lastUpdate(), ZoneOffset.UTC)),
        key);
    return line.toString();
  }

  private static void put(StringBuilder line, Field field, String value, String key) {
    if (value.length() > field.length) {
      throw new InvalidRecordException(
          key,
          "%s has %d characters, at most %d fit"
              .formatted(field.copybookName, value.length(), field.length));
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidRecordException(key, field.copybookName + " contains a control character");
    }
    line.replace(field.offset, field.offset + value.length(), value);
  }

  private static Status status(String key, String code) {
    return Status.ofCode(code)
        .orElseThrow(
            () -> new InvalidRecordException(key, "TSK-STATUS '" + code + "' is not O, I, D or X"));
  }

  private static Origin origin(String key, String code) {
    return Origin.ofCode(code)
        .orElseThrow(
            () -> new InvalidRecordException(key, "TSK-ORIGIN '" + code + "' is not H or T"));
  }

  private static LocalDate dueDate(String key, String value) {
    if (value.equals(NO_DUE_DATE)) {
      return null;
    }
    if (value.matches("\\d{8}")) {
      try {
        return LocalDate.parse(value, DUE_DATE);
      } catch (DateTimeParseException e) {
        // eight digits, but not a date such as 20261332: reported below
      }
    }
    throw new InvalidRecordException(
        key, "TSK-DUE-DATE '" + value + "' is not a date YYYYMMDD or 00000000");
  }

  private static Instant lastUpdate(String key, String value) {
    try {
      return LocalDateTime.parse(value, LAST_UPD).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      throw new InvalidRecordException(
          key, "TSK-LAST-UPD '" + value + "' is not a timestamp YYYY-MM-DD-HH.MM.SS.NNNNNN");
    }
  }
}
