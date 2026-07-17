package com.github.klboke.kkrepo.server.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

/**
 * Per-repository outbound network proxy configuration for proxy-type repositories.
 *
 * <p>Backs the "route upstream fetches through a corporate Clash / HTTP / SOCKS proxy" feature.
 * Mirrors the manual-proxy UI found in IDEs: type (HTTP or SOCKS), host, port, and optional
 * credentials. Stored inside the repository {@code attributes.proxy} JSON sub-map; the password is
 * encrypted at the DAO boundary alongside {@code remotePassword}.
 *
 * <p>This object is an immutable value carried by {@code RepositoryRuntime}. Instances are not
 * cached on their own — the {@link ProxiedHttpClientFactory} caches the heavyweight HTTP client
 * keyed by {@link #cacheKey()}, and {@code RepositoryRuntime} snapshots carrying a proxy password
 * are excluded from the shared runtime cache (see {@code RepositoryRuntimeRegistry}).
 */
public record OutboundProxyConfig(Type type, String host, int port, String username, String password) {

  public enum Type {
    HTTP,
    SOCKS
  }

  public OutboundProxyConfig {
    type = type == null ? Type.HTTP : type;
    host = host == null ? "" : host.trim();
    username = (username == null || username.isBlank()) ? null : username.trim();
    password = (password == null || password.isBlank()) ? null : password;
  }

  /** A config is "enabled" when it has a usable host and port. Type always defaults to HTTP. */
  public boolean enabled() {
    return host != null && !host.isBlank() && port > 0 && port <= 65535;
  }

  public boolean authenticated() {
    return enabled() && username != null;
  }

  /**
   * Stable identity for the per-proxy {@code HttpClient} cache. Includes a non-revealing,
   * collision-resistant SHA-256 digest of the password so a credential rotation produces a fresh
   * client and two different passwords can never share a cached client the way a 32-bit
   * {@code String.hashCode()} collision would allow.
   */
  public String cacheKey() {
    return type.name()
        + ":" + host.toLowerCase(Locale.ROOT)
        + ":" + port
        + ":" + (username == null ? "" : username)
        + ":" + (password == null ? "-" : sha256Hex(password));
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JRE", e);
    }
  }

  /**
   * Reads the outbound proxy block from a repository's {@code attributes.proxy} map. Returns
   * {@code null} when no usable host/port is stored, so "no proxy" and "incomplete proxy" both
   * collapse to absent. Shared by the runtime registry and by {@code RepositoryService}, which
   * needs the previous config to evict its cached client on update/delete.
   */
  public static OutboundProxyConfig fromAttributes(Map<?, ?> proxyMap) {
    if (proxyMap == null) {
      return null;
    }
    Object typeRaw = proxyMap.get("outboundProxyType");
    Type type = parseType(typeRaw == null ? null : typeRaw.toString());
    Object hostRaw = proxyMap.get("outboundProxyHost");
    String host = hostRaw == null ? null : hostRaw.toString();
    Integer port = asInt(proxyMap.get("outboundProxyPort"));
    Object userRaw = proxyMap.get("outboundProxyUsername");
    String username = userRaw == null || userRaw.toString().isBlank() ? null : userRaw.toString();
    Object passRaw = proxyMap.get("outboundProxyPassword");
    String password = passRaw == null || passRaw.toString().isBlank() ? null : passRaw.toString();
    if (host == null || host.isBlank() || port == null || port <= 0) {
      return null;
    }
    return new OutboundProxyConfig(type, host.trim(), port, username, password);
  }

  private static Integer asInt(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Parse a user-supplied proxy type string. Only the wire protocols the client factory actually
   * implements are recognised: plaintext HTTP ({@code http}) and SOCKS5 ({@code socks}/
   * {@code socks5}). Aliases for unimplemented protocols (HTTPS proxies, SOCKS4) return
   * {@code null} — as does blank/unrecognised input — so validators reject them instead of
   * silently executing the handshake with a different protocol than configured.
   */
  public static Type parseType(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    return switch (normalized) {
      case "HTTP" -> Type.HTTP;
      case "SOCKS", "SOCKS5" -> Type.SOCKS;
      default -> null;
    };
  }
}
