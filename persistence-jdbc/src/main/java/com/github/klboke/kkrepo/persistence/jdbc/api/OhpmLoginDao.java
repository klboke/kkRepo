package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OhpmLoginDao {
  record Key(String publishId, long userNumericId, String source, String userId, String userStatus,
      String publicKey, String fingerprint, boolean enabled, Instant updatedAt) {}

  Key putKey(String source, String userId, String publicKey, String fingerprint, boolean enabled);

  Optional<Key> findKey(String publishId);

  Optional<Key> lockKey(String publishId);

  List<Key> listKeys(int offset, int limit);

  boolean deleteKey(String publishId);

  boolean recordNonce(String publishId, String nonce, Instant expiresAt);

  int deleteExpiredNonces(Instant now);
}
