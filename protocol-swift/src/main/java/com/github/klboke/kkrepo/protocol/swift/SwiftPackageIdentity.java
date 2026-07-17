package com.github.klboke.kkrepo.protocol.swift;

/** A display-cased Swift package identity with a stable case-insensitive storage key. */
public record SwiftPackageIdentity(String scope, String name) {
  public SwiftPackageIdentity {
    scope = SwiftScope.require(scope);
    name = SwiftPackageName.require(name);
  }

  public static SwiftPackageIdentity parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Invalid Swift package identity: null");
    }
    int separator = value.indexOf('.');
    if (separator <= 0 || separator != value.lastIndexOf('.') || separator == value.length() - 1) {
      throw new IllegalArgumentException("Invalid Swift package identity: " + value);
    }
    return new SwiftPackageIdentity(value.substring(0, separator), value.substring(separator + 1));
  }

  public String value() {
    return scope + "." + name;
  }

  public String key() {
    return SwiftScope.key(scope) + "." + SwiftPackageName.key(name);
  }
}
