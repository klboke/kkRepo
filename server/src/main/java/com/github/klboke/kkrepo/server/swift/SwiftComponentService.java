package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits the logical component and immutable Swift release after all blobs are durable. */
@Service
public class SwiftComponentService {
  private final ComponentDao components;
  private final AssetDao assets;
  private final BrowseNodeDao browse;
  private final SwiftRegistryDao registry;

  public SwiftComponentService(
      ComponentDao components,
      AssetDao assets,
      BrowseNodeDao browse,
      SwiftRegistryDao registry) {
    this.components = components;
    this.assets = assets;
    this.browse = browse;
    this.registry = registry;
  }

  /**
   * Commits a hosted release only while the caller still owns the coordinate lease. Renewing the
   * lease and checking the permanent tombstone happen inside this transaction, so an
   * administrative delete and a publication serialize on the same lease row.
   */
  @Transactional
  public SwiftRegistryDao.Release publishFenced(
      RepositoryRuntime runtime,
      Publication publication,
      SwiftPublishLeaseManager.Lease lease) {
    if (lease == null) {
      throw new IllegalArgumentException("Swift hosted publication lease is required");
    }
    lease.assertHeld();
    if (registry.findTombstone(
        runtime.id(), publication.scopeLc(), publication.nameLc(), publication.version())
        .isPresent()) {
      throw new SwiftExceptions.Conflict("This Swift release coordinate is tombstoned");
    }
    return publishInTransaction(runtime, publication);
  }

  /** Commits a proxy release and its fenced source transition in one database transaction. */
  @Transactional
  public SwiftRegistryDao.Release publishProxy(
      RepositoryRuntime runtime, Publication publication, ProxyCompletion completion) {
    if (completion == null) {
      throw new IllegalArgumentException("Swift proxy completion is required");
    }
    SwiftRegistryDao.Release release = publishInTransaction(runtime, publication);
    boolean completed = registry.completeProxySource(
        runtime.id(),
        publication.scopeLc(),
        publication.nameLc(),
        publication.version(),
        completion.expectedCommitSha(),
        release.archiveSha256(),
        "READY",
        release.id(),
        completion.verifiedAt() == null ? Instant.now() : completion.verifiedAt(),
        release.revision(),
        completion.leaseKey(),
        completion.leaseOwner(),
        completion.fencingToken());
    if (!completed) {
      throw new SwiftExceptions.Conflict("Swift proxy lease was fenced by another replica");
    }
    return release;
  }

  private SwiftRegistryDao.Release publishInTransaction(
      RepositoryRuntime runtime, Publication publication) {
    if (runtime == null || runtime.format() != RepositoryFormat.SWIFT || runtime.isGroup()) {
      throw new IllegalArgumentException("Swift release requires a hosted or proxy runtime");
    }
    Instant now = publication.publishedAt() == null ? Instant.now() : publication.publishedAt();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("scope", publication.scopeDisplay());
    attributes.put("package", publication.nameDisplay());
    attributes.put("checksum", publication.archiveSha256());
    attributes.put("signed", publication.signatureFormat() != null);
    attributes.put("sourceKind", publication.sourceKind());
    attributes.put("repositoryUrls", publication.repositoryUrls().stream()
        .map(RepositoryUrlDraft::displayUrl).toList());
    attributes.put("swiftToolsVersions", publication.manifests().stream()
        .map(ManifestDraft::toolsVersion)
        .filter(value -> value != null && !value.isBlank())
        .distinct().toList());
    ComponentRecord component = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.SWIFT,
        publication.scopeLc(),
        publication.nameLc(),
        publication.version(),
        "swift-package-release",
        PersistenceHashes.sha256(
            "swift-package-release",
            publication.scopeLc(),
            publication.nameLc(),
            publication.version()),
        Map.copyOf(attributes),
        now);
    long componentId = components.upsertReturningId(component);

    String releaseBase = publication.scopeLc() + "/" + publication.nameLc() + "/"
        + publication.version();
    AssetRecord archive = promote(
        runtime,
        publication.archiveAssetId(),
        releaseBase + ".zip",
        componentId);
    List<ManifestDraft> promotedManifests = new ArrayList<>();
    for (ManifestDraft manifest : publication.manifests()) {
      AssetRecord promoted = promote(
          runtime,
          manifest.assetId(),
          releaseBase + "/" + manifest.filename(),
          componentId);
      promotedManifests.add(new ManifestDraft(
          manifest.filename(), manifest.toolsVersion(), promoted.id(), manifest.sha256()));
    }
    Long sourceSignatureAssetId = promoteOptional(
        runtime,
        publication.sourceSignatureAssetId(),
        ".swift/signatures/" + releaseBase + "/source.cms",
        componentId);
    Long metadataSignatureAssetId = promoteOptional(
        runtime,
        publication.metadataSignatureAssetId(),
        ".swift/signatures/" + releaseBase + "/metadata.sig",
        componentId);

