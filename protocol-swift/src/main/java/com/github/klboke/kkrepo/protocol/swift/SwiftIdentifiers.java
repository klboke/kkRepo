package com.github.klboke.kkrepo.protocol.swift;

import java.util.LinkedHashMap;
import java.util.List;

public record SwiftIdentifiers(List<String> identifiers) {
  public SwiftIdentifiers {
    LinkedHashMap<String, String> deduplicated = new LinkedHashMap<>();
    if (identifiers != null) {
      identifiers.forEach(value -> {
        SwiftPackageIdentity identity = SwiftPackageIdentity.parse(value);
        deduplicated.putIfAbsent(identity.key(), identity.value());
      });
    }
    identifiers = List.copyOf(deduplicated.values());
  }
}
