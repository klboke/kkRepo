package com.github.klboke.kkrepo.protocol.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConanPathsAndVersionsTest {
  @Test
  void projectsTheNexus394BrowseShapeAtWriteTime() {
    ConanReference recipe = new ConanReference(
        "hello", "1.0", null, null, "rrev", null, null);
    assertEquals(
        "_/hello/1.0/_#rrev/conanfile.py",
        ConanPaths.browsePath(recipe, "conanfile.py"));
    assertEquals(
        "conans/hello/1.0/_/_/revisions/rrev/files/conanfile.py",
        ConanPaths.storagePath(recipe, "conanfile.py"));

    ConanReference binary = recipe.packageCoordinate("packageid", "prev");
    assertEquals(
        "_/hello/1.0/_#rrev/packages/packageid/revisions/prev/files/conan_package.tgz",
        ConanPaths.browsePath(binary, "conan_package.tgz"));
    assertEquals(
        ConanPaths.browsePath(binary, "conan_package.tgz"),
        new ConanBrowsePathProjector().project(binary, "conan_package.tgz"));
    assertEquals(
        "conans/hello/1.0/_/_/revisions/rrev/packages/packageid/revisions/prev/files/"
            + "conan_package.tgz",
        ConanPaths.storagePath(binary, "conan_package.tgz"));
  }

  @Test
  void followsConanNumericPrereleaseAndBuildOrdering() {
    List<String> versions = new ArrayList<>(List.of(
        "2.0", "1.10", "1.2", "1.0", "1.0.0", "1.0-beta.2", "1.0-beta.10"));
    versions.sort(ConanVersions.comparator());
    assertEquals(List.of(
        "1.0-beta.2", "1.0-beta.10", "1.0", "1.0.0", "1.2", "1.10", "2.0"),
        versions);
    assertTrue(ConanVersions.comparator().compare("1.2.9", "1.2.10") < 0);
  }

  @Test
  void declaresHostedProxyGroupAndDeleteCapabilities() {
    var capability = new ConanRepositoryProtocol().capability();
    assertTrue(capability.hostedRead());
    assertTrue(capability.hostedWrite());
    assertTrue(capability.proxyRead());
    assertTrue(capability.groupRead());
    assertTrue(capability.nexusPathCompatible());
  }

  @Test
  void recipeCoordinateKeysAreUnambiguousAndPostgreSqlTextSafe() {
    ConanReference reference = new ConanReference(
        "hello", "1.0", "acme", "stable", null, null, null);
    assertFalse(reference.coordinateKey().contains("\0"));
    assertNotEquals(
        reference.coordinateKey(),
        new ConanReference("hello", "1.0", "acme-stable", null, null, null, null)
            .coordinateKey());
  }
}
