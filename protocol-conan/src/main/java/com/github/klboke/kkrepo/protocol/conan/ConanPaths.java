package com.github.klboke.kkrepo.protocol.conan;

/** Deterministic wire/storage/Browse path projection for committed Conan revisions. */
public final class ConanPaths {
  private ConanPaths() {
  }

  public static String storagePath(ConanReference reference, String filePath) {
    requireRevisionFile(reference, filePath);
    String root = "conans/" + reference.name() + "/" + reference.version() + "/"
        + reference.routeUser() + "/" + reference.routeChannel() + "/revisions/"
        + reference.recipeRevision();
    if (reference.packageId() == null) {
      return root + "/files/" + filePath;
    }
    if (reference.packageRevision() == null) {
      throw new IllegalArgumentException("Conan package storage path requires PREV");
    }
    return root + "/packages/" + reference.packageId() + "/revisions/"
        + reference.packageRevision() + "/files/" + filePath;
  }

  /**
   * Projects the Nexus 3.94 Browse tree at write time. This is deliberately not reversible path
   * guessing: callers persist this exact value in {@code browse_node} with the final asset.
   */
  public static String browsePath(ConanReference reference, String filePath) {
    requireRevisionFile(reference, filePath);
    String root = reference.routeUser() + "/" + reference.name() + "/"
        + reference.version() + "/" + reference.routeChannel() + "#"
        + reference.recipeRevision();
    if (reference.packageId() == null) {
      return root + "/" + filePath;
    }
    if (reference.packageRevision() == null) {
      throw new IllegalArgumentException("Conan package Browse path requires PREV");
    }
    return root + "/packages/" + reference.packageId() + "/revisions/"
        + reference.packageRevision() + "/files/" + filePath;
  }

  public static String stagingPath(long sessionId, String filePath) {
    if (sessionId <= 0 || !ConanPathParser.validFilePath(filePath)) {
      throw new IllegalArgumentException("Invalid Conan staging coordinate");
    }
    return ".conan/staging/" + sessionId + "/" + filePath;
  }

  /** Official Conan 2 file route for a typed revision file. */
  public static String fileRoute(ConanReference reference, String filePath) {
    requireRevisionFile(reference, filePath);
    String root = "v2/conans/" + reference.name() + "/" + reference.version() + "/"
        + reference.routeUser() + "/" + reference.routeChannel() + "/revisions/"
        + reference.recipeRevision();
    if (reference.packageId() == null) {
      return root + "/files/" + filePath;
    }
    if (reference.packageRevision() == null) {
      throw new IllegalArgumentException("Conan package file route requires PREV");
    }
    return root + "/packages/" + reference.packageId() + "/revisions/"
        + reference.packageRevision() + "/files/" + filePath;
  }

  /** Parses the Nexus Conan 2 blob-store path without consulting Browse presentation paths. */
  public static StorageFile parseStoragePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    String[] segments = path.split("/", -1);
    if (segments.length < 9 || !"conans".equals(segments[0])
        || !"revisions".equals(segments[5])) {
      throw new IllegalArgumentException("Invalid Conan storage path: " + rawPath);
    }
    String user = "_".equals(segments[3]) ? null : segments[3];
    String channel = "_".equals(segments[4]) ? null : segments[4];
    ConanReference reference;
    int fileStart;
    if ("files".equals(segments[7])) {
      reference = new ConanReference(
          segments[1], segments[2], user, channel, segments[6], null, null);
      fileStart = 8;
    } else if (segments.length >= 13 && "packages".equals(segments[7])
        && "revisions".equals(segments[9]) && "files".equals(segments[11])) {
      reference = new ConanReference(
          segments[1], segments[2], user, channel, segments[6], segments[8], segments[10]);
      fileStart = 12;
    } else {
      throw new IllegalArgumentException("Invalid Conan storage path: " + rawPath);
    }
    String filePath = String.join(
        "/", java.util.Arrays.copyOfRange(segments, fileStart, segments.length));
    requireRevisionFile(reference, filePath);
    if (!storagePath(reference, filePath).equals(path)) {
      throw new IllegalArgumentException("Non-canonical Conan storage path: " + rawPath);
    }
    return new StorageFile(reference, filePath);
  }

  public record StorageFile(ConanReference reference, String filePath) {
  }

  private static void requireRevisionFile(ConanReference reference, String filePath) {
    if (reference == null || reference.recipeRevision() == null
        || !ConanPathParser.validFilePath(filePath)) {
      throw new IllegalArgumentException("A complete Conan revision file is required");
    }
  }
}
