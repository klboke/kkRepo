package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CondaBrowsePathsTest {

  @Test
  void mapsLogicalBrowseHierarchyBackToPublicPackagePath() {
    String logical = "team/release/linux-64/demo/1.2.3/"
        + "demo-1.2.3-py312_0.conda";

    assertEquals(
        "team/release/linux-64/demo-1.2.3-py312_0.conda",
        CondaBrowsePaths.toStoragePath(logical));
    assertEquals(
        "team/release",
        CondaBrowsePaths.packagePath(logical).orElseThrow().channel());
  }

  @Test
  void keepsLegacyBuildDirectoryLinksResolvableDuringRollout() {
    String logical = "team/release/linux-64/demo/1.2.3/py312_0/"
        + "demo-1.2.3-py312_0.conda";

    assertEquals(
        "team/release/linux-64/demo-1.2.3-py312_0.conda",
        CondaBrowsePaths.toStoragePath(logical));
  }

  @Test
  void componentBrowsePathMatchesNexusNameVersionHierarchy() {
    assertEquals(
        "team/release/linux-64/demo/1.2.3/demo-1.2.3-py312_0.conda",
        new CondaComponentFactory().browsePath(
            "team/release", "linux-64", "demo", "1.2.3",
            "demo-1.2.3-py312_0.conda"));
  }

  @Test
  void projectsHyphenatedPackageNameDirectlyFromCanonicalStoragePath() {
    RepositoryRuntime proxy = new RepositoryRuntime(
        1, "conda-proxy", RepositoryFormat.CONDA, RepositoryType.PROXY, "conda-proxy",
        true, 1L, "ALLOW_ONCE", null, null, true, "https://repo.example/", 60, 60,
        true, null, List.of());
    String filename = "basesystem-amzn2-aarch64-10.0-5.tar.bz2";
    CondaComponentFactory.ProjectedPackage projected = new CondaComponentFactory()
        .projectPackagePath(
            proxy, new CondaPathParser().parse("noarch/" + filename), Instant.EPOCH)
        .orElseThrow();

    assertEquals("basesystem-amzn2-aarch64", projected.component().name());
    assertEquals("10.0", projected.component().version());
    assertEquals("noarch/basesystem-amzn2-aarch64/10.0/" + filename,
        projected.browsePath());
  }

  @Test
  void preservesDirectPathsAndRejectsNonPackages() {
    String direct = "label/Release Candidate/linux-64/demo-1.0-0.tar.bz2";

    assertEquals(direct, CondaBrowsePaths.toStoragePath(direct));
    assertTrue(CondaBrowsePaths.packagePath("main/linux-64/repodata.json").isEmpty());
  }
}
