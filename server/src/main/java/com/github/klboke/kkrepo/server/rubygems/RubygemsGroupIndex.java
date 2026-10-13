package com.github.klboke.kkrepo.server.rubygems;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Request-local aggregation. Member reads retain the shared proxy cache and outbound policy. */
final class RubygemsGroupIndex {
  private static final int MAX_TEXT_BYTES = 64 * 1024 * 1024;
  // Any aggregated index, including dependency queries and per-gem info, can be large.
  // This is a per-process memory bulkhead, not repository state
  // or a distributed lock: every replica independently derives responses from shared members.
  private static final ReentrantLock LARGE_INDEX_SLOT = new ReentrantLock(true);
  private final RepositoryRuntime group;
  private final BiFunction<RepositoryRuntime, String, MavenResponse> get;
  private final int maxRows;
  private final long maxBytes;

  RubygemsGroupIndex(RepositoryRuntime group,
      BiFunction<RepositoryRuntime, String, MavenResponse> get) {
    this(group, get, RubygemsIndexMarshal.MAX_ROWS, RubygemsIndexMarshal.MAX_EXPANDED_BYTES);
  }

  RubygemsGroupIndex(RepositoryRuntime group,
      BiFunction<RepositoryRuntime, String, MavenResponse> get, int maxRows, long maxBytes) {
    this.group = group;
    this.get = get;
    this.maxRows = maxRows;
    this.maxBytes = maxBytes;
  }

  static boolean handles(String path) {
    return isSpecs(path) || path.equals("names")
        || path.equals("versions") || path.startsWith("info/")
        || path.equals("api/v1/dependencies") || path.startsWith("api/v1/dependencies?");
  }

  private static boolean isSpecs(String path) {
    return path.equals("specs.4.8.gz") || path.equals("latest_specs.4.8.gz")
        || path.equals("prerelease_specs.4.8.gz");
  }

  MavenResponse get(String path, boolean headOnly) {
    boolean acquired = false;
    try {
      acquired = LARGE_INDEX_SLOT.tryLock(30, TimeUnit.SECONDS);
      if (!acquired) return MavenResponse.noBody(503).withHeader("Retry-After", "1");
      byte[] body;
      String contentType;
      if (isSpecs(path)) {
        body = specs(path); contentType = "application/octet-stream";
      } else if (path.startsWith("api/v1/dependencies")) {
        body = dependencies(path); contentType = "application/octet-stream";
      } else {
        body = switch (path) {
          case "versions" -> versions();
          case "names" -> names();
          default -> info(path);
        };
        contentType = "text/plain";
      }
      if (body == null) return MavenResponse.noBody(404);
      String etag = '"' + md5(body) + '"';
      return headOnly ? MavenResponse.noBody(200, body.length, contentType, etag, null)
          : MavenResponse.ok(new ByteArrayInputStream(body), body.length, contentType, etag, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return MavenResponse.noBody(503).withHeader("Retry-After", "1");
    } catch (InconsistentSnapshotException e) {
      return MavenResponse.noBody(404);
    } catch (IOException | IllegalArgumentException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid RubyGems group index: " + path, e);
    } finally {
      if (acquired) LARGE_INDEX_SLOT.unlock();
    }
  }

  private byte[] specs(String path) throws IOException {
    var merged = new LinkedHashSet<RubygemsIndexMarshal.Spec>();
    var budget = budget(false);
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      MavenResponse response = member(member, path);
      if (response == null) continue;
      found = true;
      try (InputStream body = response.body(); InputStream gzip = new GZIPInputStream(body)) {
        RubygemsIndexMarshal.readSpecs(gzip, spec -> {
          if (!merged.contains(spec)) {
            budget.add(spec.name(), spec.version(), spec.platform());
            merged.add(spec);
          }
        });
      }
    }
    if (!found) return null;
    if (path.equals("latest_specs.4.8.gz")) {
      // Nexus group indexes retain each platform's latest release, even when another
      // platform has a newer version. The live cold-proxy compatibility test covers this.
      Map<String, RubygemsIndexMarshal.Spec> latest = new LinkedHashMap<>();
      for (var spec : merged) {
        String key = spec.name() + '\n' + spec.platform();
        latest.merge(key, spec, (left, right) ->
            compareVersions(left.version(), right.version()) >= 0 ? left : right);
      }
      return RubygemsIndexMarshal.specs(latest.values());
    }
    return RubygemsIndexMarshal.specs(merged);
  }

