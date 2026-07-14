package com.github.klboke.kkrepo.server.migration;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestDescriptor;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestMetadata;
import com.github.klboke.kkrepo.protocol.docker.DockerPathParser;
import com.github.klboke.kkrepo.protocol.maven.MavenContentType;
import com.github.klboke.kkrepo.protocol.maven.path.ChecksumPayload;
import com.github.klboke.kkrepo.protocol.maven.path.HashType;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.docker.DockerManifestParser;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.pub.PubRepositoryDataMigrationWriter;
import com.github.klboke.kkrepo.server.terraform.TerraformRepositoryDataMigrationWriter;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
class RepositoryDataMigrationWriter {
  private static final String CREATED_BY = "nexus-migration";
  private static final ComposerPathParser COMPOSER_PATHS = new ComposerPathParser();

  private final RepositoryDao repositoryDao;
  private final ComponentDao componentDao;
  private final AssetDao assetDao;
  private final BrowseNodeDao browseNodeDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final RepositoryIndexRebuildDao indexRebuildDao;
  private final DockerRegistryDao dockerRegistryDao;
  private final DockerManifestParser dockerManifestParser;
  private final PubRepositoryDataMigrationWriter pubMigrationWriter;
  private final TerraformRepositoryDataMigrationWriter terraformMigrationWriter;
  private final TransientTransactionRetry transactionRetry;
  private final MavenPathParser mavenPathParser = new MavenPathParser();

  RepositoryDataMigrationWriter(
      RepositoryDao repositoryDao,
      ComponentDao componentDao,
      AssetDao assetDao,
      BrowseNodeDao browseNodeDao,
      BlobStorageRegistry blobStorageRegistry,
      RepositoryIndexRebuildDao indexRebuildDao,
      DockerRegistryDao dockerRegistryDao,
      DockerManifestParser dockerManifestParser,
      PubRepositoryDataMigrationWriter pubMigrationWriter,
      TransientTransactionRetry transactionRetry) {
    this(
        repositoryDao,
        componentDao,
        assetDao,
        browseNodeDao,
        blobStorageRegistry,
        indexRebuildDao,
        dockerRegistryDao,
        dockerManifestParser,
        pubMigrationWriter,
        null,
        transactionRetry);
  }

  @Autowired
  RepositoryDataMigrationWriter(
      RepositoryDao repositoryDao,
      ComponentDao componentDao,
      AssetDao assetDao,
      BrowseNodeDao browseNodeDao,
      BlobStorageRegistry blobStorageRegistry,
      RepositoryIndexRebuildDao indexRebuildDao,
      DockerRegistryDao dockerRegistryDao,
      DockerManifestParser dockerManifestParser,
      PubRepositoryDataMigrationWriter pubMigrationWriter,
      TerraformRepositoryDataMigrationWriter terraformMigrationWriter,
      TransientTransactionRetry transactionRetry) {
    this.repositoryDao = repositoryDao;
    this.componentDao = componentDao;
    this.assetDao = assetDao;
    this.browseNodeDao = browseNodeDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.indexRebuildDao = indexRebuildDao;
    this.dockerRegistryDao = dockerRegistryDao;
    this.dockerManifestParser = dockerManifestParser;
    this.pubMigrationWriter = pubMigrationWriter;
    this.terraformMigrationWriter = terraformMigrationWriter;
    this.transactionRetry = transactionRetry;
  }