    long revision = registry.nextRepositoryRevision(runtime.id());
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        null,
        runtime.id(),
        componentId,
        publication.scopeLc(),
        publication.scopeDisplay(),
        publication.nameLc(),
        publication.nameDisplay(),
        publication.version(),
        now,
        publication.metadataJson(),
        publication.archiveSha256(),
        archive.id(),
        publication.signatureFormat(),
        sourceSignatureAssetId,
        metadataSignatureAssetId,
        publication.sourceKind(),
        revision,
        SwiftRegistryDao.RELEASE_READY,
        now,
        now);
    List<SwiftRegistryDao.Manifest> manifests = promotedManifests.stream()
        .map(manifest -> new SwiftRegistryDao.Manifest(
            null,
            manifest.filename(),
            manifest.toolsVersion(),
            manifest.assetId(),
            manifest.sha256()))
        .toList();
    List<SwiftRegistryDao.RepositoryUrl> urls = publication.repositoryUrls().stream()
        .map(url -> new SwiftRegistryDao.RepositoryUrl(
            null,
            null,
            runtime.id(),
            publication.scopeLc(),
            publication.nameLc(),
            url.normalizedUrl(),
            url.displayUrl()))
        .toList();
    return registry.insertRelease(release, manifests, urls);
  }

  @Transactional
  public void rebuild(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.SWIFT || runtime.isGroup()) {
      return;
    }
    // swift_release owns the immutable release truth and has an ON DELETE CASCADE relationship
    // to component. Rebuild must therefore repair bindings in place and must never delete all
    // components first, otherwise the database would cascade-delete the releases being rebuilt.
    HashSet<Long> rebuiltReleaseIds = new HashSet<>();
    for (AssetRecord asset : assets.listAssetsByPrefix(runtime.id(), "")) {
      if (asset.path() == null || !asset.path().endsWith(".zip")) {
        continue;
      }
      String[] segments = asset.path().split("/");
      if (segments.length != 3) {
        continue;
      }
      String version = segments[2].substring(0, segments[2].length() - 4);
      registry.findRelease(runtime.id(), segments[0].toLowerCase(Locale.ROOT),
          segments[1].toLowerCase(Locale.ROOT), version).ifPresent(release -> {
            if (release.id() == null || !rebuiltReleaseIds.add(release.id())) {
              return;
            }
            bind(release.archiveAssetId(), release.componentId());
            registry.listManifests(release.id()).forEach(manifest ->
                bind(manifest.assetId(), release.componentId()));
            bind(release.sourceSignatureAssetId(), release.componentId());
            bind(release.metadataSignatureAssetId(), release.componentId());
          });
    }
  }

  private void bind(Long assetId, long componentId) {
    if (assetId == null) {
      return;
    }
    AssetRecord asset = assets.findAssetById(assetId)
        .orElseThrow(() -> new IllegalStateException("Swift release asset is missing: " + assetId));
    assets.updateAssetComponentBinding(assetId, componentId);
    browse.upsertPathAncestors(asset.repositoryId(), asset.path(), assetId, componentId);
  }

  /**
   * Promotes a uniquely named upload asset to its immutable public path without copying or
   * replacing the durable blob. The unique asset path is the publication CAS: a concurrent
   * publisher can reuse an identical winner, but can never overwrite different bytes.
   */
  private AssetRecord promote(
      RepositoryRuntime runtime, long stagedAssetId, String logicalPath, long componentId) {
    AssetRecord staged = assets.findAssetById(stagedAssetId)
        .filter(asset -> asset.repositoryId() == runtime.id())
        .orElseThrow(() -> new IllegalStateException(
            "Swift staged asset is missing: " + stagedAssetId));
    Object stagedLogicalPath = staged.attributes() == null
        ? null
        : staged.attributes().get("swiftLogicalPath");
    if (!staged.path().equals(logicalPath)
        && !Objects.equals(logicalPath, stagedLogicalPath)) {
      throw new IllegalStateException(
          "Swift staged asset logical path does not match publication: " + logicalPath);
    }
    if (staged.path().equals(logicalPath)) {
      bind(staged.id(), componentId);
      return assets.findAssetById(staged.id()).orElse(staged);
    }

    LinkedHashMap<String, Object> publicAttributes = new LinkedHashMap<>();
    if (staged.attributes() != null) {
      publicAttributes.putAll(staged.attributes());
    }
    publicAttributes.remove("swiftLogicalPath");
    AssetRecord candidate = new AssetRecord(
        null,
        runtime.id(),
        componentId,
        staged.assetBlobId(),
        staged.format(),
        logicalPath,
        PersistenceHashes.pathHash(logicalPath),
        fileName(logicalPath),
        staged.kind(),
        staged.contentType(),
        staged.size(),
        null,
        staged.lastUpdatedAt(),
        Map.copyOf(publicAttributes));
    OptionalLong inserted = assets.tryInsertAsset(candidate);
    AssetRecord promoted;
    if (inserted.isPresent()) {
      promoted = withId(candidate, inserted.getAsLong());
    } else {
      promoted = assets.findAssetByPath(runtime.id(), logicalPath)
          .orElseThrow(() -> new IllegalStateException(
              "Concurrent Swift asset promotion won but is not visible: " + logicalPath));
      if (!sameContent(staged, promoted)) {
        throw new SwiftExceptions.Conflict(
            "Swift release asset already exists with different content: " + logicalPath);
      }
      assets.updateAssetComponentBinding(promoted.id(), componentId);
      promoted = new AssetRecord(
          promoted.id(), promoted.repositoryId(), componentId, promoted.assetBlobId(),
          promoted.format(), promoted.path(), promoted.pathHash(), promoted.name(), promoted.kind(),
          promoted.contentType(), promoted.size(), promoted.lastDownloadedAt(),
          promoted.lastUpdatedAt(), promoted.attributes());
    }
    browse.upsertPathAncestors(
        runtime.id(), logicalPath, promoted.id(), componentId);
    browse.deleteByAssetId(staged.id());
    if (assets.deleteAssetById(staged.id()) != 1) {
      throw new IllegalStateException("Swift staged asset disappeared during promotion");
    }
    return promoted;
  }

  private Long promoteOptional(
      RepositoryRuntime runtime, Long stagedAssetId, String logicalPath, long componentId) {
    return stagedAssetId == null
        ? null
        : promote(runtime, stagedAssetId, logicalPath, componentId).id();
  }

  private boolean sameContent(AssetRecord left, AssetRecord right) {
    if (Objects.equals(left.assetBlobId(), right.assetBlobId())) {
      return true;
    }
    if (left.assetBlobId() == null || right.assetBlobId() == null) {
      return false;
    }
    AssetBlobRecord leftBlob = assets.findBlobById(left.assetBlobId()).orElse(null);
    AssetBlobRecord rightBlob = assets.findBlobById(right.assetBlobId()).orElse(null);
    return leftBlob != null
        && rightBlob != null
        && leftBlob.size() == rightBlob.size()
        && Objects.equals(leftBlob.sha256(), rightBlob.sha256());
  }

  private static AssetRecord withId(AssetRecord asset, long id) {
    return new AssetRecord(
        id, asset.repositoryId(), asset.componentId(), asset.assetBlobId(), asset.format(),
        asset.path(), asset.pathHash(), asset.name(), asset.kind(), asset.contentType(), asset.size(),
        asset.lastDownloadedAt(), asset.lastUpdatedAt(), asset.attributes());
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  record Publication(
      String scopeLc,
      String scopeDisplay,
      String nameLc,
      String nameDisplay,
      String version,
      Instant publishedAt,
      String metadataJson,
      String archiveSha256,
      long archiveAssetId,
      String signatureFormat,
      Long sourceSignatureAssetId,
      Long metadataSignatureAssetId,
      String sourceKind,
      List<ManifestDraft> manifests,
      List<RepositoryUrlDraft> repositoryUrls) {
    Publication {
      manifests = manifests == null ? List.of() : List.copyOf(manifests);
      repositoryUrls = repositoryUrls == null ? List.of() : List.copyOf(repositoryUrls);
    }
  }

  record ManifestDraft(String filename, String toolsVersion, long assetId, String sha256) {}

  record RepositoryUrlDraft(String normalizedUrl, String displayUrl) {}

  record ProxyCompletion(
      String expectedCommitSha,
      Instant verifiedAt,
      String leaseKey,
      String leaseOwner,
      long fencingToken) {}
}
