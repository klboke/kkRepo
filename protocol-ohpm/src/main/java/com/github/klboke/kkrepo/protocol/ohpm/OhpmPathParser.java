package com.github.klboke.kkrepo.protocol.ohpm;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.Set;
import java.util.regex.Pattern;

public final class OhpmPathParser {
  private static final Set<String> READ = Set.of("GET", "HEAD");
  private static final Set<String> TAG_WRITE = Set.of("POST", "PUT", "DELETE");
  private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,59}");

  public OhpmPath parse(String method, String rawPath) {
    if (method == null || rawPath == null) return unknown();
    try {
      String path = UriPathDecoder.decodeComponent(rawPath);
      if (path.startsWith("/")) path = path.substring(1);
      if (!safePath(path)) return unknown();
      if (READ.contains(method) && path.equals("-/ping")) return simple(OhpmPath.Kind.PING);
      if (method.equals("POST") && path.equals("login")) return simple(OhpmPath.Kind.LOGIN);
      if (method.equals("POST") && path.startsWith("stream/")) {
        return packagePath(OhpmPath.Kind.STREAM_PUBLISH, path.substring("stream/".length()));
      }
      if (path.startsWith("-/package/")) return distTag(method, path);
      int download = path.indexOf("/-/");
      if (download >= 0) {
        if (!READ.contains(method)) return unknown();
        String fileName = path.substring(download + 3);
        if (!fileName.endsWith(".har") && !fileName.endsWith(".hsp")) return unknown();
        return new OhpmPath(OhpmPath.Kind.DOWNLOAD,
            OhpmPackageId.parseDecoded(path.substring(0, download)), null, fileName, null);
      }
      if (method.equals("DELETE")) {
        int versionAt = path.lastIndexOf('@');
        if (versionAt > 0) {
          String version = path.substring(versionAt + 1);
          if (!OhpmVersions.isValid(version)) return unknown();
          return new OhpmPath(OhpmPath.Kind.UNPUBLISH,
              OhpmPackageId.parseDecoded(path.substring(0, versionAt)), version, null, null);
        }
        return packagePath(OhpmPath.Kind.UNPUBLISH, path);
      }
      if (READ.contains(method)) return packagePath(OhpmPath.Kind.METADATA, path);
      if (method.equals("PUT")) return packagePath(OhpmPath.Kind.PUBLISH, path);
      return unknown();
    } catch (IllegalArgumentException invalid) {
      return unknown();
    }
  }

  public static boolean isValidTag(String tag) {
    return tag != null && TAG.matcher(tag).matches();
  }

  private static OhpmPath distTag(String method, String path) {
    if (!TAG_WRITE.contains(method)) return unknown();
    String remainder = path.substring("-/package/".length());
    int marker = remainder.indexOf("/dist-tags/");
    if (marker < 0) return unknown();
    String tag = remainder.substring(marker + "/dist-tags/".length());
    if (!isValidTag(tag)) return unknown();
    return new OhpmPath(OhpmPath.Kind.DIST_TAG,
        OhpmPackageId.parseDecoded(remainder.substring(0, marker)), null, null, tag);
  }

  private static boolean safePath(String path) {
    if (path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf('%') >= 0
        || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
        || path.chars().anyMatch(character -> character <= 0x20 || character == 0x7f)) return false;
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
    }
    return true;
  }

  private static OhpmPath packagePath(OhpmPath.Kind kind, String packageName) {
    return new OhpmPath(kind, OhpmPackageId.parseDecoded(packageName), null, null, null);
  }

  private static OhpmPath simple(OhpmPath.Kind kind) {
    return new OhpmPath(kind, null, null, null, null);
  }

  private static OhpmPath unknown() {
    return simple(OhpmPath.Kind.UNKNOWN);
  }
}
