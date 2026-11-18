package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import java.util.Optional;

/**
 * Reads the scope values carried by a {@value StagingCredentialService#DOMAIN} API key.
 *
 * <p>The extraction is shared by the credential binder and the authentication service so both
 * enforce the same session binding on every authentication boundary. An absent or malformed scope
 * yields {@link Optional#empty()}; callers must treat that as a fail-closed condition instead of
 * falling back to the live repository.
 */
public final class StagingScopes {
  public static final String SESSION_PREFIX = "staging:session:";

  private StagingScopes() {
  }

  public static boolean isStagingToken(ApiKeyRecord key) {
    return key != null && StagingCredentialService.DOMAIN.equals(key.domain());
  }

  public static Optional<Long> sessionId(ApiKeyRecord key) {
    if (key == null || key.scopes() == null) {
      return Optional.empty();
    }
    Object scopes = key.scopes().get("values");
    if (!(scopes instanceof Iterable<?> values)) {
      return Optional.empty();
    }
    for (Object value : values) {
      if (value == null) {
        continue;
      }
      String text = value.toString();
      if (!text.startsWith(SESSION_PREFIX)) {
        continue;
      }
      try {
        long id = Long.parseLong(text.substring(SESSION_PREFIX.length()));
        return id > 0 ? Optional.of(id) : Optional.empty();
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
