package com.github.klboke.kkrepo.protocol.npm;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * npm package identifier. Mirrors Nexus's two supported shapes: {@code name} and
 * {@code @scope/name}. The scope is stored without the leading {@code @}.
 */
public record NpmPackageId(String scope, String name) implements Comparable<NpmPackageId> {
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._~!$&'()*+,;=:@%-]+$");

  public NpmPackageId {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("npm package name is required");
    }
    if (name.startsWith(".") || name.startsWith("_")) {
      throw new IllegalArgumentException("npm package name cannot start with '.' or '_': " + name);
    }
    if (!SAFE.matcher(name).matches()) {
      throw new IllegalArgumentException("npm package name has unsafe characters: " + name);
    }
    if (scope != null) {
      if (scope.isBlank()) {
        throw new IllegalArgumentException("npm package scope cannot be blank");
      }
      if (scope.startsWith(".") || scope.startsWith("_")) {
        throw new IllegalArgumentException("npm package scope cannot start with '.' or '_': " + scope);
      }
      if (!SAFE.matcher(scope).matches()) {
        throw new IllegalArgumentException("npm package scope has unsafe characters: " + scope);
      }
    }
    String id = scope == null ? name : "@" + scope + "/" + name;
    if (id.length() >= 214) {
      throw new IllegalArgumentException("npm package id must be shorter than 214 characters: " + id);
    }
  }

  public String id() {
    return scope == null ? name : "@" + scope + "/" + name;
  }

  public String tarballPath(String tarballName) {
    return id() + "/-/" + tarballName;
  }

  public static NpmPackageId parse(String raw) {
    return parseDecoded(decode(Objects.requireNonNull(raw, "raw")));
  }

  /** Parses an identity already decoded by the request path parser. */
  static NpmPackageId parseDecoded(String raw) {
    String value = raw.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    if (value.startsWith("@")) {
      int slash = value.indexOf('/');
      if (slash < 0) {
        throw new IllegalArgumentException("scoped npm package must be @scope/name: " + raw);
      }
      return new NpmPackageId(value.substring(1, slash), value.substring(slash + 1));
    }
    if (value.contains("/")) {
      throw new IllegalArgumentException("unscoped npm package cannot contain '/': " + raw);
    }
    return new NpmPackageId(null, value);
  }

  static String decode(String value) {
    // npm scoped package identities explicitly allow an encoded scope/name separator.
    return UriPathDecoder.decodeComponent(value);
  }

  @Override
  public String toString() {
    return id();
  }

  @Override
  public int compareTo(NpmPackageId other) {
    return id().compareTo(other.id());
  }
}
