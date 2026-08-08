package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.apt.AptPackageControl;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AptComponentFactoryTest {

  @Test
  void createsStableComponentIdentityAndBrowsePath() {
    AptComponentFactory factory = new AptComponentFactory();
    RepositoryRuntime runtime = new RepositoryRuntime(
        7, "apt", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted", true, 1L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
    AptPackageControl control = AptPackageControl.parse("""
        Package: libdemo
        Version: 1:2.0-1
        Architecture: amd64
        Maintainer: Demo <demo@example.com>
        Source: demo-source (1:2.0-1)
        Description: demo
        """);

    ComponentRecord component = factory.component(
        runtime, "stable", "main", control, "libdemo_2.0-1_amd64.deb",
        "pool/libd/demo-source/libdemo_2.0-1_amd64.deb", Instant.EPOCH);
    assertEquals(7, component.repositoryId());
    assertEquals(RepositoryFormat.APT, component.format());
    assertEquals("stable/main", component.namespace());
    assertEquals("libdemo", component.name());
    assertEquals("1:2.0-1", component.version());
    assertEquals("demo-source (1:2.0-1)", component.attributes().get("sourcePackage"));
    assertEquals(Instant.EPOCH, component.lastUpdatedAt());
    assertNotNull(component.coordinateHash());
    assertEquals(
        "stable/main/libdemo/1:2.0-1/amd64/libdemo_2.0-1_amd64.deb",
        factory.browsePath("stable", "main", control, "libdemo_2.0-1_amd64.deb"));

    AptPackageControl withoutSource = AptPackageControl.parse("""
        Package: demo
        Version: 1.0
        Architecture: all
        Maintainer: Demo <demo@example.com>
        Description: demo
        """);
    ComponentRecord now = factory.component(
        runtime, "stable", "main", withoutSource, "demo.deb", "pool/d/demo.deb", null);
    assertNotNull(now.lastUpdatedAt());
  }
}
