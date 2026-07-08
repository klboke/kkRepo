package com.github.klboke.kkrepo.protocol.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PubPathParserTest {
  private final PubPathParser parser = new PubPathParser();

  @Test
  void parsesHostedPubV2Paths() {
    assertEquals(PubPath.Kind.PACKAGE_METADATA,
        parser.parse("api/packages/example_package").kind());
    assertEquals(PubPath.Kind.VERSION_METADATA,
        parser.parse("api/packages/example_package/versions/1.2.3").kind());
    PubPath archive = parser.parse("packages/example_package/versions/1.2.3.tar.gz");
    assertEquals(PubPath.Kind.ARCHIVE, archive.kind());
    assertEquals("example_package", archive.packageName());
    assertEquals("1.2.3", archive.version());
  }

  @Test
  void parsesPubDevPackagesWithLeadingUnderscore() {
    PubPath metadata = parser.parse("api/packages/_fe_analyzer_shared");
    assertEquals(PubPath.Kind.PACKAGE_METADATA, metadata.kind());
    assertEquals("_fe_analyzer_shared", metadata.packageName());

    PubPath archive = parser.parse("packages/_fe_analyzer_shared/versions/104.0.0.tar.gz");
    assertEquals(PubPath.Kind.ARCHIVE, archive.kind());
    assertEquals("_fe_analyzer_shared", archive.packageName());
    assertEquals("104.0.0", archive.version());
  }

  @Test
  void parsesNexusArchiveAliasPath() {
    PubPath archive = parser.parse("api/archives/_fe_analyzer_shared-104.0.0-beta.1.tar.gz");

    assertEquals(PubPath.Kind.ARCHIVE, archive.kind());
    assertEquals("_fe_analyzer_shared", archive.packageName());
    assertEquals("104.0.0-beta.1", archive.version());
  }

  @Test
  void parsesNexusContentPaths() {
    PubPath version = parser.parse("_fe_analyzer_shared/104.0.0-beta.1/version.json");
    assertEquals(PubPath.Kind.VERSION_JSON, version.kind());
    assertEquals("_fe_analyzer_shared", version.packageName());
    assertEquals("104.0.0-beta.1", version.version());

    PubPath archive = parser.parse("_fe_analyzer_shared/104.0.0-beta.1/_fe_analyzer_shared-104.0.0-beta.1.tar.gz");
    assertEquals(PubPath.Kind.ARCHIVE, archive.kind());
    assertEquals("_fe_analyzer_shared", archive.packageName());
    assertEquals("104.0.0-beta.1", archive.version());
  }

  @Test
  void parsesEncodedBuildMetadataInArchiveAliasPath() {
    PubPath archive = parser.parse("api/archives/example_package-1.0.0%2B2.tar.gz");

    assertEquals(PubPath.Kind.ARCHIVE, archive.kind());
    assertEquals("example_package", archive.packageName());
    assertEquals("1.0.0+2", archive.version());
  }

  @Test
  void parsesPublishSessionPaths() {
    assertEquals(PubPath.Kind.PUBLISH_INIT,
        parser.parse("api/packages/versions/new").kind());
    assertEquals(PubPath.Kind.PUBLISH_UPLOAD,
        parser.parse("api/packages/versions/upload/abc-123").kind());
    assertEquals(PubPath.Kind.PUBLISH_FINALIZE,
        parser.parse("api/packages/versions/finalize/abc-123").kind());
  }

  @Test
  void rejectsDecodedSlashInPackageName() {
    assertEquals(PubPath.Kind.UNKNOWN,
        parser.parse("api/packages/team%2Fpackage").kind());
    assertEquals(PubPath.Kind.UNKNOWN,
        parser.parse("packages/team%2Fpackage/versions/1.0.0.tar.gz").kind());
  }

  @Test
  void rejectsEmptyPathSegmentsInsidePubRoutes() {
    assertEquals(PubPath.Kind.UNKNOWN,
        parser.parse("api/packages//example_package").kind());
    assertEquals(PubPath.Kind.UNKNOWN,
        parser.parse("packages/example_package//versions/1.0.0.tar.gz").kind());
  }
}
