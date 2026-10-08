package com.github.klboke.kkrepo.server.security;

/** Repository-owned content redirect policy; never grants address or credential trust. */
public enum ProxyRedirectPolicy {
  ALLOWLIST,
  PUBLIC_HTTPS;

  public static ProxyRedirectPolicy parse(Object value) {
    if (value == null) return ALLOWLIST;
    return switch (value.toString()) {
      case "ALLOWLIST" -> ALLOWLIST;
      case "PUBLIC_HTTPS" -> PUBLIC_HTTPS;
      default -> throw new IllegalArgumentException("proxy.redirectPolicy must be ALLOWLIST or PUBLIC_HTTPS");
    };
  }
}
