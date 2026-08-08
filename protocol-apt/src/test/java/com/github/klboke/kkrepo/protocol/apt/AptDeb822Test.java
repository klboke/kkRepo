package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class AptDeb822Test {
  @Test
  void parsesMultipleStanzasAndContinuationLines() {
    List<AptDeb822.Stanza> stanzas = AptDeb822.parse("""
        Package: demo
        Version: 1.0-1
        Description: short summary
         long description
         .
         final paragraph

        Package: helper
        Version: 2.0
        """);
    assertEquals(2, stanzas.size());
    assertEquals("demo", stanzas.getFirst().get("package"));
    assertEquals("short summary\nlong description\n.\nfinal paragraph",
        stanzas.getFirst().get("Description"));
  }

  @Test
  void rendersFieldsInStableInsertionOrder() {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    fields.put("Package", "demo");
    fields.put("Description", "summary\nlong line\n.");
    assertEquals("Package: demo\nDescription: summary\n long line\n .\n",
        new AptDeb822.Stanza(fields).render());
  }

  @Test
  void rejectsDuplicateFieldsInvalidUtf8AndLimits() {
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parseSingle("Package: one\npackage: two\n"));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parseSingle(" continuation\n"));
    byte[] invalid = {(byte) 0xc3, (byte) 0x28};
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse(new ByteArrayInputStream(invalid), 10, 1, 10, 10));
    byte[] oversized = "Package: demo\n".getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse(new ByteArrayInputStream(oversized), 3, 1, 10, 10));
  }
}
