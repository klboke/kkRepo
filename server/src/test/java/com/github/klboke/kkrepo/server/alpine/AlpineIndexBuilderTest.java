package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlpineIndexBuilderTest {

  @Test
  void publishesRepositoryArchitectureWhilePreservingNoarchPackageMetadata() {
    AlpineRegistryDao.PackageRecord row = new AlpineRegistryDao.PackageRecord(
        1L,
        2L,
        "v3.23",
        "main",
        "x86_64",
        "demo",
        "1.0.0-r0",
        "noarch",
        "demo-1.0.0-r0.apk",
        "v3.23/main/x86_64/demo-1.0.0-r0.apk",
        Map.of("A", "noarch", "I", "42"),
        "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "data-sha256",
        "blob-sha256",
        123L,
        3L,
        4L,
        AlpineRegistryDao.SOURCE_HOSTED,
        1L,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH);

    AlpineIndexRecord record = AlpineIndexBuilder.indexRecord(row);

    assertEquals("x86_64", record.architecture());
    assertEquals("noarch", row.packageArchitecture());
  }
}
