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
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class HelmIndex {
  public static final String CONTENT_TYPE = "text/x-yaml";
  private static final int MAX_INDEX_CODE_POINTS = 64 * 1024 * 1024;
  private static final String API_VERSION = "v1";
  private static final DateTimeFormatter INSTANTS = DateTimeFormatter.ISO_INSTANT;

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
            .filter(chart -> chart.name() != null && !chart.name().isBlank())
            .filter(chart -> chart.version() != null && !chart.version().isBlank())
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
    if (rawEntries instanceof Map<?, ?> entries) {
      entries.forEach((name, rawVersions) -> {
        if (!(rawVersions instanceof List<?> versions)) return;
        for (Object rawVersion : versions) {
          if (!(rawVersion instanceof Map<?, ?> versionMap)) continue;
          rewriteEntry(name == null ? null : name.toString(), versionMap, remoteBaseUrl,
              remoteUrlsByLocalPath);
        }
      });
    }
    return new RewriteResult(dump(root), remoteUrlsByLocalPath);
  }

  /** Rejects parseable YAML documents that do not have the classic Helm index structure. */
  public static void validateIndex(byte[] yamlBytes) {
    loadValidIndex(yamlBytes);
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
    if (memberIndexes != null) {
      for (byte[] memberIndex : memberIndexes) {
        if (memberIndex == null || memberIndex.length == 0) continue;
        Map<String, Object> root = load(memberIndex);
        Object rawEntries = root.get("entries");
        if (!(rawEntries instanceof Map<?, ?> entries)) continue;
        entries.forEach((rawName, rawVersions) -> {
          String entryName = rawName == null ? null : rawName.toString();
          if (entryName == null || entryName.isBlank() || !(rawVersions instanceof List<?> versions)) {
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
            if (name == null || name.isBlank() || version == null || version.isBlank()) continue;
            List<String> chartUrls = rewriteEntry(
                entryName, versionMap, null, new LinkedHashMap<>()).stream()
                .filter(HelmIndex::isChartArchiveUrl)
                .toList();
            if (chartUrls.isEmpty()) continue;
            if (!releases.add(name + '\0' + version)) continue;
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
    Map<String, Object> root = load(yamlBytes);
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

  private static boolean sameRelease(Release candidate, Release expected) {
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
      String local = localUrl(name, version, url);
      if (local == null || local.isBlank()) continue;
      rewritten.add(local);
      String remote = resolveRemoteUrl(remoteBaseUrl, url);
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
      return URI.create(url).getPath();
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
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      return url;
    }
    if (uri.isAbsolute()) return uri.toString();
    if (remoteBaseUrl == null || remoteBaseUrl.isBlank()) return url;
    String base = remoteBaseUrl == null || remoteBaseUrl.endsWith("/")
        ? remoteBaseUrl
        : remoteBaseUrl + "/";
    return URI.create(base).resolve(url).toString();
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
    if (!(map.get("entries") instanceof Map<?, ?> entries)) {
      throw new IllegalArgumentException("Invalid Helm index entries: expected a mapping");
    }
    for (Map.Entry<?, ?> entry : entries.entrySet()) {
      if (!(entry.getKey() instanceof String name) || name.isBlank()) {
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
        Object urls = release.get("urls");
        if (urls != null && !(urls instanceof List<?>)) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": expected a URL list");
        }
        if (urls instanceof List<?> list && list.stream()
            .anyMatch(url -> url instanceof Map<?, ?> || url instanceof Collection<?>)) {
          throw new IllegalArgumentException(
              "Invalid Helm index entry " + name + ": expected scalar URLs");
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
}
