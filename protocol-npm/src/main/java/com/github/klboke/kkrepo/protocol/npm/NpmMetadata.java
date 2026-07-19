package com.github.klboke.kkrepo.protocol.npm;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.versioning.ComparableVersion;

public final class NpmMetadata {
  public static final String NAME = "name";
  public static final String VERSION = "version";
  public static final String VERSIONS = "versions";
  public static final String DIST_TAGS = "dist-tags";
  public static final String DIST = "dist";
  public static final String TARBALL = "tarball";
  public static final String SHASUM = "shasum";
  public static final String TIME = "time";
  public static final String META_ID = "_id";
  public static final String META_REV = "_rev";
  public static final String ATTACHMENTS = "_attachments";

  private static final DateTimeFormatter NPM_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private static final String CREATED = "created";
  private static final String MODIFIED = "modified";
  private static final String LATEST = "latest";
  private static final List<String> ABBREVIATED_ROOT_FIELDS = List.of(
      NAME,
      META_ID,
      META_REV,
      DIST_TAGS,
      VERSIONS,
      TIME,
      MODIFIED,
      "description",
      "license",
      "licenses",
      "homepage",
      "repository",
      "bugs",
      "keywords",
      "maintainers");
  private static final List<String> ABBREVIATED_VERSION_FIELDS = List.of(
      NAME,
      VERSION,
      DIST,
      "dependencies",
      "optionalDependencies",
      "peerDependencies",
      "peerDependenciesMeta",
      "bundleDependencies",
      "bundledDependencies",
      "bin",
      "directories",
      "engines",
      "os",
      "cpu",
      "libc",
      "deprecated",
      "hasInstallScript",
      "_hasShrinkwrap",
      "gypfile",
      "license",
      "licenses",
      "main",
      "module",
      "types",
      "typings",
      "browser",
      "exports",
      "imports",
      "type",
      "man",
      "files",
      "workspaces",
      "sideEffects",
      "optional",
      "funding",
      "repository",
      "bugs",
      "homepage",
      "author",
      "description",
      "keywords",
      "readmeFilename");

  private NpmMetadata() {
  }

