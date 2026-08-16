package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePackageInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlpineComponentFactoryTest {
  private final AlpineComponentFactory factory = new AlpineComponentFactory();

  @Test
  void createsStableComponentAndBrowseIdentities() {
    AlpinePackageInfo info = AlpinePackageInfo.parse("""
        pkgname = demo
        pkgver = 1.2-r3
        pkgdesc = demo
        url = https://example.invalid
        builddate = 1
        packager = test
        size = 7
        arch = x86_64
        origin = demo-origin
        maintainer = Alice
        license = MIT
        datahash = %s
        """.formatted("a".repeat(64)));
    Instant updated = Instant.parse("2026-08-15T00:00:00Z");

    ComponentRecord component = factory.component(
        runtime(), "v3.20", "main", "x86_64", info, "demo-1.2-r3.apk", "asset",
        "Q1abc", "b".repeat(64), updated);

    assertEquals(RepositoryFormat.ALPINE, component.format());
    assertEquals("v3.20/main/x86_64", component.namespace());
    assertEquals("demo", component.name());
    assertEquals("1.2-r3", component.version());
    assertEquals(updated, component.lastUpdatedAt());
    assertEquals("demo-origin", component.attributes().get("origin"));
    assertEquals("MIT", component.attributes().get("license"));
    assertNotNull(component.coordinateHash());
    assertEquals(
        "v3.20/main/x86_64/demo-1.2-r3.apk",
        factory.browsePath("v3.20", "main", "x86_64", "demo-1.2-r3.apk"));

    AlpinePackageInfo minimal = AlpinePackageInfo.parse("""
        pkgname = tiny
        pkgver = 1-r0
        size = 0
        arch = noarch
        datahash = %s
        """.formatted("c".repeat(64)));
    ComponentRecord defaultTime = factory.component(
        runtime(), "edge", "testing", "x86_64", minimal, "tiny-1-r0.apk", "path",
        "Q1tiny", "d".repeat(64), null);
    assertNotNull(defaultTime.lastUpdatedAt());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "alpine", RepositoryFormat.ALPINE, RepositoryType.HOSTED, "alpine-hosted", true,
        1L, "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }
}
