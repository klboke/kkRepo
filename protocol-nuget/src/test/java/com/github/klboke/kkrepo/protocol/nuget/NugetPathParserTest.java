package com.github.klboke.kkrepo.protocol.nuget;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NugetPathParserTest {
  private final NugetPathParser parser = new NugetPathParser();

  @Test
  void parsesOfficialV3ServiceIndexAndFlatContainerPaths() {
    assertEquals(NugetPath.Kind.SERVICE_INDEX, parser.parse("index.json").kind());
    assertEquals(NugetPath.Kind.SERVICE_INDEX, parser.parse("").kind());
    assertEquals(NugetPath.Kind.QUERY, parser.parse("query").kind());
    assertEquals(NugetPath.Kind.AUTOCOMPLETE, parser.parse("autocomplete").kind());
    assertEquals(NugetPath.Kind.REGISTRATION_INDEX,
        parser.parse("v3/registration5-semver1/Newtonsoft.Json/index.json").kind());
    assertEquals(NugetPath.Kind.FLAT_CONTAINER_VERSION_INDEX,
        parser.parse("v3-flatcontainer/Newtonsoft.Json/index.json").kind());
    NugetPath nupkg = parser.parse(
        "v3-flatcontainer/Newtonsoft.Json/13.0.3/newtonsoft.json.13.0.3.nupkg");
    assertEquals(NugetPath.Kind.FLAT_CONTAINER_PACKAGE, nupkg.kind());
    assertEquals("Newtonsoft.Json", nupkg.packageId());
    assertEquals("13.0.3", nupkg.version());
  }

  @Test
  void parsesPackagePublishAndDeleteEndpoints() {
    assertEquals(NugetPath.Kind.PACKAGE_PUBLISH, parser.parse("api/v2/package").kind());
    assertEquals(NugetPath.Kind.PACKAGE_PUBLISH, parser.parse("api/v2/package/").kind());
    assertEquals(NugetPath.Kind.PACKAGE_DELETE, parser.parse("api/v2/package/Foo/1.0.0").kind());
  }
}
