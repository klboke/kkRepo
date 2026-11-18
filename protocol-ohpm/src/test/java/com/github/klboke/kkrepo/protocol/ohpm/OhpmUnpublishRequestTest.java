package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmUnpublishRequestTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final OhpmPathParser parser = new OhpmPathParser();

  @Test
  void currentBodyAndLegacyPathSelectTheSameVersion() throws Exception {
    OhpmUnpublishRequest current = OhpmUnpublishRequest.parse(parser.parse("DELETE", "@scope%2fpkg"),
        MAPPER.readTree("{\"version\":\"1.2.3\"}"));
    OhpmUnpublishRequest legacy = OhpmUnpublishRequest.parse(parser.parse("DELETE", "@scope%2fpkg@1.2.3"), null);
    assertEquals(legacy, current);
    assertEquals("1.2.3", current.version());
    assertEquals("@scope/pkg", current.packageId().id());
  }

  @Test
  void explicitEmptyVersionAndLegacyEmptyBodySelectTheWholePackage() throws Exception {
    OhpmPath path = parser.parse("DELETE", "pkg");
    assertNull(OhpmUnpublishRequest.parse(path, MAPPER.readTree("{\"version\":\"\"}")).version());
    assertNull(OhpmUnpublishRequest.parse(path, null).version());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "[]", "null", "{\"versoin\":\"1.2.3\"}", "{\"version\":null}",
      "{\"version\":1}", "{\"version\":\" \"}", "{\"version\":\"latest\"}", "{\"version\":\"*\"}"})
  void invalidBodiesNeverBecomeWholePackageDeletion(String body) {
    assertThrows(IllegalArgumentException.class,
        () -> OhpmUnpublishRequest.parse(parser.parse("DELETE", "pkg"), MAPPER.readTree(body)));
  }

  @Test
  void conflictingBodyAndPathCannotDeleteAnotherVersionOrTheWholePackage() {
    OhpmPath path = parser.parse("DELETE", "pkg@1.2.3");
    for (String body : new String[] {"{\"version\":\"2.0.0\"}", "{\"version\":\"\"}"}) {
      assertThrows(IllegalArgumentException.class, () -> OhpmUnpublishRequest.parse(path, MAPPER.readTree(body)));
    }
  }
}
