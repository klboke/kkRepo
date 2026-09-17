package com.github.klboke.kkrepo.protocol.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CargoPathParserTest {
  private final CargoPathParser parser = new CargoPathParser();

  @Test
  void rejectsEncodedSeparatorsAndMalformedVersionEncoding() {
    for (String path : new String[] {"api%2fv1/crates/new", "crates/demo/1.0.0%/download",
        "crates/demo/1.0.0%GG/download", "crates/demo/1.0.0%C3%28/download"}) {
      assertEquals(CargoPath.Kind.UNKNOWN, new CargoPathParser().parse(path).kind());
    }
  }

  @Test
  void buildsOfficialIndexTiers() {
    assertEquals("1/a", CargoIndexPath.forCrate("a"));
    assertEquals("2/ab", CargoIndexPath.forCrate("ab"));
    assertEquals("3/a/abc", CargoIndexPath.forCrate("abc"));
    assertEquals("ca/rg/cargo", CargoIndexPath.forCrate("cargo"));
    assertEquals("my/cr/mycrate", CargoIndexPath.forCrate("MyCrate"));
  }

  @Test
  void parsesSparseIndexAndWebApiPaths() {
    assertEquals(CargoPath.Kind.CONFIG, parser.parse("config.json").kind());
    assertEquals(CargoPath.Kind.INDEX, parser.parse("ca/rg/cargo").kind());
    assertEquals("cargo", parser.parse("ca/rg/cargo").crateName());
    assertEquals(CargoPath.Kind.PUBLISH, parser.parse("api/v1/crates/new").kind());

    CargoPath download = parser.parse("api/v1/crates/cargo/1.2.3/download");
    assertEquals(CargoPath.Kind.DOWNLOAD, download.kind());
    assertEquals("cargo", download.crateName());
    assertEquals("1.2.3", download.version());

    assertEquals(CargoPath.Kind.YANK, parser.parse("api/v1/crates/cargo/1.2.3/yank").kind());
    assertEquals(CargoPath.Kind.UNYANK, parser.parse("api/v1/crates/cargo/1.2.3/unyank").kind());

    CargoPath owners = parser.parse("api/v1/crates/cargo/owners");
    assertEquals(CargoPath.Kind.OWNERS, owners.kind());
    assertEquals("cargo", owners.crateName());

    CargoPath nexusDownload = parser.parse("crates/cargo/1.2.3/download");
    assertEquals(CargoPath.Kind.DOWNLOAD, nexusDownload.kind());
    assertEquals("cargo", nexusDownload.crateName());
    assertEquals("1.2.3", nexusDownload.version());

    CargoPath fileDownload = parser.parse("crates/cargo/1.2.3/cargo-1.2.3.crate");
    assertEquals(CargoPath.Kind.DOWNLOAD, fileDownload.kind());
    assertEquals("cargo", fileDownload.crateName());
    assertEquals("1.2.3", fileDownload.version());

    CargoPath encodedBuild = parser.parse("crates/cargo/1.2.3%2Bbuild/download");
    assertEquals(CargoPath.Kind.DOWNLOAD, encodedBuild.kind());
    assertEquals("1.2.3+build", encodedBuild.version());
  }

  @Test
  void rejectsWrongIndexTier() {
    assertEquals(CargoPath.Kind.UNKNOWN, parser.parse("ca/cargo").kind());
    assertEquals(CargoPath.Kind.UNKNOWN, parser.parse("3/b/abc").kind());
  }

  @Test
  void rejectsNonSemverVersionRoutes() {
    assertEquals(CargoPath.Kind.UNKNOWN, parser.parse("api/v1/crates/cargo/latest/download").kind());
    assertEquals(CargoPath.Kind.UNKNOWN, parser.parse("api/v1/crates/cargo/1/yank").kind());
    assertEquals(CargoPath.Kind.UNKNOWN, parser.parse("crates/cargo/1.0/cargo-1.0.crate").kind());
  }
}
