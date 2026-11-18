package com.github.klboke.kkrepo.protocol.ohpm;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.regex.Pattern;

public record OhpmPackageId(String scope, String name) {
  private static final Pattern NAME = Pattern.compile("[a-z](?:[a-z0-9._-]*[a-z0-9])?");
  private static final Pattern SCOPE = Pattern.compile("[a-z](?:[a-z0-9_-]*[a-z0-9])?");

  public OhpmPackageId {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid OHPM package name");
    }
    if (scope != null && !SCOPE.matcher(scope).matches()) {
      throw new IllegalArgumentException("Invalid OHPM package scope");
    }
    if ((scope == null ? name.length() : scope.length() + name.length() + 2) > 128) {
      throw new IllegalArgumentException("OHPM package name exceeds 128 characters");
    }
  }

  public static OhpmPackageId parse(String raw) {
    if (raw == null) throw new IllegalArgumentException("OHPM package name is required");
    return parseDecoded(UriPathDecoder.decodeComponent(raw));
  }

  public static OhpmPackageId parseDecoded(String value) {
    if (value == null) throw new IllegalArgumentException("OHPM package name is required");
    if (value.startsWith("@")) {
      int slash = value.indexOf('/');
      if (slash < 0) throw new IllegalArgumentException("OHPM scoped package must be @scope/name");
      return new OhpmPackageId(value.substring(1, slash), value.substring(slash + 1));
    }
    return new OhpmPackageId(null, value);
  }

  public String id() {
    return scope == null ? name : "@" + scope + "/" + name;
  }

  public String downloadPath(String fileName) {
    return id() + "/-/" + fileName;
  }

  @Override
  public String toString() {
    return id();
  }
}
