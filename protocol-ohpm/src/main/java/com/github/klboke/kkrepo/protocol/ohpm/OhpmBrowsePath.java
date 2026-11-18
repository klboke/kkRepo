package com.github.klboke.kkrepo.protocol.ohpm;

public record OhpmBrowsePath(OhpmPackageId packageId, String version, String filename) {
  public static OhpmBrowsePath parse(String path) {
    if (path == null) throw new IllegalArgumentException("OHPM browse path is required");
    String[] segments = path.split("/", -1);
    int packageParts = path.startsWith("@") ? 2 : 1;
    if (segments.length < packageParts || segments.length > packageParts + 2) {
      throw new IllegalArgumentException("Expected an OHPM package, version or file path");
    }
    OhpmPackageId packageId = OhpmPackageId.parseDecoded(packageParts == 2
        ? segments[0] + "/" + segments[1] : segments[0]);
    String version = segments.length > packageParts ? segments[packageParts] : null;
    if (version != null && !OhpmVersions.isValid(version)) throw new IllegalArgumentException("Invalid OHPM version");
    String filename = segments.length == packageParts + 2 ? segments[packageParts + 1] : null;
    if (filename != null && !filename.equals(packageId.name() + "-" + version + ".har")
        && !filename.equals(packageId.name() + "-" + version + ".hsp")) {
      throw new IllegalArgumentException("OHPM file does not match the package version");
    }
    return new OhpmBrowsePath(packageId, version, filename);
  }

  public String storagePath() {
    if (version == null) return packageId.id();
    String extension = filename != null && filename.endsWith(".hsp") ? ".hsp" : ".har";
    return packageId.downloadPath(packageId.id() + "-" + version + extension);
  }
}