  private byte[] dependencies(String path) throws IOException {
    Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
    var budget = budget(false);
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      MavenResponse response = member(member, path);
      // Returning partial dependency data makes Bundler incorrectly conclude a gem is absent.
      // A member without this optional API requires the client to use the complete legacy index.
      if (response == null) return null;
      found = true;
      try (InputStream body = response.body()) {
        RubygemsIndexMarshal.readDependencies(body, row -> {
          String key = row.get("name") + "\n" + row.get("number") + "\n" + row.get("platform");
          if (!merged.containsKey(key)) {
            budget.add(key, row);
            merged.put(key, row);
          }
        });
      }
    }
    return found ? RubygemsIndexMarshal.dependencies(merged.values()) : null;
  }

  private byte[] names() throws IOException {
    TreeSet<String> names = new TreeSet<>();
    var budget = budget(true);
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      byte[] body = text(member, "names");
      if (body == null) continue;
      found = true;
      for (String line : lines(body)) {
        if (!line.isBlank() && !names.contains(line)) {
          budget.add(line);
          names.add(line);
        }
      }
    }
    return found ? utf8("---\n" + String.join("\n", names) + (names.isEmpty() ? "" : "\n")) : null;
  }

  private byte[] info(String path) throws IOException {
    return info(path, List.of());
  }

  private byte[] info(String path, List<Contribution> expected) throws IOException {
    byte[] first = null;
    boolean identical = true;
    Map<String, String> merged = new TreeMap<>();
    var budget = budget(true);
    for (RepositoryRuntime member : group.members()) {
      Contribution source = expected.stream().filter(row -> row.member.equals(member)).findFirst().orElse(null);
      byte[] body = text(member, path);
      if (body == null) {
        if (source != null && !source.versions.isEmpty()) return null;
        continue;
      }
      Map<String, String> rows = infoRows(body);
      if (!expected.isEmpty()) {
        if (source == null ? !rows.isEmpty() : !matchesSource(rows.keySet(), body, source)) {
          throw new InconsistentSnapshotException();
        }
      }
      if (Arrays.equals(first, body)) continue;
      if (rows.isEmpty()) {
        continue;
      }
      if (first == null) first = body; else identical = false;
      rows.forEach((version, line) -> {
        if (!merged.containsKey(version)) {
          budget.add(version, line);
          merged.put(version, line);
        }
      });
    }
    if (first == null) return null;
    // Preserve original bytes/checksums when exactly one source contributes or mirrors agree.
    if (identical) return first;
    return utf8("---\n" + String.join("\n", merged.values()) + "\n");
  }

  private static boolean matchesSource(Set<String> actual, byte[] body, Contribution source) {
    Set<String> expected = source.versions.isEmpty() ? Set.of()
        : new LinkedHashSet<>(List.of(source.versions.split(",")));
    if (actual.equals(expected)) return true;
    // Nexus may omit platforms in /versions while /info includes them. Accept only a
    // checksum-verified member snapshot and only platform expansions of advertised versions.
    if (source.checksum.isEmpty() || !source.checksum.equalsIgnoreCase(md5(body))) return false;
    Set<String> represented = new LinkedHashSet<>();
    for (String version : actual) {
      if (expected.contains(version)) { represented.add(version); continue; }
      int separator = version.indexOf('-');
      String number = separator < 0 ? version : version.substring(0, separator);
      if (!expected.contains(number)) return false;
      represented.add(number);
    }
    return represented.equals(expected);
  }

  private byte[] versions() throws IOException {
    Map<String, Versions> merged = new TreeMap<>();
    var budget = budget(true);
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      byte[] body = text(member, "versions");
      // Legacy-only members cannot be represented by a partial compact index. Let clients
      // fall back to specs.4.8.gz rather than hiding that member's uncached packages.
      if (body == null) return null;
      found = true;
      Map<String, Versions> current = versionRows(body);
      current.forEach((name, row) -> {
        Contribution source = new Contribution(member, row.versions, row.checksum);
        Versions first = merged.get(name);
        if (first == null) {
          budget.add(name, row.versions, row.checksum);
          budget.charge(32);
          merged.put(name, new Versions(row.versions, row.checksum, row.conflict,
              List.of(source), false));
        } else {
          budget.charge(32 + IndexBudget.size(List.of(row.versions, row.checksum)));
          var combined = new ArrayList<>(first.contributors);
          combined.add(source);
          List<Contribution> contributors = List.copyOf(combined);
          boolean conflict = first.conflict || row.conflict
              || !first.versions.equals(row.versions) || !first.checksum.equals(row.checksum);
          String active = first.versions;
          if (!row.versions.isEmpty() && !active.equals(row.versions)) {
            var versions = new LinkedHashSet<String>();
            if (!active.isEmpty()) versions.addAll(List.of(active.split(",")));
            versions.addAll(List.of(row.versions.split(",")));
            active = String.join(",", versions);
            budget.replace(List.of(name, first.versions, first.checksum), List.of(name, active, first.checksum));
          }
          merged.put(name, new Versions(active, first.checksum, conflict, contributors, true));
        }
      });
    }
    if (!found) return null;
    StringBuilder result = new StringBuilder("---\n");
    for (var entry : merged.entrySet()) {
      Versions row = entry.getValue();
      // Append-only histories can leave no active versions in any member. Do not let
      // stale info resurrect a fully yanked gem, or require its already removed info.
      if (row.versions.isEmpty()) continue;
      if (row.conflict) {
        boolean strict = row.multipleSources;
        byte[] info = info("info/" + entry.getKey(), row.contributors);
        if (info == null) {
          // Do not publish a cached 200 response that silently drops a conflicting member.
          // A stale, single-source missing-checksum entry can still be skipped as before.
          if (strict) return null;
          continue;
        }
        var available = infoRows(info).keySet();
        Versions resolved = new Versions(String.join(",", available), md5(info), false);
        budget.replace(List.of(entry.getKey(), row.versions, row.checksum),
            List.of(entry.getKey(), resolved.versions, resolved.checksum));
        row = resolved;
      }
      if (!row.versions.isEmpty()) result.append(entry.getKey()).append(' ').append(row.versions)
          .append(' ').append(row.checksum).append('\n');
    }
    return utf8(result.toString());
  }

  private Map<String, Versions> versionRows(byte[] body) {
    Map<String, Versions> result = new LinkedHashMap<>();
    var budget = budget(true);
    for (String line : lines(body)) {
      if (line.isBlank()) continue;
      String[] fields = line.strip().split("\\s+", 3);
      if (fields.length < 2) throw invalid();
      // Bundler accepts an omitted checksum as well as an explicitly empty field.
      String checksum = fields.length == 3 ? fields[2] : "";
      if (!checksum.isEmpty() && !checksum.matches("[a-fA-F0-9]{32}")) throw invalid();
      Versions previous = result.get(fields[0]);
      String versions = fields[1];
      if (previous != null || versions.startsWith("-") || versions.contains(",-")) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (previous != null && !previous.versions.isEmpty()) values.addAll(List.of(previous.versions.split(",")));
        for (String version : versions.split(",")) {
          if (version.startsWith("-")) values.remove(version.substring(1)); else values.add(version);
        }
        versions = String.join(",", values);
      }
      if (previous == null) budget.add(fields[0], versions, checksum);
      else budget.replace(List.of(fields[0], previous.versions, previous.checksum),
          List.of(fields[0], versions, checksum));
      result.put(fields[0], new Versions(versions, checksum, checksum.isEmpty()));
    }
    return result;
  }

  private Map<String, String> infoRows(byte[] body) {
    Map<String, String> result = new LinkedHashMap<>();
    var budget = budget(true);
    for (String line : lines(body)) {
      if (line.isBlank()) continue;
      // Bundler's GemParser allows a bare version/platform token, dependencies without
      // requirements, and requirements without dependencies. Preserve the original row.
      String version = line.stripLeading().split("\\s+", 2)[0];
      String previous = result.get(version);
      if (previous == null) budget.add(version, line);
      else budget.replace(List.of(version, previous), List.of(version, line));
      result.put(version, line);
    }
    return result;
  }

  private static Iterable<String> lines(byte[] body) {
    String text = new String(body, StandardCharsets.UTF_8);
    boolean header = text.lines().anyMatch("---"::equals);
    // Do not allocate a list for every input line before the aggregate limits can run.
    return () -> (header ? text.lines().dropWhile(line -> !line.equals("---")).skip(1)
        : text.lines()).iterator();
  }

  private byte[] text(RepositoryRuntime member, String path) throws IOException {
    MavenResponse response = member(member, path);
    if (response == null) return null;
    try (InputStream body = response.body()) {
      byte[] bytes = body.readNBytes(MAX_TEXT_BYTES + 1);
      if (bytes.length > MAX_TEXT_BYTES) throw invalid();
      return bytes;
    }
  }

  private MavenResponse member(RepositoryRuntime member, String path) {
    try {
      MavenResponse response = get.apply(member, path);
      if (response.status() == 200 && response.hasBody()) return response;
      response.closeBodyIfOpen();
    } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException
        | MavenExceptions.MethodNotAllowed ignored) {
      // Match raw group fallback for missing, offline or unavailable members.
    }
    return null;
  }

  private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

  private IndexBudget budget(boolean text) {
    return new IndexBudget(maxRows, text ? Math.min(maxBytes, MAX_TEXT_BYTES) : maxBytes);
  }

  /** Limits retained aggregate rows and payload, independently of each member's input limits. */
  private static final class IndexBudget {
    private final int maxRows;
    private final long maxBytes;
    private int rows;
    private long bytes;

    private IndexBudget(int maxRows, long maxBytes) { this.maxRows = maxRows; this.maxBytes = maxBytes; }

    void add(Object... values) {
      if (rows >= maxRows) throw oversized();
      check(bytes + size(List.of(values)));
      rows++;
    }

    void replace(Object previous, Object replacement) { check(bytes - size(previous) + size(replacement)); }
    void charge(long additional) { check(bytes + additional); }

    private void check(long next) {
      if (next > maxBytes) throw oversized();
      bytes = next;
    }

    private static long size(Object value) {
      // Count UTF-8 payload plus conservative framing overhead, including nested dependencies.
      if (value instanceof String string) return 8L + utf8(string).length;
      if (value instanceof RubygemsIndexMarshal.Version version) return 16L + size(version.value());
      long bytes = 16;
      if (value instanceof Map<?, ?> map) {
        for (var entry : map.entrySet()) bytes += size(entry.getKey()) + size(entry.getValue());
      } else if (value instanceof Iterable<?> values) {
        for (Object item : values) bytes += size(item);
      }
      return bytes;
    }

    private static IllegalArgumentException oversized() {
      return new IllegalArgumentException("RubyGems group index exceeds aggregate limit");
    }
  }
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("Malformed RubyGems compact index"); }
  private static String md5(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static final Pattern VERSION_PART = Pattern.compile("[0-9]+|[A-Za-z]+");

  // Gem::Version compares numeric segments numerically, strings lexically, and prerelease
  // strings below numeric segments. Numeric components may exceed a Java int or long.
  static int compareVersions(String left, String right) {
    List<String> a = canonicalSegments(left), b = canonicalSegments(right);
    for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
      String x = i < a.size() ? a.get(i) : "0", y = i < b.size() ? b.get(i) : "0";
      boolean xn = Character.isDigit(x.charAt(0)), yn = Character.isDigit(y.charAt(0));
      int comparison = xn && yn ? new BigInteger(x).compareTo(new BigInteger(y))
          : xn != yn ? (xn ? 1 : -1) : x.compareTo(y);
      if (comparison != 0) return comparison;
    }
    return 0;
  }

  private static List<String> canonicalSegments(String version) {
    var parts = new ArrayList<>(VERSION_PART.matcher(version.replace("-", ".pre."))
        .results().map(match -> match.group()).toList());
    // Gem::Version removes trailing numeric zeros from both the release and prerelease
    // portions before comparing: 1.0.0.a.0 and 1.a denote the same version.
    while (!parts.isEmpty() && parts.getLast().chars().allMatch(c -> c == '0')) parts.removeLast();
    int releaseEnd = 0;
    while (releaseEnd < parts.size() && Character.isDigit(parts.get(releaseEnd).charAt(0))) releaseEnd++;
    while (releaseEnd > 0 && parts.get(releaseEnd - 1).chars().allMatch(c -> c == '0')) {
      parts.remove(--releaseEnd);
    }
    return parts;
  }

  private record Versions(String versions, String checksum, boolean conflict,
      List<Contribution> contributors, boolean multipleSources) {
    private Versions(String versions, String checksum, boolean conflict) {
      this(versions, checksum, conflict, List.of(), false);
    }
  }
  private record Contribution(RepositoryRuntime member, String versions, String checksum) { }
  private static final class InconsistentSnapshotException extends IOException { }
}
