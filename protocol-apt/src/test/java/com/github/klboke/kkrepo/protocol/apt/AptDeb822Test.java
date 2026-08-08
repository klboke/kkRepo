package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  @Test
  void streamsCrLfInputAndRendersMultipleStanzasDeterministically() throws Exception {
    byte[] input = "Package:\t demo  \r\nDescription: first\r\n\tsecond\r\n\r\nPackage: two"
        .getBytes(StandardCharsets.UTF_8);
    List<AptDeb822.Stanza> stanzas = AptDeb822.parse(new ByteArrayInputStream(input));

    assertEquals(2, stanzas.size());
    assertEquals(" demo", stanzas.getFirst().get("Package"));
    assertEquals("first\nsecond", stanzas.getFirst().get("description"));
    assertEquals("Package:  demo\nDescription: first\n second\n\nPackage: two\n",
        AptDeb822.render(stanzas));
  }

  @Test
  void enforcesAllStreamingAndStructuralLimits() {
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.forEach(new ByteArrayInputStream(new byte[0]), 0, 1, 1, 1, ignored -> { }));
    assertThrows(NullPointerException.class,
        () -> AptDeb822.forEach(null, 1, 1, 1, 1, ignored -> { }));
    assertThrows(NullPointerException.class,
        () -> AptDeb822.forEach(new ByteArrayInputStream(new byte[0]), 1, 1, 1, 1, null));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse(new ByteArrayInputStream("Package: demo".getBytes()), 100, 1, 1, 3));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse("Package demo\n"));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse("#Comment: no\n"));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse("-Bad: no\n"));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse(new ByteArrayInputStream("A: 1\nB: 2\n".getBytes()), 100, 1, 1, 100));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parse(new ByteArrayInputStream("A: 1\n\nB: 2\n".getBytes()), 100, 1, 10, 100));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parseSingle("A: 1\n\nB: 2\n"));
    assertThrows(IllegalArgumentException.class,
        () -> AptDeb822.parseSingle("\n"));
    assertThrows(NullPointerException.class, () -> AptDeb822.parse((String) null));
  }

  @Test
  void stanzaValidatesFieldsAndRequiredValues() {
    AptDeb822.Stanza stanza = new AptDeb822.Stanza(Map.of("Package", "demo", "Empty", ""));
    assertEquals(null, stanza.get("missing"));
    assertThrows(IllegalArgumentException.class, () -> stanza.require("Empty"));
    assertThrows(IllegalArgumentException.class,
        () -> new AptDeb822.Stanza(Map.of("Bad", "line\rbreak")));
    assertThrows(IllegalArgumentException.class,
        () -> new AptDeb822.Stanza(Map.of("Bad", "line\0break")));
    LinkedHashMap<String, String> duplicate = new LinkedHashMap<>();
    duplicate.put("Package", "one");
    duplicate.put("package", "two");
    assertThrows(IllegalArgumentException.class, () -> new AptDeb822.Stanza(duplicate));

    LinkedHashMap<String, String> blankLine = new LinkedHashMap<>();
    blankLine.put("Description", "\n");
    assertEquals("Description:\n .\n", new AptDeb822.Stanza(blankLine).render());
  }
}
