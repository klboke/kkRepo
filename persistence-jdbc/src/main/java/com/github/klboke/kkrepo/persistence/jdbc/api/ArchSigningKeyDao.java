package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable, encrypted OpenPGP signing identities for pacman repositories. */
public interface ArchSigningKeyDao {
  Optional<SigningKey> findActiveSigningKey(long repositoryId);

  Optional<SigningKey> findSigningKey(long repositoryId, int revision);

  /** Returns newest key revisions first; retained public keys survive an orderly rotation. */
  List<SigningKey> listSigningKeys(long repositoryId, int limit);

  /**
   * Locks the repository row and returns the next revision. Rotation on multiple replicas must
   * use one serialized revision allocation; a read-then-insert without the lock races on the
   * (repository_id, revision) primary key.
   */
  int nextRevisionLocked(long repositoryId);

  void insertSigningKey(SigningKey key);

  record SigningKey(
      long repositoryId,
      int revision,
      String keyId,
      String fingerprint,
      String encryptedPrivateKey,
      String publicKey,
      boolean active,
      Instant createdAt) {
  }
}
