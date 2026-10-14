package com.example.cicshost;

import static com.example.cicshost.Records.taskboardRecord;
import static com.example.cicshost.Records.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.cicshost.TskRecord.Field;
import java.io.UnsupportedEncodingException;
import java.util.List;
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
class CicsProgramControllerTest {

  private final MockMvcTester mvc;
  private final Tskmstr tskmstr;

  CicsProgramControllerTest(MockMvcTester mvc, Tskmstr tskmstr) {
    this.mvc = mvc;
    this.tskmstr = tskmstr;
  }

  @AfterEach
  void reloadTheSeed() {
    tskmstr.reset();
  }

  @Test
  void browseReturnsTheSeededRecordsInKeyOrderOnePerLine() throws Exception {
    MvcTestResult result = mvc.post().uri("/cics/TSKBRWS").exchange();

    assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_PLAIN);
    List<String> records = result.getResponse().getContentAsString().lines().toList();
    assertThat(records).hasSize(8).allSatisfy(record -> assertThat(record).hasSize(300));
    assertThat(records)
        .extracting(record -> record.substring(0, 10))
        .containsExactly(
            "H000001001",
            "H000001002",
            "H000001003",
            "H000001004",
            "H000001005",
            "H000001006",
            "H000001007",
            "H000001008");
    assertThat(records)
        .extracting(record -> record.charAt(Field.TSK_STATUS.offset))
        .containsExactly('O', 'I', 'O', 'D', 'O', 'I', 'O', 'X');
  }

  @Test
  void updateInsertsANewRecordInKeyOrder() throws Exception {
    String record = taskboardRecord("T000000042", "Call back about the invoice", "O");

    assertThat(update(record)).hasStatusOk().bodyText().isEqualTo("RC=00");
    assertThat(browse()).hasSize(9).last().isEqualTo(record);
  }

  @Test
  void updateReplacesTheRecordWithTheSameKey() throws Exception {
    String done = with(browse().get(2), Field.TSK_STATUS, "D");

    assertThat(update(done)).hasStatusOk().bodyText().isEqualTo("RC=00");
    assertThat(browse()).hasSize(8).element(2).isEqualTo(done);
  }

  @Test
  void updateRejectsAnInvalidRecordWithRc12AndLeavesTheFileAlone() throws Exception {
    List<String> before = browse();

    assertThat(update(with(before.get(0), Field.TSK_STATUS, "Q")))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .bodyText()
        .isEqualTo("RC=12 TSK-STATUS 'Q' is not O, I, D or X");
    assertThat(update("H000001001 too short"))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .bodyText()
        .isEqualTo("RC=12 record length is 20, TSK-RECORD has 300");
    assertThat(update(""))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .bodyText()
        .isEqualTo("RC=12 record length is 0, TSK-RECORD has 300");
    assertThat(browse()).isEqualTo(before);
  }

  @Test
  void updateIgnoresATrailingLineBreak() throws Exception {
    String record = taskboardRecord("T000000043", "Scan incoming mail", "I");

    assertThat(update(record + "\n")).hasStatusOk();
    assertThat(browse()).contains(record);
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
