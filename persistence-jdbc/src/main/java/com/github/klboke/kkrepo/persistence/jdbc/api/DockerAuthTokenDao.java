package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DockerAuthTokenDao {
  void insert(
      String tokenHash,
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      TokenKind tokenKind,
      List<Map<String, Object>> scopes,
      Instant expiresAt);

  Optional<TokenRecord> findValid(String tokenHash, Instant now);

  /**
   * Persists the immutable manifest/blob allowlist for one scanner token.
   *
   * <p>The token row and resources must be inserted in one transaction. A primary key over
   * token, kind, and digest makes every subsequent OCI request an indexed lookup instead of a
   * repeated manifest-graph traversal.
   */
  void insertScannerResources(String tokenHash, List<ScannerTokenResource> resources);

  boolean scannerResourceAllowed(
      String tokenHash, ScannerResourceKind resourceKind, String digest);

  /**
   * Claims and deletes at most {@code maxItems} expired tokens.
   *
   * <p>The claim must skip rows locked by another replica so scheduled cleanup can run safely on
   * every node.
   */
  int deleteExpired(Instant now, int maxItems);

  record TokenRecord(
      String tokenHash,
      String subjectSource,
      String subjectUserId,
      String subjectRealmId,
      Long subjectApiKeyId,
      TokenKind tokenKind,
      Map<String, Object> scopes,
      Instant expiresAt) {
  }

  enum TokenKind {
    USER,
    SECURITY_SCANNER
  }

  enum ScannerResourceKind {
    MANIFEST,
    BLOB
  }

  record ScannerTokenResource(ScannerResourceKind resourceKind, String digest) {
  }
}
