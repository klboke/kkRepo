package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;

/** Durable registration of an externally visible Nexus-compatible asset identifier. */
public record AssetPublicIdentifierRecord(
    Long id,
    long repositoryId,
    String opaqueId,
    Long assetId,
    Long nativeAssetId,
    IdentifierType identifierType,
    String sourceInstance,
    Long migrationJobId,
    Instant createdAt) {

  public enum IdentifierType {
    KKREPO_NATIVE,
    NEXUS_ALIAS
  }
}
