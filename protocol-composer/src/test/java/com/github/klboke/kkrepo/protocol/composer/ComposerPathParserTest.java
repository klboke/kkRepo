package com.github.klboke.kkrepo.protocol.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ComposerPathParserTest {
  private final ComposerPathParser parser = new ComposerPathParser();

  @Test
  void parsesComposerV2Routes() {
    assertEquals(ComposerPath.Kind.PACKAGES, parser.parse("packages.json").kind());
    ComposerPath stable = parser.parse("p2/company/example.json");
    assertEquals(ComposerPath.Kind.PACKAGE_METADATA, stable.kind());
    assertEquals("company/example", stable.packageName());
    assertEquals(false, stable.dev());
    ComposerPath dev = parser.parse("p2/company/example~dev.json");
    assertEquals(ComposerPath.Kind.PACKAGE_METADATA, dev.kind());
    assertEquals(true, dev.dev());
    assertEquals(ComposerPath.Kind.PROVIDERS,
        parser.parse("providers/psr/log-implementation.json").kind());
    assertEquals(ComposerPath.Kind.PACKAGE_LIST, parser.parse("packages/list.json").kind());
    ComposerPath nexusDist = parser.parse("company/example/1.0.0/company-example-1.0.0.zip");
    assertEquals(ComposerPath.Kind.DIST, nexusDist.kind());
    assertEquals("company/example", nexusDist.packageName());
    assertEquals("1.0.0", nexusDist.version());
    assertEquals("company-example-1.0.0.zip", nexusDist.fileName());
  }

  @Test
  void rejectsAmbiguousAndInvalidPaths() {
    assertEquals(ComposerPath.Kind.UNKNOWN, parser.parse("p2/Company/Example.json").kind());
    assertEquals(ComposerPath.Kind.UNKNOWN, parser.parse("p2/company.json").kind());
    assertEquals(ComposerPath.Kind.UNKNOWN, parser.parse("dists/abcdef12/archive.zip").kind());
    assertEquals(ComposerPath.Kind.UNKNOWN,
        parser.parse("company/example/../company-example-1.0.0.zip").kind());
  }

  @Test
  void buildsNexusCompatibleProxyDistPath() {
    assertEquals(
        "psr/log/3.0.2/psr-log-3.0.2.zip",
        ComposerPaths.componentDist("psr/log", "3.0.2", "zip"));
    assertEquals(
        "symfony/console/v8.1.1/symfony-console-v8.1.1.zip",
        ComposerPaths.componentDist("symfony/console", "v8.1.1", "zip"));
  }
}
