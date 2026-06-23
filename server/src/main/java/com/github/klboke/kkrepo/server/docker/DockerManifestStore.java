package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.HashColumns;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestAccept;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestDescriptor;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestMetadata;
import com.github.klboke.kkrepo.protocol.docker.DockerPathParser;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DockerManifestStore {
  private final AssetDao assetDao;
  private final DockerRegistryDao dockerDao;
  private final DockerBlobStore blobStore;
  private final DockerManifestParser manifestParser;
  private final AssetMetadataCache assetMetadataCache;
  private final TransientTransactionRetry transactionRetry;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final DockerMetrics metrics;

  @Autowired
  public DockerManifestStore(
      AssetDao assetDao,
      DockerRegistryDao dockerDao,
      DockerBlobStore blobStore,
      DockerManifestParser manifestParser,
      AssetMetadataCache assetMetadataCache,
      TransientTransactionRetry transactionRetry,
      GroupMemberAssetCache groupMemberAssetCache,
      DockerMetrics metrics) {
    this.assetDao = assetDao;
    this.dockerDao = dockerDao;
    this.blobStore = blobStore;
    this.manifestParser = manifestParser;
    this.assetMetadataCache = assetMetadataCache;
    this.transactionRetry = transactionRetry;
    this.groupMemberAssetCache = groupMemberAssetCache;
    this.metrics = metrics;
  }

  public DockerManifestStore(
      AssetDao assetDao,
      DockerRegistryDao dockerDao,
      DockerBlobStore blobStore,
      DockerManifestParser manifestParser,
      AssetMetadataCache assetMetadataCache,
      TransientTransactionRetry transactionRetry) {
    this(assetDao, dockerDao, blobStore, manifestParser, assetMetadataCache, transactionRetry, null, null);
  }

  public StoredManifest putManifest(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      byte[] body,
      String contentType,
      String createdBy,
      String createdByIp,
      boolean validateReferences) {
    return putManifest(runtime, imageName, reference, body, contentType, createdBy, createdByIp,
        validateReferences, List.of());
  }

  public StoredManifest putManifest(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      byte[] body,
      String contentType,
      String createdBy,
      String createdByIp,
      boolean validateReferences,
      List<String> tags) {
    DockerPathParser.validateImageName(imageName);
    DockerDigest digest = DockerDigest.sha256(body);
    if (reference != null && DockerPathParser.isDigestReference(reference)
        && !DockerDigest.parse(reference).equals(digest)) {
      throw new DockerProtocolException(DockerErrorCode.DIGEST_INVALID, "manifest digest does not match reference", 400);
    }
    DockerManifestMetadata metadata = manifestParser.parse(body, contentType);
    if (validateReferences) {
      validateReferences(runtime, imageName, metadata);
    }
    List<String> tagReferences = tagReferences(reference, tags);
    enforceWritePolicy(runtime, imageName, reference, digest, tagReferences);
    BlobStorage storage = blobStore.storage(runtime);
    BlobReference blobReference = storage.put(
        runtime.name(),
        manifestPath(imageName, digest),
        new ByteArrayInputStream(body),
        body.length,
        digest.hex());
    try {
      return executePersist(() -> persistManifest(
          runtime,
          imageName,
          reference,
          digest,
          metadata,
          blobReference,
          body.length,
          createdBy,
          createdByIp,
          tagReferences));
    } catch (RuntimeException e) {
      BlobTransactionCleanup.deleteIfUnreferenced(
          assetDao, storage, runtime.blobStoreId(), blobReference, "Docker manifest persist failure");
      throw e;
    }
  }

  public StoredManifest getManifest(RepositoryRuntime runtime, String imageName, String reference) {
    DockerManifestRecord manifest = dockerDao.findManifestByReference(runtime.id(), imageName, reference)
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, reference));
    AssetRecord asset = assetDao.findAssetById(manifest.assetId())
        .filter(candidate -> candidate.repositoryId() == runtime.id())
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, reference));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assetDao.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      throw new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, reference);
    }
    return new StoredManifest(manifest, asset, blob);
  }

  public boolean referencesBlob(RepositoryRuntime runtime, String imageName, DockerDigest digest) {
    return dockerDao.imageReferencesDigest(runtime.id(), imageName, digest.value());
  }

  public List<String> referencedDigests(StoredManifest stored) {
    return dockerDao.listReferences(stored.manifest().id()).stream()
        .map(DockerManifestReferenceRecord::digest)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .toList();
  }

  public DockerResponse serveManifest(RepositoryRuntime runtime, String imageName, String reference, boolean headOnly) {
    return serveManifest(runtime, imageName, reference, headOnly, List.of());
  }

  public DockerResponse serveManifest(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      boolean headOnly,
      List<String> acceptHeaders) {
    StoredManifest stored = getManifest(runtime, imageName, reference);
    return serveManifest(stored, headOnly, acceptHeaders);
  }

  public DockerResponse serveManifest(StoredManifest stored, boolean headOnly) {
    return serveManifest(stored, headOnly, List.of());
  }

  public DockerResponse serveManifest(StoredManifest stored, boolean headOnly, List<String> acceptHeaders) {
    ensureAccepted(stored.manifest().mediaType(), acceptHeaders);
    DockerResponse response = headOnly
        ? DockerResponse.noBody(200, stored.blob().size(), stored.manifest().mediaType(), stored.manifest().updatedAt())
        : DockerResponse.body(
            200,
            () -> open(stored.blob()),
            stored.blob().size(),
            stored.manifest().mediaType(),
            stored.manifest().updatedAt());
    return response
        .withContentType(stored.manifest().mediaType())
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, stored.manifest().digest())
        .withHeader("ETag", "\"" + stored.manifest().digest() + "\"");
  }

  private static void ensureAccepted(String mediaType, List<String> acceptHeaders) {
    if (!DockerManifestAccept.accepts(acceptHeaders, mediaType)) {
      throw new DockerProtocolException(
          DockerErrorCode.MANIFEST_UNKNOWN,
          "manifest media type is not accepted by the client");
    }
  }

  public DockerTagList listTags(RepositoryRuntime runtime, String imageName, String last, int limit) {
    int pageSize = Math.max(1, Math.min(limit, 1000));
    if (!dockerDao.imageExists(runtime.id(), imageName)) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, imageName);
    }
    List<String> tags = dockerDao.listTags(runtime.id(), imageName, last, pageSize + 1);
    boolean hasNext = tags.size() > pageSize;
    if (hasNext) {
      tags = tags.subList(0, pageSize);
    }
    return new DockerTagList(imageName, tags, hasNext);
  }

  public DockerCatalogList listCatalog(RepositoryRuntime runtime, String last, int limit) {
    int pageSize = Math.max(1, Math.min(limit, 1000));
    List<String> repositories = dockerDao.listCatalog(runtime.id(), last, pageSize + 1);
    boolean hasNext = repositories.size() > pageSize;
    if (hasNext) {
      repositories = repositories.subList(0, pageSize);
    }
    return new DockerCatalogList(repositories, hasNext);
  }

  @Transactional
  public int deleteReference(RepositoryRuntime runtime, String imageName, String reference) {
    if (DockerPathParser.isDigestReference(reference)) {
      DockerRegistryDao.DeletedManifest deleted = dockerDao.deleteManifest(runtime.id(), imageName, reference);
      if (deleted.deleted() > 0) {
        if (deleted.assetId() != null) {
          assetDao.deleteAssetById(deleted.assetId());
        }
        if (deleted.assetBlobId() != null) {
          assetDao.markBlobDeletedIfUnreferenced(
              deleted.assetBlobId(), "docker manifest deleted");
        }
        invalidateGroupMemberCaches(runtime);
      }
      return deleted.deleted();
    }
    DockerPathParser.validateTag(reference);
    int deleted = dockerDao.deleteTag(runtime.id(), imageName, reference);
    if (deleted > 0) {
      invalidateGroupMemberCaches(runtime);
    }
    return deleted;
  }

  public List<DockerManifestRecord> referrers(
      RepositoryRuntime runtime, String subjectDigest, String artifactType) {
    return dockerDao.listReferrers(runtime.id(), subjectDigest, artifactType);
  }

  private StoredManifest persistManifest(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      DockerDigest digest,
      DockerManifestMetadata metadata,
      BlobReference blobReference,
      long size,
      String createdBy,
      String createdByIp,
      List<String> tags) {
    Instant now = Instant.now();
    String blobRef = BlobReferenceCodec.format(blobReference);
    AssetBlobRecord blob = assetDao.insertBlobOrFindExisting(new AssetBlobRecord(
        null,
        runtime.blobStoreId(),
        blobRef,
        HashColumns.blobRefHash(blobRef),
        blobReference.objectKey(),
        HashColumns.objectKeyHash(blobReference.objectKey()),
        null,
        digest.hex(),
        null,
        size,
        metadata.mediaType(),
        createdBy,
        createdByIp,
        now,
        now,
        Map.of("dockerDigest", digest.value(), "dockerManifest", true)));
    AssetRecord asset = upsertAsset(runtime, imageName, digest, metadata, blob, now);
    DockerManifestRecord manifest = dockerDao.upsertManifest(new DockerManifestRecord(
        null,
        runtime.id(),
        imageName,
        DockerRegistryDao.hash(imageName),
        digest.algorithm(),
        digest.value(),
        DockerRegistryDao.hash(digest.value()),
        metadata.mediaType(),
        metadata.artifactType(),
        metadata.subjectDigest(),
        metadata.subjectDigest() == null ? null : DockerRegistryDao.hash(metadata.subjectDigest()),
        asset.id(),
        size,
        createdBy,
        createdByIp,
        null,
        manifestAttributes(digest, metadata),
        null,
        null));
    dockerDao.replaceManifestReferences(manifest.id(), metadata.references().stream()
        .map(ref -> toReference(manifest.id(), runtime.id(), imageName, ref))
        .toList());
    if (tags != null) {
      for (String tag : tags) {
        DockerPathParser.validateTag(tag);
        dockerDao.upsertTag(new DockerTagRecord(
            null,
            runtime.id(),
            imageName,
            DockerRegistryDao.hash(imageName),
            tag,
            DockerRegistryDao.hash(tag),
            manifest.id(),
            digest.value(),
            createdBy,
            createdByIp,
            null,
            null));
      }
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), asset.path());
    invalidateGroupMemberCaches(runtime);
    return new StoredManifest(manifest, asset, blob);
  }

  @Transactional
  AssetRecord upsertAsset(
      RepositoryRuntime runtime,
      String imageName,
      DockerDigest digest,
      DockerManifestMetadata metadata,
      AssetBlobRecord blob,
      Instant now) {
    String path = manifestPath(imageName, digest);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("docker", Map.of(
        "digest", digest.value(),
        "imageName", imageName,
        "mediaType", metadata.mediaType(),
        "artifactType", metadata.artifactType() == null ? "" : metadata.artifactType(),
        "subjectDigest", metadata.subjectDigest() == null ? "" : metadata.subjectDigest()));
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      assetDao.updateAssetBlobBindingAndMetadata(
          prior.id(),
          null,
          blob.id(),
          "MANIFEST",
          metadata.mediaType(),
          blob.size(),
          now,
          attrs);
      return new AssetRecord(
          prior.id(), prior.repositoryId(), null, blob.id(), prior.format(), prior.path(),
          prior.pathHash(), prior.name(), "MANIFEST", metadata.mediaType(), blob.size(),
          prior.lastDownloadedAt(), now, attrs);
    }
    AssetRecord record = new AssetRecord(
        null,
        runtime.id(),
        null,
        blob.id(),
        RepositoryFormat.DOCKER,
        path,
        HashColumns.pathHash(path),
        imageName + "@" + digest.value(),
        "MANIFEST",
        metadata.mediaType(),
        blob.size(),
        null,
        now,
        attrs);
    OptionalLong id = assetDao.tryInsertAsset(record);
    if (id.isPresent()) {
      return new AssetRecord(
          id.getAsLong(),
          record.repositoryId(),
          record.componentId(),
          record.assetBlobId(),
          record.format(),
          record.path(),
          record.pathHash(),
          record.name(),
          record.kind(),
          record.contentType(),
          record.size(),
          record.lastDownloadedAt(),
          record.lastUpdatedAt(),
          record.attributes());
    }
    return assetDao.findAssetByPath(runtime.id(), path).orElseThrow();
  }

  private static Map<String, Object> manifestAttributes(
      DockerDigest digest,
      DockerManifestMetadata metadata) {
    Map<String, Object> annotations = metadata.annotations() == null ? Map.of() : metadata.annotations();
    if (annotations.isEmpty()) {
      return Map.of("rawBytesDigest", digest.value());
    }
    return Map.of(
        "rawBytesDigest", digest.value(),
        "annotations", annotations);
  }

  private void validateReferences(
      RepositoryRuntime runtime, String imageName, DockerManifestMetadata metadata) {
    for (DockerManifestDescriptor ref : metadata.references()) {
      if (!dockerDao.referencedDigestExists(runtime.id(), imageName, ref.digest())) {
        throw new DockerProtocolException(
            DockerErrorCode.MANIFEST_BLOB_UNKNOWN,
            "manifest references unknown digest " + ref.digest(),
            400);
      }
    }
  }

  private void enforceWritePolicy(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      DockerDigest digest,
      List<String> tagReferences) {
    if (!runtime.isHosted()) {
      return;
    }
    String policy = runtime.writePolicy() == null || runtime.writePolicy().isBlank()
        ? "ALLOW_ONCE"
        : runtime.writePolicy().trim().toUpperCase(java.util.Locale.ROOT);
    if ("DENY".equals(policy)) {
      throw new DockerProtocolException(
          DockerErrorCode.DENIED, "Write policy DENY forbids writing Docker manifests", 403);
    }
    if (!"ALLOW_ONCE".equals(policy)) {
      return;
    }
    if (tagReferences != null && !tagReferences.isEmpty()) {
      for (String tag : tagReferences) {
        if (dockerDao.tagExists(runtime.id(), imageName, tag)) {
          throw new DockerProtocolException(
              DockerErrorCode.DENIED, "Write policy ALLOW_ONCE forbids overwriting Docker tag " + tag, 403);
        }
      }
      return;
    }
    if (reference != null && DockerPathParser.isDigestReference(reference)
        && dockerDao.findManifestByDigest(runtime.id(), imageName, digest.value()).isPresent()) {
      throw new DockerProtocolException(
          DockerErrorCode.DENIED, "Write policy ALLOW_ONCE forbids overwriting Docker manifests", 403);
    }
  }

  private static List<String> tagReferences(String reference, List<String> tags) {
    Set<String> normalized = new LinkedHashSet<>();
    if (reference != null && !DockerPathParser.isDigestReference(reference)) {
      DockerPathParser.validateTag(reference);
      normalized.add(reference);
    }
    if (tags != null) {
      for (String tag : tags) {
        if (tag == null || tag.isBlank()) {
          continue;
        }
        DockerPathParser.validateTag(tag);
        normalized.add(tag);
      }
    }
    return List.copyOf(normalized);
  }

  private void invalidateGroupMemberCaches(RepositoryRuntime runtime) {
    if (groupMemberAssetCache != null && runtime != null) {
      groupMemberAssetCache.invalidateMemberAfterCommit(runtime.id());
      recordCache(runtime, "group_member", "invalidate_member");
    }
  }

  private void recordCache(RepositoryRuntime runtime, String cache, String result) {
    if (metrics != null) {
      metrics.cache(cache, runtime, result);
    }
  }

  private DockerManifestReferenceRecord toReference(
      long manifestId,
      long repositoryId,
      String imageName,
      DockerManifestDescriptor ref) {
    return new DockerManifestReferenceRecord(
        null,
        manifestId,
        repositoryId,
        imageName,
        ref.digest(),
        DockerRegistryDao.hash(ref.digest()),
        ref.kind(),
        ref.mediaType(),
        ref.size(),
        ref.platform(),
        ref.annotations());
  }

  private InputStream open(AssetBlobRecord blob) {
    return blobStore.openBlob(blob);
  }

  private <T> T executePersist(java.util.function.Supplier<T> callback) {
    if (transactionRetry == null) {
      return callback.get();
    }
    return transactionRetry.executeIfNoTransaction("Persist Docker manifest", callback);
  }

  static String manifestPath(String imageName, DockerDigest digest) {
    return "docker/manifests/" + imageName + "/" + digest.algorithm() + "/" + digest.hex();
  }

  public record StoredManifest(
      DockerManifestRecord manifest,
      AssetRecord asset,
      AssetBlobRecord blob) {
  }
}
