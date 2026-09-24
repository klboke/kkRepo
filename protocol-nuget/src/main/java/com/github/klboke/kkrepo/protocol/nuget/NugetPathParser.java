package com.github.klboke.kkrepo.protocol.nuget;

public final class NugetPathParser {
  public NugetPath parse(String rawPath) {
    String path = normalize(rawPath);
    if (path.isEmpty() || NugetPaths.SERVICE_INDEX.equals(path)) {
      return new NugetPath(NugetPath.Kind.SERVICE_INDEX, path, null, null);
    }
    if ("query".equals(path)) {
      return new NugetPath(NugetPath.Kind.QUERY, path, null, null);
    }
    if ("autocomplete".equals(path)) {
      return new NugetPath(NugetPath.Kind.AUTOCOMPLETE, path, null, null);
    }
    if (NugetPaths.PACKAGE_PUBLISH.equals(path)) {
      return new NugetPath(NugetPath.Kind.PACKAGE_PUBLISH, path, null, null);
    }
    String[] parts = path.split("/", -1);
    if (parts.length == 5 && "api".equals(parts[0]) && "v2".equals(parts[1])
        && "package".equals(parts[2])) {
      return new NugetPath(NugetPath.Kind.PACKAGE_DELETE, path, parts[3], parts[4]);
    }
    if (parts.length == 4 && "v3".equals(parts[0])
        && ("registration5-semver1".equals(parts[1]) || "registration5-semver2".equals(parts[1]))
        && !parts[2].isEmpty() && "index.json".equals(parts[3])) {
      return new NugetPath(NugetPath.Kind.REGISTRATION_INDEX, path, parts[2], null);
    }
    if (parts.length == 3 && "v3-flatcontainer".equals(parts[0]) && !parts[1].isEmpty() && "index.json".equals(parts[2])) {
      return new NugetPath(NugetPath.Kind.FLAT_CONTAINER_VERSION_INDEX, path, parts[1], null);
    }
    if (parts.length == 4 && "v3-flatcontainer".equals(parts[0])
        && !parts[1].isEmpty() && !parts[2].isEmpty()) {
      String packageId = parts[1];
      String version = parts[2];
      String packageFile = NugetPaths.normalizePackageId(packageId) + "." + NugetPaths.normalizeVersion(version);
      if ((packageFile + ".nupkg").equals(parts[3])) {
        return new NugetPath(NugetPath.Kind.FLAT_CONTAINER_PACKAGE, path, packageId, version);
      }
      if ((NugetPaths.normalizePackageId(packageId) + ".nuspec").equals(parts[3])) {
        return new NugetPath(NugetPath.Kind.FLAT_CONTAINER_NUSPEC, path, packageId, version);
      }
    }
    return new NugetPath(NugetPath.Kind.RAW, path, null, null);
  }

  public static String normalize(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    // Discovered V3 resource suffixes are opaque: empty and trailing segments carry identity.
    if (path.startsWith("v3-flatcontainer/") || path.startsWith("v3/registration5-semver1/")
        || path.startsWith("v3/registration5-semver2/")) return path;
    path = path.replaceAll("/+", "/");
    while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
    return path;
  }
}
