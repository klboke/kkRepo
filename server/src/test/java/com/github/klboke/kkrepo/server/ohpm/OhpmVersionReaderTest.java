package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OhpmVersionReaderTest {
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/pkg");
  private final OhpmRepositoryDao dao = mock(OhpmRepositoryDao.class);
  private final OhpmVersionReader reader = new OhpmVersionReader(dao, new ObjectMapper());

  @Test
  void rejectsForeignPathsAndInvalidVersionsBeforeQueryingPersistence() {
    for (String path : new String[] {"other/-/other-1.0.0.har", "@scope/pkg/-/@scope/pkg-1.0.0.zip",
        "@scope/pkg/-/@scope/pkg-../1.0.0.har", "@scope/pkg/-/@scope/pkg-latest.hsp"}) {
      assertEquals(404, assertThrows(OhpmException.class,
          () -> reader.find(mock(RepositoryRuntime.class), PACKAGE, path)).status());
    }
    verifyNoInteractions(dao);
  }

  @Test
  void extractsLiteralPrereleaseAndBuildIdentityFromCanonicalHspPath() {
    assertEquals("1.2.3-rc.1+build.7",
        OhpmVersionReader.version(PACKAGE, PACKAGE.downloadPath(PACKAGE.id() + "-1.2.3-rc.1+build.7.hsp")));
  }

  @Test
  void distinguishesAbsentVersionFromCorruptDurableManifest() {
    String path = PACKAGE.downloadPath(PACKAGE.id() + "-1.0.0.har");
    var absent = new OhpmRepositoryDao.Version(Map.of(), null, null, null, Map.of());
    assertTrue(reader.parse(PACKAGE, path, absent, true).downloads().isEmpty());
    var corrupt = new OhpmRepositoryDao.Version(
        Map.of("name", "another-package", "version", "1.0.0"), null, null, null, Map.of());
    assertEquals(502, assertThrows(OhpmException.class,
        () -> reader.parse(PACKAGE, path, corrupt, false)).status());
  }
}
