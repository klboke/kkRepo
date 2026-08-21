package com.github.klboke.kkrepo.protocol.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RDcfAndPackageIndexTest {
  @Test
  void parsesDescriptionContinuationsAndBuildsIndexProjection() {
    byte[] description = ("Package: example\n"
        + "Version: 1.2-3\n"
        + "Title: Example package\n"
        + "Description: first line\n second line\n"
        + "License: MIT\n"
        + "Authors@R: person(\"A\", \"B\")\n"
        + "Imports: methods, utils (>= 4.0.0)\n").getBytes(StandardCharsets.UTF_8);
    RPackageMetadata metadata = RPackageMetadata.fromDescription(
        description, "example_1.2-3.tar.gz");
    assertEquals("example", metadata.packageName());
    assertTrue(metadata.fields().get("Description").contains("second line"));
    assertEquals("methods, utils (>= 4.0.0)", metadata.dependencies().get("Imports"));
  }

  @Test
  void rendersDeterministicPackagesRecords() {
    RPackageMetadata older = RPackageMetadata.fromIndexRecord(Map.of(
        "Package", "example", "Version", "1.0", "MD5sum", "a"));
    RPackageMetadata newer = RPackageMetadata.fromIndexRecord(Map.of(
        "Package", "example", "Version", "1.10", "MD5sum", "b"));
    byte[] rendered = RPackageIndex.render(List.of(newer, older));
    List<RPackageMetadata> parsed = RPackageIndex.parse(rendered);
    assertEquals(List.of("1.0", "1.10"), parsed.stream().map(RPackageMetadata::version).toList());
  }

  @Test
  void rejectsDuplicateFieldsAndFilenameDrift() {
    assertThrows(IllegalArgumentException.class,
        () -> RDcf.parse("Package: a\nPackage: b\n".getBytes(StandardCharsets.UTF_8)));
    byte[] description = ("Package: example\nVersion: 1.0\nTitle: T\nDescription: D\n"
        + "License: MIT\nAuthor: A\nMaintainer: M\n").getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class,
        () -> RPackageMetadata.fromDescription(description, "other_1.0.tar.gz"));
  }
}
