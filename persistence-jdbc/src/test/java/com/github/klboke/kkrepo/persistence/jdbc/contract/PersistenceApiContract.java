package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationCheckpointRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Reusable black-box contract that every database backend must pass through the public API. */
public abstract class PersistenceApiContract {
  protected abstract PersistenceStores stores();

  protected abstract <T> T inTransaction(Supplier<T> action);

  protected abstract Set<String> databaseTables();

  @Test
  void baselineContainsTheCompleteSharedLogicalSchema() {
    assertEquals(Set.of(
        "api_key",
        "ansible_collection_signature",
        "ansible_collection_version",
        "ansible_group_binding",
        "ansible_import_task",
        "ansible_proxy_version_state",
        "ansible_registry_lease",
        "asset",
        "asset_blob",
        "auth_ticket",
        "blob_store",
        "browse_node",
        "cache_version",
        "cleanup_policy",
        "component",
        "component_search",
        "content_selector",
        "docker_auth_token",
        "docker_manifest",
        "docker_manifest_reference",
        "docker_tag",
        "docker_upload_chunk",
        "docker_upload_session",
        "maintenance_cursor",
        "metadata_rebuild_marker",
        "migration_checkpoint",
        "migration_job",
        "migration_validation_result",
        "npm_release_index_entry",
        "npm_release_index_revision",
        "proxy_remote_state",
        "pub_upload_session",
        "repository",
        "repository_cleanup_policy",
        "repository_data_migration_asset",
        "repository_data_migration_repository",
        "repository_index_rebuild_marker",
        "repository_member",
        "routing_rule",
        "security_anonymous_config",
        "security_audit_log",
        "security_privilege",
        "security_realm",
        "security_realm_config",
        "security_repository_target",
        "security_role",
        "security_role_inheritance",
        "security_role_privilege",
        "security_user",
        "security_user_role",
        "spring_session",
        "spring_session_attributes",
        "swift_coordinate_lease",
        "swift_group_source_binding",
        "swift_manifest",
        "swift_proxy_inventory",
        "swift_proxy_negative_cache",
        "swift_proxy_source",
        "swift_release",
        "swift_release_tombstone",
        "swift_repository_url",
        "terraform_provider_platform",
        "terraform_provider_signing_state",
        "terraform_publish_lease",
        "terraform_signing_key",
        "terraform_source_binding",
        "ui_settings"), databaseTables());
  }

