package com.github.klboke.kkrepo.server.proxy;

import java.util.Locale;

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
   * Stable identity for the per-proxy {@code HttpClient} cache. Includes a non-revealing hash of the
   * password so a credential rotation produces a fresh client instead of reusing a stale one.
   */
  public String cacheKey() {
    int passwordHash = password == null ? 0 : password.hashCode();
    return type.name()
        + ":" + host.toLowerCase(Locale.ROOT)
        + ":" + port
        + ":" + (username == null ? "" : username)
        + ":" + passwordHash;
  }

  /**
   * Parse a user-supplied proxy type string. Recognises {@code http}/{@code https} and
   * {@code socks}/{@code socks5}/{@code socks4}. Returns {@code null} for blank/unrecognised input
   * so callers can treat "absent" as "no proxy".
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
      case "HTTP", "HTTPS", "HTTP_PROXY", "HTTPS_PROXY" -> Type.HTTP;
      case "SOCKS", "SOCKS5", "SOCKS4", "SOCKS_PROXY" -> Type.SOCKS;
      default -> {
        try {
          yield Type.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
          yield null;
        }
      }
    };
  }
}
