package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerRegistryDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void manifestTagAndReferenceLifecycleUsesDockerUniqueKeys() {
    long repositoryId = insertRepository("docker-hosted", "docker");
    AssetDao assetDao = new JdbcAssetDao(jdbc(), jsonColumns());
    long assetId = assetDao.insertAsset(asset(repositoryId, "docker/manifests/acme/app/v1"));
    DockerRegistryDao dao = new JdbcDockerRegistryDao(jdbc(), jsonColumns());
    DockerManifestRecord first = manifest(repositoryId, assetId, "sha256:" + "a".repeat(64), 10);

    DockerManifestRecord inserted = inTransaction(() -> dao.upsertManifest(first));
    DockerManifestRecord updated = inTransaction(() -> dao.upsertManifest(
        manifest(repositoryId, assetId, first.digest(), 20)));

    assertEquals(inserted.id(), updated.id());
    assertEquals(20, updated.size());
    assertEquals(updated.id(), dao.findManifestByReference(
        repositoryId, "acme/app", first.digest()).orElseThrow().id());

    inTransaction(() -> {
      dao.upsertTag(tag(repositoryId, updated, "latest"));
      dao.upsertTag(tag(repositoryId, updated, "1.0"));
      dao.replaceManifestReferences(updated.id(), List.of(reference(repositoryId, updated)));
    });

    assertEquals(List.of("1.0", "latest"), dao.listTags(repositoryId, "acme/app", null, 10));
    assertEquals(List.of("latest"), dao.listTags(repositoryId, "acme/app", "1.0", 10));
    assertEquals(2, dao.countTags(repositoryId, "acme/app"));
    assertTrue(dao.tagExists(repositoryId, "acme/app", "latest"));
    assertEquals(updated.id(), dao.findManifestByTag(
        repositoryId, "acme/app", "latest").orElseThrow().id());
    assertEquals(1, dao.listReferences(updated.id()).size());
    assertTrue(dao.imageReferencesDigest(repositoryId, "acme/app", referenceDigest()));
    assertEquals(List.of("acme/app"), dao.listCatalog(repositoryId, null, 10));

    DockerRegistryDao.DeletedManifest deleted = inTransaction(
        () -> dao.deleteManifest(repositoryId, "acme/app", first.digest()));
    assertEquals(1, deleted.deleted());
    assertEquals(assetId, deleted.assetId());
    assertFalse(dao.imageExists(repositoryId, "acme/app"));
    assertTrue(dao.listTagsForManifest(updated.id()).isEmpty());
    assertEquals(0, inTransaction(
        () -> dao.deleteManifest(repositoryId, "acme/app", first.digest())).deleted());
  }

  @Test
  void paginationAndBrowsePathsReturnOnlyLiveImages() {
    long repositoryId = insertRepository("docker-browse", "docker");
    AssetDao assetDao = new JdbcAssetDao(jdbc(), jsonColumns());
    DockerRegistryDao dao = new JdbcDockerRegistryDao(jdbc(), jsonColumns());
    long alphaAssetId = assetDao.insertAsset(asset(repositoryId, "docker/manifests/acme/alpha/v1"));
    long betaAssetId = assetDao.insertAsset(asset(repositoryId, "docker/manifests/acme/beta/v1"));
    DockerManifestRecord alpha = inTransaction(() -> dao.upsertManifest(
        manifest(repositoryId, alphaAssetId, "sha256:" + "b".repeat(64), 11, "acme/alpha")));
    DockerManifestRecord beta = inTransaction(() -> dao.upsertManifest(
        manifest(repositoryId, betaAssetId, "sha256:" + "c".repeat(64), 12, "acme/beta")));
    inTransaction(() -> {
      dao.upsertTag(tag(repositoryId, alpha, "stable"));
      dao.upsertTag(tag(repositoryId, beta, "latest"));
    });

    assertEquals(List.of("acme/alpha"), dao.listCatalog(repositoryId, null, 1));
    assertEquals(List.of("acme/beta"), dao.listCatalog(repositoryId, "acme/alpha", 10));
    assertEquals(2, dao.listBrowseImages(repositoryId, "acme").size());
    assertEquals(2, dao.listBrowseReferences(repositoryId, "acme/alpha").size());
    assertTrue(dao.findBrowseManifestByReferencePath(
        repositoryId, "acme/alpha/manifests/stable").isPresent());
    assertTrue(dao.findBrowseManifestByReferencePath(repositoryId, "invalid").isEmpty());
  }

  private static AssetRecord asset(long repositoryId, String path) {
    return new AssetRecord(
        null,
        repositoryId,
        null,
        null,
        RepositoryFormat.DOCKER,
        path,
        HashColumns.pathHash(path),
        "manifest.json",
        "MANIFEST",
        "application/vnd.oci.image.manifest.v1+json",
        10L,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of());
  }

  private static DockerManifestRecord manifest(
      long repositoryId, long assetId, String digest, long size) {
    return manifest(repositoryId, assetId, digest, size, "acme/app");
  }

  private static DockerManifestRecord manifest(
      long repositoryId, long assetId, String digest, long size, String imageName) {
    return new DockerManifestRecord(
        null,
        repositoryId,
        imageName,
        JdbcDockerRegistryDao.hash(imageName),
        "sha256",
        digest,
        JdbcDockerRegistryDao.hash(digest),
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.example.sbom",
        null,
        null,
        assetId,
        size,
        "tester",
        "127.0.0.1",
        null,
        Map.of("source", "integration-test"),
        null,
        null);
  }

  private static DockerTagRecord tag(
      long repositoryId, DockerManifestRecord manifest, String tag) {
    return new DockerTagRecord(
        null,
        repositoryId,
        manifest.imageName(),
        JdbcDockerRegistryDao.hash(manifest.imageName()),
        tag,
        JdbcDockerRegistryDao.hash(tag),
        manifest.id(),
        manifest.digest(),
        "tester",
        "127.0.0.1",
        null,
        null);
  }

  private static DockerManifestReferenceRecord reference(
      long repositoryId, DockerManifestRecord manifest) {
    return new DockerManifestReferenceRecord(
        null,
        manifest.id(),
        repositoryId,
        manifest.imageName(),
        referenceDigest(),
        JdbcDockerRegistryDao.hash(referenceDigest()),
        "MANIFEST",
        "application/vnd.oci.image.manifest.v1+json",
        123L,
        Map.of("os", "linux", "architecture", "amd64"),
        Map.of("org.opencontainers.image.title", "base"));
  }

  private static String referenceDigest() {
    return "sha256:" + "d".repeat(64);
  }
}
