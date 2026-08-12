package com.github.klboke.kkrepo.protocol.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
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

  @Test
  void roundTripsCanonicalRecipeAndPackageStorageAndClientPaths() {
    ConanReference recipe = new ConanReference(
        "hello", "1.0", "acme", "stable", "rrev", null, null);
    String recipeStorage = ConanPaths.storagePath(recipe, "metadata/sign");
    assertEquals(
        "v2/conans/hello/1.0/acme/stable/revisions/rrev/files/metadata/sign",
        ConanPaths.fileRoute(recipe, "metadata/sign"));
    assertEquals(recipe, ConanPaths.parseStoragePath(recipeStorage).reference());
    assertEquals("metadata/sign", ConanPaths.parseStoragePath(recipeStorage).filePath());
    assertEquals(recipe, ConanPaths.parseStoragePath("/" + recipeStorage + "/").reference());

    ConanReference binary = recipe.packageCoordinate("packageid", "prev");
    String packageStorage = ConanPaths.storagePath(binary, "conan_package.tgz");
    ConanPaths.StorageFile parsed = ConanPaths.parseStoragePath(packageStorage);
    assertEquals(binary, parsed.reference());
    assertEquals("conan_package.tgz", parsed.filePath());
    assertEquals(
        "v2/conans/hello/1.0/acme/stable/revisions/rrev/packages/packageid/revisions/prev/"
            + "files/conan_package.tgz",
        ConanPaths.fileRoute(binary, "conan_package.tgz"));
    assertEquals(".conan/staging/42/metadata/sign",
        ConanPaths.stagingPath(42, "metadata/sign"));
  }

  @Test
  void rejectsIncompleteOrNonCanonicalPathCoordinates() {
    ConanReference recipe = new ConanReference(
        "hello", "1.0", null, null, "rrev", null, null);
    ConanReference packageWithoutRevision = recipe.packageCoordinate("packageid", null);
    assertThrows(IllegalArgumentException.class,
        () -> ConanPaths.storagePath(packageWithoutRevision, "file"));
    assertThrows(IllegalArgumentException.class,
        () -> ConanPaths.browsePath(packageWithoutRevision, "file"));
    assertThrows(IllegalArgumentException.class,
        () -> ConanPaths.fileRoute(packageWithoutRevision, "file"));
    assertThrows(IllegalArgumentException.class,
        () -> ConanPaths.storagePath(null, "file"));
    assertThrows(IllegalArgumentException.class,
        () -> ConanPaths.storagePath(recipe, "../file"));
    assertThrows(IllegalArgumentException.class,
        () -> ConanPaths.stagingPath(0, "file"));
    assertThrows(IllegalArgumentException.class,
        () -> ConanPaths.stagingPath(1, "bad/../file"));
    for (String invalid : List.of(
        "", "wrong/hello/1.0/_/_/revisions/r/files/x",
        "conans/hello/1.0/_/_/wrong/r/files/x",
        "conans/hello/1.0/_/_/revisions/r/wrong/x",
        "conans/hello/1.0/_/_/revisions/r/packages/p/wrong/q/files/x",
        "conans/hello/1.0/_/_/revisions/r/packages/p/revisions/q/wrong/x",
        "conans/hello/1.0/_/_/revisions/r/files/../x")) {
      assertThrows(IllegalArgumentException.class, () -> ConanPaths.parseStoragePath(invalid));
    }
  }

  @Test
  void exposesCanonicalReferenceStringsAndRejectsInvalidIdentityGraphs() {
    ConanReference recipe = new ConanReference(
        "hello", "1.0", null, null, null, null, null);
    assertEquals("hello/1.0", recipe.recipe());
    assertEquals("hello/1.0", recipe.recipeWithRevision());
    assertNull(recipe.packageReference());
    assertEquals("_", recipe.routeUser());
    assertEquals("_", recipe.routeChannel());
    assertEquals("_/_", recipe.namespace());

    ConanReference revision = recipe.recipeRevision("rrev");
    assertEquals("hello/1.0#rrev", revision.recipeWithRevision());
    assertEquals("hello/1.0#rrev:pkg", revision.packageCoordinate("pkg", null).packageReference());
    assertEquals("hello/1.0#rrev:pkg#prev",
        revision.packageCoordinate("pkg", "prev").packageReference());

    assertThrows(IllegalArgumentException.class,
        () -> new ConanReference("hello", "1.0", null, "stable", null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanReference("HELLO", "1.0", null, null, null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanReference("h", "1.0", null, null, null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanReference("hello", "1.0", null, null, null, "pkg", null));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanReference("hello", "1.0", null, null, "r", null, "prev"));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanReference("hello", "1.0", null, null, "!", null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanReference("a".repeat(100), "b".repeat(100), null, null,
            null, null, null));
  }

  @Test
  void ordersTextNumericPrereleaseBuildAndNullVersionsDeterministically() {
    var comparator = ConanVersions.comparator();
    assertTrue(comparator.compare(null, "1") < 0);
    assertTrue(comparator.compare("1.alpha", "1.beta") < 0);
    assertTrue(comparator.compare("1", "1-alpha") > 0);
    assertTrue(comparator.compare("1+1", "1+2") < 0);
    assertTrue(comparator.compare("1", "1+1") < 0);
    assertTrue(comparator.compare("1+2", "1") > 0);
    assertEquals(0, comparator.compare("1.0.0", "1"));
    assertEquals(RepositoryFormat.CONAN, new ConanRepositoryProtocol().format());
  }
}
