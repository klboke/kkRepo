package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AptRepositoryDataMigrationWriterTest {

  @Test
  void onlyAcceptsCanonicalPoolDebianPackagePaths() {
    assertTrue(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "pool/main/d/demo/demo_1%3a2.0-1_amd64.deb"));
    assertTrue(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "/pool/main/libd/libdemo/libdemo_2.0-1_all.deb"));

    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "dists/stable/main/binary-amd64/Packages.gz"));
    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "pool/main/d/demo/demo_2.0-1_amd64.deb/extra"));
    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath("../demo.deb"));
    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath(null));
  }
}
