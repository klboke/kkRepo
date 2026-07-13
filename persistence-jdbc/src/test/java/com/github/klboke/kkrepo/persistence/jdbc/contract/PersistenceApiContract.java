package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Reusable black-box contract that every database backend must pass through the public API. */
public abstract class PersistenceApiContract {
  protected abstract PersistenceStores stores();

  @Test
  void componentUpsertIsAtomicAndJsonValuesRoundTrip() throws Exception {
    long repositoryId = createRepository("maven-hosted", RepositoryFormat.MAVEN2);
    ComponentRecord component = component(
        repositoryId,
        RepositoryFormat.MAVEN2,
        "com.acme.platform",
        "observability-library",
        "1.2.3",
        Map.of("description", "distributed tracing", "verified", true),
        Instant.parse("2026-07-13T08:00:00Z"));

    List<Callable<Long>> writes = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      writes.add(() -> stores().components().upsertReturningId(component));
    }
    List<Long> ids;
    try (var executor = Executors.newFixedThreadPool(6)) {
      ids = executor.invokeAll(writes).stream().map(future -> {
        try {
          return future.get();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }).toList();
    }

    assertEquals(1, new HashSet<>(ids).size());
    assertEquals(1L, stores().components().countByRepositoryId(repositoryId));
    long componentId = ids.getFirst();
    assertEquals(component.attributes(), stores().components().findById(componentId).orElseThrow().attributes());

    Map<String, Object> updated = Map.of("description", "telemetry platform", "verified", false);
    stores().components().updateAttributes(componentId, updated, Instant.parse("2026-07-13T09:00:00Z"));
    assertEquals(updated, stores().components().findById(componentId).orElseThrow().attributes());
  }

  @Test
  void componentSearchPreservesAndPrefixFormatRepositoryAndStableOrderingSemantics() {
    long mavenRepository = createRepository("maven-search", RepositoryFormat.MAVEN2);
    long otherMavenRepository = createRepository("maven-other", RepositoryFormat.MAVEN2);
    long npmRepository = createRepository("npm-search", RepositoryFormat.NPM);

    stores().components().upsertReturningId(component(
        mavenRepository, RepositoryFormat.MAVEN2, "com.acme.platform", "observability-library",
        "2.0.0", Map.of("keywords", "telemetry tracing"), Instant.parse("2026-07-13T10:00:00Z")));
    stores().components().upsertReturningId(component(
        mavenRepository, RepositoryFormat.MAVEN2, "com.acme.platform", "observability-agent",
        "1.0.0", Map.of("keywords", "telemetry collector"), Instant.parse("2026-07-13T09:00:00Z")));
    stores().components().upsertReturningId(component(
        otherMavenRepository, RepositoryFormat.MAVEN2, "org.example", "observability-library",
        "3.0.0", Map.of("keywords", "telemetry tracing"), Instant.parse("2026-07-13T08:00:00Z")));
    stores().components().upsertReturningId(component(
        npmRepository, RepositoryFormat.NPM, "@acme", "observability-library",
        "4.0.0", Map.of("keywords", "telemetry tracing"), Instant.parse("2026-07-13T11:00:00Z")));

    var andSearch = stores().components().search("acme library", RepositoryFormat.MAVEN2, 20);
    assertEquals(List.of("2.0.0"), andSearch.stream().map(row -> row.version()).toList());
    assertEquals("observability-library",
        stores().components().search("observ libr", RepositoryFormat.MAVEN2, 20).getFirst().name());
    assertEquals(2,
        stores().components().search("telemetry tracing", RepositoryFormat.MAVEN2, 20).size());
    assertEquals(List.of("2.0.0"), stores().components().searchByRepositoryIds(
            List.of(mavenRepository), RepositoryFormat.MAVEN2, "telemetry tracing", 20)
        .stream().map(row -> row.version()).toList());
    assertEquals(List.of("2.0.0", "3.0.0"),
        stores().components().search("telemetry tracing", RepositoryFormat.MAVEN2, 20)
            .stream().map(row -> row.version()).toList());
    assertFalse(stores().components().search("telemetry", RepositoryFormat.NPM, 20).isEmpty());
  }

