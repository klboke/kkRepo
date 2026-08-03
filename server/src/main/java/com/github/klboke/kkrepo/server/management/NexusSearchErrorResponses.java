package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidContinuationTokenException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class NexusSearchErrorResponses {
  private static final MediaType NEXUS_TEXT_PLAIN =
      new MediaType("text", "plain", StandardCharsets.UTF_8);

  private NexusSearchErrorResponses() {
  }

  static ResponseEntity<String> invalidContinuation(InvalidContinuationTokenException exception) {
    Throwable detail = exception.getCause() == null ? exception : exception.getCause();
    String body = "ERROR: (ID " + UUID.randomUUID() + ") java.lang.IllegalArgumentException: "
        + detail.getMessage();
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(NEXUS_TEXT_PLAIN)
        .body(body);
  }
}
