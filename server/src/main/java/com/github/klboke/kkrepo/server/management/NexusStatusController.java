package com.github.klboke.kkrepo.server.management;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nexus-compatible status endpoints used by clients, probes, and orchestration. */
@RestController
@RequestMapping("/service/rest/v1/status")
public class NexusStatusController {
  @GetMapping
  public StatusXo status() {
    return new StatusXo("kkrepo", "Community", "dev", true, Instant.now().toString());
  }

  @GetMapping("/check")
  public Map<String, Object> check() {
    return Map.of("available", true, "healthy", true, "timestamp", Instant.now().toString());
  }

  @GetMapping("/writable")
  public ResponseEntity<Void> writable() {
    return ResponseEntity.ok().build();
  }

  @GetMapping("/read-only")
  public ResponseEntity<Void> readOnly() {
    return ResponseEntity.status(405).build();
  }

  public record StatusXo(
      String productName,
      String edition,
      String version,
      boolean available,
      String timestamp) {
  }
}
