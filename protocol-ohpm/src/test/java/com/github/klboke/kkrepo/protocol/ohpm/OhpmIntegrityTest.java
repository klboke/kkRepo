package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OhpmIntegrityTest {
  private static final String ABC = "sha512-"
      + "3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw==";

  @Test
  void verifiesTheStandardSha512VectorAndDetectsCorruption() throws Exception {
    OhpmIntegrity integrity = new OhpmIntegrity(ABC);
    assertEquals(ABC, OhpmIntegrity.compute(bytes("abc")).value());
    assertTrue(integrity.matches(bytes("abc")));
    assertFalse(integrity.matches(bytes("abd")));
    assertFalse(integrity.matches(bytes("")));
  }

  @Test
  void rejectsWrongAlgorithmsInvalidBase64AndTruncatedDigests() {
    for (String value : new String[] {"sha1-YWJj", "sha512-YWJj", ABC.replace("==", ""),
        ABC + " ", "sha512-$$$", "sha512-" + "A".repeat(85) + "B==", ""}) {
      assertThrows(IllegalArgumentException.class, () -> new OhpmIntegrity(value), value);
    }
    assertThrows(IllegalArgumentException.class, () -> new OhpmIntegrity(null));
  }

  @Test
  void propagatesStreamFailuresInsteadOfAcceptingPartialContent() {
    InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("interrupted upload");
      }
    };
    assertThrows(IOException.class, () -> new OhpmIntegrity(ABC).matches(broken));
  }

  private static InputStream bytes(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
