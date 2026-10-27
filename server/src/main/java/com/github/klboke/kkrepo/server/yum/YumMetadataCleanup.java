package com.github.klboke.kkrepo.server.yum;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.YumMetadataCleanupDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryValidationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes only managed repodata during explicit repository deletion. The caller owns a
 * READ_COMMITTED transaction: parent, component and asset locks fence rebuilds and uploads across
 * replicas. Busy rows or unrecognized content refuse deletion and roll back the entire cleanup.
 * Blob references/outbox use the normal DAO path; physical removal belongs to global blob GC.
 */
@Component
public class YumMetadataCleanup {
  public static final String GENERATED_ATTRIBUTE = "yumGeneratedMetadata";
  private static final int BATCH_SIZE = 128;
  private static final Pattern HASHED_METADATA =
      Pattern.compile("([0-9a-f]{64})-(primary|filelists|other)\\.xml\\.gz");
  private static final Pattern LEGACY_REPOMD = legacyRepomdPattern();
  private final YumMetadataCleanupDao locks;
  private final AssetDao assets;
  private final ComponentDao components;
  private final BlobStorageRegistry storage;

  public YumMetadataCleanup(YumMetadataCleanupDao locks, AssetDao assets, ComponentDao components,
      BlobStorageRegistry storage) {
    this.locks = locks;
    this.assets = assets;
    this.components = components;
    this.storage = storage;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void cleanupBeforeRepositoryDeletion(long repositoryId) {
    if (!locks.lockRepository(repositoryId)) throw refused();
    List<Long> batch;
    while (!(batch = locks.lockComponents(repositoryId, BATCH_SIZE)).isEmpty()) {
      for (long componentId : batch) {
        boolean removed = false;
        List<Long> assetIds;
        while (!(assetIds = locks.lockAssets(componentId, BATCH_SIZE)).isEmpty()) {
          for (long assetId : assetIds) {
            AssetRecord asset = assets.findAssetById(assetId).orElseThrow();
            AssetBlobRecord blob = asset.assetBlobId() == null ? null
                : assets.findBlobById(asset.assetBlobId()).orElse(null);
            if (asset.repositoryId() != repositoryId || !isGenerated(asset, blob)) throw refused();
            if (!locks.lockBlob(blob.id())) throw refused();
            assets.deleteAssetById(assetId);
            assets.markBlobDeletedIfUnreferenced(blob.id(), "empty Yum repository deleted");
            removed = true;
          }
        }
        // Preserve unknown empty components and components with assets held by a concurrent writer.
        if (!removed || components.deleteIfNoAssets(componentId) == 0) throw refused();
      }
    }
    // Includes unbound assets and skipped locked rows. A filename alone never makes them disposable.
    if (assets.countAssetsByRepositoryId(repositoryId) != 0) throw refused();
  }

  boolean isGenerated(AssetRecord asset, AssetBlobRecord blob) {
    if (asset.format() != RepositoryFormat.YUM || blob == null) return false;
    String root = YumMetadataDepth.metadataRoot(asset.path());
    if (root == null) return false;
    String name = asset.path().substring((root + "repodata/").length());
    var hashed = HASHED_METADATA.matcher(name);
    boolean repomd = name.equals("repomd.xml");
    if (!repomd && (!hashed.matches() || !hashed.group(1).equals(blob.sha256()))) return false;
    Object generated = asset.attributes().get(GENERATED_ATTRIBUTE);
    if (generated != null) return Boolean.TRUE.equals(generated);
    // Pre-upgrade assets have no per-asset provenance. Fail closed unless both the system audit
    // identity and the legacy generator's filename/content signature agree. Do not infer ownership
    // from blob metadata for new uploads: deduplicated blobs can have a different original author.
    if (!asset.attributes().keySet().equals(java.util.Set.of("path"))
        || !"system".equals(blob.createdBy())) return false;
    if (!repomd) return true;
    if (blob.size() > 16384) return false;
    var reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    try (var input = storage.forBlobStoreId(blob.blobStoreId()).get(reference).orElse(null)) {
      return input != null && LEGACY_REPOMD.matcher(
          new String(input.readNBytes(16385), StandardCharsets.UTF_8)).matches();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static Pattern legacyRepomdPattern() {
    StringBuilder regex = new StringBuilder(Pattern.quote(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<repomd xmlns=\"http://linux.duke.edu/metadata/repo\">\n"));
    for (String type : List.of("primary", "filelists", "other")) {
      regex.append("  <data type=\"").append(type).append("\">\n")
          .append("    <checksum type=\"sha256\">[0-9a-f]{64}</checksum>\n")
          .append("    <open-checksum type=\"sha256\">[0-9a-f]{64}</open-checksum>\n")
          .append("    <location href=\"repodata/[0-9a-f]{64}-").append(type).append("\\.xml\\.gz\"/>\n")
          .append("    <timestamp>[0-9]+</timestamp>\n")
          .append("    <size>[0-9]+</size>\n")
          .append("    <open-size>[0-9]+</open-size>\n  </data>\n");
    }
    return Pattern.compile(regex.append("</repomd>\n").toString());
  }

  private static RepositoryValidationException refused() {
    return new RepositoryValidationException(
        "Yum repository still has user content or a content operation is in progress. "
            + "Empty it and retry deletion.");
  }
}
