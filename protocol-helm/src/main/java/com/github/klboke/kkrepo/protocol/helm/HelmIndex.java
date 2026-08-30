package com.github.klboke.kkrepo.protocol.helm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class HelmIndex {
  public static final String CONTENT_TYPE = "text/x-yaml";
  private static final int MAX_INDEX_CODE_POINTS = 64 * 1024 * 1024;
  private static final int MAX_VERSION_LENGTH = 256;
  private static final String API_VERSION = "v1";
  private static final Set<String> CHART_API_VERSIONS = Set.of("v1", "v2");
  private static final Set<String> CHART_TYPES = Set.of("application", "library");
  private static final DateTimeFormatter INSTANTS = DateTimeFormatter.ISO_INSTANT;
  // Helm parses chart versions with Masterminds semver.NewVersion: a lower-case v prefix,
  // shortened numeric cores, and leading zero coercion are accepted, while arbitrary labels are
  // not. Keep this grammar local to the Helm protocol instead of imposing strict SemVer on it.
  private static final Pattern HELM_VERSION = Pattern.compile(
      "^v?([0-9]+)(?:\\.([0-9]+))?(?:\\.([0-9]+))?"
          + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
          + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

  private HelmIndex() {
  }

  public static byte[] buildHosted(Collection<ChartRecord> charts, Instant generated) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      writeHosted(charts, generated, out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  public static void writeHosted(Collection<ChartRecord> charts, Instant generated, OutputStream out)
      throws IOException {
    List<ChartRecord> sorted = charts == null
        ? List.of()
        : charts.stream()
            // Legacy rows written before hosted package validation may contain coordinates that
            // Helm cannot load. Omit only those releases so one bad row never invalidates the
            // hosted member's entire generated index when it is consumed through a group.
            .filter(chart -> isSafeChartPathSegment(chart.name()))
            .filter(chart -> isSafeChartPathSegment(chart.version()))
            .filter(chart -> isValidChartVersion(chart.version()))
            .filter(chart -> chart.apiVersion() == null
                || chart.apiVersion().isBlank()
                || isValidChartApiVersion(chart.apiVersion()))
            .sorted(Comparator.comparing(ChartRecord::name)
                .thenComparing(ChartRecord::version, Comparator.reverseOrder()))
            .toList();
    line(out, "apiVersion: " + yamlString(API_VERSION));
    if (sorted.isEmpty()) {
      line(out, "entries: {}");
    } else {
      line(out, "entries:");
      String currentName = null;
      for (ChartRecord chart : sorted) {
        if (!chart.name().equals(currentName)) {
          currentName = chart.name();
          line(out, "  " + yamlString(currentName) + ":");
        }
        writeChart(out, chart);
      }
    }
    line(out, "generated: " + yamlString(INSTANTS.format(generated == null ? Instant.now() : generated)));
  }

  public static RewriteResult rewriteProxyIndex(byte[] yamlBytes, String remoteBaseUrl) {
    Map<String, Object> root = loadValidIndex(yamlBytes);
    Object rawEntries = root.get("entries");
    Map<String, String> remoteUrlsByLocalPath = new LinkedHashMap<>();
    Map<String, String> releasesByLocalPath = new LinkedHashMap<>();
    Set<String> acceptedReleases = new HashSet<>();
    if (rawEntries instanceof Map<?, ?> entries) {
      Map<String, Object> rewrittenEntries = new LinkedHashMap<>();
      entries.forEach((name, rawVersions) -> {
        if (!(rawVersions instanceof List<?> versions)) return;
        List<Object> acceptedVersions = new ArrayList<>(versions.size());
        for (Object rawVersion : versions) {
          if (!(rawVersion instanceof Map<?, ?> versionMap)) continue;
          String releaseName = string(
              versionMap.get("name"), name == null ? null : name.toString());
          String releaseVersion = string(versionMap.get("version"), null);
          String releaseKey = releaseName + '\0' + releaseVersion;
          if (acceptedReleases.contains(releaseKey)) continue;
          Map<String, String> releaseRemoteUrls = new LinkedHashMap<>();
          List<String> rewritten = rewriteEntry(
              name == null ? null : name.toString(),
              versionMap,
              remoteBaseUrl,
              releaseRemoteUrls);
          // A classic chart repository can only serve chart archives through the URLs published
          // in index.yaml. Keep explicit provenance mappings for .prov requests, but never expose
          // unsupported upstream extensions as chart alternatives to Helm clients.
          List<String> chartUrls = rewritten.stream()
              .filter(HelmIndex::isChartArchiveUrl)
              .toList();
          if (chartUrls.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid Helm index entry: expected a resolvable chart archive URL");
          }
          // name-version is not an injective local-path encoding. Keep the first release that
          // owns a generated chart path so every advertised digest resolves to that release's
          // bytes, including for a direct proxy repository rather than only after group merging.
          if (chartUrls.stream().anyMatch(url -> {
            String owner = releasesByLocalPath.get(normalizeLocalPath(url));
            return owner != null && !owner.equals(releaseKey);
          })) {
            continue;
          }
          acceptedReleases.add(releaseKey);
          chartUrls.forEach(url ->
              releasesByLocalPath.putIfAbsent(normalizeLocalPath(url), releaseKey));
          releaseRemoteUrls.forEach(remoteUrlsByLocalPath::putIfAbsent);
          putRaw(
              versionMap,
              "urls",
              chartUrls);
          acceptedVersions.add(versionMap);
        }
        if (!acceptedVersions.isEmpty()) {
          rewrittenEntries.put(name.toString(), acceptedVersions);
        }
      });
      root.put("entries", rewrittenEntries);
    }
    return new RewriteResult(dump(root), remoteUrlsByLocalPath);
  }

  /** Rejects parseable YAML documents that do not have the classic Helm index structure. */
  public static void validateIndex(byte[] yamlBytes) {
    parseValidatedIndex(yamlBytes);
  }

  /**
   * Parse and validate a classic Helm index once for repeated release selection.
   *
   * <p>The returned view is detached from SnakeYAML's mutable object graph and can be safely
   * retained for the lifetime of one request.
   */
  public static ValidatedIndex parseValidatedIndex(byte[] yamlBytes) {
    return new ValidatedIndex(releases(loadValidIndex(yamlBytes)));
  }

  /** Return whether a chart coordinate is safe to embed as one repository path segment. */
  public static boolean isSafeChartPathSegment(String value) {
    if (value == null
        || value.isBlank()
        || value.contains("/")
        || value.contains("\\")
        || value.contains("?")
        || value.contains("#")
        || value.contains(":")
        || value.contains("%")
        || value.equals(".")
        || value.equals("..")
        || value.contains("..")) {
      return false;
    }
    return value.codePoints()
        .noneMatch(codePoint ->
            Character.isISOControl(codePoint)
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint));
  }

  /** Return whether Helm can coerce the supplied chart version into SemVer. */
  public static boolean isValidChartVersion(String value) {
    if (value == null || value.length() > MAX_VERSION_LENGTH) return false;
    Matcher matcher = HELM_VERSION.matcher(value);
    if (!matcher.matches()) return false;
    for (int group = 1; group <= 3; group++) {
      String segment = matcher.group(group);
      if (segment == null) continue;
      try {
        int firstSignificantDigit = 0;
        while (firstSignificantDigit < segment.length() - 1
            && segment.charAt(firstSignificantDigit) == '0') {
          firstSignificantDigit++;
        }
        Long.parseUnsignedLong(segment.substring(firstSignificantDigit));
      } catch (NumberFormatException e) {
        return false;
      }
    }
    String prerelease = matcher.group(4);
    if (prerelease == null) return true;
    for (String identifier : prerelease.split("\\.")) {
      if (identifier.length() > 1
          && identifier.charAt(0) == '0'
          && identifier.chars().allMatch(character -> character >= '0' && character <= '9')) {
        return false;
      }
    }
    return true;
  }

  /** Return whether Helm accepts the release's Chart.yaml API version. */
  public static boolean isValidChartApiVersion(String value) {
    return value != null && CHART_API_VERSIONS.contains(value);
  }

  /**
   * Merge member indexes in configured group order.
   *
   * <p>Helm identifies one chart release by name and version. The first member providing a release
   * therefore wins, while unique releases from later members remain visible. Member URLs are
   * rewritten to repository-local paths so clients always download through the group endpoint.
   */
  public static byte[] mergeGroupIndexes(Collection<byte[]> memberIndexes, Instant generated) {
    Map<String, Object> mergedEntries = new LinkedHashMap<>();
    Map<String, Set<String>> releasesByEntry = new LinkedHashMap<>();
    Map<String, String> releasesByLocalPath = new LinkedHashMap<>();
    if (memberIndexes != null) {
      for (byte[] memberIndex : memberIndexes) {
        if (memberIndex == null || memberIndex.length == 0) continue;
        Map<String, Object> root = load(memberIndex);
        Object rawEntries = root.get("entries");
        if (!(rawEntries instanceof Map<?, ?> entries)) continue;
        entries.forEach((rawName, rawVersions) -> {
          String entryName = rawName == null ? null : rawName.toString();
          if (!isSafeChartPathSegment(entryName)
              || !(rawVersions instanceof List<?> versions)) {
            return;
          }
          @SuppressWarnings("unchecked")
          List<Object> mergedVersions = (List<Object>) mergedEntries.computeIfAbsent(
              entryName, ignored -> new ArrayList<>());
          Set<String> releases = releasesByEntry.computeIfAbsent(entryName, ignored -> new HashSet<>());
          for (Object rawVersion : versions) {
            if (!(rawVersion instanceof Map<?, ?> versionMap)) continue;
            String name = string(versionMap.get("name"), entryName);
            String version = string(versionMap.get("version"), null);
            if (!entryName.equals(name)
                || !isSafeChartPathSegment(name)
                || !isSafeChartPathSegment(version)
                || !isValidChartVersion(version)
                || !isValidChartApiVersion(string(versionMap.get("apiVersion"), null))
                || !isValidChartType(versionMap.get("type"))
                || !isValidTimestamp(versionMap.get("created"))) {
              continue;
            }
            String releaseKey = name + '\0' + version;
            if (releases.contains(releaseKey)) continue;
            List<String> chartUrls = rewriteEntry(
                entryName, versionMap, null, new LinkedHashMap<>()).stream()
                .filter(HelmIndex::isChartArchiveUrl)
                .toList();
            if (chartUrls.isEmpty()) continue;
            // name-version is not an injective encoding: for example, demo@1-2.0 and
            // demo-1@2.0 both become demo-1-2.0.tgz. Preserve configured first-wins order and
            // omit a later ambiguous release instead of advertising a path that can resolve to
            // only one source and violate the other release's digest.
            if (chartUrls.stream().anyMatch(url -> {
              String owner = releasesByLocalPath.get(normalizeLocalPath(url));
              return owner != null && !owner.equals(releaseKey);
            })) {
              continue;
            }
            releases.add(releaseKey);
            chartUrls.forEach(url ->
                releasesByLocalPath.putIfAbsent(normalizeLocalPath(url), releaseKey));
            putRaw(versionMap, "urls", chartUrls);
            mergedVersions.add(versionMap);
          }
        });
      }
    }
    mergedEntries.entrySet().removeIf(entry -> ((List<?>) entry.getValue()).isEmpty());
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("apiVersion", API_VERSION);
    root.put("entries", mergedEntries);
    root.put("generated", INSTANTS.format(generated == null ? Instant.now() : generated));
    return dump(root);
  }

  public static List<Entry> entries(byte[] yamlBytes) {
    Map<String, Object> root = load(yamlBytes);
    Object rawEntries = root.get("entries");
    if (!(rawEntries instanceof Map<?, ?> entries)) return List.of();
    List<Entry> result = new ArrayList<>();
    entries.forEach((name, rawVersions) -> {
      if (!(rawVersions instanceof List<?> versions)) return;
      for (Object rawVersion : versions) {
        if (!(rawVersion instanceof Map<?, ?> versionMap)) continue;
        String chartName = string(versionMap.get("name"), name == null ? null : name.toString());
        String chartVersion = string(versionMap.get("version"), null);
        result.add(new Entry(chartName, chartVersion, stringList(versionMap.get("urls"))));
      }
    });
    return result;
  }

  /**
   * Resolve the release advertised for a repository-local chart or provenance path.
   *
   * <p>Provenance files are conventionally discovered by appending {@code .prov} to the chart
   * URL, so they remain bound to the same release even when the index only lists the chart
   * archive.
   */
  public static Optional<Release> releaseForPath(byte[] yamlBytes, String path) {
    String requested = normalizeLocalPath(path);
    return releases(yamlBytes).stream()
        .filter(release -> advertises(release, requested))
        .findFirst();
  }

  /** Return whether a member index advertises the exact release selected by a group index. */
  public static boolean containsRelease(byte[] yamlBytes, Release expected, String path) {
    if (expected == null) return false;
    String requested = normalizeLocalPath(path);
    return releases(yamlBytes).stream()
        .anyMatch(candidate -> sameRelease(candidate, expected)
            && advertises(candidate, requested));
  }

  private static List<Release> releases(byte[] yamlBytes) {
    return releases(load(yamlBytes));
  }

  private static List<Release> releases(Map<String, Object> root) {
    Object rawEntries = root.get("entries");
    if (!(rawEntries instanceof Map<?, ?> entries)) return List.of();
    List<Release> result = new ArrayList<>();
    entries.forEach((name, rawVersions) -> {
      if (!(rawVersions instanceof List<?> versions)) return;
      for (Object rawVersion : versions) {
        if (!(rawVersion instanceof Map<?, ?> versionMap)) continue;
        String chartName = string(versionMap.get("name"), name == null ? null : name.toString());
        String chartVersion = string(versionMap.get("version"), null);
        if (chartName == null || chartName.isBlank()
            || chartVersion == null || chartVersion.isBlank()) {
          continue;
        }
        List<String> localUrls = stringList(versionMap.get("urls")).stream()
            .map(url -> localUrl(chartName, chartVersion, url))
            .filter(Objects::nonNull)
            .filter(url -> !url.isBlank())
            .toList();
        result.add(new Release(
            chartName,
            chartVersion,
            string(versionMap.get("digest"), null),
            localUrls));
      }
    });
    return result;
  }

  /** Compare chart identity and digest while accepting the optional {@code sha256:} prefix. */
  public static boolean sameRelease(Release candidate, Release expected) {
    if (candidate == null || expected == null) return false;
    return Objects.equals(candidate.name(), expected.name())
        && Objects.equals(candidate.version(), expected.version())
        && Objects.equals(normalizeDigest(candidate.digest()), normalizeDigest(expected.digest()));
  }

  private static boolean advertises(Release release, String requested) {
    if (requested == null || requested.isBlank()) return false;
    for (String url : release.urls()) {
      String local = normalizeLocalPath(url);
      if (requested.equals(local)) return true;
      if (requested.endsWith(".tgz.prov")
          && local.endsWith(".tgz")
          && requested.equals(local + ".prov")) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeLocalPath(String path) {
    if (path == null) return "";
    String normalized = pathOf(path);
    while (normalized.startsWith("/")) normalized = normalized.substring(1);
    return normalized;
  }

  private static String normalizeDigest(String digest) {
    if (digest == null || digest.isBlank()) return null;
    String normalized = digest.trim().toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("sha256:") ? normalized.substring(7) : normalized;
  }

  private static Map<String, Object> chartEntry(ChartRecord chart) {
    Map<String, Object> entry = new LinkedHashMap<>();
    put(entry, "apiVersion", chart.apiVersion());
    put(entry, "name", chart.name());
    put(entry, "version", chart.version());
    put(entry, "description", chart.description());
    put(entry, "appVersion", chart.appVersion());
    put(entry, "icon", chart.icon());
    if (chart.created() != null) entry.put("created", INSTANTS.format(chart.created()));
    put(entry, "digest", chart.digest());
    if (chart.urls() != null && !chart.urls().isEmpty()) entry.put("urls", chart.urls());
    if (chart.sources() != null && !chart.sources().isEmpty()) entry.put("sources", chart.sources());
    if (chart.maintainers() != null && !chart.maintainers().isEmpty()) {
      entry.put("maintainers", chart.maintainers());
    }
    return entry;
  }

  private static void writeChart(OutputStream out, ChartRecord chart) throws IOException {
    line(out, "  - apiVersion: " + yamlString(blankToDefault(chart.apiVersion(), API_VERSION)));
    line(out, "    name: " + yamlString(chart.name()));
    line(out, "    version: " + yamlString(chart.version()));
    putYaml(out, "description", chart.description());
    putYaml(out, "appVersion", chart.appVersion());
    putYaml(out, "icon", chart.icon());
    if (chart.created() != null) {
      line(out, "    created: " + yamlString(INSTANTS.format(chart.created())));
    }
    putYaml(out, "digest", chart.digest());
    writeStringList(out, "urls", chart.urls());
    writeStringList(out, "sources", chart.sources());
    writeMaintainers(out, chart.maintainers());
  }

  private static void putYaml(OutputStream out, String key, String value) throws IOException {
    if (value == null || value.isBlank()) return;
    line(out, "    " + key + ": " + yamlString(value));
  }

  private static void writeStringList(OutputStream out, String key, List<String> values) throws IOException {
    if (values == null || values.isEmpty()) return;
    line(out, "    " + key + ":");
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        line(out, "      - " + yamlString(value));
      }
    }
  }

  private static void writeMaintainers(OutputStream out, List<Map<String, String>> maintainers) throws IOException {
    if (maintainers == null || maintainers.isEmpty()) return;
    line(out, "    maintainers:");
    for (Map<String, String> maintainer : maintainers) {
      if (maintainer == null || maintainer.isEmpty()) continue;
      boolean first = true;
      for (Map.Entry<String, String> entry : maintainer.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) continue;
        if (first) {
          line(out, "      - " + entry.getKey() + ": " + yamlString(entry.getValue()));
          first = false;
        } else {
          line(out, "        " + entry.getKey() + ": " + yamlString(entry.getValue()));
        }
      }
    }
  }

  private static void line(OutputStream out, String value) throws IOException {
    out.write(value.getBytes(StandardCharsets.UTF_8));
    out.write('\n');
  }

  private static String yamlString(String value) {
    String text = value == null ? "" : value;
    StringBuilder out = new StringBuilder(text.length() + 2);
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(c);
      }
    }
    out.append('"');
    return out.toString();
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static List<String> rewriteEntry(String fallbackName, Map<?, ?> rawEntry, String remoteBaseUrl,
      Map<String, String> remoteUrlsByLocalPath) {
    String name = string(rawEntry.get("name"), fallbackName);
    String version = string(rawEntry.get("version"), null);
    List<String> urls = stringList(rawEntry.get("urls"));
    if (urls.isEmpty()) return List.of();
    List<String> rewritten = new ArrayList<>(urls.size());
    for (String url : urls) {
      String remote = resolveRemoteUrl(remoteBaseUrl, url);
      if (remote == null) continue;
      String local = localUrl(name, version, url);
      if (local == null || local.isBlank()) continue;
      if (!isChartArchiveUrl(local) && !isProvenanceUrl(local)) continue;
      rewritten.add(local);
      if (local.toLowerCase().endsWith(".tgz.prov")) {
        // An explicitly indexed provenance URL is authoritative over the derived chart sibling.
        remoteUrlsByLocalPath.put(local, remote);
      } else {
        remoteUrlsByLocalPath.putIfAbsent(local, remote);
      }
      if (local.toLowerCase().endsWith(".tgz")) {
        remoteUrlsByLocalPath.putIfAbsent(local + ".prov", provenanceUrlForChart(remote));
      }
    }
    putRaw(rawEntry, "urls", rewritten);
    return rewritten;
  }

  private static boolean isChartArchiveUrl(String url) {
    return normalizeLocalPath(url).toLowerCase(java.util.Locale.ROOT).endsWith(".tgz");
  }

  private static boolean isProvenanceUrl(String url) {
    return normalizeLocalPath(url).toLowerCase(java.util.Locale.ROOT).endsWith(".tgz.prov");
  }

  private static boolean isSupportedChartUrlReference(String url) {
    if (url == null || url.isBlank()) return false;
    try {
      URI uri = URI.create(url);
      if (uri.isOpaque()
          || uri.getUserInfo() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535) {
        return false;
      }
      if (!uri.isAbsolute()) return true;
      String scheme = uri.getScheme();
      return uri.getHost() != null
          && !uri.getHost().isBlank()
          && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean isValidChartType(Object value) {
    return value == null
        || (value instanceof String type && (type.isEmpty() || CHART_TYPES.contains(type)));
  }

  private static boolean isValidTimestamp(Object value) {
    if (value == null || value instanceof java.util.Date || value instanceof Instant) return true;
    if (!(value instanceof String timestamp) || timestamp.isBlank()) return false;
    try {
      Instant.parse(timestamp);
      return true;
    } catch (DateTimeParseException ignored) {
      return false;
    }
  }

  /** Derive the conventional provenance sibling without moving a query or fragment suffix. */
  public static String provenanceUrlForChart(String chartUrl) {
    if (chartUrl == null || chartUrl.isBlank()) return chartUrl;
    int query = chartUrl.indexOf('?');
    int fragment = chartUrl.indexOf('#');
    int suffix = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
    return suffix < 0
        ? chartUrl + ".prov"
        : chartUrl.substring(0, suffix) + ".prov" + chartUrl.substring(suffix);
  }

  private static String localUrl(String name, String version, String oldUrl) {
    String suffix = suffix(oldUrl);
    if (name != null && !name.isBlank() && version != null && !version.isBlank() && suffix != null) {
      return name + "-" + version + suffix;
    }
    String basename = basename(pathOf(oldUrl));
    return basename == null || basename.isBlank() ? null : basename;
  }

  private static String suffix(String url) {
    String path = pathOf(url).toLowerCase();
    if (path.endsWith(".tgz.prov")) return ".tgz.prov";
    int dot = path.lastIndexOf('.');
    return dot < 0 ? null : path.substring(dot);
  }

  private static String pathOf(String url) {
    if (url == null) return "";
    try {
      String path = URI.create(url).getPath();
      return path == null ? "" : path;
    } catch (RuntimeException ignored) {
      return url;
    }
  }

  private static String basename(String path) {
    if (path == null) return null;
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String resolveRemoteUrl(String remoteBaseUrl, String url) {
    try {
      URI uri = URI.create(url);
      if (uri.isAbsolute()) {
        return isSupportedChartUrlReference(url) ? uri.toString() : null;
      }
      if (remoteBaseUrl == null || remoteBaseUrl.isBlank()) return uri.toString();
      String base = remoteBaseUrl.endsWith("/")
          ? remoteBaseUrl
          : remoteBaseUrl + "/";
      URI resolved = URI.create(base).resolve(uri);
      return isSupportedChartUrlReference(resolved.toString())
          ? resolved.toString()
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Object loadDocument(byte[] yamlBytes) {
    Object loaded;
    try {
      loaded = loadYaml().load(new ByteArrayInputStream(yamlBytes));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid Helm index YAML", e);
    }
    return loaded;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load(byte[] yamlBytes) {
    Object loaded = loadDocument(yamlBytes);
    if (!(loaded instanceof Map<?, ?> map)) {
      return new LinkedHashMap<>();
    }
    return (Map<String, Object>) map;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadValidIndex(byte[] yamlBytes) {
    Object loaded = loadDocument(yamlBytes);
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Invalid Helm index root: expected a mapping");
    }
    if (!API_VERSION.equals(map.get("apiVersion"))) {
      throw new IllegalArgumentException("Invalid Helm index apiVersion: expected v1");
    }
    if (!(map.get("entries") instanceof Map<?, ?> entries)) {
      throw new IllegalArgumentException("Invalid Helm index entries: expected a mapping");
    }
    if (!isValidTimestamp(map.get("generated"))) {
      throw new IllegalArgumentException("Invalid Helm index generated timestamp");
    }
    for (Map.Entry<?, ?> entry : entries.entrySet()) {
      if (!(entry.getKey() instanceof String name) || !isSafeChartPathSegment(name)) {
        throw new IllegalArgumentException("Invalid Helm index entry: expected a chart name");
      }
      if (!(entry.getValue() instanceof List<?> versions)) {
        throw new IllegalArgumentException(
            "Invalid Helm index entry " + name + ": expected a release list");
      }
      for (Object version : versions) {
        if (!(version instanceof Map<?, ?> release)) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": expected a release mapping");
        }
        if (!(release.get("name") instanceof String releaseName)
            || !isSafeChartPathSegment(releaseName)) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": expected a release name");
        }
        if (!name.equals(releaseName)) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": release name must match entry name");
        }
        if (!(release.get("apiVersion") instanceof String releaseApiVersion)
            || !isValidChartApiVersion(releaseApiVersion)) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name
                  + ": expected chart apiVersion v1 or v2");
        }
        if (!isValidChartType(release.get("type"))) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name
                  + ": expected chart type application or library");
        }
        if (!isValidTimestamp(release.get("created"))) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": expected an RFC 3339 creation timestamp");
        }
        if (!(release.get("version") instanceof String releaseVersion)
            || !isSafeChartPathSegment(releaseVersion)
            || !isValidChartVersion(releaseVersion)) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name
                  + ": expected a Helm-compatible release version");
        }
        Object urls = release.get("urls");
        if (!(urls instanceof List<?> list) || list.isEmpty()) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": expected a non-empty URL list");
        }
        if (list.stream().anyMatch(url -> !(url instanceof String value) || value.isBlank())) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": expected non-empty string URLs");
        }
        if (list.stream().map(String.class::cast).noneMatch(
            url -> isChartArchiveUrl(url) && isSupportedChartUrlReference(url))) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name
                  + ": expected a resolvable chart archive URL");
        }
      }
    }
    return (Map<String, Object>) map;
  }

  private static byte[] dump(Map<String, Object> root) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String body = dumpYaml().dump(root);
    out.writeBytes(body.getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static Yaml loadYaml() {
    LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(MAX_INDEX_CODE_POINTS);
    return new Yaml(new SafeConstructor(options));
  }

  private static Yaml dumpYaml() {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    options.setSplitLines(false);
    return new Yaml(options);
  }

  private static String string(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(item -> item != null && !item.toString().isBlank())
        .map(Object::toString)
        .toList();
  }

  private static void put(Map<String, Object> map, String key, String value) {
    if (value != null && !value.isBlank()) map.put(key, value);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void putRaw(Map<?, ?> map, String key, Object value) {
    ((Map) map).put(key, value);
  }

  public record ChartRecord(
      String name,
      String version,
      String apiVersion,
      String description,
      String appVersion,
      String icon,
      Instant created,
      String digest,
      List<String> urls,
      List<String> sources,
      List<Map<String, String>> maintainers) {
  }

  public record RewriteResult(byte[] body, Map<String, String> remoteUrlsByLocalPath) {
  }

  public record Entry(String name, String version, List<String> urls) {
  }

  public record Release(String name, String version, String digest, List<String> urls) {
    public Release {
      urls = urls == null ? List.of() : List.copyOf(urls);
    }
  }

  /** An immutable release lookup derived from one fully validated index parse. */
  public static final class ValidatedIndex {
    private final List<Release> releases;

    private ValidatedIndex(List<Release> releases) {
      this.releases = releases == null ? List.of() : List.copyOf(releases);
    }

    public Optional<Release> releaseForPath(String path) {
      String requested = normalizeLocalPath(path);
      return releases.stream()
          .filter(release -> advertises(release, requested))
          .findFirst();
    }

    public boolean containsRelease(Release expected, String path) {
      if (expected == null) return false;
      String requested = normalizeLocalPath(path);
      return releases.stream()
          .anyMatch(candidate -> sameRelease(candidate, expected)
              && advertises(candidate, requested));
    }
  }
}
