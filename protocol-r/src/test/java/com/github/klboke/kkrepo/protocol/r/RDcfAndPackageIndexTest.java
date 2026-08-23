package com.github.klboke.kkrepo.protocol.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.ProtocolCapability;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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

  @Test
  void enforcesDcfBoundsAndSupportsCanonicalRenderingAndLegacyEncoding() {
    assertThrows(IllegalArgumentException.class, () -> RDcf.parse(null));
    assertThrows(IllegalArgumentException.class,
        () -> RDcf.parse(" continuation\n".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class, () -> RDcf.parse(
        ("Field: " + "a".repeat(RDcf.MAX_FIELD_BYTES) + "\n b\n")
            .getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class, () -> RDcf.parse(
        ("Field: " + "a".repeat(RDcf.MAX_FIELD_BYTES + 1) + "\n")
            .getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> RDcf.parse("Field: bad\0value\n".getBytes(StandardCharsets.UTF_8)));

    StringBuilder tooManyFields = new StringBuilder();
    for (int index = 0; index <= RDcf.MAX_FIELDS; index++) {
      tooManyFields.append("F").append(index).append(": value\n");
    }
    assertThrows(IllegalArgumentException.class,
        () -> RDcf.parse(tooManyFields.toString().getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> RDcf.parseOne("A: one\n\nA: two\n".getBytes(StandardCharsets.UTF_8)));

    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    fields.put("Description", "first\nsecond");
    fields.put("Package", "demo");
    assertEquals("Package: demo\nDescription: first\n second\n",
        RDcf.renderRecord(fields, List.of("Package")));
    assertEquals("café", RDcf.parseOne(
        "Title: café\n".getBytes(StandardCharsets.ISO_8859_1)).get("Title"));
  }

  @Test
  void exposesRProtocolCapabilitiesAndMediaTypes() {
    RRepositoryProtocol protocol = new RRepositoryProtocol();
    assertEquals(RepositoryFormat.R, protocol.format());
    assertEquals(new ProtocolCapability(true, true, true, true, true), protocol.capability());

    RPathParser parser = new RPathParser();
    assertEquals(RMediaTypes.PACKAGES,
        RMediaTypes.forPath(parser.parse("src/contrib/PACKAGES")));
    assertEquals(RMediaTypes.PACKAGES_GZIP,
        RMediaTypes.forPath(parser.parse("src/contrib/PACKAGES.gz")));
    assertEquals(RMediaTypes.PACKAGES_RDS,
        RMediaTypes.forPath(parser.parse("src/contrib/PACKAGES.rds")));
    assertEquals("application/octet-stream", RMediaTypes.forPath(parser.parse("")));
  }

  @Test
  void rejectsInvalidPackageIdentityAndMissingAuthorMetadata() {
    assertThrows(IllegalArgumentException.class,
        () -> new RPackageMetadata("1demo", "1.0", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> RVersions.require("latest"));
    assertThrows(IllegalArgumentException.class,
        () -> RPathParser.sourceFilename("1demo", "1.0"));

    byte[] missingAuthors = ("Package: demo\n"
        + "Version: 1.0\n"
        + "Title: Demo\n"
        + "Description: Demo package\n"
        + "License: MIT\n").getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class,
        () -> RPackageMetadata.fromDescription(missingAuthors, "demo_1.0.tar.gz"));
  }

  @Test
  void projectsNonCanonicalFilenameAndClassifiesSafeStaticPaths() {
    RPackageMetadata metadata = RPackageMetadata.fromIndexRecord(Map.of(
        "Package", "demo", "Version", "1.0"));
    assertEquals("mirror-demo.tar.gz",
        metadata.indexFields("ABCDEF", "mirror-demo.tar.gz").get("File"));

    RPathParser parser = new RPathParser();
    assertEquals(RPath.Kind.STATIC, parser.parse("README.html").kind());
    assertEquals(RPath.Kind.UNKNOWN, parser.parse("README\u0001.html").kind());
    assertEquals(RPath.Kind.UNKNOWN, parser.parse("%C3%28").kind());
  }
}
