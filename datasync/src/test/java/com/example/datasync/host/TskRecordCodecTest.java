package com.example.datasync.host;

import static com.example.datasync.HostRecords.hostRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.datasync.HostRecords;
import com.example.datasync.host.TskRecord.Origin;
import com.example.datasync.host.TskRecord.Status;
import com.example.datasync.host.TskRecordCodec.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TskRecordCodecTest {

  /** H000001001 as TSKMSTR stores it: every field padded to its length, 300 characters. */
  private static final String LINE =
      "H000001001"
          + pad("Clarify returned direct debit", 40)
          + pad("The bank returned the October instalment", 200)
          + "O"
          + "AMUELLER"
          + "20261031"
          + "H"
          + "2026-10-01-07.42.10.000000"
          + "M     ";

  private static final TskRecord RECORD =
      new TskRecord(
          "H000001001",
          "Clarify returned direct debit",
          "The bank returned the October instalment",
          Status.OPEN,
          "AMUELLER",
          LocalDate.of(2026, 10, 31),
          Origin.FROM_HOST,
          Instant.parse("2026-10-01T07:42:10Z"));

  private static final TskRecord SPARSE =
      new TskRecord(
          "T000000042",
          "Call back",
          "",
          Status.DELETED,
          "",
          null,
          Origin.FROM_TASKBOARD,
          Instant.parse("2026-10-14T08:15:30.123456Z"));

  @Test
  void parsesEveryFieldWithoutItsPadding() {
    assertThat(TskRecordCodec.parse(LINE)).isEqualTo(RECORD);
  }

  @Test
  void formatsEveryFieldPaddedToItsLength() {
    assertThat(TskRecordCodec.format(RECORD)).isEqualTo(LINE).hasSize(300);
  }

  @Test
  void writesNoDueDateAsZerosAndBlankTextsAsSpaces() {
    String line = TskRecordCodec.format(SPARSE);

    assertThat(field(line, Field.TSK_DUE_DATE)).isEqualTo("00000000");
    assertThat(field(line, Field.TSK_DESC)).isEqualTo(" ".repeat(200));
    assertThat(field(line, Field.TSK_USER)).isEqualTo(" ".repeat(8));
    assertThat(field(line, Field.FILLER)).isEqualTo(" ".repeat(5));
  }

  @Test
  void roundTripsInBothDirections() {
    assertThat(TskRecordCodec.format(TskRecordCodec.parse(LINE))).isEqualTo(LINE);
    assertThat(TskRecordCodec.parse(TskRecordCodec.format(SPARSE))).isEqualTo(SPARSE);
  }

  @Test
  void writesTheLastUpdateAsAHostTimestampInUtcWithMicroseconds() {
    String line =
        hostRecord("H000001001").lastUpdate(Instant.parse("2026-10-14T08:15:30.123456789Z")).line();

    assertThat(field(line, Field.TSK_LAST_UPD)).isEqualTo("2026-10-14-08.15.30.123456");
  }

  @Test
  void rejectsARecordOfTheWrongLengthWithItsKey() {
    assertThatThrownBy(() -> TskRecordCodec.parse(LINE.substring(0, 299)))
        .isInstanceOf(InvalidRecordException.class)
        .hasMessage("record length is 299, TSK-RECORD has 300")
        .extracting(e -> ((InvalidRecordException) e).key())
        .isEqualTo("H000001001");
    assertThatThrownBy(() -> TskRecordCodec.parse(LINE + " "))
        .hasMessage("record length is 301, TSK-RECORD has 300");
    assertThatThrownBy(() -> TskRecordCodec.parse(""))
        .hasMessage("record length is 0, TSK-RECORD has 300");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          TSK_ID       | ''       | TSK-ID is blank
          TSK_STATUS   | Q        | TSK-STATUS 'Q' is not O, I, D or X
          TSK_STATUS   | ''       | TSK-STATUS '' is not O, I, D or X
          TSK_DUE_DATE | 20261332 | TSK-DUE-DATE '20261332' is not a date YYYYMMDD or 00000000
          TSK_DUE_DATE | 2026103A | TSK-DUE-DATE '2026103A' is not a date YYYYMMDD or 00000000
          TSK_DUE_DATE | -0261031 | TSK-DUE-DATE '-0261031' is not a date YYYYMMDD or 00000000
          TSK_DUE_DATE | ''       | TSK-DUE-DATE '' is not a date YYYYMMDD or 00000000
          TSK_ORIGIN   | Z        | TSK-ORIGIN 'Z' is not H or T
          TSK_LAST_UPD | 2026-10-01 07:42:10        | TSK-LAST-UPD '2026-10-01 07:42:10' is not a timestamp YYYY-MM-DD-HH.MM.SS.NNNNNN
          TSK_LAST_UPD | 2026-02-30-07.42.10.000000 | TSK-LAST-UPD '2026-02-30-07.42.10.000000' is not a timestamp YYYY-MM-DD-HH.MM.SS.NNNNNN
          """)
  void rejectsAnInvalidField(Field field, String value, String reason) {
    assertThatThrownBy(() -> TskRecordCodec.parse(with(LINE, field, value)))
        .isInstanceOf(InvalidRecordException.class)
        .hasMessage(reason);
  }

  @Test
  void refusesToWriteAValueThatDoesNotFitItsField() {
    assertThatThrownBy(() -> hostRecord("H000001001").title("x".repeat(41)).line())
        .isInstanceOf(InvalidRecordException.class)
        .hasMessage("TSK-TITLE has 41 characters, at most 40 fit");
    assertThatThrownBy(() -> hostRecord("H000001001").description("x".repeat(201)).line())
        .hasMessage("TSK-DESC has 201 characters, at most 200 fit");
    assertThatThrownBy(() -> hostRecord("H000001001").user("AMUELLERX").line())
        .hasMessage("TSK-USER has 9 characters, at most 8 fit");
    assertThatThrownBy(() -> HostRecords.taskboardRecord("T1000000000").line())
        .hasMessage("TSK-ID has 11 characters, at most 10 fit");
  }

  @Test
  void refusesToWriteALineBreakOrABlankKey() {
    assertThatThrownBy(() -> hostRecord("H000001001").title("Call\nback").line())
        .hasMessage("TSK-TITLE contains a control character");
    assertThatThrownBy(() -> hostRecord(" ").line()).hasMessage("TSK-ID is blank");
  }

  @Test
  void writesTheCodeOfEveryPriorityAtOffset294() {
    for (TskRecord.Priority priority : TskRecord.Priority.values()) {
      TskRecord record =
          new TskRecord(
              "H000000001",
              "t",
              "",
              TskRecord.Status.OPEN,
              "",
              null,
              TskRecord.Origin.FROM_HOST,
              RECORD.lastUpdate(),
              priority);

      String line = TskRecordCodec.format(record);

      assertThat(line.charAt(294)).isEqualTo(priority.code().charAt(0));
      assertThat(TskRecordCodec.parse(line).priority()).isEqualTo(priority);
    }
  }

  @Test
  void readsABlankPriorityAsMediumAndNeverWritesOne() {
    String line = with(LINE, Field.TSK_PRIO, "");

    TskRecord parsed = TskRecordCodec.parse(line);

    assertThat(parsed.priority()).isEqualTo(TskRecord.Priority.MEDIUM);
    assertThat(field(TskRecordCodec.format(parsed), Field.TSK_PRIO)).isEqualTo("M");
  }

  @Test
  void rejectsAnUnknownPriority() {
    assertThatThrownBy(() -> TskRecordCodec.parse(with(LINE, Field.TSK_PRIO, "Q")))
        .isInstanceOf(InvalidRecordException.class)
        .hasMessageContaining("TSK-PRIO 'Q' is not H, M, L or blank");
  }

  @Test
  void fieldsMatchTheCopybook() throws IOException {
    Pattern pic = Pattern.compile("^\\s+05\\s+(\\S+)\\s+PIC\\s+[X9]\\((\\d+)\\)\\.");
    List<String> copybook =
        Files.readAllLines(Path.of("..", "copybooks", "TSKREC.cpy")).stream()
            .map(pic::matcher)
            .filter(Matcher::find)
            .map(m -> m.group(1) + " " + Integer.parseInt(m.group(2)))
            .toList();

    assertThat(Arrays.stream(Field.values()).map(f -> f.copybookName + " " + f.length))
        .containsExactlyElementsOf(copybook);
    int offset = 0;
    for (Field field : Field.values()) {
      assertThat(field.offset).as(field.copybookName).isEqualTo(offset);
      offset += field.length;
    }
    assertThat(offset).isEqualTo(TskRecordCodec.RECORD_LENGTH);
  }

  private static String pad(String value, int length) {
    return value + " ".repeat(length - value.length());
  }

  private static String field(String line, Field field) {
    return line.substring(field.offset, field.offset + field.length);
  }

  private static String with(String line, Field field, String value) {
    return line.substring(0, field.offset)
        + pad(value, field.length)
        + line.substring(field.offset + field.length);
  }
}
