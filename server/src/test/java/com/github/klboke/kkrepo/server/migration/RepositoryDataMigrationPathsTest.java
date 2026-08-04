package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import org.junit.jupiter.api.Test;

class RepositoryDataMigrationPathsTest {
  private static final MavenPathParser MAVEN_PATH_PARSER = new MavenPathParser();

  @Test
  void discoverySkipsOnlyMavenChecksumSidecars() {
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.MAVEN2, "com/acme/app/1.0/app-1.0.jar.md5"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.MAVEN2, "com/acme/app/1.0/app-1.0.jar.sha1"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.MAVEN2, "com/acme/app/1.0/app-1.0.jar.sha256"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.MAVEN2, "com/acme/app/1.0/app-1.0.jar.sha512"));

    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.MAVEN2, "com/acme/app/1.0/app-1.0.jar"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.MAVEN2, "com/acme/app/maven-metadata.xml"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.NPM, "left-pad/-/left-pad-1.0.0.tgz.sha1"));
  }

  @Test
  void dockerDiscoveryOnlyKeepsMigratableManifestAndBlobAssets() {
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.DOCKER, "v2/team/app/manifests/latest"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.DOCKER, "v2/team/app/blobs/sha256:"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.DOCKER, "v2/blobs/sha256:"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));

    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.DOCKER, "v2/team/app/tags/list"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.DOCKER, "v2/team/app/search?q=app"));
  }

  @Test
  void pubDiscoveryKeepsPackageMetadataAndArchiveAssetsOnly() {
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "api/packages/example_package"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "packages/example_package/versions/1.0.0.tar.gz"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "api/archives/example_package-1.0.0-beta.1.tar.gz"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "example_package/1.0.0/example_package-1.0.0.tar.gz"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "example_package/1.0.0-beta.1/example_package-1.0.0-beta.1.tar.gz"));

    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "api/packages/example_package/versions/1.0.0"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "example_package/1.0.0/version.json"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "example_package/1.0.0/other_package-1.0.0.tar.gz"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "api/packages/versions/new"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.PUB, "api/package-names"));
  }

  @Test
  void swiftDiscoveryKeepsOnlyValidSourceArchives() {
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "Acme/Demo/1.2.3.zip"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "/Acme/Demo/2.0.0-beta.1+build.7.zip"));

    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "Acme/Demo/1.2.3"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "Acme/Demo/1.2.3/Package.swift"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "Acme/Demo/1.2.zip"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "Acme/Demo/1.2.3.ZIP"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "Acme/../1.2.3.zip"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, "Acme/Demo/1.2.3.zip/"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.SWIFT, null));
  }

  @Test
  void ansibleDiscoveryKeepsOnlyCanonicalCollectionArchives() {
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.ANSIBLEGALAXY,
        "api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.2.3.tar.gz"));
    assertTrue(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.ANSIBLEGALAXY, "acme/tools/1.2.3/acme-tools-1.2.3.tar.gz"));

    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.ANSIBLEGALAXY, "api/v3/collections/acme/tools/versions/1.2.3/"));
    assertFalse(RepositoryDataMigrationPaths.shouldDiscoverAsset(
        RepositoryFormat.ANSIBLEGALAXY, "acme-tools-latest.tar.gz"));
  }

  @Test
  void checksumGenerationRunsForMavenNonChecksumContent() {
    assertTrue(RepositoryDataMigrationPaths.shouldGenerateMavenChecksumSiblings(
        MAVEN_PATH_PARSER.parsePath("com/acme/app/1.0/app-1.0.jar")));
    assertTrue(RepositoryDataMigrationPaths.shouldGenerateMavenChecksumSiblings(
        MAVEN_PATH_PARSER.parsePath("com/acme/app/maven-metadata.xml")));

    assertFalse(RepositoryDataMigrationPaths.shouldGenerateMavenChecksumSiblings(
        MAVEN_PATH_PARSER.parsePath("com/acme/app/1.0/app-1.0.jar.sha1")));
    assertTrue(RepositoryDataMigrationPaths.shouldGenerateMavenChecksumSiblings(
        MAVEN_PATH_PARSER.parsePath("com/acme/app/1.0/app-1.0.jar.asc")));
  }

  @Test
  void checksumSuffixMatchIsCaseInsensitive() {
    assertTrue(RepositoryDataMigrationPaths.isMavenChecksumPath("a/b/c.JAR.SHA512"));
  }
}
