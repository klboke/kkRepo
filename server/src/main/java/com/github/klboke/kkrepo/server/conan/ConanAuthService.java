package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Repository-scoped Conan bearer exchange backed by durable, hashed tokens. */
@Service
public class ConanAuthService {
  private static final String PREFIX = "kkrepo_conan_";
  private final ConanRegistryDao registry;
  private final SecurityAuthenticationService authentication;
  private final SecureRandom random = new SecureRandom();
  private final Duration ttl;

  ConanAuthService(
      ConanRegistryDao registry,
      SecurityAuthenticationService authentication,
      @Value("${kkrepo.conan.auth-token-ttl-minutes:60}") long ttlMinutes) {
    this.registry = registry;
    this.authentication = authentication;
    this.ttl = Duration.ofMinutes(Math.max(1, Math.min(1440, ttlMinutes)));
  }

  public String issue(long repositoryId, AuthenticatedSubject subject) {
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw new ConanExceptions.Unauthorized("Logged user needed!");
    }
    byte[] entropy = new byte[32];
    random.nextBytes(entropy);
    String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    Instant now = Instant.now();
    registry.insertAuthToken(new ConanRegistryDao.AuthToken(
        hash(token), repositoryId, subject.source(), subject.userId(), subject.realmId(),
        subject.apiKeyId(), now.plus(ttl), null, now));
    return token;
  }

  public boolean hasConanBearer(HttpServletRequest request) {
    return token(request).isPresent();
  }

  public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request, long repositoryId) {
    Optional<String> token = token(request);
    if (token.isEmpty()) return Optional.empty();
    Instant now = Instant.now();
    return registry.findValidAuthToken(hash(token.orElseThrow()), repositoryId, now)
        .flatMap(row -> authentication.authenticateStoredSubject(
                row.subjectSource(), row.subjectUserId(), row.realmId(), row.apiKeyId())
            .map(subject -> {
              registry.touchAuthToken(row.tokenHash(), now);
              return subject;
            }));
  }

  private static Optional<String> token(HttpServletRequest request) {
    if (request == null) return Optional.empty();
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String token = authorization.substring(7).trim();
    if (!token.startsWith(PREFIX) || token.length() > 256
        || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
      return Optional.empty();
    }
    return Optional.of(token);
  }

  private static String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
