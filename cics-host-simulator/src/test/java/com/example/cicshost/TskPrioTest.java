package com.example.cicshost;

import static com.example.cicshost.Records.taskboardRecord;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** PRIO-1 AC8: TSK-PRIO at offset 294, validated, shown in the admin API. Black-box via HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
class TskPrioTest {

  private static final int PRIO_OFFSET = 294;

  private final MockMvcTester mvc;
  private final Tskmstr tskmstr;

  TskPrioTest(MockMvcTester mvc, Tskmstr tskmstr) {
    this.mvc = mvc;
    this.tskmstr = tskmstr;
  }

  @AfterEach
  void reloadTheSeed() {
    tskmstr.reset();
  }

  /** A 300-character record with this character at offset 294 (the rest as taskboardRecord). */
  private static String withPrio(String id, String prio) {
    String base = taskboardRecord(id, "Call back", "O");
    assertThat(base).hasSize(300);
    return base.substring(0, PRIO_OFFSET) + prio + base.substring(PRIO_OFFSET + 1);
  }

  @Test
  void copybookDeclaresTskPrioAfterLastUpdWithItsLevelsAndAFiveByteFiller() throws IOException {
    String copybook = Files.readString(Path.of("..", "copybooks", "TSKREC.cpy"));

    assertThat(copybook)
        .containsPattern("TSK-LAST-UPD\\s+PIC X\\(26\\)\\.\\s+05\\s+TSK-PRIO\\s+PIC X\\(01\\)\\.")
        .containsPattern("TSK-PRIO-HIGH\\s+VALUE 'H'")
        .containsPattern("TSK-PRIO-MEDIUM\\s+VALUE 'M'")
        .containsPattern("TSK-PRIO-LOW\\s+VALUE 'L'")
        .containsPattern("FILLER\\s+PIC X\\(05\\)\\.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"H", "M", "L", " "})
  void updateAcceptsHMLAndBlankAndKeepsTheRecordAsItIs(String prio) throws Exception {
    String record = withPrio("T000000050", prio);

    assertThat(update(record)).hasStatusOk().bodyText().isEqualTo("RC=00");

    assertThat(browse()).contains(record);
    assertThat(browse()).allSatisfy(line -> assertThat(line).hasSize(300));
  }

  @ParameterizedTest
  @ValueSource(strings = {"X", "h", "1", "U"})
  void updateRejectsAnyOtherPriorityLikeAnInvalidStatus(String prio) throws Exception {
    List<String> before = browse();

    assertThat(update(withPrio("T000000051", prio)))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .bodyText()
        .isEqualTo("RC=12 TSK-PRIO '" + prio + "' is not H, M, L or blank");
    assertThat(browse()).isEqualTo(before);
  }

  @Test
  void adminShowsTskPrioAfterTskLastUpdBlankAsEmptyString() throws Exception {
    String json = mvc.get().uri("/admin/tskmstr").exchange().getResponse().getContentAsString();

    assertThat(mvc.get().uri("/admin/tskmstr"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$[0]['TSK-PRIO']")
        .isEqualTo("");
    assertThat(json).containsPattern("\"TSK-LAST-UPD\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"TSK-PRIO\"");
  }

  @Test
  void adminShowsTheStoredPriority() throws Exception {
    update(withPrio("T000000052", "H"));
    update(withPrio("T000000053", "L"));

    assertThat(mvc.get().uri("/admin/tskmstr"))
        .bodyJson()
        .extractingPath("$[?(@['TSK-ID'] == 'T000000052')]['TSK-PRIO']")
        .isEqualTo(List.of("H"));
    assertThat(mvc.get().uri("/admin/tskmstr"))
        .bodyJson()
        .extractingPath("$[?(@['TSK-ID'] == 'T000000053')]['TSK-PRIO']")
        .isEqualTo(List.of("L"));
  }

  @Test
  void otherFieldsKeepTheirOffsets() throws Exception {
    String record = withPrio("T000000054", "M");
    update(record);

    String stored = browse().stream().filter(l -> l.startsWith("T000000054")).findFirst().get();

    assertThat(stored.substring(0, 10)).isEqualTo("T000000054");
    assertThat(stored.substring(10, 50).strip()).isEqualTo("Call back");
    assertThat(stored.charAt(250)).isEqualTo('O');
    assertThat(stored.substring(251, 259)).isEqualTo("AMUELLER");
    assertThat(stored.substring(259, 267)).isEqualTo("20261031");
    assertThat(stored.charAt(267)).isEqualTo('T');
    assertThat(stored.substring(268, 294)).isEqualTo("2026-10-14-08.15.30.123000");
    assertThat(stored.charAt(294)).isEqualTo('M');
    assertThat(stored.substring(295)).isEqualTo("     ");
  }

  private MvcTestResult update(String record) {
    return mvc.post()
        .uri("/cics/TSKUPDT")
        .contentType(MediaType.TEXT_PLAIN)
        .content(record)
        .exchange();
  }

  private List<String> browse() throws Exception {
    return mvc.post()
        .uri("/cics/TSKBRWS")
        .exchange()
        .getResponse()
        .getContentAsString()
        .lines()
        .toList();
  }
}
