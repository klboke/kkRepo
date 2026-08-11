package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConanServiceResponseTest {
  @Test
  void packageSearchKeepsTheNexusContentShapeForListOnlyAndEmptyConaninfo() {
    ConanRegistryDao.Package empty = pkg(Map.of(), Map.of(), Map.of());

    assertEquals(Map.of("content", ""), ConanService.packageSearchEntry(empty, true));
    assertEquals(Map.of("content", ""), ConanService.packageSearchEntry(empty, false));
  }

  @Test
  void packageSearchRendersCanonicalConaninfoSections() {
    ConanRegistryDao.Package value = pkg(
        Map.of("os", "Linux", "arch", "x86_64"),
        Map.of("shared", "False"),
        Map.of("zlib/1.3.1", "zlib/1.3.1#rrev"));

    assertEquals(Map.of("content", ""), ConanService.packageSearchEntry(value, true));
    assertEquals(Map.of("content", """
        [settings]
        arch=x86_64
        os=Linux

        [options]
        shared=False

        [requires]
        zlib/1.3.1=zlib/1.3.1#rrev

        """), ConanService.packageSearchEntry(value, false));
  }

  private static ConanRegistryDao.Package pkg(
      Map<String, String> settings,
      Map<String, String> options,
      Map<String, String> requires) {
    return new ConanRegistryDao.Package(
        1L, 2L, "package-id", settings, options, requires, null,
        Instant.EPOCH, Instant.EPOCH);
  }
}
