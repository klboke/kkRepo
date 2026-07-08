package com.github.klboke.kkrepo.protocol.pub;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class PubPaths {
  private PubPaths() {
  }

  public static String metadataPath(String packageName) {
    return "api/packages/" + PubPackageName.require(packageName);
  }

  public static String versionPath(String packageName, String version) {
    return metadataPath(packageName) + "/versions/" + PubVersions.require(version);
  }

  public static String apiArchivePath(String packageName, String version) {
    String name = PubPackageName.require(packageName);
    String safeVersion = PubVersions.require(version);
    return "api/archives/" + segment(name + "-" + safeVersion + ".tar.gz");
  }

  public static String versionJsonPath(String packageName, String version) {
    String name = PubPackageName.require(packageName);
    String safeVersion = PubVersions.require(version);
    return name + "/" + safeVersion + "/version.json";
  }

  public static String nexusArchivePath(String packageName, String version) {
    String name = PubPackageName.require(packageName);
    String safeVersion = PubVersions.require(version);
    return name + "/" + safeVersion + "/" + name + "-" + safeVersion + ".tar.gz";
  }

  public static String archivePath(String packageName, String version) {
    String name = PubPackageName.require(packageName);
    String safeVersion = PubVersions.require(version);
    return "packages/" + name + "/versions/" + safeVersion + ".tar.gz";
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }
}
