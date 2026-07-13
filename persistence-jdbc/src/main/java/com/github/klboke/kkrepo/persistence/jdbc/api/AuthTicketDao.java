package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.Optional;

public interface AuthTicketDao {
  void insert(String tokenHash, String payload, Instant expiresAt);

  Optional<String> consume(String tokenHash, Instant now);

  int deleteExpired(Instant now);
}
