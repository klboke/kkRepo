package com.github.klboke.kkrepo.protocol.npm;

import java.util.Arrays;

public final class NpmPathParser {
  public NpmPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    while (raw.startsWith("/")) raw = raw.substring(1);
    String decoded;
    try {
      decoded = NpmPackageId.decode(raw);
    } catch (IllegalArgumentException e) {
      return simple(NpmPath.Kind.UNKNOWN, raw);
    }
    while (decoded.startsWith("/")) decoded = decoded.substring(1);
    while (decoded.endsWith("/") && decoded.length() > 1) {
      decoded = decoded.substring(0, decoded.length() - 1);
    }

    if (decoded.equals("-/ping")) {
      return simple(NpmPath.Kind.PING, raw);
    }
    if (decoded.equals("-/whoami")) {
      return simple(NpmPath.Kind.WHOAMI, raw);
    }
    if (decoded.equals("-/v1/search")) {
      return simple(NpmPath.Kind.SEARCH_V1, raw);
    }
    if (decoded.equals("-/all") || decoded.equals("-/all/since")) {
      return simple(NpmPath.Kind.SEARCH_INDEX, raw);
    }
    if (decoded.equals("-/npm/v1/security/audits")) {
      return simple(NpmPath.Kind.AUDIT, raw);
    }
    if (decoded.equals("-/npm/v1/security/audits/quick")) {
      return simple(NpmPath.Kind.AUDIT_QUICK, raw);
    }
    if (decoded.equals("-/npm/v1/security/advisories/bulk")) {
      return simple(NpmPath.Kind.ADVISORIES_BULK, raw);
    }
    if (decoded.startsWith("-/package/")) {
      return parseDistTags(raw, decoded.substring("-/package/".length()));
    }
    return parsePackage(raw, decoded);
  }

  private static NpmPath simple(NpmPath.Kind kind, String raw) {
    return new NpmPath(kind, raw, null, null, null, null, null);
  }

  private static NpmPath parseDistTags(String raw, String remainder) {
    String marker = "/dist-tags";
    int markerIndex = remainder.indexOf(marker);
    if (markerIndex < 0) {
      return simple(NpmPath.Kind.UNKNOWN, raw);
    }
    String packagePart = remainder.substring(0, markerIndex);
    String after = remainder.substring(markerIndex + marker.length());
    String tag = null;
    if (after.startsWith("/") && after.length() > 1) {
      tag = after.substring(1);
    } else if (!after.isEmpty()) {
      return simple(NpmPath.Kind.UNKNOWN, raw);
    }
    try {
      return new NpmPath(tag == null ? NpmPath.Kind.DIST_TAGS : NpmPath.Kind.DIST_TAG,
          raw, NpmPackageId.parseDecoded(packagePart), null, null, tag, null);
    } catch (IllegalArgumentException e) {
      return simple(NpmPath.Kind.UNKNOWN, raw);
    }
  }

  private static NpmPath parsePackage(String raw, String decoded) {
    if (decoded.isBlank()) {
      return simple(NpmPath.Kind.REPOSITORY_ROOT, raw);
    }
    String[] parts = Arrays.stream(decoded.split("/"))
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
    if (parts.length == 0) {
      return simple(NpmPath.Kind.UNKNOWN, raw);
    }

    int packageSegments = parts[0].startsWith("@") ? 2 : 1;
    if (parts.length < packageSegments) {
      return simple(NpmPath.Kind.UNKNOWN, raw);
    }
    String packagePart = packageSegments == 2 ? parts[0] + "/" + parts[1] : parts[0];
    NpmPackageId packageId;
    try {
      packageId = NpmPackageId.parseDecoded(packagePart);
    } catch (IllegalArgumentException e) {
      return simple(NpmPath.Kind.UNKNOWN, raw);
    }
    int index = packageSegments;
    if (index == parts.length) {
      return new NpmPath(NpmPath.Kind.PACKAGE_ROOT, raw, packageId, null, null, null, null);
    }
    if (parts[index].equals("-rev") && parts.length == index + 2) {
      return new NpmPath(NpmPath.Kind.PACKAGE_ROOT, raw, packageId, null, null, null, parts[index + 1]);
    }
    if (parts[index].equals("-") && parts.length >= index + 2) {
      String tarballName = parts[index + 1];
      String revision = null;
      if (parts.length == index + 4 && parts[index + 2].equals("-rev")) {
        revision = parts[index + 3];
      } else if (parts.length != index + 2) {
        return simple(NpmPath.Kind.UNKNOWN, raw);
      }
      return new NpmPath(NpmPath.Kind.TARBALL, raw, packageId, null, tarballName, null, revision);
    }
    if (parts.length == index + 1) {
      return new NpmPath(NpmPath.Kind.PACKAGE_VERSION, raw, packageId, parts[index], null, null, null);
    }
    return simple(NpmPath.Kind.UNKNOWN, raw);
  }
}