  public static String extractTarballName(String tarballUrl) {
    if (tarballUrl == null) return null;
    int question = tarballUrl.indexOf('?');
    String clean = question < 0 ? tarballUrl : tarballUrl.substring(0, question);
    int slash = clean.lastIndexOf('/');
    return slash < 0 ? clean : clean.substring(slash + 1);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> versions(Map<String, Object> packageRoot) {
    Object raw = packageRoot.get(VERSIONS);
    if (raw instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    Map<String, Object> created = new LinkedHashMap<>();
    packageRoot.put(VERSIONS, created);
    return created;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> distTags(Map<String, Object> packageRoot) {
    Object raw = packageRoot.get(DIST_TAGS);
    if (raw instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    Map<String, Object> created = new LinkedHashMap<>();
    packageRoot.put(DIST_TAGS, created);
    return created;
  }

  @SuppressWarnings("unchecked")
  public static String findVersionForTarball(Map<String, Object> packageRoot, String tarballName) {
    String expected = extractTarballName(tarballName);
    for (Map.Entry<String, Object> entry : versions(packageRoot).entrySet()) {
      if (entry.getValue() instanceof Map<?, ?> versionMap) {
        Object dist = ((Map<String, Object>) versionMap).get(DIST);
        if (dist instanceof Map<?, ?> distMap) {
          Object tarball = ((Map<String, Object>) distMap).get(TARBALL);
          if (expected != null && expected.equals(extractTarballName(String.valueOf(tarball)))) {
            return stringValue(((Map<String, Object>) versionMap).get(VERSION), entry.getKey());
          }
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  public static void rewriteTarballUrls(
      Map<String, Object> packageRoot,
      NpmPackageId packageId,
      String repositoryBaseUrl) {
    if (repositoryBaseUrl == null || repositoryBaseUrl.isBlank()) return;
    String base = repositoryBaseUrl.endsWith("/")
        ? repositoryBaseUrl.substring(0, repositoryBaseUrl.length() - 1)
        : repositoryBaseUrl;
    for (Object value : versions(packageRoot).values()) {
      if (!(value instanceof Map<?, ?> rawVersion)) continue;
      Map<String, Object> version = (Map<String, Object>) rawVersion;
      Object distRaw = version.get(DIST);
      if (!(distRaw instanceof Map<?, ?> rawDist)) continue;
      Map<String, Object> dist = (Map<String, Object>) rawDist;
      String tarballName = extractTarballName(stringValue(dist.get(TARBALL), null));
      if (tarballName != null && !tarballName.isBlank()) {
        dist.put(TARBALL, base + "/" + packageId.tarballPath(tarballName));
      }
    }
  }

  public static void prepareForStorage(Map<String, Object> packageRoot, NpmPackageId packageId, String revision) {
    packageRoot.remove(ATTACHMENTS);
    packageRoot.put(META_ID, packageId.id());
    packageRoot.put(META_REV, revision);
    maintainTime(packageRoot);
  }

  @SuppressWarnings("unchecked")
  public static void maintainTime(Map<String, Object> packageRoot) {
    Object raw = packageRoot.get(TIME);
    Map<String, Object> time;
    if (raw instanceof Map<?, ?> map) {
      time = (Map<String, Object>) map;
    } else {
      time = new LinkedHashMap<>();
      packageRoot.put(TIME, time);
    }
    String now = NPM_TIME.format(OffsetDateTime.now(ZoneOffset.UTC));
    time.putIfAbsent(CREATED, now);
    time.put(MODIFIED, now);
    for (String version : versions(packageRoot).keySet()) {
      time.putIfAbsent(version, now);
    }
  }

  public static String nextRevision(Map<String, Object> existing, boolean created) {
    if (created || existing == null) return "1";
    String rev = stringValue(existing.get(META_REV), null);
    if (rev == null) return "1";
    try {
      return Integer.toString(Integer.parseInt(rev) + 1);
    } catch (NumberFormatException e) {
      return Long.toString(System.currentTimeMillis());
    }
  }

  public static Map<String, Object> overlay(Map<String, Object> existing, Map<String, Object> incoming) {
    Map<String, Object> result = deepCopy(existing);
    overlayInto(result, incoming, true);
    return result;
  }

  public static Map<String, Object> merge(List<Map<String, Object>> packages) {
    Map<String, Object> result = new LinkedHashMap<>();
    String latest = null;
    for (Map<String, Object> pkg : packages) {
      String pkgLatest = stringValue(distTags(pkg).get(LATEST), null);
      if (pkgLatest != null && (latest == null
          || new ComparableVersion(pkgLatest).compareTo(new ComparableVersion(latest)) > 0)) {
        latest = pkgLatest;
      }
      overlayInto(result, pkg, false);
    }
    distTags(result).put(LATEST, latest);
    result.remove(META_ID);
    result.remove(META_REV);
    return result;
  }

  public static Map<String, Object> shrinkForSearch(Map<String, Object> packageRoot) {
    Map<String, Object> result = deepCopy(packageRoot);
    Map<String, Object> versions = versions(result);
    for (String version : new ArrayList<>(versions.keySet())) {
      versions.put(version, resolveVersionToTag(result, version));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> abbreviatePackageRoot(Map<String, Object> packageRoot) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (packageRoot == null) {
      return result;
    }
    for (String field : ABBREVIATED_ROOT_FIELDS) {
      if (!VERSIONS.equals(field) && packageRoot.containsKey(field)) {
        result.put(field, deepCopyValue(packageRoot.get(field)));
      }
    }
    Object versionsRaw = packageRoot.get(VERSIONS);
    if (versionsRaw instanceof Map<?, ?> rawVersions) {
      Map<String, Object> abbreviatedVersions = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) rawVersions).entrySet()) {
        if (entry.getValue() instanceof Map<?, ?> versionMap) {
          abbreviatedVersions.put(entry.getKey(), abbreviateVersion((Map<String, Object>) versionMap));
        } else {
          abbreviatedVersions.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
      }
      result.put(VERSIONS, abbreviatedVersions);
    }
    return result;
  }

  private static Map<String, Object> abbreviateVersion(Map<String, Object> version) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : ABBREVIATED_VERSION_FIELDS) {
      if (version.containsKey(field)) {
        result.put(field, deepCopyValue(version.get(field)));
      }
    }
    if (!result.containsKey("hasInstallScript") && hasInstallScript(version.get("scripts"))) {
      result.put("hasInstallScript", true);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public static boolean hasInstallScript(Object scriptsRaw) {
    if (!(scriptsRaw instanceof Map<?, ?> rawScripts)) {
      return false;
    }
    Map<String, Object> scripts = (Map<String, Object>) rawScripts;
    return scripts.containsKey("preinstall")
        || scripts.containsKey("install")
        || scripts.containsKey("postinstall");
  }

  public static boolean isAbbreviatedRootField(String field) {
    return ABBREVIATED_ROOT_FIELDS.contains(field);
  }

  public static boolean isAbbreviatedVersionField(String field) {
    return ABBREVIATED_VERSION_FIELDS.contains(field);
  }

  @SuppressWarnings("unchecked")
  private static void overlayInto(
      Map<String, Object> recessive,
      Map<String, Object> dominant,
      boolean mergeVersionDocuments) {
    for (Map.Entry<String, Object> entry : dominant.entrySet()) {
      Object recessiveValue = recessive.get(entry.getKey());
      Object dominantValue = entry.getValue();
      if (dominantValue instanceof Map<?, ?> dominantMap && recessiveValue instanceof Map<?, ?> recessiveMap) {
        if (isSpecialOverlayKey(entry.getKey())) {
          recessive.put(entry.getKey(), deepCopy((Map<String, Object>) dominantMap));
        } else if (mergeVersionDocuments || !(VERSIONS.equals(entry.getKey()) || DIST_TAGS.equals(entry.getKey()))) {
          Map<String, Object> merged = deepCopy((Map<String, Object>) recessiveMap);
          overlayInto(merged, (Map<String, Object>) dominantMap, mergeVersionDocuments);
          recessive.put(entry.getKey(), merged);
        } else {
          Map<String, Object> merged = deepCopy((Map<String, Object>) recessiveMap);
          for (Map.Entry<String, Object> child : ((Map<String, Object>) dominantMap).entrySet()) {
            merged.put(child.getKey(), deepCopyValue(child.getValue()));
          }
          recessive.put(entry.getKey(), merged);
        }
      } else if (dominantValue instanceof String && recessiveValue instanceof Map<?, ?> && VERSIONS.equals(entry.getKey())) {
        // Nexus ignores shrunk metadata version tags when a full version map is already present.
      } else {
        recessive.put(entry.getKey(), deepCopyValue(dominantValue));
      }
    }
  }

  private static boolean isSpecialOverlayKey(String key) {
    return "dependencies".equals(key)
        || "devDependencies".equals(key)
        || "scripts".equals(key)
        || "author".equals(key)
        || "publishConfig".equals(key);
  }

  private static String resolveVersionToTag(Map<String, Object> packageRoot, String version) {
    for (Map.Entry<String, Object> tag : distTags(packageRoot).entrySet()) {
      if (version.equals(stringValue(tag.getValue(), null))) {
        return tag.getKey();
      }
    }
    return version;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> deepCopy(Map<String, Object> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (source == null) return result;
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      result.put(entry.getKey(), deepCopyValue(entry.getValue()));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Object deepCopyValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return deepCopy((Map<String, Object>) map);
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      for (Object item : list) result.add(deepCopyValue(item));
      return result;
    }
    return value;
  }

  public static String stringValue(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }
}