  WriteResult write(long targetRepositoryId, RepositoryDataMigrationAssetRecord source, InputStream body,
      String responseContentType, boolean validateSize) {
    RepositoryRecord repository = repositoryDao.findById(targetRepositoryId)
        .orElseThrow(() -> new IllegalArgumentException("target repository not found: " + targetRepositoryId));
    if (repository.blobStoreId() == null) {
      throw new IllegalArgumentException("target repository has no blob store: " + repository.name());
    }
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(repository.blobStoreId());
    if (repository.format() == RepositoryFormat.PUB) {
      if (pubMigrationWriter == null) {
        throw new IllegalStateException("Pub migration writer is not configured");
      }
      PubRepositoryDataMigrationWriter.MigratedAsset migrated = pubMigrationWriter.write(
          repository, storage, source, body, responseContentType, validateSize);
      return new WriteResult(
          migrated.componentId(), migrated.assetId(), migrated.assetBlobId(), migrated.assetBlobObjectKey());
    }
    if (repository.format() == RepositoryFormat.TERRAFORM) {
      if (terraformMigrationWriter == null) {
        throw new IllegalStateException("Terraform migration writer is not configured");
      }
      TerraformRepositoryDataMigrationWriter.MigratedAsset migrated = terraformMigrationWriter.write(
          repository, source, body, responseContentType, validateSize);
      return new WriteResult(
          migrated.componentId(), migrated.assetId(), migrated.assetBlobId(), migrated.assetBlobObjectKey());
    }
    DigestedUpload upload = uploadWithDigests(repository, storage, source, body, validateSize);
    Map<MavenPath, ChecksumUpload> checksumUploads = new LinkedHashMap<>();
    Map<MavenPath, WriteResult> checksumResults = new LinkedHashMap<>();
    try {
      checksumUploads.putAll(generatedMavenChecksumUploads(repository, storage, source, upload.digests()));
      String contentType = firstNonBlank(source.contentType(), responseContentType);
      WriteResult result = transactionRetry.execute(
          "Persist migrated asset " + repository.name() + "/" + source.sourcePath(),
          () -> {
            WriteResult persisted = persist(repository, source, upload, contentType, null);
            indexMigratedDockerAsset(repository, source, upload, contentType, persisted);
            for (Map.Entry<MavenPath, ChecksumUpload> entry : checksumUploads.entrySet()) {
              ChecksumUpload checksum = entry.getValue();
              WriteResult persistedChecksum = persist(
                  repository, checksum.source(), checksum.upload(), MavenContentType.CHECKSUM, persisted.componentId());
              checksumResults.put(entry.getKey(), persistedChecksum);
            }
            return persisted;
          });
      cleanupUnusedUploadedBlob(storage, repository.blobStoreId(), upload, result);
      for (Map.Entry<MavenPath, ChecksumUpload> entry : checksumUploads.entrySet()) {
        cleanupUnusedUploadedBlob(
            storage, repository.blobStoreId(), entry.getValue().upload(), checksumResults.get(entry.getKey()));
      }
      deleteTemp(upload);
      checksumUploads.values().forEach(checksum -> deleteTemp(checksum.upload()));
      return result;
    } catch (RuntimeException e) {
      cleanupUploadedBlob(storage, repository.blobStoreId(), upload);
      checksumUploads.values()
          .forEach(checksum -> cleanupUploadedBlob(storage, repository.blobStoreId(), checksum.upload()));
      deleteTemp(upload);
      checksumUploads.values().forEach(checksum -> deleteTemp(checksum.upload()));
      throw e;
    }
  }

  private Map<MavenPath, ChecksumUpload> generatedMavenChecksumUploads(
      RepositoryRecord repository,
      BlobStorage storage,
      RepositoryDataMigrationAssetRecord source,
      Digests digests) {
    if (repository.format() != RepositoryFormat.MAVEN2) {
      return Map.of();
    }
    MavenPath mainPath = mavenPathParser.parsePath(source.sourcePath());
    if (!RepositoryDataMigrationPaths.shouldGenerateMavenChecksumSiblings(mainPath)) {
      return Map.of();
    }
    Map<MavenPath, ChecksumUpload> uploads = new LinkedHashMap<>();
    try {
      for (GeneratedMavenChecksum checksum : generatedMavenChecksums(mainPath, digests)) {
        RepositoryDataMigrationAssetRecord checksumSource =
            generatedChecksumSource(source, checksum.path().path(), checksum.payload().length);
        DigestedUpload checksumUpload = uploadWithDigests(
            repository, storage, checksumSource, new ByteArrayInputStream(checksum.payload()), true);
        uploads.put(checksum.path(), new ChecksumUpload(checksumSource, checksumUpload));
      }
      return uploads;
    } catch (RuntimeException e) {
      uploads.values().forEach(checksum -> cleanupUploadedBlob(storage, repository.blobStoreId(), checksum.upload()));
      uploads.values().forEach(checksum -> deleteTemp(checksum.upload()));
      throw e;
    }
  }

