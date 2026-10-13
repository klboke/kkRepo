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
  // A full public index is large. This is a per-process memory bulkhead, not repository state
  // or a distributed lock: every replica independently derives responses from shared members.
  private static final ReentrantLock LARGE_INDEX_SLOT = new ReentrantLock(true);
  private final RepositoryRuntime group;
  private final BiFunction<RepositoryRuntime, String, MavenResponse> get;

  RubygemsGroupIndex(RepositoryRuntime group,
      BiFunction<RepositoryRuntime, String, MavenResponse> get) {
    this.group = group;
    this.get = get;
  }

  static boolean handles(String path) {
    return path.equals("specs.4.8.gz") || path.equals("latest_specs.4.8.gz")
        || path.equals("prerelease_specs.4.8.gz") || path.equals("names")
        || path.equals("versions") || path.startsWith("info/")
        || path.equals("api/v1/dependencies") || path.startsWith("api/v1/dependencies?");
  }

  MavenResponse get(String path, boolean headOnly) {
    boolean large = path.endsWith("specs.4.8.gz") || path.equals("versions") || path.equals("names");
    boolean acquired = false;
    try {
      if (large) {
        acquired = LARGE_INDEX_SLOT.tryLock(30, TimeUnit.SECONDS);
        if (!acquired) return MavenResponse.noBody(503).withHeader("Retry-After", "1");
      }
      byte[] body;
      String contentType;
      if (path.endsWith("specs.4.8.gz")) {
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
    } catch (IOException | IllegalArgumentException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid RubyGems group index: " + path, e);
    } finally {
      if (acquired) LARGE_INDEX_SLOT.unlock();
    }
  }

  private byte[] specs(String path) throws IOException {
    var merged = new LinkedHashSet<RubygemsIndexMarshal.Spec>();
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      MavenResponse response = member(member, path);
      if (response == null) continue;
      found = true;
      try (InputStream body = response.body(); InputStream gzip = new GZIPInputStream(body)) {
        RubygemsIndexMarshal.readSpecs(gzip, merged::add);
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
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      MavenResponse response = member(member, path);
      // Returning partial dependency data makes Bundler incorrectly conclude a gem is absent.
      // A member without this optional API requires the client to use the complete legacy index.
      if (response == null) return null;
      found = true;
      try (InputStream body = response.body()) {
        RubygemsIndexMarshal.readDependencies(body, row -> merged.putIfAbsent(
            row.get("name") + "\n" + row.get("number") + "\n" + row.get("platform"), row));
      }
    }
    return found ? RubygemsIndexMarshal.dependencies(merged.values()) : null;
  }

  private byte[] names() throws IOException {
    TreeSet<String> names = new TreeSet<>();
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      byte[] body = text(member, "names");
      if (body == null) continue;
      found = true;
      for (String line : lines(body)) if (!line.isBlank()) names.add(line);
    }
    return found ? utf8("---\n" + String.join("\n", names) + (names.isEmpty() ? "" : "\n")) : null;
  }

  private byte[] info(String path) throws IOException {
    List<byte[]> bodies = new ArrayList<>();
    for (RepositoryRuntime member : group.members()) {
      byte[] body = text(member, path);
      if (body != null && !infoRows(body).isEmpty()) bodies.add(body);
    }
    if (bodies.isEmpty()) return null;
    byte[] first = bodies.getFirst();
    // Preserve original bytes/checksums when exactly one source contributes or mirrors agree.
    if (bodies.stream().allMatch(body -> Arrays.equals(first, body))) return first;
    Map<String, String> merged = new TreeMap<>();
    for (byte[] body : bodies) infoRows(body).forEach(merged::putIfAbsent);
    return utf8("---\n" + String.join("\n", merged.values()) + "\n");
  }

  private byte[] versions() throws IOException {
    Map<String, Versions> merged = new TreeMap<>();
    boolean found = false;
    for (RepositoryRuntime member : group.members()) {
      byte[] body = text(member, "versions");
      // Legacy-only members cannot be represented by a partial compact index. Let clients
      // fall back to specs.4.8.gz rather than hiding that member's uncached packages.
      if (body == null) return null;
      found = true;
      Map<String, Versions> current = versionRows(body);
      current.forEach((name, row) -> merged.merge(name, row, (first, second) -> {
        if (first.versions.equals(second.versions) && first.checksum.equals(second.checksum)) return first;
        // Only conflicting gem names need per-gem reads. Identical upstream mirrors and
        // disjoint hosted packages must not trigger a request for every public gem.
        return new Versions(first.versions, first.checksum, true);
      }));
    }
    if (!found) return null;
    StringBuilder result = new StringBuilder("---\n");
    for (var entry : merged.entrySet()) {
      Versions row = entry.getValue();
      if (row.conflict) {
        byte[] info = info("info/" + entry.getKey());
        if (info == null) continue;
        row = new Versions(String.join(",", infoRows(info).keySet()), md5(info), false);
      }
      if (!row.versions.isEmpty()) result.append(entry.getKey()).append(' ').append(row.versions)
          .append(' ').append(row.checksum).append('\n');
    }
    return utf8(result.toString());
  }

  private static Map<String, Versions> versionRows(byte[] body) {
    Map<String, Versions> result = new LinkedHashMap<>();
    for (String line : lines(body)) {
      if (line.isBlank()) continue;
      String[] fields = line.split(" ", 3);
      if (fields.length != 3 || (!fields[2].isEmpty() && !fields[2].matches("[a-fA-F0-9]{32}"))) throw invalid();
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
      result.put(fields[0], new Versions(versions, fields[2], fields[2].isEmpty()));
    }
    return result;
  }

  private static Map<String, String> infoRows(byte[] body) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String line : lines(body)) {
      if (line.isBlank()) continue;
      int space = line.indexOf(' ');
      if (space <= 0 || !line.contains("|")) throw invalid();
      result.put(line.substring(0, space), line);
    }
    return result;
  }

  private static List<String> lines(byte[] body) {
    List<String> lines = new String(body, StandardCharsets.UTF_8).lines().toList();
    int header = lines.indexOf("---");
    return header < 0 ? lines : lines.subList(header + 1, lines.size());
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
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("Malformed RubyGems compact index"); }
  private static String md5(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static final Pattern VERSION_PART = Pattern.compile("[0-9]+|[A-Za-z]+");

  // Gem::Version compares numeric segments numerically, strings lexically, and prerelease
  // strings below numeric segments. Numeric components may exceed a Java int or long.
  static int compareVersions(String left, String right) {
    List<String> a = VERSION_PART.matcher(left.replace("-", ".pre.")).results().map(match -> match.group()).toList();
    List<String> b = VERSION_PART.matcher(right.replace("-", ".pre.")).results().map(match -> match.group()).toList();
    for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
      String x = i < a.size() ? a.get(i) : "0", y = i < b.size() ? b.get(i) : "0";
      boolean xn = Character.isDigit(x.charAt(0)), yn = Character.isDigit(y.charAt(0));
      int comparison = xn && yn ? new BigInteger(x).compareTo(new BigInteger(y))
          : xn != yn ? (xn ? 1 : -1) : x.compareTo(y);
      if (comparison != 0) return comparison;
    }
    return 0;
  }

  private record Versions(String versions, String checksum, boolean conflict) { }
}
