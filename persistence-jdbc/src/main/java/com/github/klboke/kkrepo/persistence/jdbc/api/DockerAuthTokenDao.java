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

  /**
   * Same as {@link #insert}, but records the staging session an issued bearer is restricted to.
   *
   * <p>The restriction travels with the bearer row instead of the API key: closing, cancelling or
   * rotating the staging session deletes the API key, and a bearer minted before that deletion must
   * keep publishing into the session (or fail) rather than falling back to live content.
   */
  void insert(
      String tokenHash,
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      TokenKind tokenKind,
      List<Map<String, Object>> scopes,
      String stagingSession,
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
      Instant expiresAt,
      String stagingSession) {

    public TokenRecord(
        String tokenHash,
        String subjectSource,
        String subjectUserId,
        String subjectRealmId,
        Long subjectApiKeyId,
        TokenKind tokenKind,
        Map<String, Object> scopes,
        Instant expiresAt) {
      this(tokenHash, subjectSource, subjectUserId, subjectRealmId, subjectApiKeyId,
          tokenKind, scopes, expiresAt, null);
    }
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
