package com.github.klboke.kkrepo.security.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanFingerprintsTest {
  @Test
  void catalogFingerprintIsStableAndContentSensitive() {
    ScanSubject subject = new ScanSubject(
        SubjectKind.ASSET_BLOB, 1, 2L, 3L, "sha256:" + "a".repeat(64), "a".repeat(64),
        42, "MAVEN", "artifact", "application/java-archive",
        TargetClassification.PACKAGE, List.of(), Map.of());
    String first = ScanFingerprints.catalog(subject, "syft", "1.0", "config-a");
    String second = ScanFingerprints.catalog(subject, "syft", "1.0", "config-a");
    String changed = ScanFingerprints.catalog(subject, "syft", "1.1", "config-a");

    assertEquals(first, second);
    assertNotEquals(first, changed);
    assertEquals(64, first.length());
  }

  @Test
  void lengthPrefixPreventsAmbiguousInputs() {
    assertNotEquals(
        ScanFingerprints.sha256("ab", "c"),
        ScanFingerprints.sha256("a", "bc"));
  }
}
