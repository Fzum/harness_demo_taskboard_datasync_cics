package com.example.cicshost;

import static com.example.cicshost.Records.record;
import static com.example.cicshost.Records.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cicshost.TskRecord.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TskRecordTest {

  private static final String VALID =
      record(
          "H000001001",
          "Clarify returned direct debit",
          "The bank returned the October instalment",
          "O",
          "AMUELLER",
          "20261031",
          "H",
          "2026-10-01-07.42.10.000000");

  @Test
  void readsEveryFieldWithoutItsPadding() {
    assertThat(TskRecord.parse(VALID))
        .isEqualTo(
            new TskRecord(
                "H000001001",
                "Clarify returned direct debit",
                "The bank returned the October instalment",
                "O",
                "AMUELLER",
                "20261031",
                "H",
                "2026-10-01-07.42.10.000000",
                ""));
  }

  @Test
  void acceptsADeletedRecordWithoutUserAndDueDate() {
    String deleted =
        with(
            with(with(VALID, Field.TSK_STATUS, "X"), Field.TSK_USER, ""),
            Field.TSK_DUE_DATE,
            "00000000");

    assertThat(TskRecord.parse(deleted))
        .extracting(TskRecord::status, TskRecord::user, TskRecord::dueDate)
        .containsExactly("X", "", "00000000");
  }

  @Test
  void rejectsARecordOfTheWrongLength() {
    assertThatThrownBy(() -> TskRecord.parse(VALID + " "))
        .isInstanceOf(InvalidRecordException.class)
        .hasMessage("record length is 301, TSK-RECORD has 300");
    assertThatThrownBy(() -> TskRecord.parse(VALID.substring(1)))
        .hasMessage("record length is 299, TSK-RECORD has 300");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          TSK_ID       | ''                  | TSK-ID is blank
          TSK_TITLE    | ''                  | TSK-TITLE is blank
          TSK_STATUS   | Q                   | TSK-STATUS 'Q' is not O, I, D or X
          TSK_DUE_DATE | 20261332            | TSK-DUE-DATE '20261332' is not a date YYYYMMDD or 00000000
          TSK_DUE_DATE | 2026103A            | TSK-DUE-DATE '2026103A' is not a date YYYYMMDD or 00000000
          TSK_DUE_DATE | ''                  | TSK-DUE-DATE '' is not a date YYYYMMDD or 00000000
          TSK_ORIGIN   | Z                   | TSK-ORIGIN 'Z' is not H or T
          TSK_LAST_UPD | 2026-10-01 07:42:10 | TSK-LAST-UPD '2026-10-01 07:42:10' is not a timestamp YYYY-MM-DD-HH.MM.SS.NNNNNN
          """)
  void rejectsAnInvalidField(Field field, String value, String reason) {
    assertThatThrownBy(() -> TskRecord.parse(with(VALID, field, value)))
        .isInstanceOf(InvalidRecordException.class)
        .hasMessage(reason);
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
    assertThat(offset).isEqualTo(TskRecord.LENGTH);
  }
}
