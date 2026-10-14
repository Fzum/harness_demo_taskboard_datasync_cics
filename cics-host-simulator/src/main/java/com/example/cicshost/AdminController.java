package com.example.cicshost;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The simulator's admin API for humans and QA: look into TSKMSTR, simulate an outage, reset. */
@RestController
@RequestMapping("/admin")
class AdminController {

  /** Request and response of {@code /admin/outage}. */
  record Outage(boolean down) {}

  private final Tskmstr tskmstr;
  private final CicsRegion region;

  AdminController(Tskmstr tskmstr, CicsRegion region) {
    this.tskmstr = tskmstr;
    this.region = region;
  }

  /** The records of TSKMSTR in key order, each field under its copybook name. */
  @GetMapping("/tskmstr")
  List<TskRecord> records() {
    return tskmstr.browse().stream().map(TskRecord::parse).toList();
  }

  /** The records of TSKMSTR as they are stored: fixed width, one per line. */
  @GetMapping(path = "/tskmstr/raw", produces = MediaType.TEXT_PLAIN_VALUE)
  String raw() {
    return Tskmstr.asText(tskmstr.browse());
  }

  @GetMapping("/outage")
  Outage outage() {
    return new Outage(region.isDown());
  }

  /** {@code {"down": true}} starts a simulated outage of the CICS region, {@code false} ends it. */
  @PostMapping("/outage")
  Outage outage(@RequestBody Outage outage) {
    region.setDown(outage.down());
    return outage;
  }

  /** Reloads TSKMSTR from the seed, dropping every change since the start. */
  @PostMapping("/reset")
  ResponseEntity<Void> reset() {
    tskmstr.reset();
    return ResponseEntity.noContent().build();
  }
}
