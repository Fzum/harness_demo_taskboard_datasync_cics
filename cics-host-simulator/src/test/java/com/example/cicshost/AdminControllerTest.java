package com.example.cicshost;

import static com.example.cicshost.Records.taskboardRecord;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

  private final MockMvcTester mvc;
  private final Tskmstr tskmstr;
  private final CicsRegion region;

  AdminControllerTest(MockMvcTester mvc, Tskmstr tskmstr, CicsRegion region) {
    this.mvc = mvc;
    this.tskmstr = tskmstr;
    this.region = region;
  }

  @AfterEach
  void endOutageAndReloadTheSeed() {
    region.setDown(false);
    tskmstr.reset();
  }

  @Test
  void tskmstrShowsEveryRecordWithItsCopybookFields() {
    assertThat(mvc.get().uri("/admin/tskmstr"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$[0]")
        .isEqualTo(
            Map.ofEntries(
                entry("TSK-ID", "H000001001"),
                entry("TSK-TITLE", "Clarify returned direct debit"),
                entry(
                    "TSK-DESC",
                    "The bank returned the October instalment; ask the customer for a new direct"
                        + " debit mandate"),
                entry("TSK-STATUS", "O"),
                entry("TSK-USER", "AMUELLER"),
                entry("TSK-DUE-DATE", "20261031"),
                entry("TSK-ORIGIN", "H"),
                entry("TSK-LAST-UPD", "2026-10-01-07.42.10.000000")));
    assertThat(mvc.get().uri("/admin/tskmstr"))
        .bodyJson()
        .extractingPath("$.length()")
        .isEqualTo(8);
  }

  @Test
  void rawShowsTheRecordsAsStored() throws Exception {
    MvcTestResult raw = mvc.get().uri("/admin/tskmstr/raw").exchange();

    assertThat(raw).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_PLAIN);
    assertThat(raw.getResponse().getContentAsString())
        .isEqualTo(Tskmstr.asText(tskmstr.browse()))
        .hasLineCount(8);
  }

  @Test
  void anOutageMakesEveryCicsProgramAnswer503UntilItEnds() {
    assertThat(outage("{\"down\": true}"))
        .hasStatusOk()
        .bodyJson()
        .isStrictlyEqualTo("{\"down\": true}");

    assertThat(mvc.post().uri("/cics/TSKBRWS"))
        .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .bodyText()
        .isEqualTo("CICS region unavailable");
    assertThat(
            mvc.post()
                .uri("/cics/TSKUPDT")
                .contentType(MediaType.TEXT_PLAIN)
                .content(taskboardRecord("T000000042", "Call back", "O")))
        .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(mvc.get().uri("/admin/outage")).bodyJson().isStrictlyEqualTo("{\"down\": true}");
    assertThat(mvc.get().uri("/admin/tskmstr")).hasStatusOk();
    assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();

    assertThat(outage("{\"down\": false}")).hasStatusOk();
    assertThat(mvc.post().uri("/cics/TSKBRWS")).hasStatusOk();
  }

  @Test
  void resetReloadsTheSeed() {
    mvc.post()
        .uri("/cics/TSKUPDT")
        .contentType(MediaType.TEXT_PLAIN)
        .content(taskboardRecord("T000000042", "Call back", "O"))
        .exchange();
    assertThat(tskmstr.browse()).hasSize(9);

    assertThat(mvc.post().uri("/admin/reset")).hasStatus(HttpStatus.NO_CONTENT);
    assertThat(tskmstr.browse()).hasSize(8);
  }

  private MvcTestResult outage(String body) {
    return mvc.post()
        .uri("/admin/outage")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }
}
