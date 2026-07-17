package com.github.klboke.kkrepo.protocol.swift;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Canonical relative paths below {@code /repository/{repo}/}. */
public final class SwiftPaths {
  public static final String LOGIN = "login";
  public static final String IDENTIFIERS = "identifiers";

  private SwiftPaths() {
  }

  public static String releases(String scope, String name) {
    return segment(SwiftScope.require(scope)) + "/" + segment(SwiftPackageName.require(name));
  }

  public static String release(String scope, String name, String version) {
    return releases(scope, name) + "/" + segment(SwiftVersions.require(version));
  }

  public static String manifest(String scope, String name, String version) {
    return release(scope, name, version) + "/Package.swift";
  }

  public static String manifest(String scope, String name, String version, String swiftVersion) {
    return manifest(scope, name, version)
        + "?swift-version=" + segment(SwiftToolsVersions.require(swiftVersion));
  }

  public static String sourceArchive(String scope, String name, String version) {
    return release(scope, name, version) + ".zip";
  }

  public static String identifierLookup(String repositoryUrl) {
    if (repositoryUrl == null || repositoryUrl.isBlank()) {
      throw new IllegalArgumentException("Swift repository URL must not be blank");
    }
    return IDENTIFIERS + "?url=" + query(repositoryUrl);
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