  @Test
  void ansibleGalaxyRegistryStateIsImmutableFencedAndSharedAcrossReplicas() {
    long repositoryId = createRepository("ansible-hosted", RepositoryFormat.ANSIBLEGALAXY);
    long proxyRepositoryId = createRepository(
        "ansible-proxy", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.PROXY);
    long groupRepositoryId = createRepository(
        "ansible-group", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP);
    stores().repositories().addMember(groupRepositoryId, repositoryId, 0);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-21T08:00:00Z");

    long componentId = stores().components().upsertReturningId(component(
        repositoryId, RepositoryFormat.ANSIBLEGALAXY, "acme", "tools", "1.2.3",
        Map.of("kind", "ansible-collection"), now));
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "ansible/acme-tools-1.2.3.tar.gz", "ansible-artifact-ref"));
    String path = "api/v3/plugin/ansible/content/published/collections/artifacts/"
        + "acme-tools-1.2.3.tar.gz";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, componentId, blobId, RepositoryFormat.ANSIBLEGALAXY, path,
        PersistenceHashes.pathHash(path), "acme-tools-1.2.3.tar.gz", "collection-artifact",
        "application/octet-stream", 42L, null, now, Map.of()));

    AnsibleGalaxyRegistryDao registry = stores().ansibleGalaxyRegistry();
    long revision = registry.nextRepositoryRevision(repositoryId);
    AnsibleGalaxyRegistryDao.CollectionVersion fixture =
        new AnsibleGalaxyRegistryDao.CollectionVersion(
            null, repositoryId, componentId, assetId, "acme", "acme", "tools", "tools",
            "1.2.3", "1.2.3", "acme-tools-1.2.3.tar.gz", "a".repeat(64), 42L,
            Map.of("authors", List.of("kkrepo")),
            Map.of("community.general", ">=8.0.0,<9.0.0"), ">=2.16", "HOSTED",
            revision, AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
    AnsibleGalaxyRegistryDao.CollectionVersion stored =
        inTransaction(() -> registry.insertVersion(fixture));

    assertNotNull(stored.id());
    assertEquals("1.2.3", registry.findVersion(
        repositoryId, "acme", "tools", "1.2.3").orElseThrow().versionOriginal());
    assertEquals(List.of("1.2.3"), registry.listVersions(repositoryId, "acme", "tools")
        .stream().map(AnsibleGalaxyRegistryDao.CollectionVersion::versionNormalized).toList());
    assertEquals(
        List.of("1.2.3"), registry.listVersionNames(repositoryId, "acme", "tools"));
    assertThrows(RuntimeException.class,
        () -> inTransaction(() -> registry.insertVersion(fixture)));

    AnsibleGalaxyRegistryDao.Signature signature = registry.insertSignature(
        new AnsibleGalaxyRegistryDao.Signature(
            null, stored.id(), null, "b".repeat(64), "fingerprint", "HOSTED", now));
    assertNotNull(signature.id());
    assertEquals("fingerprint", registry.listSignatures(stored.id()).getFirst().keyFingerprint());

    String taskId = "11111111-2222-3333-4444-555555555555";
    registry.createTask(new AnsibleGalaxyRegistryDao.ImportTask(
        taskId, repositoryId, "alice", AnsibleGalaxyRegistryDao.TASK_WAITING, List.of(),
        null, null, "acme", "tools", "1.2.4", "acme-tools-1.2.4.tar.gz",
        "c".repeat(64), null, assetId, 0, null, null, 0L, now, null, null, now));
    Instant leaseNow = Instant.now();
    AnsibleGalaxyRegistryDao.ImportTask claimed = registry.claimTask(
        taskId, "replica-a", leaseNow.plusSeconds(30), leaseNow).orElseThrow();
    assertEquals(1, claimed.attemptCount());
    assertTrue(registry.claimTask(
        taskId, "replica-b", leaseNow.plusSeconds(30), leaseNow).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> registry.renewTaskLease(
        taskId, "replica-a", claimed.fencingToken(), Instant.EPOCH));
    Instant renewedUntil = Instant.now().plusSeconds(60);
    assertFalse(registry.renewTaskLease(
        taskId, "replica-a", claimed.fencingToken() + 1, renewedUntil));
    assertTrue(registry.renewTaskLease(
        taskId, "replica-a", claimed.fencingToken(), renewedUntil));
    assertFalse(registry.finishTask(
        taskId, "replica-a", claimed.fencingToken() + 1,
        AnsibleGalaxyRegistryDao.TASK_COMPLETED, List.of(), null, null,
        "acme", "tools", "1.2.4", "acme-tools-1.2.4.tar.gz", "c".repeat(64),
        now.plusSeconds(2)));
    assertTrue(registry.finishTask(
        taskId, "replica-a", claimed.fencingToken(),
        AnsibleGalaxyRegistryDao.TASK_COMPLETED,
        List.of(Map.of("level", "INFO", "message", "imported")), null, null,
        "acme", "tools", "1.2.4", "acme-tools-1.2.4.tar.gz", "c".repeat(64),
        now.plusSeconds(2)));
    assertEquals(AnsibleGalaxyRegistryDao.TASK_COMPLETED,
        registry.findTask(taskId).orElseThrow().state());

    registry.upsertProxyState(new AnsibleGalaxyRegistryDao.ProxyVersionState(
        proxyRepositoryId, "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
        "https://galaxy.example/v3/collections/acme/tools/versions/1.2.3/",
        "https://cdn.example/acme-tools-1.2.3.tar.gz", "a".repeat(64), "etag-one",
        now.toString(), now.plusSeconds(60), now, null, null,
        Map.of("version", "1.2.3"), 1L, now));
    registry.upsertProxyState(new AnsibleGalaxyRegistryDao.ProxyVersionState(
        proxyRepositoryId, "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
        "https://galaxy.example/v3/collections/acme/tools/versions/1.2.3/",
        "https://cdn.example/acme-tools-1.2.3.tar.gz", "a".repeat(64), "etag-two",
        now.toString(), now.plusSeconds(120), now.plusSeconds(1), null, null,
        Map.of("version", "1.2.3", "signatures", List.of()), 2L, now.plusSeconds(1)));
    assertEquals("etag-two", registry.findProxyState(
        proxyRepositoryId, "acme", "tools", "1.2.3").orElseThrow().metadataEtag());
    assertEquals("1.2.3", registry.findProxyStateByArtifactFilename(
        proxyRepositoryId, "acme-tools-1.2.3.tar.gz").orElseThrow().versionNormalized());

    long groupRevision = registry.currentRepositoryRevision(groupRepositoryId);
    assertTrue(groupRevision > 0L);
    assertTrue(registry.bindGroupSourceIfCurrent(new AnsibleGalaxyRegistryDao.GroupBinding(
        groupRepositoryId, "acme", "tools", "1.2.3", repositoryId, stored.id(), revision,
        groupRevision, stored.artifactSha256(), now, now)));
    assertEquals(repositoryId, registry.findGroupBinding(
        groupRepositoryId, "acme", "tools", "1.2.3").orElseThrow().memberRepositoryId());
    assertEquals(stored.id(), registry.findGroupBindingByArtifactFilename(
        groupRepositoryId, stored.artifactFilename()).orElseThrow().memberVersionId());

    AnsibleGalaxyRegistryDao.Lease firstLease = registry.tryAcquireLease(
        "ansible:" + repositoryId + ":artifact:acme:tools:1.2.3",
        "replica-a", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(registry.tryAcquireLease(
        firstLease.leaseKey(), "replica-b", Instant.now().plusSeconds(30)).isEmpty());
    assertTrue(registry.renewLease(
        firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken(),
        Instant.now().plusSeconds(60)));
    registry.releaseLease(
        firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken());
    AnsibleGalaxyRegistryDao.Lease secondLease = registry.tryAcquireLease(
        firstLease.leaseKey(), "replica-b", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(secondLease.fencingToken() > firstLease.fencingToken());
  }

  @Test
  void swiftRegistryStateIsImmutableFencedAndSharedAcrossReplicas() {
    long repositoryId = createRepository("swift-hosted", RepositoryFormat.SWIFT);
    long groupRepositoryId = createRepository("swift-group", RepositoryFormat.SWIFT);
    long otherMemberId = createRepository("swift-other-member", RepositoryFormat.SWIFT);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-16T08:00:00Z");

    long componentId = stores().components().upsertReturningId(component(
        repositoryId, RepositoryFormat.SWIFT, "acme", "fixture", "1.2.3",
        Map.of("kind", "swift-package-release"), now));
    long archiveBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/acme/fixture/1.2.3.zip", "swift-archive-ref"));
    String archivePath = "acme/fixture/1.2.3.zip";
    long archiveAssetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, componentId, archiveBlobId, RepositoryFormat.SWIFT, archivePath,
        PersistenceHashes.pathHash(archivePath), "1.2.3.zip", "source-archive",
        "application/zip", 42L, null, now, Map.of()));
    long manifestBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/acme/fixture/Package.swift", "swift-manifest-ref"));
    String manifestPath = "acme/fixture/1.2.3/Package.swift";
    long manifestAssetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, componentId, manifestBlobId, RepositoryFormat.SWIFT, manifestPath,
        PersistenceHashes.pathHash(manifestPath), "Package.swift", "manifest", "text/x-swift",
        42L, null, now, Map.of("toolsVersion", "5.9")));

    SwiftRegistryDao registry = stores().swiftRegistry();
    assertEquals(0, registry.currentRepositoryRevision(repositoryId));
    long revision = registry.nextRepositoryRevision(repositoryId);
    assertEquals(1, revision);
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        null, repositoryId, componentId, "acme", "Acme", "fixture", "Fixture", "1.2.3",
        now, "{\"repositoryURLs\":[\"https://github.com/acme/fixture\"]}", "a".repeat(64),
        archiveAssetId, null, null, null, "HOSTED", revision,
        SwiftRegistryDao.RELEASE_READY, now, now);
    SwiftRegistryDao.Release stored = inTransaction(() -> registry.insertRelease(
        release,
        List.of(new SwiftRegistryDao.Manifest(
            null, "Package.swift", "", manifestAssetId, "b".repeat(64), "5.9")),
        List.of(new SwiftRegistryDao.RepositoryUrl(
            null, null, repositoryId, "acme", "fixture",
            "https://github.com/acme/fixture", "https://github.com/Acme/Fixture"))));

    assertNotNull(stored.id());
    assertEquals(1, registry.listReleases(repositoryId, "acme", "fixture").size());
    assertEquals(manifestAssetId,
        registry.findManifest(stored.id(), null).orElseThrow().assetId());
    assertEquals("5.9",
        registry.findManifest(stored.id(), null).orElseThrow().declaredToolsVersion());
    assertEquals(
        Map.of(repositoryId, revision, groupRepositoryId, 0L),
        registry.currentRepositoryRevisions(List.of(repositoryId, groupRepositoryId)));
    assertEquals("Acme", registry.findIdentities(
        repositoryId, "https://github.com/acme/fixture").getFirst().scopeDisplay());
    assertEquals(
        List.of("https://github.com/acme/fixture"),
        registry.listRepositoryUrls(repositoryId, "acme", "fixture").stream()
            .map(SwiftRegistryDao.RepositoryUrl::normalizedUrl)
            .toList());
    assertThrows(RuntimeException.class, () -> inTransaction(() -> registry.insertRelease(
        release, List.of(), List.of())));
    assertEquals(1, registry.listReleases(repositoryId, "acme", "fixture").size());

    for (String distinctVersion : List.of(
        "1.0.0-alpha", "1.0.0-ALPHA", "1.0.0+build.one", "1.0.0+build.two")) {
      insertSwiftReleaseFixture(
          registry, repositoryId, blobStoreId, distinctVersion, now.plusSeconds(10));
    }
    assertEquals(
        Set.of("1.2.3", "1.0.0-alpha", "1.0.0-ALPHA",
            "1.0.0+build.one", "1.0.0+build.two"),
        registry.listReleases(repositoryId, "acme", "fixture").stream()
            .map(SwiftRegistryDao.Release::version)
            .collect(java.util.stream.Collectors.toSet()),
        "SemVer prerelease and build identifiers must remain case-sensitive on every database");

    SwiftRegistryDao.ProxySource firstSource = registry.bindProxySource(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "1.2.3", "https://github.com/acme/fixture",
            "v1.2.3", "c".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now, revision, 0, now));
    SwiftRegistryDao.ProxySource movedTag = registry.bindProxySource(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "1.2.3", "https://github.com/acme/fixture",
            "v1.2.3", "d".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now.plusSeconds(1), revision, 0, now.plusSeconds(1)));
    assertEquals(firstSource.commitSha(), movedTag.commitSha());
    assertEquals(2, movedTag.observedCount());
    assertEquals(List.of("1.2.3"), registry.listProxySources(
        repositoryId, "acme", "fixture").stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertEquals(now.plusSeconds(1), registry.listProxySources(
        repositoryId, "acme", "fixture").getFirst().lastCheckedAt());

    List<SwiftRegistryDao.ProxySource> bulkSources = registry.bindProxySources(List.of(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "1.2.3", "https://github.com/acme/fixture",
            "v1.2.3", "e".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now.plusSeconds(2), revision, 0, now.plusSeconds(2)),
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "2.0.0", "https://github.com/acme/fixture",
            "v2.0.0", "f".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now.plusSeconds(2), revision, 0, now.plusSeconds(2))));
    assertEquals(List.of("1.2.3", "2.0.0"), bulkSources.stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertEquals(firstSource.commitSha(), bulkSources.getFirst().commitSha());
    assertEquals(3, bulkSources.getFirst().observedCount());

    SwiftRegistryDao.ProxyInventory inventory = new SwiftRegistryDao.ProxyInventory(
        repositoryId,
        "acme",
        "fixture",
        revision,
        now.plusSeconds(3),
        Map.of("1", List.of(new SwiftRegistryDao.ProxyTag(
            "1.2.3", "v1.2.3", firstSource.commitSha()))),
        Map.of("1", "github-page-one"));
    registry.upsertProxyInventory(inventory);
    assertEquals(inventory,
        registry.findProxyInventory(repositoryId, "acme", "fixture").orElseThrow());

    registry.bindProxySources(List.of(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "pruned", "1.0.0", "https://github.com/acme/pruned",
            "v1.0.0", "1".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            null, revision, 0, now),
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "pruned", "2.0.0", "https://github.com/acme/pruned",
            "v2.0.0", "2".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            null, revision, 0, now)));
    List<SwiftRegistryDao.ProxySource> replacedSources = registry.replaceProxySources(
        repositoryId,
        "acme",
        "pruned",
        List.of(new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "pruned", "2.0.0", "https://github.com/acme/pruned",
            "v2.0.0", "2".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            null, revision, 0, now.plusSeconds(1))));
    assertEquals(List.of("2.0.0"), replacedSources.stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertEquals(List.of("2.0.0"), registry.listProxySources(
        repositoryId, "acme", "pruned").stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertTrue(registry.replaceProxySources(
        repositoryId, "acme", "pruned", List.of()).isEmpty());
    assertTrue(registry.listProxySources(repositoryId, "acme", "pruned").isEmpty());

    SwiftRegistryDao.Lease lowerCasePrereleaseLease = registry.tryAcquireLease(
        "swift:acme:fixture:1.0.0-alpha", "replica-a", Instant.now().plusSeconds(30))
        .orElseThrow();
    SwiftRegistryDao.Lease upperCasePrereleaseLease = registry.tryAcquireLease(
        "swift:acme:fixture:1.0.0-ALPHA", "replica-b", Instant.now().plusSeconds(30))
        .orElseThrow();
    assertNotEquals(
        lowerCasePrereleaseLease.leaseKey(), upperCasePrereleaseLease.leaseKey(),
        "coordinate leases must preserve SemVer identifier case");
    registry.releaseLease(
        lowerCasePrereleaseLease.leaseKey(), lowerCasePrereleaseLease.owner(),
        lowerCasePrereleaseLease.fencingToken());
    registry.releaseLease(
        upperCasePrereleaseLease.leaseKey(), upperCasePrereleaseLease.owner(),
        upperCasePrereleaseLease.fencingToken());

    SwiftRegistryDao.Lease firstLease = registry.tryAcquireLease(
        "swift:acme:fixture:1.2.3", "replica-a", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(registry.tryAcquireLease(
        "swift:acme:fixture:1.2.3", "replica-b", Instant.now().plusSeconds(30)).isEmpty());
    assertFalse(registry.completeProxySource(
        repositoryId, "acme", "fixture", "1.2.3", firstSource.commitSha(), "e".repeat(64),
        "READY", stored.id(), now, revision, firstLease.leaseKey(), firstLease.owner(),
        firstLease.fencingToken() + 1));
    assertTrue(registry.completeProxySource(
        repositoryId, "acme", "fixture", "1.2.3", firstSource.commitSha(), "e".repeat(64),
        "READY", stored.id(), now, revision, firstLease.leaseKey(), firstLease.owner(),
        firstLease.fencingToken()));
    registry.releaseLease(firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken());
    SwiftRegistryDao.Lease secondLease = registry.tryAcquireLease(
        firstLease.leaseKey(), "replica-b", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(secondLease.fencingToken() > firstLease.fencingToken());
    assertFalse(registry.renewLease(
        firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken(),
        Instant.now().plusSeconds(60)));
    assertTrue(registry.renewLease(
        secondLease.leaseKey(), secondLease.owner(), secondLease.fencingToken(),
        Instant.now().plusSeconds(60)));

    long groupConfigRevision = registry.nextRepositoryRevision(groupRepositoryId);
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", repositoryId, stored.id(),
            revision, groupConfigRevision, now))));
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", otherMemberId, stored.id(),
            revision, groupConfigRevision, now.plusMillis(500)))));
    assertEquals(repositoryId, registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").orElseThrow().memberRepositoryId(),
        "the first binding for one configuration revision remains canonical across replicas");
    long updatedGroupConfigRevision = registry.nextRepositoryRevision(groupRepositoryId);
    assertFalse(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", otherMemberId, stored.id(),
            revision, groupConfigRevision, now.plusSeconds(1)))));
    assertEquals(repositoryId, registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").orElseThrow().memberRepositoryId());
    registry.deleteGroupSourceBindings(groupRepositoryId);
    assertFalse(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", repositoryId, stored.id(),
            revision, groupConfigRevision, now.plusSeconds(2)))));
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").isEmpty());
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", otherMemberId, stored.id(),
            revision, updatedGroupConfigRevision, now.plusSeconds(3)))));
    assertEquals(otherMemberId, registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").orElseThrow().memberRepositoryId());

    registry.putNegativeCache(new SwiftRegistryDao.NegativeCache(
        repositoryId, "github:missing", 404, null, Instant.now().plusSeconds(30), now));
    assertEquals(404, registry.findNegativeCache(
        repositoryId, "github:missing").orElseThrow().statusCode());
    assertEquals(1, registry.deleteExpiredNegativeCache(Instant.now().plusSeconds(60)));

    long tombstoneRevision = registry.nextRepositoryRevision(repositoryId);
    registry.tombstoneRelease(new SwiftRegistryDao.Tombstone(
        repositoryId, "acme", "fixture", "1.2.3", "deleted", tombstoneRevision, now));
    assertTrue(registry.findRelease(repositoryId, "acme", "fixture", "1.2.3").isEmpty());
    assertEquals(tombstoneRevision, registry.findTombstone(
        repositoryId, "acme", "fixture", "1.2.3").orElseThrow().revision());
    assertEquals(List.of("1.2.3"), registry.listTombstones(
        repositoryId, "acme", "fixture").stream()
        .map(SwiftRegistryDao.Tombstone::version)
        .toList());

    long migrationJobId = stores().migrationJobs().create(
        "3.94.0", "/nexus-data", Map.of("scope", "swift", "dryRun", false));
    MigrationCheckpointRecord checkpoint = new MigrationCheckpointRecord(
        migrationJobId, "component", "swift-package-release", "#12:0", "swift_release",
        stored.id().toString(), "f".repeat(64), now);
    stores().migrationCheckpoints().upsert(checkpoint);
    stores().migrationCheckpoints().upsert(checkpoint);
    assertEquals(stored.id().toString(), stores().migrationCheckpoints().find(
        migrationJobId, "component", "swift-package-release", "#12:0")
        .orElseThrow().targetId());
  }

  @Test
  void swiftMemberMutationsRecursivelyInvalidateGroupBindingsInTheSameTransaction() {
    long memberRepositoryId = createRepository("swift-member", RepositoryFormat.SWIFT);
    long groupRepositoryId = createRepository(
        "swift-inner-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    long outerGroupRepositoryId = createRepository(
        "swift-outer-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    stores().repositories().addMember(groupRepositoryId, memberRepositoryId, 0);
    stores().repositories().addMember(outerGroupRepositoryId, groupRepositoryId, 0);
    SwiftRegistryDao registry = stores().swiftRegistry();
    long blobStoreId = stores().repositories().findById(memberRepositoryId)
        .orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-16T09:00:00Z");

    SwiftRegistryDao.Release first = insertSwiftReleaseFixture(
        registry, memberRepositoryId, blobStoreId, "1.0.0", now);
    long innerRevision = registry.currentRepositoryRevision(groupRepositoryId);
    long outerRevision = registry.currentRepositoryRevision(outerGroupRepositoryId);
    assertTrue(innerRevision > 0);
    assertTrue(outerRevision > 0);
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", first.version(), memberRepositoryId,
            first.id(), first.revision(), innerRevision, now))));
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            outerGroupRepositoryId, "acme", "fixture", first.version(), groupRepositoryId,
            first.id(), first.revision(), outerRevision, now))));

    SwiftRegistryDao.Release second = insertSwiftReleaseFixture(
        registry, memberRepositoryId, blobStoreId, "2.0.0", now.plusSeconds(1));

    long innerAfterPublish = registry.currentRepositoryRevision(groupRepositoryId);
    long outerAfterPublish = registry.currentRepositoryRevision(outerGroupRepositoryId);
    assertTrue(innerAfterPublish > innerRevision);
    assertTrue(outerAfterPublish > outerRevision);
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", first.version()).isEmpty());
    assertTrue(registry.findGroupSourceBinding(
        outerGroupRepositoryId, "acme", "fixture", first.version()).isEmpty());

    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", second.version(), memberRepositoryId,
            second.id(), second.revision(), innerAfterPublish, now.plusSeconds(1)))));
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            outerGroupRepositoryId, "acme", "fixture", second.version(), groupRepositoryId,
            second.id(), second.revision(), outerAfterPublish, now.plusSeconds(1)))));

    assertThrows(IllegalStateException.class, () -> inTransaction(() -> {
      registry.tombstoneAndDeleteReleaseState(
          memberRepositoryId, "acme", "fixture", second.version(), "rollback", now.plusSeconds(2))
          .orElseThrow();
      throw new IllegalStateException("force rollback");
    }));
    assertEquals(innerAfterPublish, registry.currentRepositoryRevision(groupRepositoryId));
    assertEquals(outerAfterPublish, registry.currentRepositoryRevision(outerGroupRepositoryId));
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", second.version()).isPresent());
    assertTrue(registry.findGroupSourceBinding(
        outerGroupRepositoryId, "acme", "fixture", second.version()).isPresent());
    assertTrue(registry.findRelease(
        memberRepositoryId, "acme", "fixture", second.version()).isPresent());
    assertTrue(registry.findTombstone(
        memberRepositoryId, "acme", "fixture", second.version()).isEmpty());

    SwiftRegistryDao.DeletedRelease deleted = inTransaction(() ->
        registry.tombstoneAndDeleteReleaseState(
            memberRepositoryId, "acme", "fixture", second.version(), "deleted",
            now.plusSeconds(3)).orElseThrow());

    assertEquals(second.componentId(), deleted.componentId());
    assertTrue(registry.currentRepositoryRevision(groupRepositoryId) > innerAfterPublish);
    assertTrue(registry.currentRepositoryRevision(outerGroupRepositoryId) > outerAfterPublish);
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", second.version()).isEmpty());
    assertTrue(registry.findGroupSourceBinding(
        outerGroupRepositoryId, "acme", "fixture", second.version()).isEmpty());
    assertTrue(registry.findRelease(
        memberRepositoryId, "acme", "fixture", second.version()).isEmpty());
    assertTrue(registry.findTombstone(
        memberRepositoryId, "acme", "fixture", second.version()).isPresent());
  }

  @Test
  void swiftAdministrativeDeleteSerializesWithTheCoordinatePublishFence() throws Exception {
    long repositoryId = createRepository("swift-delete-fence", RepositoryFormat.SWIFT);
    long blobStoreId = stores().repositories().findById(repositoryId)
        .orElseThrow().blobStoreId();
    SwiftRegistryDao registry = stores().swiftRegistry();
    String version = "3.0.0";
    insertSwiftReleaseFixture(
        registry, repositoryId, blobStoreId, version, Instant.parse("2026-07-16T10:00:00Z"));
    String leaseKey = "swift:" + repositoryId + ":acme:fixture:" + version;
    SwiftRegistryDao.Lease lease = registry.tryAcquireLease(
        leaseKey, "publishing-replica", Instant.now().plusSeconds(30)).orElseThrow();
    CountDownLatch publishFenceLocked = new CountDownLatch(1);
    CountDownLatch allowPublishCommit = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var publishing = executor.submit(() -> inTransaction(() -> {
        assertTrue(registry.renewLease(
            lease.leaseKey(), lease.owner(), lease.fencingToken(), Instant.now().plusSeconds(30)));
        publishFenceLocked.countDown();
        await(allowPublishCommit);
        assertTrue(registry.findTombstone(
            repositoryId, "acme", "fixture", version).isEmpty());
        return null;
      }));
      assertTrue(publishFenceLocked.await(30, java.util.concurrent.TimeUnit.SECONDS));

      var deleting = executor.submit(() -> inTransaction(() ->
          registry.tombstoneAndDeleteReleaseState(
              repositoryId,
              "acme",
              "fixture",
              version,
              "administrative delete",
              Instant.now()).orElseThrow()));

      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> deleting.get(250, java.util.concurrent.TimeUnit.MILLISECONDS),
          "delete must wait for the in-transaction publication fence");
      allowPublishCommit.countDown();
      publishing.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          1L,
          deleting.get(30, java.util.concurrent.TimeUnit.SECONDS).assetIds().size());
    } finally {
      allowPublishCommit.countDown();
    }

    assertTrue(registry.findRelease(
        repositoryId, "acme", "fixture", version).isEmpty());
    assertTrue(registry.findTombstone(
        repositoryId, "acme", "fixture", version).isPresent());
  }

  @Test
  void terraformRegistryStateIsSharedTransactionalAndReplicaSafe() {
    long repositoryId = createRepository("terraform-hosted", RepositoryFormat.TERRAFORM);
    long memberRepositoryId = createRepository("terraform-member", RepositoryFormat.TERRAFORM);
    TerraformRegistryDao registry = stores().terraformRegistry();
    Instant now = Instant.now();
    registry.insertSigningKey(new TerraformRegistryDao.SigningKey(
        repositoryId, 1, "0123456789ABCDEF", "encrypted-private", "public-key", now));

    assertEquals("0123456789ABCDEF", registry.findActiveSigningKey(repositoryId).orElseThrow().keyId());
    assertEquals(1, registry.findSigningKey(repositoryId, 1).orElseThrow().revision());

    registry.publishProvider(
        new TerraformRegistryDao.ProviderPlatform(
            repositoryId, "kkrepo", "fixture", "1.2.3", "linux", "amd64",
            "terraform-provider-fixture_1.2.3_linux_amd64.zip",
            "v1/providers/kkrepo/fixture/1.2.3/package/linux/terraform-provider-fixture-fixture.zip",
            "a".repeat(64), "5.0", 1, now),
        new TerraformRegistryDao.ProviderState(
            repositoryId, "kkrepo", "fixture", "1.2.3", 1,
            "v1/providers/kkrepo/fixture/1.2.3/terraform-provider-fixture_1.2.3_SHA256SUMS",
            "v1/providers/kkrepo/fixture/1.2.3/terraform-provider-fixture_1.2.3_SHA256SUMS.sig",
            1, now));
    assertEquals(1, registry.listProviderPlatforms(
        repositoryId, "kkrepo", "fixture", "1.2.3").size());
    assertEquals(1, registry.listProviderPlatformsForProvider(
        repositoryId, "kkrepo", "fixture").size());
    assertEquals(1, registry.findProviderState(
        repositoryId, "kkrepo", "fixture", "1.2.3").orElseThrow().revision());

    assertTrue(registry.tryAcquirePublishLease("provider:fixture", "replica-a", now.plusSeconds(30)));
    assertFalse(registry.tryAcquirePublishLease("provider:fixture", "replica-b", now.plusSeconds(30)));
    assertFalse(registry.renewPublishLease("provider:fixture", "replica-b", now.plusSeconds(60)));
    assertTrue(registry.renewPublishLease("provider:fixture", "replica-a", now.plusSeconds(60)));
    registry.releasePublishLease("provider:fixture", "replica-a");
    assertTrue(registry.tryAcquirePublishLease("provider:fixture", "replica-b", now.plusSeconds(30)));
    assertTrue(registry.tryAcquirePublishLease(
        "provider:expired", "replica-a", now.minusSeconds(1)));
    assertFalse(registry.renewPublishLease(
        "provider:expired", "replica-a", now.plusSeconds(60)));

    registry.upsertSourceBinding(new TerraformRegistryDao.SourceBinding(
        repositoryId, "asset:v1/providers/kkrepo/fixture", memberRepositoryId, 7,
        now.plusSeconds(60), now));
    assertEquals(memberRepositoryId, registry.findSourceBinding(
        repositoryId, "asset:v1/providers/kkrepo/fixture").orElseThrow().memberRepositoryId());
    registry.deleteSourceBindings(repositoryId);
    assertTrue(registry.findSourceBinding(
        repositoryId, "asset:v1/providers/kkrepo/fixture").isEmpty());
  }

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

  @Test
  void generatedKeysAndConcurrentBlobAndAssetNaturalKeysArePortable() throws Exception {
    long firstStoreId = stores().blobStores().insert(blobStore("keys-one"));
    long secondStoreId = stores().blobStores().insert(blobStore("keys-two"));
    assertNotEquals(firstStoreId, secondStoreId);

    AssetBlobRecord blob = blob(firstStoreId, "blobs/acme.jar", "blob-ref-acme");
    List<Callable<Long>> blobWrites = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      blobWrites.add(() -> stores().assets().insertBlobOrFindExisting(blob).id());
    }
    List<Long> blobIds = invokeConcurrently(blobWrites, 5);
    assertEquals(1, new HashSet<>(blobIds).size());
    long blobId = blobIds.getFirst();
    assertEquals(Map.of("origin", "contract"),
        stores().assets().findBlobById(blobId).orElseThrow().attributes());

    long repositoryId = insertRepository("asset-keys", RepositoryFormat.MAVEN2, firstStoreId);
    String path = "com/acme/app/1.0/app-1.0.jar";
    AssetRecord asset = new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.MAVEN2, path, sha256(path),
        "app-1.0.jar", "ARTIFACT", "application/java-archive", 42L, null,
        Instant.parse("2026-07-13T10:00:00Z"), Map.of("classifier", ""));
    List<Callable<Long>> assetWrites = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      assetWrites.add(() -> stores().assets().insertAsset(asset));
    }
    List<Long> assetIds = invokeConcurrently(assetWrites, 5);
    assertEquals(1, new HashSet<>(assetIds).size());
    assertEquals(path, stores().assets().findAssetById(assetIds.getFirst()).orElseThrow().path());
  }

  @Test
  void batchedAssetPathLookupUsesExactProtocolPaths() {
    long blobStoreId = stores().blobStores().insert(blobStore("asset-batch-store"));
    long repositoryId = insertRepository("asset-batch", RepositoryFormat.TERRAFORM, blobStoreId);
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "terraform/provider.zip", "terraform-provider-ref"));
    String existingPath = "v1/providers/acme/cloud/1.2.3/package/linux/provider.zip";
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.TERRAFORM, existingPath,
        PersistenceHashes.pathHash(existingPath), "provider.zip", "provider-archive",
        "application/zip", 42L, null, Instant.parse("2026-07-13T10:00:00Z"), Map.of()));

    assertEquals(
        Set.of(existingPath),
        stores().assets().findExistingAssetPaths(
            repositoryId,
            List.of(existingPath, "v1/providers/acme/cloud/1.2.3/package/darwin/missing.zip",
                existingPath)));
  }

  @Test
  void duplicateAssetInsertKeepsProtocolWriterTransactionUsable() throws Exception {
    long blobStoreId = stores().blobStores().insert(blobStore("asset-conflict-store"));
    long repositoryId = insertRepository(
        "asset-conflict", RepositoryFormat.HELM, blobStoreId);
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "helm/index.yaml", "helm-index-ref"));
    String path = "index.yaml";
    AssetRecord asset = new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.HELM, path,
        PersistenceHashes.pathHash(path),
        path, "INDEX", "text/x-yaml", 42L, null,
        Instant.parse("2026-07-13T10:00:00Z"), Map.of("generated", true));
    CyclicBarrier transactionsReady = new CyclicBarrier(2);

    List<Callable<AssetInsertResult>> writes = List.of(
        () -> insertOrFindAssetInWriterTransaction(asset, transactionsReady),
        () -> insertOrFindAssetInWriterTransaction(asset, transactionsReady));
    List<AssetInsertResult> results = invokeConcurrently(writes, 2);

    assertEquals(1, results.stream().filter(AssetInsertResult::inserted).count());
    assertEquals(1, results.stream().map(AssetInsertResult::assetId).distinct().count());
    assertEquals(path, stores().assets().findAssetByPath(repositoryId, path).orElseThrow().path());
  }

  @Test
  void pypiPrefixLookupsArePortableAcrossDatabaseCollations() {
    long pypiRepositoryId = createRepository("pypi-prefix", RepositoryFormat.PYPI);
    long pypiBlobStoreId = stores().repositories().findById(pypiRepositoryId)
        .orElseThrow().blobStoreId();
    long pypiBlobId = stores().assets().insertBlob(
        blob(pypiBlobStoreId, "pypi/portable-pkg.whl", "pypi-portable-ref"));
    String pypiPath = "packages/portable-pkg/1.0.0/portable_pkg-1.0.0-py3-none-any.whl";
    stores().assets().insertAsset(new AssetRecord(
        null, pypiRepositoryId, null, pypiBlobId, RepositoryFormat.PYPI,
        pypiPath, sha256(pypiPath), "portable_pkg-1.0.0-py3-none-any.whl", "package",
        "application/octet-stream", 42L, null, Instant.parse("2026-07-13T10:00:00Z"),
        Map.of("normalizedName", "portable-pkg", "requires_python", ">=3.8")));

    var pypiRows = stores().assets().listPypiProjectIndexRows(pypiRepositoryId, "portable-pkg");
    assertEquals(1, pypiRows.size());
    assertEquals(pypiPath, pypiRows.getFirst().path());
    assertEquals(">=3.8", pypiRows.getFirst().attributes().get("requires_python"));
    assertEquals(List.of(pypiPath), stores().assets()
        .listAssetsByPrefix(pypiRepositoryId, "packages/portable-pkg/")
        .stream().map(AssetRecord::path).toList());
  }

  @Test
  void staleAssetPrefixClaimsAreBoundedAndPortable() {
    long repositoryId = createRepository("swift-staging-cleanup", RepositoryFormat.SWIFT);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    long oldBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/staging/old.zip", "swift-staging-old-ref"));
    long freshBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/staging/fresh.zip", "swift-staging-fresh-ref"));
    long publicBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/releases/public.zip", "swift-release-public-ref"));
    String oldPath = ".swift/staging/old/source.zip";
    String freshPath = ".swift/staging/fresh/source.zip";
    String publicPath = "acme/demo/1.0.0.zip";
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, oldBlobId, RepositoryFormat.SWIFT,
        oldPath, sha256(oldPath), "source.zip", "swift", "application/zip", 42L,
        null, Instant.parse("2026-07-13T08:00:00Z"), Map.of()));
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, freshBlobId, RepositoryFormat.SWIFT,
        freshPath, sha256(freshPath), "source.zip", "swift", "application/zip", 42L,
        null, Instant.parse("2026-07-13T10:00:00Z"), Map.of()));
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, publicBlobId, RepositoryFormat.SWIFT,
        publicPath, sha256(publicPath), "1.0.0.zip", "swift", "application/zip", 42L,
        null, Instant.parse("2026-07-13T08:00:00Z"), Map.of()));

    List<AssetRecord> claimed = inTransaction(() -> stores().assets()
        .claimStaleAssetsByPrefix(
            repositoryId,
            ".swift/staging/",
            Instant.parse("2026-07-13T09:00:00Z"),
            1));

    assertEquals(List.of(oldPath), claimed.stream().map(AssetRecord::path).toList());
  }

  @Test
  void dockerUnreferencedBlobCleanupSqlIsPortable() {
    long dockerRepositoryId = createRepository("docker-cleanup", RepositoryFormat.DOCKER);
    long dockerBlobStoreId = stores().repositories().findById(dockerRepositoryId)
        .orElseThrow().blobStoreId();
    AssetBlobRecord dockerBlob = blob(
        dockerBlobStoreId, "docker/blobs/sha256/layer", "docker-layer-ref");
    long dockerBlobId = stores().assets().insertBlob(dockerBlob);
    String digest = "sha256:" + dockerBlob.sha256();
    String dockerPath = "v2/acme/app/blobs/" + digest;
    long dockerAssetId = stores().assets().insertAsset(new AssetRecord(
        null, dockerRepositoryId, null, dockerBlobId, RepositoryFormat.DOCKER,
        dockerPath, sha256(dockerPath), digest, "BLOB", "application/octet-stream",
        dockerBlob.size(), null, Instant.parse("2026-07-13T10:00:00Z"),
        Map.of("docker", Map.of("digest", digest))));

    assertEquals(dockerAssetId, stores().dockerRegistry()
        .findUnreferencedBlobAssetIdForCleanup(
            dockerRepositoryId, 0, 10, Instant.parse("2026-07-13T11:00:00Z"))
        .orElseThrow());
  }

  @Test
  void browseSubtreeFlagsSupportBooleanReactivationAndPruning() {
    long repositoryId = createRepository("browse-boolean", RepositoryFormat.MAVEN2);
    String path = "com/acme/app/1.0/app-1.0.jar";

    stores().browseNodes().upsertPathAncestors(repositoryId, path, null, null);
    var emptyRoot = stores().browseNodes().listChildren(repositoryId, "").getFirst();
    assertEquals("com", emptyRoot.path());
    assertFalse(emptyRoot.hasAssetSubtree());

    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "browse/app-1.0.jar", "browse-app-ref"));
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.MAVEN2,
        path, sha256(path), "app-1.0.jar", "ARTIFACT", "application/java-archive",
        42L, null, Instant.parse("2026-07-13T10:00:00Z"), Map.of()));

    stores().browseNodes().upsertPathAncestors(repositoryId, path, assetId, null);
    assertTrue(stores().browseNodes().listChildren(repositoryId, "").getFirst().hasAssetSubtree());

    assertEquals(1, stores().browseNodes().deleteByAssetId(assetId));
    assertTrue(stores().browseNodes().listChildren(repositoryId, "").isEmpty());
  }

  @Test
  void groupMemberOrderAndReplacementAreStable() {
    long first = createRepository("member-first", RepositoryFormat.MAVEN2);
    long second = createRepository("member-second", RepositoryFormat.MAVEN2);
    long third = createRepository("member-third", RepositoryFormat.MAVEN2);
    long group = stores().repositories().insert(new RepositoryRecord(
        null, "maven-public", RepositoryFormat.MAVEN2, RepositoryType.GROUP,
        "maven2-group", true, null, null, null, null, null, null, true, Map.of()));

    stores().repositories().addMember(group, first, 20);
    stores().repositories().addMember(group, second, 10);
    assertEquals(List.of("member-second", "member-first"),
        stores().repositories().listMembers(group).stream().map(RepositoryRecord::name).toList());

    stores().repositories().replaceMembers(group, List.of(third, first, second));
    assertEquals(List.of("member-third", "member-first", "member-second"),
        stores().repositories().listMembers(group).stream().map(RepositoryRecord::name).toList());
    assertEquals(List.of("maven-public"),
        stores().repositories().listGroupsContaining(first).stream().map(RepositoryRecord::name).toList());
  }

  @Test
  void auditFilteringFreeTextAndPaginationHaveIdenticalSemantics() {
    var audit = stores().securityAudit();
    audit.insert(auditRecord(LocalDateTime.of(2026, 7, 13, 8, 0), "alice", "GET",
        "/repository/releases/a.jar", 200, "SUCCESS", Map.of("traceId", "trace-one")));
    audit.insert(auditRecord(LocalDateTime.of(2026, 7, 13, 9, 0), "bob", "PUT",
        "/repository/releases/b.jar", 201, "SUCCESS", Map.of("traceId", "trace-two")));
    audit.insert(auditRecord(LocalDateTime.of(2026, 7, 13, 10, 0), "alice", "DELETE",
        "/repository/releases/c.jar", 403, "DENIED", Map.of("reason", "policy")));

    var filtered = audit.search(new SecurityAuditDao.AuditLogQuery(
        null, null, " ALICE ", null, null, "/repository/releases", null,
        null, null, null, null, 0, 10));
    assertEquals(2, filtered.total());
    assertEquals(List.of("DELETE", "GET"), filtered.items().stream().map(item -> item.method()).toList());

    var freeText = audit.search(new SecurityAuditDao.AuditLogQuery(
        "trace-two", null, null, null, null, null, null, null, null,
        null, null, 0, 10));
    assertEquals(1, freeText.total());
    assertEquals("bob", freeText.items().getFirst().actorUserId());

    var firstPage = audit.search(new SecurityAuditDao.AuditLogQuery(
        null, null, null, null, null, null, null, null, null,
        null, null, 0, 1));
    var secondPage = audit.search(new SecurityAuditDao.AuditLogQuery(
        null, null, null, null, null, null, null, null, null,
        null, null, 1, 1));
    assertEquals(3, firstPage.total());
    assertNotEquals(firstPage.items().getFirst().id(), secondPage.items().getFirst().id());
  }

  @Test
  void markerClaimsPartitionConcurrentWorkersAndFailuresReenqueue() throws Exception {
    long repositoryId = createRepository("claim-markers", RepositoryFormat.MAVEN2);
    for (int index = 0; index < 8; index++) {
      stores().metadataRebuild().enqueue(repositoryId, "ga:com.acme/app-" + index);
      stores().repositoryIndexRebuild().enqueue(
          repositoryId, RepositoryIndexRebuildDao.PYPI_PROJECT, "app-" + index);
    }

    List<Callable<String>> metadataClaims = new ArrayList<>();
    List<Callable<String>> indexClaims = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      metadataClaims.add(() -> inTransaction(() ->
          stores().metadataRebuild().claim(1).getFirst().scopeKey()));
      indexClaims.add(() -> inTransaction(() ->
          stores().repositoryIndexRebuild().claim(1).getFirst().scopeKey()));
    }
    Set<String> claimedMetadata = new HashSet<>(invokeConcurrently(metadataClaims, 4));
    Set<String> claimedIndexes = new HashSet<>(invokeConcurrently(indexClaims, 4));
    assertEquals(8, claimedMetadata.size());
    assertEquals(8, claimedIndexes.size());
    assertEquals(0L, stores().metadataRebuild().countBacklog());
    assertEquals(0L, stores().repositoryIndexRebuild().countBacklog());

    var metadataFailure = new com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao.Claim(
        repositoryId, "ga:com.acme/failure", Instant.now(), 0, null);
    stores().metadataRebuild().reenqueueFailure(metadataFailure, new IllegalStateException("boom"));
    var indexFailure = new RepositoryIndexRebuildDao.Claim(
        repositoryId, RepositoryIndexRebuildDao.HELM_INDEX, "", Instant.now(), 0, null);
    stores().repositoryIndexRebuild().reenqueueFailure(indexFailure, new IllegalStateException("boom"));
    assertEquals(1L, stores().metadataRebuild().countFailures());
    assertEquals(1L, stores().repositoryIndexRebuild().countFailures());
  }

  @Test
  void blobGcDockerAndPubUploadCoordinationRoundTrip() throws Exception {
    long repositoryId = createRepository("upload-coordination", RepositoryFormat.DOCKER);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    long deletedBlobId = stores().assets().insertBlob(blob(
        blobStoreId, "gc/deleted-layer", "gc-ref"));
    stores().assets().markBlobDeletedById(deletedBlobId, "contract cleanup");
    var gcClaims = inTransaction(() -> stores().assets().claimDeletedBlobsForGc(
        10, Instant.now().plusSeconds(1), Instant.EPOCH));
    assertEquals(List.of(deletedBlobId), gcClaims.stream().map(AssetBlobRecord::id).toList());
    assertEquals(1, stores().assets().releaseBlobGcClaim(deletedBlobId));

    Instant expiresAt = Instant.now().plusSeconds(600);
    stores().dockerUploads().insertSession(new DockerUploadSessionRecord(
        "docker-session", repositoryId, "acme/app", sha256("acme/app"), "STARTED", 0,
        null, null, "alice", "127.0.0.1", expiresAt, null, null,
        Map.of("node", "one"), null, null));
    List<Callable<Long>> chunkAppends = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      chunkAppends.add(() -> inTransaction(() -> {
        var session = stores().dockerUploads().lockSession("docker-session").orElseThrow();
        int chunkIndex = stores().dockerUploads().nextChunkIndex("docker-session");
        long startOffset = session.nextOffset();
        long nextOffset = startOffset + 4;
        stores().dockerUploads().appendChunk(
            "docker-session", chunkIndex, startOffset, nextOffset - 1,
            "chunk-ref-" + chunkIndex, "chunks/" + chunkIndex, "abcd", 4, nextOffset);
        return startOffset;
      }));
    }
    assertEquals(List.of(0L, 4L, 8L, 12L),
        invokeConcurrently(chunkAppends, 4).stream().sorted().toList());
    inTransaction(() -> {
      stores().dockerUploads().lockSession("docker-session").orElseThrow();
      stores().dockerUploads().completeSession("docker-session", "sha256:abcd", "sha256");
      return null;
    });
    assertEquals(16L,
        stores().dockerUploads().findSession("docker-session").orElseThrow().nextOffset());
    assertEquals(List.of(0, 1, 2, 3), stores().dockerUploads().listChunks("docker-session")
        .stream().map(chunk -> chunk.chunkIndex()).toList());
    assertEquals(1, inTransaction(() -> stores().dockerUploads().claimTerminalSessions(
        Instant.now(), "worker-one", Instant.now().plusSeconds(30), 10)).size());

    long pubId = stores().pubUploadSessions().insert(new PubUploadSessionRecord(
        null, repositoryId, "pub-session", "field-token", "alice", null,
        PubUploadSessionDao.STATUS_NEW, expiresAt, null, null, null, null, null, null, null,
        null, null, null, Map.of(), null, null, null, null));
    inTransaction(() -> {
      assertEquals(pubId, stores().pubUploadSessions().lockById(pubId).orElseThrow().id());
      stores().pubUploadSessions().markUploaded(
          pubId, blobStoreId, "pub-ref", "pub/pkg.tar.gz", "md5", "sha1", "sha256",
          "sha512", 99, "acme_pkg", "1.0.0", Map.of("name", "acme_pkg"));
      stores().pubUploadSessions().markFinalized(pubId, Instant.parse("2026-07-13T11:00:00Z"));
      return null;
    });
    var finalized = stores().pubUploadSessions().find(repositoryId, "pub-session").orElseThrow();
    assertEquals(PubUploadSessionDao.STATUS_FINALIZED, finalized.status());
    assertEquals(Map.of("name", "acme_pkg"), finalized.pubspec());
    assertNotNull(finalized.finalizedAt());
  }

  @Test
  void repositoryMigrationAssetsCanBeClaimedFailedRetriedAndCompleted() throws Exception {
    long repositoryId = createRepository("migration-target", RepositoryFormat.MAVEN2);
    long migrationJobId = stores().migrationJobs().create(
        "3.70.1", "/nexus-data", Map.of(
            "scope", "repository-data", "packageMigrationEnabled", true));
    long repositoryJobId = stores().repositoryDataMigrations().createRepositoryJob(
        migrationJobId, "maven-releases", "migration-target", repositoryId,
        RepositoryFormat.MAVEN2, 100, Map.of("sourceType", "hosted"));
    List<RepositoryDataMigrationAssetRecord> discovered = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      String path = "com/acme/app/1.0/app-1.0-" + index + ".jar";
      discovered.add(new RepositoryDataMigrationAssetRecord(
          null, repositoryJobId, "source-asset-" + index, "source-component", path, sha256(path),
          RepositoryFormat.MAVEN2, "com.acme", "app", "1.0", "ARTIFACT",
          "application/java-archive", 1024L, "default@abc-" + index,
          Instant.parse("2026-07-13T08:00:00Z"), null,
          Instant.parse("2026-07-13T08:00:00Z"), Instant.parse("2026-07-13T08:00:00Z"),
          "admin", "127.0.0.1", "pending", 0, null, null, null, null, null, null,
          Map.of("sourceRepositoryType", "hosted"), null));
    }
    stores().repositoryDataMigrations().upsertDiscoveredAssets(
        repositoryJobId, discovered, Map.of());
    stores().repositoryDataMigrations().finishDiscoveryPage(repositoryJobId, null, true);

    Set<Long> concurrentlyClaimedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    List<Callable<List<RepositoryDataMigrationDao.AssetClaim>>> workerClaims = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      workerClaims.add(() -> {
        List<RepositoryDataMigrationDao.AssetClaim> workerClaimsResult = new ArrayList<>();
        // MySQL also locks the joined repository row, so competing replicas may briefly receive
        // an empty batch. A real worker polls again after the winning transaction commits.
        for (int attempt = 0; attempt < 100; attempt++) {
          if (concurrentlyClaimedIds.size() == discovered.size()) {
            return workerClaimsResult;
          }
          List<RepositoryDataMigrationDao.AssetClaim> claims = inTransaction(() ->
              stores().repositoryDataMigrations().claimAssetsForMigration(
                  migrationJobId, 2, 3, Instant.now().minusSeconds(60)));
          for (RepositoryDataMigrationDao.AssetClaim claim : claims) {
            assertTrue(concurrentlyClaimedIds.add(claim.asset().id()),
                () -> "Asset was claimed by more than one worker: " + claim.asset().id());
            workerClaimsResult.add(claim);
          }
          if (claims.isEmpty()) {
            Thread.sleep(10);
          }
        }
        return workerClaimsResult;
      });
    }
    List<RepositoryDataMigrationDao.AssetClaim> claimed = invokeConcurrently(workerClaims, 4)
        .stream().flatMap(List::stream).toList();
    assertEquals(8, claimed.size());
    assertEquals(8, concurrentlyClaimedIds.size());

    var firstClaim = claimed.getFirst();
    stores().repositoryDataMigrations().markAssetFailed(
        firstClaim.asset().id(), repositoryJobId, 1, "upstream failed");
    for (var successfulClaim : claimed.subList(1, claimed.size())) {
      stores().repositoryDataMigrations().markAssetMigrated(
          successfulClaim.asset().id(), repositoryJobId, null, null, null);
    }
    stores().repositoryDataMigrations().refreshRepositoryProgress(repositoryJobId);
    assertEquals(1, stores().repositoryDataMigrations().retryFailedAssets(migrationJobId));

    var retried = inTransaction(() -> stores().repositoryDataMigrations()
        .claimAssetsForMigration(migrationJobId, 10, 3, Instant.now().plusSeconds(1)))
        .getFirst();
    stores().repositoryDataMigrations().markAssetMigrated(
        retried.asset().id(), repositoryJobId, null, null, null);
    stores().repositoryDataMigrations().refreshRepositoryProgress(repositoryJobId);
    var progress = stores().repositoryDataMigrations().jobProgress(migrationJobId);
    assertEquals(8, progress.migratedAssets());
    assertEquals(0, progress.failedAssets());
    assertFalse(progress.active());
  }

  @Test
  void absoluteTimestampsRoundTripAcrossUtcShanghaiAndDstBoundaries() {
    long blobStoreId = stores().blobStores().insert(blobStore("time-roundtrip"));
    List<Instant> instants = List.of(
        Instant.parse("2026-07-13T08:00:00.123Z"),
        ZonedDateTime.of(2026, 7, 13, 16, 0, 0, 456_000_000,
            ZoneId.of("Asia/Shanghai")).toInstant(),
        ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 789_000_000,
            ZoneId.of("America/New_York")).withLaterOffsetAtOverlap().toInstant());

    for (int index = 0; index < instants.size(); index++) {
      Instant instant = instants.get(index);
      AssetBlobRecord fixture = blob(blobStoreId, "time/" + index, "time-ref-" + index);
      long id = stores().assets().insertBlob(new AssetBlobRecord(
          fixture.id(), fixture.blobStoreId(), fixture.blobRef(), fixture.blobRefHash(),
          fixture.objectKey(), fixture.objectKeyHash(), fixture.sha1(), fixture.sha256(), fixture.md5(),
          fixture.size(), fixture.contentType(), fixture.createdBy(), fixture.createdByIp(),
          instant, instant, fixture.attributes()));
      AssetBlobRecord stored = stores().assets().findBlobById(id).orElseThrow();
      assertEquals(instant, stored.blobCreatedAt());
      assertEquals(instant, stored.blobUpdatedAt());
    }
  }

  private long createRepository(String name, RepositoryFormat format) {
    return createRepository(name, format, RepositoryType.HOSTED);
  }

  private long createRepository(
      String name, RepositoryFormat format, RepositoryType type) {
    long blobStoreId = stores().blobStores().insert(blobStore(name + "-store"));
    return insertRepository(name, format, type, blobStoreId);
  }

  private SwiftRegistryDao.Release insertSwiftReleaseFixture(
      SwiftRegistryDao registry,
      long repositoryId,
      long blobStoreId,
      String version,
      Instant now) {
    long componentId = stores().components().upsertReturningId(component(
        repositoryId,
        RepositoryFormat.SWIFT,
        "acme",
        "fixture",
        version,
        Map.of("kind", "swift-package-release"),
        now));
    String token = version.replaceAll("[^A-Za-z0-9]", "-");
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/acme/fixture/" + token + ".zip", "swift-" + token));
    String path = "acme/fixture/" + version + ".zip";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        componentId,
        blobId,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        version + ".zip",
        "source-archive",
        "application/zip",
        42L,
        null,
        now,
        Map.of()));
    long revision = registry.nextRepositoryRevision(repositoryId);
    SwiftRegistryDao.Release fixture = new SwiftRegistryDao.Release(
        null,
        repositoryId,
        componentId,
        "acme",
        "Acme",
        "fixture",
        "Fixture",
        version,
        now,
        "{}",
        "a".repeat(64),
        assetId,
        null,
        null,
        null,
        "HOSTED",
        revision,
        SwiftRegistryDao.RELEASE_READY,
        now,
        now);
    return inTransaction(() -> registry.insertRelease(fixture, List.of(), List.of()));
  }

  private long insertRepository(String name, RepositoryFormat format, long blobStoreId) {
    return insertRepository(name, format, RepositoryType.HOSTED, blobStoreId);
  }

  private long insertRepository(
      String name, RepositoryFormat format, RepositoryType type, long blobStoreId) {
    return stores().repositories().insert(new RepositoryRecord(
        null,
        name,
        format,
        type,
        format.id() + "-" + type.name().toLowerCase(java.util.Locale.ROOT),
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

  private static BlobStoreRecord blobStore(String name) {
    return new BlobStoreRecord(
        null, name, "S3", "https://s3.example", "cn-test-1", "artifacts", name,
        Map.of("pathStyleAccess", true));
  }

  private static AssetBlobRecord blob(long blobStoreId, String objectKey, String blobRef) {
    Instant now = Instant.parse("2026-07-13T08:00:00Z");
    return new AssetBlobRecord(
        null, blobStoreId, blobRef, sha256(blobRef), objectKey, sha256(objectKey),
        "1".repeat(40), "2".repeat(64), "3".repeat(32), 42,
        "application/octet-stream", "contract",
        "127.0.0.1", now, now, Map.of("origin", "contract"));
  }

  private static SecurityAuditDao.AuditLogRecord auditRecord(
      LocalDateTime occurredAt,
      String actor,
      String method,
      String path,
      int status,
      String outcome,
      Map<String, Object> details) {
    return new SecurityAuditDao.AuditLogRecord(
        occurredAt, "Local", actor, "LocalAuthenticatingRealm", null, "127.0.0.1",
        method, path, "repository-view", status, outcome, details);
  }

  private static <T> List<T> invokeConcurrently(List<Callable<T>> calls, int threads) throws Exception {
    try (var executor = Executors.newFixedThreadPool(threads)) {
      List<T> results = new ArrayList<>(calls.size());
      for (var future : executor.invokeAll(calls)) {
        results.add(future.get());
      }
      return results;
    }
  }

  private AssetInsertResult insertOrFindAssetInWriterTransaction(
      AssetRecord asset, CyclicBarrier transactionsReady) {
    return inTransaction(() -> {
      await(transactionsReady);
      var inserted = stores().assets().tryInsertAsset(asset);
      long assetId = inserted.isPresent()
          ? inserted.getAsLong()
          : stores().assets().findAssetByPath(asset.repositoryId(), asset.path()).orElseThrow().id();
      return new AssetInsertResult(assetId, inserted.isPresent());
    });
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception e) {
      throw new AssertionError("Failed to coordinate duplicate asset insert race", e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent transaction");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for concurrent transaction", e);
    }
  }

  private record AssetInsertResult(long assetId, boolean inserted) {
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
