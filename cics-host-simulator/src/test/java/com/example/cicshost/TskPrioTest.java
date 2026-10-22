package com.example.cicshost;

import static com.example.cicshost.Records.taskboardRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
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

/** PRIO-1: TSK-PRIO at offset 294 of the 300-character TSK-RECORD, through the CICS programs. */
@SpringBootTest
@AutoConfigureMockMvc
class TskPrioTest {

  private static final int PRIO_OFFSET = 294;
  private static final String ID = "T000000077";

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

  /** A valid record with the given byte at offset 294. */
  private static String recordWithPrio(String prio) {
    String base = taskboardRecord(ID, "Call back about the invoice", "O");
    return base.substring(0, PRIO_OFFSET) + prio + base.substring(PRIO_OFFSET + 1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"H", "M", "L", " "})
  void updateAcceptsHMLAndBlankAndStoresTheByteAtOffset294(String prio) throws Exception {
    String record = recordWithPrio(prio);
    assertThat(record).hasSize(300);

    assertThat(update(record)).hasStatusOk().bodyText().isEqualTo("RC=00");

    assertThat(browse()).contains(record);
    assertThat(browse()).allSatisfy(line -> assertThat(line).hasSize(300));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Q", "h", "1", "X"})
  void updateRejectsAnyOtherValueWithRc12AndLeavesTheFileAlone(String prio) throws Exception {
    List<String> before = browse();

    assertThat(update(recordWithPrio(prio)))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .bodyText()
        .isEqualTo("RC=12 TSK-PRIO '" + prio + "' is not H, M, L or blank");
    assertThat(browse()).isEqualTo(before);
  }

  @Test
  void adminShowsTskPrioAfterTskLastUpdForEveryRecord() throws Exception {
    update(recordWithPrio("H"));
    update(recordWithPrio(" ").replace(ID, "T000000078"));

    MvcTestResult admin = mvc.get().uri("/admin/tskmstr").exchange();

    assertThat(admin).hasStatusOk();
    assertThat(admin)
        .bodyJson()
        .extractingPath("$[?(@.['TSK-ID'] == '" + ID + "')].['TSK-PRIO']")
        .isEqualTo(List.of("H"));
    assertThat(admin)
        .bodyJson()
        .extractingPath("$[?(@.['TSK-ID'] == 'T000000078')].['TSK-PRIO']")
        .isEqualTo(List.of(""));
    assertThat(admin)
        .bodyJson()
        .extractingPath("$[?(@.['TSK-PRIO'] == null)].['TSK-ID']")
        .isEqualTo(List.of());
  }

  @Test
  void everySeedRecordShowsABlankOrValidPriorityInAdmin() {
    List<String> prios = JsonPath.read(adminBody(), "$[*]['TSK-PRIO']");

    assertThat(prios).hasSize(8).allSatisfy(p -> assertThat(p).isIn("", "H", "M", "L"));
  }

  @Test
  void copybookPlacesTskPrioRightAfterTskLastUpdWithTheThreeLevels() throws IOException {
    String copybook = Files.readString(Path.of("..", "copybooks", "TSKREC.cpy"));

    assertThat(copybook)
        .containsPattern("TSK-LAST-UPD\\s+PIC X\\(26\\)\\.\\s+05\\s+TSK-PRIO\\s+PIC X\\(01\\)\\.")
        .containsPattern("TSK-PRIO-HIGH\\s+VALUE 'H'")
        .containsPattern("TSK-PRIO-MEDIUM\\s+VALUE 'M'")
        .containsPattern("TSK-PRIO-LOW\\s+VALUE 'L'")
        .containsPattern("FILLER\\s+PIC X\\(05\\)\\.");
    assertThat(copybook).doesNotContainPattern("FILLER\\s+PIC X\\(06\\)");
  }

  private String adminBody() {
    return new String(
        mvc.get().uri("/admin/tskmstr").exchange().getResponse().getContentAsByteArray(),
        StandardCharsets.UTF_8);
  }

  private MvcTestResult update(String record) {
    return mvc.post()
        .uri("/cics/TSKUPDT")
        .contentType(MediaType.TEXT_PLAIN)
        .content(record)
        .exchange();
  }

  private List<String> browse() throws UnsupportedEncodingException {
    return mvc.post()
        .uri("/cics/TSKBRWS")
        .exchange()
        .getResponse()
        .getContentAsString()
        .lines()
        .toList();
  }
}
