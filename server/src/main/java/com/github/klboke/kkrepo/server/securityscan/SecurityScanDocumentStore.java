package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Stores immutable SBOM and report documents as non-browsable blob rows. */
@Service
public class SecurityScanDocumentStore {
  private static final HexFormat HEX = HexFormat.of();

  private final AssetDao assets;
  private final RepositoryDao repositories;
  private final BlobStorageRegistry storages;

  public SecurityScanDocumentStore(
      AssetDao assets, RepositoryDao repositories, BlobStorageRegistry storages) {
    this.assets = assets;
    this.repositories = repositories;
    this.storages = storages;
  }

  public StoredDocument store(
      long repositoryId, String documentKind, byte[] bytes, String contentType) {
    if (bytes == null) throw new IllegalArgumentException("document bytes are required");
    RepositoryRecord repository = repositories.findById(repositoryId)
        .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId));
    if (repository.blobStoreId() == null) {
      throw new IllegalStateException("Repository has no blob store: " + repositoryId);
    }
    String sha256 = digest("SHA-256", bytes);
    var reusable = assets.findReusableBlobBySha256(
        repository.blobStoreId(), sha256, bytes.length);
    if (reusable.isPresent()) {
      return new StoredDocument(reusable.get().id(), sha256, bytes.length);
    }

    String safeKind = documentKind == null
        ? "document" : documentKind.replaceAll("[^a-zA-Z0-9._-]", "_");
    String logicalPath = ".kkrepo/security-scan/" + safeKind + "/" + sha256 + ".json";
    BlobStorage storage = storages.forBlobStoreId(repository.blobStoreId());
    BlobReference reference = storage.put(
        repository.name(),
        logicalPath,
        new ByteArrayInputStream(bytes),
        bytes.length,
        sha256);
    try {
      Instant now = Instant.now();
      String blobRef = BlobReferenceCodec.format(reference);
      AssetBlobRecord stored = assets.insertBlobOrFindExisting(new AssetBlobRecord(
          null,
          repository.blobStoreId(),
          blobRef,
          PersistenceHashes.blobRefHash(blobRef),
          reference.objectKey(),
          PersistenceHashes.objectKeyHash(reference.objectKey()),
          digest("SHA-1", bytes),
          sha256,
          digest("MD5", bytes),
          bytes.length,
          contentType,
          "security-scanner",
          null,
          now,
          now,
          Map.of("securityScanDocument", true, "documentKind", safeKind)));
      return new StoredDocument(stored.id(), sha256, bytes.length);
    } catch (RuntimeException e) {
      BlobTransactionCleanup.deleteIfUnreferenced(
          assets,
          storage,
          repository.blobStoreId(),
          reference,
          "security scan document persist failure");
      throw e;
    }
  }

  public InputStream open(long blobId) throws IOException {
    AssetBlobRecord blob = assets.findBlobById(blobId)
        .orElseThrow(() -> new IOException("Security scan document blob not found"));
    BlobStorage storage = storages.forBlobStoreId(blob.blobStoreId());
    BlobReference reference =
        BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    return storage.get(reference)
        .orElseThrow(() -> new IOException("Security scan document object not found"));
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is unavailable", e);
    }
  }

  public record StoredDocument(long blobId, String sha256, long size) {}
}