  @Test
  void cacheVersionsAreMonotonicAcrossConcurrentConnections() throws Exception {
    List<Callable<Long>> bumps = new ArrayList<>();
    for (int index = 0; index < 16; index++) {
      bumps.add(() -> stores().cacheVersions().bump("security"));
    }
    List<Long> versions;
    try (var executor = Executors.newFixedThreadPool(8)) {
      versions = executor.invokeAll(bumps).stream().map(future -> {
        try {
          return future.get();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }).sorted().toList();
    }

    assertEquals(java.util.stream.LongStream.rangeClosed(1, 16).boxed().toList(), versions);
    assertEquals(16L, stores().cacheVersions().current("security"));
  }

  @Test
  void insertIfAbsentRelationshipsAndBacklogAgeRemainIdempotent() {
    var security = stores().security();
    long userId = security.insertUser(new SecurityUserRecord(
        null, "Local", "alice", "Alice", "Example", "alice@example.com", "hash", "active",
        null, Map.of("team", "infra")));
    security.upsertRole(new SecurityRoleRecord(
        "developers", "Local", "Developers", "Developers", false, Map.of()));
    security.upsertRole(new SecurityRoleRecord(
        "readers", "Local", "Readers", "Readers", false, Map.of()));
    var privilege = new SecurityPrivilegeRecord(
        "repository-read", "Repository read", "Read repositories", "repository-view", false,
        Map.of("actions", List.of("read")));
    security.insertPrivilegeIfAbsent(privilege);
    security.insertPrivilegeIfAbsent(privilege);
    security.assignRole(userId, "developers");
    security.assignRole(userId, "developers");
    security.grantPrivilege("developers", privilege.privilegeId());
    security.grantPrivilege("developers", privilege.privilegeId());
    security.inheritRole("developers", "readers");
    security.inheritRole("developers", "readers");

    assertEquals(List.of("developers"), security.listUserRoleIds(userId));
    assertEquals(List.of("repository-read"), security.listRolePrivilegeIds("developers"));
    assertEquals(List.of("readers"), security.listRoleChildIds("developers"));

    long repositoryId = createRepository("marker-hosted", RepositoryFormat.MAVEN2);
    stores().metadataRebuild().enqueue(repositoryId, "ga:com.acme/library");
    stores().metadataRebuild().enqueue(repositoryId, "ga:com.acme/library");
    assertEquals(1L, stores().metadataRebuild().countBacklog());
    assertTrue(stores().metadataRebuild().oldestBacklogAgeSeconds() >= 0);
    stores().repositoryIndexRebuild().enqueue(
        repositoryId, RepositoryIndexRebuildDao.HELM_INDEX, RepositoryIndexRebuildDao.ROOT_SCOPE);
    assertEquals(1L, stores().repositoryIndexRebuild().countBacklog());
    assertTrue(stores().repositoryIndexRebuild().oldestBacklogAgeSeconds() >= 0);
  }

  @Test
  void migrationJsonBindingExtractionAndBooleanUpdateUsePublicContracts() {
    long jobId = stores().migrationJobs().create(
        "3.70.1",
        "/nexus-data",
        Map.of("scope", "repository-data", "packageMigrationEnabled", false));

    assertEquals(1, stores().repositoryDataMigrations().listRepositoryDataJobs(10).size());
    stores().repositoryDataMigrations().setPackageMigrationEnabled(jobId, true);
    assertEquals(
        true,
        stores().migrationJobs().findById(jobId).orElseThrow().options().get("packageMigrationEnabled"));
    stores().migrationJobs().markFinished(jobId, "finished", Map.of("migrated", 12));
    assertEquals(
        12,
        stores().migrationJobs().findById(jobId).orElseThrow().summary().get("migrated"));
  }

  private long createRepository(String name, RepositoryFormat format) {
    long blobStoreId = stores().blobStores().insert(new BlobStoreRecord(
        null, name + "-store", "S3", "https://s3.example", "cn-test-1", "artifacts", name,
        Map.of("pathStyleAccess", true)));
    return stores().repositories().insert(new RepositoryRecord(
        null,
        name,
        format,
        RepositoryType.HOSTED,
        format.id() + "-hosted",
        true,
        blobStoreId,
        null,
        null,
        "RELEASE",
        "STRICT",
        "ALLOW_ONCE",
        true,
        Map.of("storage", Map.of("blobStoreName", name + "-store"))));
  }

  private static ComponentRecord component(
      long repositoryId,
      RepositoryFormat format,
      String namespace,
      String name,
      String version,
      Map<String, Object> attributes,
      Instant updatedAt) {
    return new ComponentRecord(
        null,
        repositoryId,
        format,
        namespace,
        name,
        version,
        "release",
        sha256(namespace + '\u0000' + name + '\u0000' + version),
        attributes,
        updatedAt);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
