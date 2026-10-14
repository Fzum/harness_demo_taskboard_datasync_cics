package com.example.cicshost;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CICS programs of the task application, called over HTTP the way CICS web services or a CICS
 * Transaction Gateway expose them. The COMMAREA is plain text: readable ASCII in this model, where
 * the real region speaks EBCDIC.
 */
@RestController
@RequestMapping(path = "/cics", produces = MediaType.TEXT_PLAIN_VALUE)
class CicsProgramController {

  private static final Logger log = LoggerFactory.getLogger(CicsProgramController.class);

  private final Tskmstr tskmstr;

  CicsProgramController(Tskmstr tskmstr) {
    this.tskmstr = tskmstr;
  }

  /** TSKBRWS browses TSKMSTR from the first key to the last: one 300-character line per record. */
  @PostMapping("/TSKBRWS")
  String browse() {
    List<String> records = tskmstr.browse();
    log.info("TSKBRWS returned {} records", records.size());
    return Tskmstr.asText(records);
  }

  /**
   * TSKUPDT writes one TSK-RECORD: it inserts the record or replaces the one with its TSK-ID. A
   * trailing line break is ignored, so a record file can be posted as it is.
   */
  @PostMapping("/TSKUPDT")
  ResponseEntity<String> update(@RequestBody(required = false) String commarea) {
    String record = commarea == null ? "" : commarea.replaceFirst("\\r?\\n\\z", "");
    try {
      TskRecord written = tskmstr.write(record);
      log.info("TSKUPDT RC=00 {} status {}", written.id(), written.status());
      return ResponseEntity.ok("RC=00");
    } catch (InvalidRecordException e) {
      log.warn("TSKUPDT RC=12 {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
          .body("RC=12 " + e.getMessage());
    }
  }
}
