package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord.IdentifierType;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.DecodedAssetId;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the durable one-to-one resolution rules for all externally visible asset IDs. */
@Service
public class AssetPublicIdService {
  private static final int MAX_REGISTRATION_ATTEMPTS = 16;

  private final AssetDao assetDao;
  private final NexusAssetIdCodec idCodec;
  private final SecureRandom random = new SecureRandom();

  public AssetPublicIdService(AssetDao assetDao, NexusAssetIdCodec idCodec) {
    this.assetDao = assetDao;
    this.idCodec = idCodec;
  }

  @Transactional
  public String nativePublicId(String repositoryName, long repositoryId, long assetId) {
    AssetPublicIdentifierRecord existing = assetDao.lockNativePublicIdentifier(assetId).orElse(null);
    if (existing != null) {
      return encodedNative(repositoryName, repositoryId, assetId, existing);
    }

    String candidate = String.format("%032x", assetId);
    for (int attempt = 0; attempt < MAX_REGISTRATION_ATTEMPTS; attempt++) {
      AssetPublicIdentifierRecord record = new AssetPublicIdentifierRecord(
          null,
          repositoryId,
          candidate,
          assetId,
          assetId,
          IdentifierType.KKREPO_NATIVE,
          null,
          null,
          Instant.now());
      if (assetDao.tryInsertPublicIdentifier(record)) {
        return idCodec.encodeAssetId(repositoryName, candidate);
      }
      existing = assetDao.lockNativePublicIdentifier(assetId).orElse(null);
      if (existing != null) {
        return encodedNative(repositoryName, repositoryId, assetId, existing);
      }
      candidate = randomOpaqueId();
    }
    throw new PublicIdConflictException(
        "Could not allocate a unique public identifier for asset " + assetId);
  }

  @Transactional(readOnly = true)
  public Long resolveAssetId(long repositoryId, String opaqueId) {
    return assetDao.findPublicIdentifier(repositoryId, opaqueId)
        .map(AssetPublicIdentifierRecord::assetId)
        .orElse(null);
  }

  @Transactional
  public void registerNexusAlias(
      String encodedNexusId,
      String expectedRepositoryName,
      long repositoryId,
      long assetId,
      String sourceInstance,
      Long migrationJobId) {
    DecodedAssetId decoded = idCodec.decodeAssetId(encodedNexusId);
    if (!expectedRepositoryName.equals(decoded.repositoryName())) {
      throw new PublicIdConflictException(
          "Nexus public asset ID belongs to repository " + decoded.repositoryName()
              + ", expected " + expectedRepositoryName);
    }

    nativePublicId(expectedRepositoryName, repositoryId, assetId);

    AssetPublicIdentifierRecord existing = assetDao
        .lockPublicIdentifier(repositoryId, decoded.opaqueId())
        .orElse(null);
    if (existing != null) {
      requireSameAsset(existing, assetId, encodedNexusId);
      return;
    }

    AssetPublicIdentifierRecord alias = new AssetPublicIdentifierRecord(
        null,
        repositoryId,
        decoded.opaqueId(),
        assetId,
        null,
        IdentifierType.NEXUS_ALIAS,
        sourceInstance,
        migrationJobId,
        Instant.now());
    if (assetDao.tryInsertPublicIdentifier(alias)) {
      return;
    }
    existing = assetDao.lockPublicIdentifier(repositoryId, decoded.opaqueId())
        .orElseThrow(() -> new PublicIdConflictException(
            "Nexus public asset ID conflicted but the winning registration was not visible"));
    requireSameAsset(existing, assetId, encodedNexusId);
  }

  private String encodedNative(
      String repositoryName,
      long repositoryId,
      long assetId,
      AssetPublicIdentifierRecord existing) {
    if (existing.repositoryId() != repositoryId
        || existing.assetId() == null
        || existing.assetId() != assetId
        || existing.identifierType() != IdentifierType.KKREPO_NATIVE) {
      throw new PublicIdConflictException(
          "Invalid native public identifier registration for asset " + assetId);
    }
    return idCodec.encodeAssetId(repositoryName, existing.opaqueId());
  }

  private static void requireSameAsset(
      AssetPublicIdentifierRecord existing, long assetId, String encodedNexusId) {
    if (existing.assetId() == null || existing.assetId() != assetId) {
      throw new PublicIdConflictException(
          "Public asset ID is already registered to another asset or a deletion tombstone: "
              + encodedNexusId);
    }
  }

  private String randomOpaqueId() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  public static final class PublicIdConflictException extends RuntimeException {
    public PublicIdConflictException(String message) {
      super(message);
    }
  }
}