  private static RepositoryDataMigrationAssetRecord generatedChecksumSource(
      RepositoryDataMigrationAssetRecord source,
      String checksumPath,
      int payloadSize) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source", "nexus-repository-data-migration");
    metadata.put("generatedFrom", source.sourcePath());
    putIfPresent(metadata, "generatedFromSourceAssetId", source.sourceAssetId());
    putIfPresent(metadata, "generatedFromSourceBlobRef", source.sourceBlobRef());
    return new RepositoryDataMigrationAssetRecord(
        null,
        source.repositoryJobId(),
        null,
        source.sourceComponentId(),
        checksumPath,
        null,
        source.format(),
        source.namespace(),
        source.name(),
        source.version(),
        "checksum",
        MavenContentType.CHECKSUM,
        (long) payloadSize,
        null,
        source.sourceLastUpdatedAt(),
        source.sourceLastDownloadedAt(),
        source.sourceBlobCreatedAt(),
        source.sourceBlobUpdatedAt(),
        firstNonBlank(source.sourceCreatedBy(), CREATED_BY),
        source.sourceCreatedByIp(),
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.copyOf(metadata),
        source.discoveredAt());
  }

  private static List<GeneratedMavenChecksum> generatedMavenChecksums(MavenPath mainPath, Digests digests) {
    return generatedMavenChecksums(mainPath, digests.md5(), digests.sha1(), digests.sha256(), digests.sha512());
  }

  static List<GeneratedMavenChecksum> generatedMavenChecksums(
      MavenPath mainPath,
      String md5,
      String sha1,
      String sha256,
      String sha512) {
    return List.of(
        new GeneratedMavenChecksum(mainPath.hash(HashType.MD5), ChecksumPayload.format(md5)),
        new GeneratedMavenChecksum(mainPath.hash(HashType.SHA1), ChecksumPayload.format(sha1)),
        new GeneratedMavenChecksum(mainPath.hash(HashType.SHA256), ChecksumPayload.format(sha256)),
        new GeneratedMavenChecksum(mainPath.hash(HashType.SHA512), ChecksumPayload.format(sha512)));
  }

  private WriteResult persist(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      DigestedUpload upload,
      String contentType,
      Long componentIdOverride) {
    Instant now = Instant.now();
    Instant lastUpdatedAt = source.sourceLastUpdatedAt() == null ? now : source.sourceLastUpdatedAt();
    BlobReference ref = upload.reference();
    Digests digests = upload.digests();
    String blobRef = BlobReferenceCodec.format(ref);
    MigratedCargoMetadata cargoMetadata = repository.format() == RepositoryFormat.CARGO
        ? migratedCargoMetadata(source, upload)
        : null;

    Optional<AssetRecord> existing = assetDao.findAssetByPath(repository.id(), source.sourcePath());
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);

    AssetBlobRecord persistedBlob = assetDao.findReusableBlobBySha256(
        repository.blobStoreId(), digests.sha256(), digests.size()).orElse(null);
    long blobId;
    if (persistedBlob == null) {
      Map<String, Object> blobAttributes = blobAttributes(digests);
      AssetBlobRecord blobRecord = new AssetBlobRecord(
          null,
          repository.blobStoreId(),
          blobRef,
          PersistenceHashes.blobRefHash(blobRef),
          ref.objectKey(),
          PersistenceHashes.objectKeyHash(ref.objectKey()),
          digests.sha1(),
          digests.sha256(),
          digests.md5(),
          digests.size(),
          contentType,
          firstNonBlank(source.sourceCreatedBy(), CREATED_BY),
          source.sourceCreatedByIp(),
          source.sourceBlobCreatedAt() == null ? now : source.sourceBlobCreatedAt(),
          source.sourceBlobUpdatedAt() == null ? now : source.sourceBlobUpdatedAt(),
          blobAttributes);
      persistedBlob = assetDao.insertBlobOrFindExisting(blobRecord);
      blobId = persistedBlob.id();
    } else {
      blobId = persistedBlob.id();
      updateReusableBlobAttributesIfNeeded(persistedBlob, digests);
    }

    Long componentId = componentIdOverride == null
        ? upsertComponent(repository, source, lastUpdatedAt, digests, cargoMetadata)
        : componentIdOverride;
    String kind = assetKind(repository.format(), source);
    Map<String, Object> attributes = assetAttributes(source, digests, cargoMetadata);
    AssetRecord persistedAsset;
    if (existing.isPresent()) {
      persistedAsset = updateExistingAsset(existing.get(), componentId, blobId, kind, contentType,
          digests.size(), lastUpdatedAt, attributes);
    } else {
      AssetRecord record = new AssetRecord(
          null,
          repository.id(),
          componentId,
          blobId,
          repository.format(),
          source.sourcePath(),
          PersistenceHashes.pathHash(source.sourcePath()),
          fileName(source.sourcePath()),
          kind,
          contentType,
          digests.size(),
          source.sourceLastDownloadedAt(),
          lastUpdatedAt,
          attributes);
      OptionalLong insertedAssetId = assetDao.tryInsertAsset(record);
      if (insertedAssetId.isPresent()) {
        long assetId = insertedAssetId.getAsLong();
        persistedAsset = new AssetRecord(
            assetId, record.repositoryId(), record.componentId(), record.assetBlobId(),
            record.format(), record.path(), record.pathHash(), record.name(), record.kind(),
            record.contentType(), record.size(), record.lastDownloadedAt(), record.lastUpdatedAt(),
            record.attributes());
      } else {
        AssetRecord prior = assetDao.findAssetByPath(repository.id(), source.sourcePath())
            .orElseThrow(() -> new IllegalStateException("Concurrent migrated asset insert won but row is not visible for "
                + repository.name() + "/" + source.sourcePath()));
        previousBlobId = prior.assetBlobId();
        persistedAsset = updateExistingAsset(prior, componentId, blobId, kind, contentType,
            digests.size(), lastUpdatedAt, attributes);
      }
    }

    if (previousBlobId != null && previousBlobId != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlobId, "asset replaced by repository data migration");
    }
    browseNodeDao.upsertPathAncestors(repository.id(), source.sourcePath(), persistedAsset.id(), componentId);
    enqueueDerivedIndex(repository, source);
    return new WriteResult(componentId, persistedAsset.id(), persistedBlob.id(), persistedBlob.objectKey());
  }

  private void indexMigratedDockerAsset(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      DigestedUpload upload,
      String contentType,
      WriteResult persisted) {
    if (repository.format() != RepositoryFormat.DOCKER) {
      return;
    }
    dockerBlobMigrationTarget(source.sourcePath())
        .ifPresent(target -> validateMigratedDockerBlob(source, upload, target));
    dockerManifestMigrationTarget(source.sourcePath())
        .ifPresent(target -> indexMigratedDockerManifest(repository, source, upload, contentType, persisted, target));
  }

  private void validateMigratedDockerBlob(
      RepositoryDataMigrationAssetRecord source,
      DigestedUpload upload,
      DockerBlobMigrationTarget target) {
    if (!target.digest().isSha256()) {
      throw new IllegalStateException("Unsupported Docker blob digest algorithm for migrated asset "
          + source.sourcePath() + ": " + target.digest().algorithm());
    }
    if (!target.digest().hex().equals(upload.digests().sha256())) {
      throw new IllegalStateException("Docker blob digest mismatch for migrated asset " + source.sourcePath()
          + ": expected " + target.digest().value() + ", actual sha256:" + upload.digests().sha256());
    }
  }

  private void indexMigratedDockerManifest(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      DigestedUpload upload,
      String contentType,
      WriteResult persisted,
      DockerManifestMigrationTarget target) {
    byte[] body = readTemp(upload);
    DockerDigest digest = new DockerDigest("sha256", upload.digests().sha256());
    if (DockerPathParser.isDigestReference(target.reference())
        && !DockerDigest.parse(target.reference()).equals(digest)) {
      throw new IllegalStateException("Docker manifest digest mismatch for migrated asset " + source.sourcePath()
          + ": expected " + target.reference() + ", actual " + digest.value());
    }
    DockerManifestMetadata metadata = dockerManifestParser.parse(body, contentType);
    Map<String, Object> attributes = dockerManifestAttributes(source, digest, metadata);
    DockerManifestRecord manifest = dockerRegistryDao.upsertManifest(new DockerManifestRecord(
        null,
        repository.id(),
        target.imageName(),
        PersistenceHashes.sha256(target.imageName()),
        digest.algorithm(),
        digest.value(),
        PersistenceHashes.sha256(digest.value()),
        metadata.mediaType(),
        metadata.artifactType(),
        metadata.subjectDigest(),
        metadata.subjectDigest() == null ? null : PersistenceHashes.sha256(metadata.subjectDigest()),
        persisted.assetId(),
        upload.digests().size(),
        firstNonBlank(source.sourceCreatedBy(), CREATED_BY),
        source.sourceCreatedByIp(),
        null,
        attributes,
        null,
        null));
    dockerRegistryDao.replaceManifestReferences(manifest.id(), metadata.references().stream()
        .map(ref -> dockerManifestReference(manifest.id(), repository.id(), target.imageName(), ref))
        .toList());
    if (!DockerPathParser.isDigestReference(target.reference())) {
      DockerPathParser.validateTag(target.reference());
      dockerRegistryDao.upsertTag(new DockerTagRecord(
          null,
          repository.id(),
          target.imageName(),
          PersistenceHashes.sha256(target.imageName()),
          target.reference(),
          PersistenceHashes.sha256(target.reference()),
          manifest.id(),
          digest.value(),
          firstNonBlank(source.sourceCreatedBy(), CREATED_BY),
          source.sourceCreatedByIp(),
          null,
          null));
    }
  }

  private static DockerManifestReferenceRecord dockerManifestReference(
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
        PersistenceHashes.sha256(ref.digest()),
        ref.kind(),
        ref.mediaType(),
        ref.size(),
        ref.platform(),
        ref.annotations());
  }

  private static Map<String, Object> dockerManifestAttributes(
      RepositoryDataMigrationAssetRecord source,
      DockerDigest digest,
      DockerManifestMetadata metadata) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("source", "nexus-repository-data-migration");
    attributes.put("rawBytesDigest", digest.value());
    if (metadata.annotations() != null && !metadata.annotations().isEmpty()) {
      attributes.put("annotations", metadata.annotations());
    }
    putIfPresent(attributes, "sourceAssetId", source.sourceAssetId());
    putIfPresent(attributes, "sourceBlobRef", source.sourceBlobRef());
    return attributes;
  }

  private static byte[] readTemp(DigestedUpload upload) {
    try {
      return Files.readAllBytes(upload.tempFile());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read staged Docker manifest", e);
    }
  }

  private Long upsertComponent(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      Instant lastUpdatedAt,
      Digests digests,
      MigratedCargoMetadata cargoMetadata) {
    if (repository.format() == RepositoryFormat.CARGO) {
      return upsertCargoComponent(repository, source, lastUpdatedAt, digests, cargoMetadata);
    }
    if (source.name() == null || source.name().isBlank()) {
      return null;
    }
    ComponentRecord component = new ComponentRecord(
        null,
        repository.id(),
        repository.format(),
        blankToNull(source.namespace()),
        source.name(),
        blankToNull(source.version()),
        componentKind(repository.format(), source),
        PersistenceHashes.componentCoordinateHash(blankToNull(source.namespace()), source.name(), blankToNull(source.version())),
        componentAttributes(source),
        lastUpdatedAt);
    return componentDao.upsertReturningId(component);
  }

  private Long upsertCargoComponent(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      Instant lastUpdatedAt,
      Digests digests,
      MigratedCargoMetadata cargoMetadata) {
    if (cargoMetadata == null) {
      return null;
    }
    ComponentRecord component = new ComponentRecord(
        null,
        repository.id(),
        repository.format(),
        null,
        cargoMetadata.normalizedName(),
        cargoMetadata.versionKey(),
        "crate",
        PersistenceHashes.componentCoordinateHash(null, cargoMetadata.normalizedName(), cargoMetadata.versionKey()),
        cargoMetadata.componentAttributes(digests.sha256(), source.sourcePath(), migratedCargoYanked(source)),
        lastUpdatedAt);
    return componentDao.upsertReturningId(component);
  }

  private AssetRecord updateExistingAsset(
      AssetRecord prior,
      Long componentId,
      long blobId,
      String kind,
      String contentType,
      long size,
      Instant lastUpdatedAt,
      Map<String, Object> attributes) {
    Long effectiveComponentId = componentId == null ? prior.componentId() : componentId;
    assetDao.updateAssetBlobBindingAndMetadata(
        prior.id(), effectiveComponentId, blobId, kind, contentType, size, lastUpdatedAt, attributes);
    return new AssetRecord(
        prior.id(), prior.repositoryId(), effectiveComponentId, blobId, prior.format(), prior.path(),
        prior.pathHash(), prior.name(), kind, contentType, size, prior.lastDownloadedAt(),
        lastUpdatedAt, attributes);
  }

  private DigestedUpload uploadWithDigests(
      RepositoryRecord repository,
      BlobStorage storage,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      boolean validateSize) {
    Path tempFile = null;
    try (InputStream input = body) {
      tempFile = TempBlobFiles.createTempFile(storage, "kkrepo-repository-migration-", ".blob");
      MessageDigest md5 = digest(HashType.MD5.javaAlgorithm());
      MessageDigest sha1 = digest(HashType.SHA1.javaAlgorithm());
      MessageDigest sha256 = digest(HashType.SHA256.javaAlgorithm());
      MessageDigest sha512 = digest(HashType.SHA512.javaAlgorithm());
      long size;
      try (OutputStream out = Files.newOutputStream(tempFile)) {
        size = streamWithDigests(input, out, md5, sha1, sha256, sha512);
      }
      if (validateSize && source.size() != null && source.size() != size) {
        throw new IllegalStateException("Downloaded size mismatch for " + repository.name() + "/"
            + source.sourcePath() + ": expected " + source.size() + ", actual " + size);
      }
      Digests digests = new Digests(
          hex(md5.digest()),
          hex(sha1.digest()),
          hex(sha256.digest()),
          hex(sha512.digest()),
          size);
      AssetBlobRecord reusable = assetDao.findReusableBlobBySha256(repository.blobStoreId(), digests.sha256(), size)
          .orElse(null);
      if (reusable != null) {
        BlobReference reference = BlobReferenceCodec.reference(
            reusable.blobRef(), reusable.objectKey(), reusable.sha256(), reusable.size());
        return new DigestedUpload(reference, digests, tempFile, false);
      }
      BlobReference reference = storage.putFile(repository.name(), source.sourcePath(), tempFile, digests.sha256());
      return new DigestedUpload(reference, digests, tempFile, true);
    } catch (IOException e) {
      TempBlobFiles.deleteQuietly(tempFile);
      throw new UncheckedIOException("Failed to stage migrated blob for " + repository.name() + "/"
          + source.sourcePath(), e);
    } catch (RuntimeException e) {
      TempBlobFiles.deleteQuietly(tempFile);
      throw e;
    }
  }

  private static long streamWithDigests(InputStream in, OutputStream out, MessageDigest... digests)
      throws IOException {
    byte[] buffer = new byte[TempBlobFiles.responseBufferSize()];
    long size = 0;
    int read;
    while ((read = in.read(buffer)) >= 0) {
      out.write(buffer, 0, read);
      size += read;
      for (MessageDigest digest : digests) {
        digest.update(buffer, 0, read);
      }
    }
    return size;
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " digest is not available", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  private void updateReusableBlobAttributesIfNeeded(AssetBlobRecord persistedBlob, Digests digests) {
    Map<String, Object> attributes = mergeReusableBlobAttributes(persistedBlob.attributes(), digests);
    if (attributes != null) {
      assetDao.updateBlobAttributes(persistedBlob.id(), attributes);
    }
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "repository data migration persist failure");
  }

  private void cleanupUnusedUploadedBlob(
      BlobStorage storage,
      long blobStoreId,
      DigestedUpload upload,
      WriteResult result) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfNotReferencedByMetadata(
        assetDao, storage, blobStoreId, upload.reference(),
        result == null ? null : result.assetBlobObjectKey(), "repository data migration blob reuse");
  }

  private void deleteTemp(DigestedUpload upload) {
    if (upload != null) {
      TempBlobFiles.deleteQuietly(upload.tempFile());
    }
  }

  static Map<String, Object> mergeReusableBlobAttributes(Map<String, Object> existing, Digests digests) {
    Map<String, Object> current = existing == null ? Map.of() : existing;
    if (current.get("sha512") != null || digests.sha512() == null) {
      return null;
    }
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(current);
    attributes.put("sha512", digests.sha512());
    return attributes;
  }

  private static Map<String, Object> blobAttributes(Digests digests) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("sha512", digests.sha512());
    attributes.put("source", "nexus-repository-data-migration");
    return Map.copyOf(attributes);
  }

  private static Map<String, Object> assetAttributes(
      RepositoryDataMigrationAssetRecord source,
      Digests digests,
      MigratedCargoMetadata cargoMetadata) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("source", "nexus-repository-data-migration");
    putIfPresent(attributes, "sourceAssetId", source.sourceAssetId());
    putIfPresent(attributes, "sourceComponentId", source.sourceComponentId());
    putIfPresent(attributes, "sourceBlobRef", source.sourceBlobRef());
    putIfPresent(attributes, "sourceAssetKind", source.assetKind());
    attributes.put("sha512", digests.sha512());
    putIfPresent(attributes, "sourceAttributes",
        source.metadata() == null ? null : source.metadata().get("attributes"));
    putIfPresent(attributes, "sourceComponentAttributes",
        source.metadata() == null ? null : source.metadata().get("componentAttributes"));
    Map<String, Object> docker = dockerAssetAttributes(source, digests);
    if (!docker.isEmpty()) {
      attributes.put("docker", docker);
    }
    if (cargoMetadata != null) {
      attributes.put("cargo", cargoMetadata.assetAttributes());
      attributes.put("cratePath", source.sourcePath());
    }
    return Map.copyOf(attributes);
  }

  private static Map<String, Object> dockerAssetAttributes(
      RepositoryDataMigrationAssetRecord source,
      Digests digests) {
    if (source.format() != RepositoryFormat.DOCKER) {
      return Map.of();
    }
    LinkedHashMap<String, Object> docker = new LinkedHashMap<>();
    docker.put("kind", dockerAssetKind(source.sourcePath()));
    dockerBlobMigrationTarget(source.sourcePath()).ifPresent(target -> {
      putIfPresent(docker, "imageName", target.imageName());
      docker.put("digest", target.digest().value());
    });
    dockerManifestMigrationTarget(source.sourcePath()).ifPresent(target -> {
      docker.put("imageName", target.imageName());
      docker.put("reference", target.reference());
      if (DockerPathParser.isDigestReference(target.reference())) {
        docker.put("digest", target.reference());
      } else {
        docker.put("rawBytesDigest", "sha256:" + digests.sha256());
      }
    });
    return Map.copyOf(docker);
  }

  private static Map<String, Object> componentAttributes(RepositoryDataMigrationAssetRecord source) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("source", "nexus-repository-data-migration");
    putIfPresent(attributes, "sourceComponentId", source.sourceComponentId());
    if (source.metadata() != null && source.metadata().get("componentAttributes") != null) {
      attributes.put("sourceComponentAttributes", source.metadata().get("componentAttributes"));
    }
    return Map.copyOf(attributes);
  }

  private void enqueueDerivedIndex(RepositoryRecord repository, RepositoryDataMigrationAssetRecord source) {
    if (repository.format() == RepositoryFormat.HELM) {
      indexRebuildDao.enqueue(repository.id(), RepositoryIndexRebuildDao.HELM_INDEX);
      return;
    }
    if (repository.format() == RepositoryFormat.PYPI) {
      indexRebuildDao.enqueue(repository.id(), RepositoryIndexRebuildDao.PYPI_ROOT);
      if (source.name() != null && !source.name().isBlank()) {
        indexRebuildDao.enqueue(repository.id(), RepositoryIndexRebuildDao.PYPI_PROJECT, source.name());
      }
    }
  }

  private static String assetKind(RepositoryFormat format, RepositoryDataMigrationAssetRecord source) {
    return switch (format) {
      case MAVEN2 -> mavenAssetKind(source.sourcePath());
      case NPM -> source.sourcePath().endsWith(".tgz") ? "tarball" : "package-root";
      case PYPI -> "PACKAGE";
      case HELM -> source.sourcePath().endsWith("index.yaml")
          ? "INDEX"
          : source.sourcePath().endsWith(".prov") ? "PROVENANCE" : "PACKAGE";
      case GO -> goAssetKind(source.sourcePath());
      case NUGET -> source.sourcePath().endsWith(".nupkg") ? "PACKAGE" : "ASSET";
      case RUBYGEMS -> source.sourcePath().endsWith(".gem") ? "PACKAGE" : "ASSET";
      case YUM -> source.sourcePath().endsWith(".rpm") ? "PACKAGE" : "METADATA";
      case DOCKER -> dockerAssetKind(source.sourcePath());
      case CARGO -> cargoAssetKind(source.sourcePath());
      case PUB -> source.sourcePath().endsWith(".tar.gz") ? "archive" : "metadata";
      case COMPOSER -> COMPOSER_PATHS.parse(source.sourcePath()).kind() == ComposerPath.Kind.DIST
              ? "composer-dist" : "composer-metadata";
      case TERRAFORM -> "terraform-asset";
      case RAW -> "asset";
    };
  }

  private static String componentKind(RepositoryFormat format, RepositoryDataMigrationAssetRecord source) {
    if (format == RepositoryFormat.MAVEN2) {
      return source.version() != null && source.version().endsWith("-SNAPSHOT") ? "snapshot" : "release";
    }
    return "package";
  }

  private static String mavenAssetKind(String path) {
    if (path == null) {
      return "artifact";
    }
    if (RepositoryDataMigrationPaths.isMavenChecksumPath(path)) {
      return "checksum";
    }
    if (path.endsWith(".pom")) {
      return "pom";
    }
    if (path.endsWith("maven-metadata.xml")) {
      return "metadata";
    }
    return "artifact";
  }

  private static String dockerAssetKind(String path) {
    if (path == null) {
      return "ASSET";
    }
    if (path.contains("/manifests/")) {
      return "MANIFEST";
    }
    if (path.contains("/blobs/")) {
      return "BLOB";
    }
    if (path.endsWith("/tags/list")) {
      return "TAGS";
    }
    return "ASSET";
  }

  private static String cargoAssetKind(String path) {
    if (path == null) {
      return "ASSET";
    }
    if (isCargoCrateAssetPath(path)) {
      return "crate";
    }
    if (path.endsWith("config.json")) {
      return "config";
    }
    return "index";
  }

  private static MigratedCargoMetadata migratedCargoMetadata(
      RepositoryDataMigrationAssetRecord source,
      DigestedUpload upload) {
    if (source == null || !isCargoCrateAssetPath(source.sourcePath())) {
      return null;
    }
    return MigratedCargoMetadata.fromCrate(upload.tempFile());
  }

  private static boolean isCargoCrateAssetPath(String path) {
    List<String> segments = normalizedSegments(path);
    if (segments.size() >= 4
        && "crates".equals(segments.get(0))
        && "download".equals(segments.get(3))) {
      return true;
    }
    return path != null && path.endsWith(".crate");
  }

  private static boolean migratedCargoYanked(RepositoryDataMigrationAssetRecord source) {
    Object componentAttributes = source.metadata() == null ? null : source.metadata().get("componentAttributes");
    if (componentAttributes instanceof Map<?, ?> componentMap) {
      Object entry = componentMap.get("indexEntry");
      if (entry instanceof Map<?, ?> entryMap && entryMap.get("yanked") != null) {
        return bool(entryMap.get("yanked"));
      }
      if (componentMap.get("yanked") != null) {
        return bool(componentMap.get("yanked"));
      }
    }
    Object sourceAttributes = source.metadata() == null ? null : source.metadata().get("attributes");
    if (sourceAttributes instanceof Map<?, ?> attributesMap) {
      Object nested = attributesMap.get("sourceAssetAttributes");
      if (nested instanceof Map<?, ?> nestedMap && nestedMap.get("yanked") != null) {
        return bool(nestedMap.get("yanked"));
      }
      if (attributesMap.get("yanked") != null) {
        return bool(attributesMap.get("yanked"));
      }
    }
    return false;
  }

  private static boolean bool(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  static Optional<DockerManifestMigrationTarget> dockerManifestMigrationTarget(String path) {
    List<String> segments = normalizedSegments(path);
    int manifest = rightSideEndpoint(segments, "manifests", 1);
    if (manifest <= 0) {
      return Optional.empty();
    }
    List<String> imageSegments = dockerImageSegments(segments, manifest);
    if (imageSegments.isEmpty()) {
      return Optional.empty();
    }
    String imageName = String.join("/", imageSegments);
    String reference = segments.get(manifest + 1);
    DockerPathParser.validateImageName(imageName);
    if (!DockerPathParser.isDigestReference(reference)) {
      DockerPathParser.validateTag(reference);
    }
    return Optional.of(new DockerManifestMigrationTarget(imageName, reference));
  }

  static Optional<DockerBlobMigrationTarget> dockerBlobMigrationTarget(String path) {
    List<String> segments = normalizedSegments(path);
    int blob = rightSideEndpoint(segments, "blobs", 1);
    if (blob <= 0) {
      return Optional.empty();
    }
    List<String> imageSegments = dockerImageSegments(segments, blob);
    String imageName = imageSegments.isEmpty() ? null : String.join("/", imageSegments);
    if (imageName != null) {
      DockerPathParser.validateImageName(imageName);
    }
    return Optional.of(new DockerBlobMigrationTarget(imageName, DockerDigest.parse(segments.get(blob + 1))));
  }

  private static int rightSideEndpoint(List<String> segments, String endpoint, int trailingSegments) {
    int index = segments.size() - trailingSegments - 1;
    return index > 0 && endpoint.equals(segments.get(index)) ? index : -1;
  }

  private static List<String> dockerImageSegments(List<String> segments, int endpointIndex) {
    if (endpointIndex <= 0) {
      return List.of();
    }
    int start = "v2".equals(segments.get(0)) ? 1 : 0;
    List<String> imageSegments = segments.subList(start, endpointIndex);
    if (imageSegments.size() == 1 && "-".equals(imageSegments.get(0))) {
      return List.of();
    }
    return imageSegments;
  }

  private static List<String> normalizedSegments(String path) {
    if (path == null || path.isBlank()) {
      return List.of();
    }
    String normalized = path.trim();
    while (normalized.startsWith("/")) normalized = normalized.substring(1);
    while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    if (normalized.isBlank()) {
      return List.of();
    }
    List<String> segments = new ArrayList<>();
    for (String segment : normalized.split("/")) {
      if (!segment.isBlank()) {
        segments.add(URLDecoder.decode(segment, StandardCharsets.UTF_8));
      }
    }
    return List.copyOf(segments);
  }

  private static String goAssetKind(String path) {
    if (path == null) {
      return "PACKAGE";
    }
    if (path.endsWith(".zip")) {
      return "PACKAGE";
    }
    if (path.endsWith(".info")) {
      return "INFO";
    }
    if (path.endsWith(".mod")) {
      return "MODULE";
    }
    if (path.endsWith("/@v/list")) {
      return "LIST";
    }
    return "ASSET";
  }

  private static String fileName(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }
    int slash = path.lastIndexOf('/');
    String name = slash < 0 ? path : path.substring(slash + 1);
    return name.length() <= 512 ? name : name.substring(name.length() - 512);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    return second == null || second.isBlank() ? null : second;
  }

  private static void putIfPresent(Map<String, Object> attributes, String key, Object value) {
    if (value != null) {
      attributes.put(key, value);
    }
  }

  record WriteResult(Long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) {
  }

  record Digests(String md5, String sha1, String sha256, String sha512, long size) {
  }

  record GeneratedMavenChecksum(MavenPath path, byte[] payload) {
  }

  private record ChecksumUpload(
      RepositoryDataMigrationAssetRecord source,
      DigestedUpload upload) {
  }

  private record DigestedUpload(
      BlobReference reference,
      Digests digests,
      Path tempFile,
      boolean uploaded) {
  }

  record DockerManifestMigrationTarget(String imageName, String reference) {
  }

  record DockerBlobMigrationTarget(String imageName, DockerDigest digest) {
  }
}
