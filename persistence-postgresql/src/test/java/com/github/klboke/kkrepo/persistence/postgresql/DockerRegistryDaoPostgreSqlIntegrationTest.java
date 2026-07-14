package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcAssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcDockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class DockerRegistryDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  private static final int CONCURRENT_WRITERS = 20;
  private static final String IMAGE_NAME = "acme/app";
  private static final String MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final String REFERENCE_DIGEST = "sha256:" + "b".repeat(64);

  @Test
  void concurrentManifestUpsertsKeepTransactionTagAndReferencesConsistent() throws Exception {
    long repositoryId = insertRepository("docker-proxy", "docker");
    AssetDao assetDao = new JdbcAssetDao(jdbc(), jsonColumns());
    long assetId = assetDao.insertAsset(asset(repositoryId));
    CyclicBarrier start = new CyclicBarrier(CONCURRENT_WRITERS);
    List<Callable<Long>> writers = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_WRITERS; i++) {
      writers.add(writer(repositoryId, assetId, start, i));
    }

    List<Long> manifestIds = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(CONCURRENT_WRITERS)) {
      for (var future : executor.invokeAll(writers)) {
        manifestIds.add(future.get());
      }
    }

    assertEquals(CONCURRENT_WRITERS, manifestIds.size());
    assertEquals(1, manifestIds.stream().distinct().count());
    assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM docker_manifest", Integer.class));

    DockerRegistryDao dao = new JdbcDockerRegistryDao(jdbc(), jsonColumns());
    DockerManifestRecord manifest = dao.findManifestByDigest(
        repositoryId, IMAGE_NAME, MANIFEST_DIGEST).orElseThrow();
    assertEquals(manifest.id(), dao.findManifestByTag(
        repositoryId, IMAGE_NAME, "latest").orElseThrow().id());
    assertEquals(List.of(REFERENCE_DIGEST), dao.listReferences(manifest.id()).stream()
        .map(DockerManifestReferenceRecord::digest)
        .toList());
  }

  private Callable<Long> writer(
      long repositoryId, long assetId, CyclicBarrier start, int writerIndex) {
    return () -> {
      start.await();
      DockerRegistryDao dao = new JdbcDockerRegistryDao(jdbc(), jsonColumns());
      return inTransaction(() -> {
        DockerManifestRecord manifest = dao.upsertManifest(
            manifest(repositoryId, assetId, writerIndex));
        dao.replaceManifestReferences(manifest.id(), List.of(reference(repositoryId, manifest)));
        dao.upsertTag(tag(repositoryId, manifest));

        // These statements must remain usable after the expected PostgreSQL unique-key conflict.
        assertEquals(manifest.id(), dao.findManifestByDigest(
            repositoryId, IMAGE_NAME, MANIFEST_DIGEST).orElseThrow().id());
        assertTrue(dao.findManifestByTag(repositoryId, IMAGE_NAME, "latest").isPresent());
        return manifest.id();
      });
    };
  }

  private long insertRepository(String name, String format) {
    jdbc().update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES (?, 'S3', CAST('{}' AS jsonb))
        """, name + "-store");
    long blobStoreId = jdbc().queryForObject(
        "SELECT id FROM blob_store WHERE name = ?", Long.class, name + "-store");
    jdbc().update("""
        INSERT INTO repository
          (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES (?, ?, 'proxy', ?, ?, CAST('{}' AS jsonb))
        """, name, format, format + "-proxy", blobStoreId);
    return jdbc().queryForObject(
        "SELECT id FROM repository WHERE name = ?", Long.class, name);
  }

  private static AssetRecord asset(long repositoryId) {
    String path = "docker/manifests/" + IMAGE_NAME + "/" + MANIFEST_DIGEST;
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
      long repositoryId, long assetId, int writerIndex) {
    return new DockerManifestRecord(
        null,
        repositoryId,
        IMAGE_NAME,
        JdbcDockerRegistryDao.hash(IMAGE_NAME),
        "sha256",
        MANIFEST_DIGEST,
        JdbcDockerRegistryDao.hash(MANIFEST_DIGEST),
        "application/vnd.oci.image.manifest.v1+json",
        null,
        null,
        null,
        assetId,
        10,
        "writer-" + writerIndex,
        "127.0.0.1",
        null,
        Map.of("source", "concurrency-test"),
        null,
        null);
  }

  private static DockerManifestReferenceRecord reference(
      long repositoryId, DockerManifestRecord manifest) {
    return new DockerManifestReferenceRecord(
        null,
        manifest.id(),
        repositoryId,
        IMAGE_NAME,
        REFERENCE_DIGEST,
        JdbcDockerRegistryDao.hash(REFERENCE_DIGEST),
        "MANIFEST",
        "application/vnd.oci.image.manifest.v1+json",
        123L,
        Map.of("os", "linux", "architecture", "amd64"),
        Map.of());
  }

  private static DockerTagRecord tag(long repositoryId, DockerManifestRecord manifest) {
    return new DockerTagRecord(
        null,
        repositoryId,
        IMAGE_NAME,
        JdbcDockerRegistryDao.hash(IMAGE_NAME),
        "latest",
        JdbcDockerRegistryDao.hash("latest"),
        manifest.id(),
        MANIFEST_DIGEST,
        "concurrency-test",
        "127.0.0.1",
        null,
        null);
  }
}
