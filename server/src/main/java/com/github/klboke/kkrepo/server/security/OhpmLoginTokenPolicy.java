package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmLoginDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OhpmLoginTokenPolicy {
  public static final String DOMAIN = "OhpmLogin";
  private final OhpmLoginDao keys;
  private final boolean enabled;

  public OhpmLoginTokenPolicy(OhpmLoginDao keys, @Value("${kkrepo.ohpm.login.enabled:false}") boolean enabled) {
    this.keys = keys;
    this.enabled = enabled;
  }

  public boolean valid(ApiKeyRecord token) {
    if (!enabled || !DOMAIN.equals(token.domain()) || !"ACTIVE".equalsIgnoreCase(token.status()) || token.expiresAt() == null
        || !token.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))
        || token.scopes() == null
        || !(token.scopes().get("ohpmPublishId") instanceof String publishId)
        || !(token.scopes().get("ohpmFingerprint") instanceof String fingerprint)) return false;
    return keys.findKey(publishId).filter(key -> key.enabled() && "ACTIVE".equalsIgnoreCase(key.userStatus())
        && key.source().equals(token.ownerSource()) && key.userId().equals(token.ownerUserId())
        && fingerprint.equals(key.fingerprint())).isPresent();
  }
}
