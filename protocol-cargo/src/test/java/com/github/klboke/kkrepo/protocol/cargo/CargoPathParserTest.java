package com.github.klboke.kkrepo.protocol.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CargoPathParserTest {
  private final CargoPathParser parser = new CargoPathParser();

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

    CargoPath nexusDownload = parser.parse("crates/cargo/1.2.3/download");
    assertEquals(CargoPath.Kind.DOWNLOAD, nexusDownload.kind());
    assertEquals("cargo", nexusDownload.crateName());
    assertEquals("1.2.3", nexusDownload.version());

    CargoPath fileDownload = parser.parse("crates/cargo/1.2.3/cargo-1.2.3.crate");
    assertEquals(CargoPath.Kind.DOWNLOAD, fileDownload.kind());
    assertEquals("cargo", fileDownload.crateName());
    assertEquals("1.2.3", fileDownload.version());
  }

  @Test
  void rejectsWrongIndexTier() {
    assertEquals(CargoPath.Kind.UNKNOWN, parser.parse("ca/cargo").kind());
    assertEquals(CargoPath.Kind.UNKNOWN, parser.parse("3/b/abc").kind());
  }
}
