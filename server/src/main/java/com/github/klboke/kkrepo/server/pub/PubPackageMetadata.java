package com.github.klboke.kkrepo.server.pub;

import com.github.klboke.kkrepo.protocol.pub.PubPackageName;
import com.github.klboke.kkrepo.protocol.pub.PubVersions;
import java.util.LinkedHashMap;
import java.util.Map;

record PubPackageMetadata(
    String packageName,
    String version,
    Map<String, Object> pubspec) {

  PubPackageMetadata {
    packageName = PubPackageName.require(packageName);
    version = PubVersions.require(version);
    pubspec = pubspec == null ? Map.of() : Map.copyOf(pubspec);
  }

  static PubPackageMetadata fromPubspec(Map<String, Object> pubspec) {
    if (pubspec == null || pubspec.isEmpty()) {
      throw new PubExceptions.BadRequestException("pubspec.yaml is empty");
    }
    Object name = pubspec.get("name");
    Object version = pubspec.get("version");
    if (name == null || version == null) {
      throw new PubExceptions.BadRequestException("pubspec.yaml must contain name and version");
    }
    return new PubPackageMetadata(String.valueOf(name), String.valueOf(version), normalizePubspec(pubspec));
  }

  private static Map<String, Object> normalizePubspec(Map<String, Object> pubspec) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    pubspec.forEach((key, value) -> {
      if (key != null && value != null) {
        normalized.put(key, value);
      }
    });
    normalized.put("name", PubPackageName.require(String.valueOf(normalized.get("name"))));
    normalized.put("version", PubVersions.require(String.valueOf(normalized.get("version"))));
    return normalized;
  }
}
