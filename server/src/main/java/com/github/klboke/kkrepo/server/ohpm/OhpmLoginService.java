package com.github.klboke.kkrepo.server.ohpm;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.security.ApiKeyTokenPayloads;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmLoginDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmLoginRequest;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.OhpmLoginTokenPolicy;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OhpmLoginService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final OhpmLoginDao keys;
  private final SecurityDao security;
  private final OhpmLoginTokenPolicy tokens;
  private final TransientTransactionRetry transactions;
  private final boolean enabled;
  private final Duration tolerance;
  private final Duration tokenTtl;

  public OhpmLoginService(OhpmLoginDao keys, SecurityDao security, OhpmLoginTokenPolicy tokens,
      TransientTransactionRetry transactions, @Value("${kkrepo.ohpm.login.enabled:false}") boolean enabled,
      @Value("${kkrepo.ohpm.login.timestamp-tolerance-seconds:300}") long toleranceSeconds,
      @Value("${kkrepo.ohpm.login.token-ttl-seconds:300}") long tokenTtlSeconds) {
    if (toleranceSeconds < 1 || toleranceSeconds > 600 || tokenTtlSeconds < 1 || tokenTtlSeconds > 3600) {
      throw new IllegalArgumentException("OHPM login tolerance must be 1–600 seconds and token TTL 1–3600 seconds");
    }
    this.keys = keys;
    this.security = security;
    this.tokens = tokens;
    this.transactions = transactions;
    this.enabled = enabled;
    this.tolerance = Duration.ofSeconds(toleranceSeconds);
    this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
  }

  public boolean enabled() {
    return enabled;
  }

  public Configuration configuration() {
    return new Configuration(enabled, tolerance.toSeconds(), tokenTtl.toSeconds());
  }

  public KeyView putKey(String source, String userId, String pem, boolean active, Runnable authorize) {
    return configureKey(null, source, userId, pem, active, authorize);
  }

  public KeyView rotateKey(String publishId, String pem, Runnable authorize) {
    return configureKey(publishId, null, null, pem, false, authorize);
  }

  private KeyView configureKey(String publishId, String source, String userId, String pem,
      boolean active, Runnable authorize) {
    RSAPublicKey publicKey = publicKey(pem);
    String normalized = "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(publicKey.getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
    String fingerprint = hash(publicKey.getEncoded());
    return transactions.execute("Configure OHPM signing key", () -> {
      authorize.run();
      var existing = publishId == null ? null : keys.lockKey(publishId)
          .orElseThrow(() -> new OhpmException(404, "OHPM signing key is missing"));
      var key = existing == null
          ? keys.putKey(source, userId, normalized, fingerprint, active)
          : keys.putKey(existing.source(), existing.userId(), normalized, fingerprint, existing.enabled());
      security.deleteApiKey(OhpmLoginTokenPolicy.DOMAIN, key.source(), key.userId());
      return view(key);
    });
  }

  public boolean deleteKey(String publishId, Runnable authorize) {
    return transactions.execute("Remove OHPM signing key", () -> {
      authorize.run();
      var key = keys.lockKey(publishId).orElseThrow(() -> new OhpmException(404, "OHPM signing key is missing"));
      security.deleteApiKey(OhpmLoginTokenPolicy.DOMAIN, key.source(), key.userId());
      return keys.deleteKey(publishId);
    });
  }

  public List<KeyView> keys(int offset) {
    return keys.listKeys(offset, 100).stream().map(OhpmLoginService::view).toList();
  }

  public KeyView setEnabled(String publishId, boolean active, Runnable authorize) {
    return transactions.execute("Change OHPM signing key status", () -> {
      authorize.run();
      var key = keys.lockKey(publishId).orElseThrow(() -> new OhpmException(404, "OHPM signing key is missing"));
      var updated = keys.putKey(key.source(), key.userId(), key.publicKey(), key.fingerprint(), active);
      security.deleteApiKey(OhpmLoginTokenPolicy.DOMAIN, key.source(), key.userId());
      return view(updated);
    });
  }

  public Login login(OhpmLoginRequest request) {
    if (!enabled) throw new OhpmException(404, "OHPM Login is disabled");
    if (!request.timely(Instant.now(), tolerance)) throw denied("The timestamp is expired");
    return transactions.execute("Authenticate OHPM signature", () -> {
      var key = keys.lockKey(request.publishId()).filter(value -> value.enabled()
          && "ACTIVE".equalsIgnoreCase(value.userStatus())).orElseThrow(() -> denied("Invalid signing identity"));
      if (!request.timely(Instant.now(), tolerance)) throw denied("The timestamp is expired");
      if (!request.verify(publicKey(key.publicKey()))) throw denied("Invalid signature");
      Instant deadline = Instant.ofEpochMilli(request.timestamp()).plus(tolerance).plusSeconds(1);
      if (!keys.recordNonce(key.publishId(), request.nonce().toLowerCase(Locale.ROOT), deadline)) {
        throw denied("Nonce has already been used");
      }
      var existing = security.findApiKey(OhpmLoginTokenPolicy.DOMAIN, key.source(), key.userId())
          .filter(tokens::valid);
      String material = existing.flatMap(token -> ApiKeyTokenPayloads.decryptRawToken(token.encryptedPayload())
          .filter(raw -> token.apiKeyHash().equals(hash(raw)))).orElse(null);
      if (material == null) {
        security.deleteApiKey(OhpmLoginTokenPolicy.DOMAIN, key.source(), key.userId());
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        material = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        security.upsertApiKey(new ApiKeyRecord(null, OhpmLoginTokenPolicy.DOMAIN, key.source(), key.userId(),
            "OHPM signed login", "ACTIVE", hash(material), OhpmLoginTokenPolicy.DOMAIN,
            Map.of("ohpmPublishId", key.publishId(), "ohpmFingerprint", key.fingerprint()),
            ApiKeyTokenPayloads.encryptRawToken(material), null, null,
            LocalDateTime.ofInstant(Instant.now().plus(tokenTtl), ZoneOffset.UTC), null));
      }
      ApiKeyRecord token = security.findApiKey(OhpmLoginTokenPolicy.DOMAIN, key.source(), key.userId()).orElseThrow();
      var subject = new AuthenticatedSubject(key.source(), key.userId(), "ohpm-login", token.id(),
          new PermissionSubject(key.source(), key.userId(), Set.copyOf(security.listUserRoleIds(key.userNumericId())), null));
      return new Login(OhpmLoginTokenPolicy.DOMAIN + "." + material, subject);
    });
  }

  @Scheduled(fixedDelayString = "${kkrepo.ohpm.login.nonce-cleanup-ms:60000}",
      initialDelayString = "${kkrepo.ohpm.login.nonce-cleanup-ms:60000}")
  public void cleanup() {
    keys.deleteExpiredNonces(Instant.now());
  }

  static RSAPublicKey publicKey(String pem) {
    if (pem == null || pem.length() > 16384 || !pem.strip().startsWith("-----BEGIN PUBLIC KEY-----")
        || !pem.strip().endsWith("-----END PUBLIC KEY-----")) {
      throw new IllegalArgumentException("Provide an X.509 PEM RSA public key");
    }
    try {
      String encoded = pem.strip().replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
          .replaceAll("\\s", "");
      var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
      if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048
          || rsa.getModulus().bitLength() > 8192) throw new IllegalArgumentException("RSA key must be 2048–8192 bits");
      return rsa;
    } catch (GeneralSecurityException invalid) {
      throw new IllegalArgumentException("Invalid RSA public key", invalid);
    }
  }

  private static String hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static KeyView view(OhpmLoginDao.Key key) {
    return new KeyView(key.publishId(), key.source(), key.userId(), key.fingerprint(), key.enabled(), key.updatedAt());
  }

  private static OhpmException denied(String message) {
    return new OhpmException(401, message);
  }

  public record Configuration(boolean enabled, long timestampToleranceSeconds, long tokenTtlSeconds) {}
  public record KeyView(String publishId, String source, String userId, String fingerprint, boolean enabled, Instant updatedAt) {}
  public record Login(String token, AuthenticatedSubject subject) {}
}
