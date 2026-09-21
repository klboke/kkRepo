package com.github.klboke.kkrepo.server.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Immutable upstream credentials, carried only by request snapshots; never stored in shared caches. */
public record NtlmCredentials(String username, String password, String domain, String workstation) {
  public NtlmCredentials {
    username = username == null ? "" : username.trim();
    domain = domain == null ? "" : domain.trim();
    workstation = workstation == null ? "" : workstation.trim();
    int separator = username.indexOf('\\');
    if (separator >= 0) {
      if (domain.isEmpty()) domain = username.substring(0, separator);
      username = username.substring(separator + 1);
    }
    password = password == null ? "" : password;
  }

  public static NtlmCredentials fromAttributes(Map<?, ?> attributes) {
    if (!"ntlm".equals(attributes.get("remoteAuthenticationType"))) return null;
    return new NtlmCredentials(value(attributes, "remoteUsername"), value(attributes, "remotePassword"),
        value(attributes, "remoteNtlmDomain"), value(attributes, "remoteNtlmHost"));
  }

  private static String value(Map<?, ?> attributes, String key) {
    Object value = attributes.get(key);
    return value == null ? null : value.toString();
  }

  /** Length prefixes prevent ambiguous identities; rotating any credential creates a new pool. */
  public String cacheKey() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String field : new String[] {username, password, domain, workstation}) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return "NtlmCredentials[redacted]";
  }
}
