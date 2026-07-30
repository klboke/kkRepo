package com.github.klboke.kkrepo.security.scan;

import java.util.Locale;

/**
 * Closed set of file identities that may influence a scanner workspace filename.
 *
 * <p>The wire value selects an enum constant only. Untrusted request text is never appended to a
 * path or command argument.
 */
public enum ScannerArtifactType {
  UNKNOWN("", "artifact"),
  TAR_GZ(".tar.gz", "artifact.tar.gz"),
  TAR_BZ2(".tar.bz2", "artifact.tar.bz2"),
  TAR_XZ(".tar.xz", "artifact.tar.xz"),
  TAR_ZST(".tar.zst", "artifact.tar.zst"),
  XZ(".xz", "artifact.tar.xz"),
  ZIP(".zip", "artifact.zip"),
  TAR(".tar", "artifact.tar"),
  TGZ(".tgz", "artifact.tgz"),
  TBZ2(".tbz2", "artifact.tbz2"),
  TXZ(".txz", "artifact.txz"),
  TZST(".tzst", "artifact.tzst"),
  JAR(".jar", "artifact.jar"),
  WAR(".war", "artifact.war"),
  EAR(".ear", "artifact.ear"),
  AAR(".aar", "artifact.aar"),
  WHL(".whl", "artifact.whl"),
  EGG(".egg", "artifact.egg"),
  CRATE(".crate", "artifact.crate"),
  GEM(".gem", "artifact.gem"),
  NUPKG(".nupkg", "artifact.nupkg"),
  RPM(".rpm", "artifact.rpm"),
  DEB(".deb", "artifact.deb"),
  APK(".apk", "artifact.apk"),
  IPA(".ipa", "artifact.ipa");

  private final String suffix;
  private final String safeFilename;

  ScannerArtifactType(String suffix, String safeFilename) {
    this.suffix = suffix;
    this.safeFilename = safeFilename;
  }

  public String suffix() {
    return suffix;
  }

  public String safeFilename() {
    return safeFilename;
  }

  public String wireValue() {
    return name();
  }

  public static ScannerArtifactType fromPath(String path) {
    if (path == null || path.isBlank()) {
      return UNKNOWN;
    }
    String normalized = path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    int separator = normalized.lastIndexOf('/');
    String filename = normalized.substring(separator + 1);
    for (ScannerArtifactType type : values()) {
      if (type != UNKNOWN && filename.endsWith(type.suffix)) {
        return type;
      }
    }
    return UNKNOWN;
  }

  public static ScannerArtifactType fromWireValue(String value) {
    if (value == null || value.isBlank()) {
      return UNKNOWN;
    }
    return switch (value.trim().toUpperCase(Locale.ROOT)) {
      case "UNKNOWN" -> UNKNOWN;
      case "TAR_GZ" -> TAR_GZ;
      case "TAR_BZ2" -> TAR_BZ2;
      case "TAR_XZ" -> TAR_XZ;
      case "TAR_ZST" -> TAR_ZST;
      case "XZ" -> XZ;
      case "ZIP" -> ZIP;
      case "TAR" -> TAR;
      case "TGZ" -> TGZ;
      case "TBZ2" -> TBZ2;
      case "TXZ" -> TXZ;
      case "TZST" -> TZST;
      case "JAR" -> JAR;
      case "WAR" -> WAR;
      case "EAR" -> EAR;
      case "AAR" -> AAR;
      case "WHL" -> WHL;
      case "EGG" -> EGG;
      case "CRATE" -> CRATE;
      case "GEM" -> GEM;
      case "NUPKG" -> NUPKG;
      case "RPM" -> RPM;
      case "DEB" -> DEB;
      case "APK" -> APK;
      case "IPA" -> IPA;
      default -> throw new IllegalArgumentException("Unsupported scanner artifact type");
    };
  }
}
