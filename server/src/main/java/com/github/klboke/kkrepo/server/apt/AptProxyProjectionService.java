package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.apt.AptDeb822;
import com.github.klboke.kkrepo.protocol.apt.AptPackageControl;
import com.github.klboke.kkrepo.protocol.apt.AptPath;
import com.github.klboke.kkrepo.protocol.apt.AptPathParser;
import com.github.klboke.kkrepo.protocol.apt.AptRelease;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Verifies upstream Release/Packages/package checksum chains and maintains proxy projections. */
@Service
final class AptProxyProjectionService {
  private static final Logger log = LoggerFactory.getLogger(AptProxyProjectionService.class);
  private static final int MAX_RELEASE_BYTES = 8 * 1024 * 1024;
  private static final long MAX_EXPANDED_INDEX_BYTES = 1024L * 1024 * 1024;
  private static final int MAX_INDEX_STANZAS = 500_000;
  private static final int MAX_RESIGN_PACKAGES = 10_000;
  private static final long MAX_RESIGN_PACKAGE_BYTES = 20L * 1024 * 1024 * 1024;

  private final AptRegistryDao registry;
  private final RawProxyService proxy;
  private final AptAssetSupport assets;
  private final AptComponentFactory components;
  private final AptPathParser paths = new AptPathParser();

  AptProxyProjectionService(
      AptRegistryDao registry,
      RawProxyService proxy,
      AptAssetSupport assets,
      AptComponentFactory components) {
    this.registry = registry;
    this.proxy = proxy;
    this.assets = assets;
    this.components = components;
  }

  /** Best-effort projection must never alter passthrough response semantics. */
  void observePassthrough(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      AptPath requested) {
    if (requested.kind() == AptPath.Kind.PACKAGE) {
      verifyAndBindKnownPackage(runtime, requested.normalized());
      return;
    }
    try {
      if (requested.kind() == AptPath.Kind.RELEASE
          || requested.kind() == AptPath.Kind.IN_RELEASE) {
        if (requested.distribution() != null) refreshRelease(runtime, requested.distribution());
      } else if (requested.kind() == AptPath.Kind.PACKAGES) {
        projectIndex(runtime, requested, false);
      }
    } catch (RuntimeException failure) {
      log.warn(
          "APT passthrough projection skipped for repository {} path {}: {}",
          runtime.name(), requested.normalized(), failure.getMessage());
    }
  }

  /** Builds a complete local projection only after every advertised package is cached and verified. */
  void refreshForResign(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      String distribution) {
    AptRegistryDao.ProxyDistribution release = refreshRelease(runtime, distribution);
    LinkedHashMap<String, AptRegistryDao.PackageRecord> projected = new LinkedHashMap<>();
    LinkedHashSet<String> indexArchitectures = new LinkedHashSet<>(settings.architectures());
    indexArchitectures.add("all");
    for (String architecture : indexArchitectures) {
      String indexPath = chooseIndexPath(
          release, distribution, settings.component(), architecture);
      if (indexPath == null) {
        if ("all".equals(architecture)) continue;
        throw new MavenExceptions.BadUpstreamException(
            "Upstream APT Release does not publish Packages for "
                + settings.component() + "/" + architecture);
      }
      AptPath parsed = paths.parse(indexPath);
      for (AptRegistryDao.PackageRecord row : parseIndex(runtime, release, parsed)) {
        projected.put(coordinate(row), row);
      }
    }
    if (projected.size() > MAX_RESIGN_PACKAGES) {
      throw new MavenExceptions.BadUpstreamException(
          "APT re-sign projection exceeds package limit: " + projected.size());
    }
    long totalBytes = 0;
    for (AptRegistryDao.PackageRecord row : projected.values()) {
      try {
        totalBytes = Math.addExact(totalBytes, row.size());
      } catch (ArithmeticException overflow) {
        throw new MavenExceptions.BadUpstreamException("APT re-sign package size overflow");
      }
    }
    if (totalBytes > MAX_RESIGN_PACKAGE_BYTES) {
      throw new MavenExceptions.BadUpstreamException(
          "APT re-sign projection exceeds cached byte limit: " + totalBytes);
    }

    ArrayList<AptRegistryDao.PackageRecord> verified = new ArrayList<>(projected.size());
    for (AptRegistryDao.PackageRecord upstream : projected.values()) {
      AptRegistryDao.PackageRecord local = localizedForResign(upstream);
      fetchPackage(runtime, local.path(), upstream.path());
      verified.add(bindVerifiedPackage(runtime, local));
    }
    Set<String> currentCoordinates = new LinkedHashSet<>();
    for (AptRegistryDao.PackageRecord row : verified) {
      AptRegistryDao.PackageRecord saved = saveIfChanged(row);
      currentCoordinates.add(coordinate(saved));
    }
    for (AptRegistryDao.PackageRecord stale : registry.listPackages(runtime.id(), distribution)) {
      if (!AptRegistryDao.SOURCE_PROXY.equals(stale.sourceKind())
          || !settings.component().equals(stale.component())
          || currentCoordinates.contains(coordinate(stale))) {
        continue;
      }
      registry.deletePackage(
          stale.repositoryId(), stale.distribution(), stale.component(), stale.packageName(),
          stale.version(), stale.architecture(), "upstream-release-replaced", Instant.now());
      assets.retirePackageProjection(stale.assetId());
    }
  }

  AptRegistryDao.PackageRecord verifyAndBindKnownPackage(
      RepositoryRuntime runtime, String packagePath) {
    AptRegistryDao.PackageRecord expected = registry.findPackageByPath(runtime.id(), packagePath)
        .orElse(null);
    if (expected == null) return null;
    return saveIfChanged(bindVerifiedPackage(runtime, expected));
  }

  private AptRegistryDao.ProxyDistribution refreshRelease(
      RepositoryRuntime runtime, String distribution) {
    String path = "dists/" + distribution + "/Release";
    MavenResponse response = proxy.getMetadataFromUrlUnindexed(
        runtime,
        path,
        RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), path),
        false);
    response.closeBodyIfOpen();
    AssetBlobRecord blob = assets.requireBlob(runtime, path);
    byte[] releaseBytes = readBounded(assets.serve(runtime, path, false), MAX_RELEASE_BYTES);
    AptRelease release;
    try {
      release = AptRelease.parse(new String(
          releaseBytes, java.nio.charset.StandardCharsets.UTF_8));
    } catch (RuntimeException invalid) {
      throw new MavenExceptions.BadUpstreamException("Invalid upstream APT Release", invalid);
    }
    LinkedHashMap<String, AptRegistryDao.ProxyIndex> indices = new LinkedHashMap<>();
    String prefix = "dists/" + distribution + "/";
    for (AptRelease.Checksum checksum : release.checksums()) {
      if (!"SHA256".equals(checksum.algorithm())) continue;
      String fullPath = prefix + checksum.path();
      AptPath parsed = paths.parse(fullPath);
      if (parsed.kind() == AptPath.Kind.UNKNOWN || parsed.kind() == AptPath.Kind.ROOT) {
        throw new MavenExceptions.BadUpstreamException(
            "Unsafe upstream APT Release target: " + checksum.path());
      }
      indices.put(fullPath, new AptRegistryDao.ProxyIndex(checksum.digest(), checksum.size()));
    }
    registry.observeProxyDistribution(
        runtime.id(), distribution, blob.sha256(), Map.copyOf(indices), false, Instant.now());
    return registry.findProxyDistribution(runtime.id(), distribution).orElseThrow();
  }

  private void projectIndex(RepositoryRuntime runtime, AptPath path, boolean requireReleaseEntry) {
    if (path.distribution() == null) return;
    AptRegistryDao.ProxyDistribution release = registry.findProxyDistribution(
        runtime.id(), path.distribution()).orElseGet(
            () -> refreshRelease(runtime, path.distribution()));
    AptRegistryDao.ProxyIndex expected = release.indices().get(path.normalized());
    if (expected == null) {
      if (requireReleaseEntry) {
        throw new MavenExceptions.BadUpstreamException(
            "APT Packages index is not covered by upstream Release: " + path.normalized());
      }
      return;
    }
    for (AptRegistryDao.PackageRecord row : parseIndex(runtime, release, path)) {
      saveIfChanged(row);
    }
  }

  private List<AptRegistryDao.PackageRecord> parseIndex(
      RepositoryRuntime runtime,
      AptRegistryDao.ProxyDistribution release,
      AptPath indexPath) {
    AptRegistryDao.ProxyIndex expected = release.indices().get(indexPath.normalized());
    if (expected == null) {
      throw new MavenExceptions.BadUpstreamException(
          "APT Packages index is not covered by upstream Release: " + indexPath.normalized());
    }
    MavenResponse fetched = proxy.getMetadataFromUrlUnindexed(
        runtime,
        indexPath.normalized(),
        RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), indexPath.normalized()),
        false);
    fetched.closeBodyIfOpen();
    verifyBlob("Packages index", assets.requireBlob(runtime, indexPath.normalized()), expected);

    MavenResponse cached = assets.serve(runtime, indexPath.normalized(), false);
    ArrayList<AptRegistryDao.PackageRecord> records = new ArrayList<>();
    try (InputStream encoded = cached.body(); InputStream decoded = decompress(
        encoded, indexPath.compression())) {
      AptDeb822.forEach(
          decoded,
          MAX_EXPANDED_INDEX_BYTES,
          MAX_INDEX_STANZAS,
          AptDeb822.DEFAULT_MAX_FIELDS,
          AptDeb822.DEFAULT_MAX_LINE_LENGTH,
          stanza -> records.add(packageRecord(runtime, indexPath, stanza)));
    } catch (IOException | RuntimeException invalid) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid upstream APT Packages index: " + indexPath.normalized(), invalid);
    }
    return List.copyOf(records);
  }

  private AptRegistryDao.PackageRecord packageRecord(
      RepositoryRuntime runtime, AptPath indexPath, AptDeb822.Stanza stanza) {
    AptPackageControl control = AptPackageControl.from(stanza);
    if (!Objects.equals(indexPath.architecture(), control.architecture())
        && !"all".equals(control.architecture())) {
      throw new IllegalArgumentException(
          "APT package architecture does not match index: " + control.architecture());
    }
    String path = requirePackagePath(stanza.require("Filename"));
    long size = positiveLong("Size", stanza.require("Size"));
    String sha256 = digest("SHA256", stanza.require("SHA256"), 64);
    String md5 = optionalDigest("MD5sum", stanza.get("MD5sum"), 32);
    String sha1 = optionalDigest("SHA1", stanza.get("SHA1"), 40);
    Instant now = Instant.now();
    String filename = path.substring(path.lastIndexOf('/') + 1);
    return new AptRegistryDao.PackageRecord(
        null,
        runtime.id(),
        indexPath.distribution(),
        indexPath.component(),
        control.architecture(),
        control.packageName(),
        control.version(),
        sourcePackage(control),
        filename,
        path,
        objectFields(control.fields()),
        md5,
        sha1,
        sha256,
        size,
        null,
        null,
        AptRegistryDao.SOURCE_PROXY,
        0,
        now,
        now,
        now);
  }

  private AptRegistryDao.PackageRecord bindVerifiedPackage(
      RepositoryRuntime runtime, AptRegistryDao.PackageRecord expected) {
    AssetBlobRecord blob = assets.requireBlob(runtime, expected.path());
    verifyBlob(
        "package",
        blob,
        new AptRegistryDao.ProxyIndex(expected.sha256(), expected.size()));
    AptPackageControl control = packageControl(expected);
    ComponentRecord component = components.component(
        runtime,
        expected.distribution(),
        expected.component(),
        control,
        expected.filename(),
        expected.path(),
        Instant.now());
    AssetRecord asset = assets.bindProxyPackage(
        runtime,
        expected.path(),
        component,
        components.browsePath(
            expected.distribution(), expected.component(), control, expected.filename()),
        Map.of(
            "aptDistribution", expected.distribution(),
            "aptComponent", expected.component(),
            "aptArchitecture", expected.architecture(),
            "aptPackage", expected.packageName(),
            "aptVersion", expected.version(),
            "aptSha256", expected.sha256(),
            "aptSize", expected.size(),
            "aptSource", "proxy"));
    Instant now = Instant.now();
    return new AptRegistryDao.PackageRecord(
        expected.id(), expected.repositoryId(), expected.distribution(), expected.component(),
        expected.architecture(), expected.packageName(), expected.version(),
        expected.sourcePackage(), expected.filename(), expected.path(), expected.controlFields(),
        expected.md5(), expected.sha1(), expected.sha256(), expected.size(), asset.id(),
        asset.componentId(), AptRegistryDao.SOURCE_PROXY, expected.revision(),
        expected.indexedAt(), expected.createdAt(), now);
  }

  private AptRegistryDao.PackageRecord saveIfChanged(AptRegistryDao.PackageRecord candidate) {
    AptRegistryDao.PackageRecord existing = registry.findPackage(
        candidate.repositoryId(), candidate.distribution(), candidate.component(),
        candidate.packageName(), candidate.version(), candidate.architecture()).orElse(null);
    if (existing != null && sameProjection(existing, candidate)) return existing;
    if (existing != null && candidate.assetId() == null
        && existing.assetId() != null && existing.sha256().equals(candidate.sha256())) {
      candidate = new AptRegistryDao.PackageRecord(
          existing.id(), candidate.repositoryId(), candidate.distribution(), candidate.component(),
          candidate.architecture(), candidate.packageName(), candidate.version(),
          candidate.sourcePackage(), candidate.filename(), candidate.path(), candidate.controlFields(),
          candidate.md5(), candidate.sha1(), candidate.sha256(), candidate.size(),
          existing.assetId(), existing.componentId(), AptRegistryDao.SOURCE_PROXY,
          existing.revision(), candidate.indexedAt(), existing.createdAt(), candidate.updatedAt());
    }
    AptRegistryDao.PackageRecord saved = registry.savePackage(candidate);
    if (existing != null && existing.assetId() != null
        && (!Objects.equals(existing.assetId(), saved.assetId())
            || !existing.path().equals(saved.path()))) {
      assets.retirePackageProjection(existing.assetId());
    }
    return saved;
  }

  private void fetchPackage(
      RepositoryRuntime runtime, String localPath, String upstreamPath) {
    MavenResponse response = proxy.getPinnedAssetFromUrlUnindexed(
        runtime,
        localPath,
        RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), upstreamPath),
        true);
    response.closeBodyIfOpen();
  }

  private static AptRegistryDao.PackageRecord localizedForResign(
      AptRegistryDao.PackageRecord upstream) {
    return new AptRegistryDao.PackageRecord(
        upstream.id(), upstream.repositoryId(), upstream.distribution(), upstream.component(),
        upstream.architecture(), upstream.packageName(), upstream.version(),
        upstream.sourcePackage(), upstream.filename(), upstream.path(), upstream.controlFields(),
        upstream.md5(), upstream.sha1(), upstream.sha256(), upstream.size(), null,
        upstream.componentId(), upstream.sourceKind(), upstream.revision(), upstream.indexedAt(),
        upstream.createdAt(), upstream.updatedAt());
  }

  private static String chooseIndexPath(
      AptRegistryDao.ProxyDistribution release,
      String distribution,
      String component,
      String architecture) {
    String base = "dists/" + distribution + "/" + component
        + "/binary-" + architecture + "/Packages";
    for (String suffix : List.of(".xz", ".gz", ".bz2", ".zst", "")) {
      String candidate = base + suffix;
      if (release.indices().containsKey(candidate)) return candidate;
    }
    return null;
  }

  private String requirePackagePath(String value) {
    String path = value == null ? "" : value.trim();
    AptPath parsed = paths.parse(path);
    if (parsed.kind() != AptPath.Kind.PACKAGE || !path.equals(parsed.normalized())) {
      throw new IllegalArgumentException("Unsafe APT package Filename: " + value);
    }
    return path;
  }

  private static void verifyBlob(
      String label, AssetBlobRecord actual, AptRegistryDao.ProxyIndex expected) {
    if (actual.size() != expected.size()
        || actual.sha256() == null
        || !actual.sha256().equalsIgnoreCase(expected.sha256())) {
      throw new MavenExceptions.BadUpstreamException(
          "Upstream APT " + label + " checksum or size mismatch");
    }
  }

  private static InputStream decompress(InputStream input, AptPath.Compression compression)
      throws IOException {
    return switch (compression) {
      case NONE -> input;
      case GZIP -> new GzipCompressorInputStream(input, false);
      case BZIP2 -> new BZip2CompressorInputStream(input, false);
      case XZ -> new XZCompressorInputStream(input, false);
      case ZSTD -> new ZstdCompressorInputStream(input);
    };
  }

  private static byte[] readBounded(MavenResponse response, int maxBytes) {
    try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > maxBytes) {
          throw new MavenExceptions.BadUpstreamException("Upstream APT Release exceeds limit");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException("Unable to read upstream APT Release", error);
    }
  }

  private static AptPackageControl packageControl(AptRegistryDao.PackageRecord record) {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    record.controlFields().forEach((name, value) -> {
      if (value != null) fields.put(name, value.toString());
    });
    return AptPackageControl.from(new AptDeb822.Stanza(fields));
  }

  private static String sourcePackage(AptPackageControl control) {
    return control.sourcePackageName();
  }

  private static Map<String, Object> objectFields(Map<String, String> fields) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    fields.forEach(result::put);
    return Map.copyOf(result);
  }

  private static long positiveLong(String field, String value) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed < 0) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Invalid APT " + field + ": " + value, invalid);
    }
  }

  private static String digest(String field, String value, int length) {
    String result = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (result.length() != length
        || !result.chars().allMatch(character -> Character.digit(character, 16) >= 0)) {
      throw new IllegalArgumentException("Invalid APT " + field + " digest");
    }
    return result;
  }

  private static String optionalDigest(String field, String value, int length) {
    return value == null || value.isBlank() ? null : digest(field, value, length);
  }

  private static String coordinate(AptRegistryDao.PackageRecord row) {
    return String.join("\u0000", row.distribution(), row.component(), row.packageName(),
        row.version(), row.architecture());
  }

  private static boolean sameProjection(
      AptRegistryDao.PackageRecord left, AptRegistryDao.PackageRecord right) {
    return left.path().equals(right.path())
        && left.sha256().equalsIgnoreCase(right.sha256())
        && left.size() == right.size()
        && Objects.equals(left.md5(), right.md5())
        && Objects.equals(left.sha1(), right.sha1())
        && Objects.equals(left.assetId(), right.assetId())
        && Objects.equals(left.componentId(), right.componentId())
        && left.controlFields().equals(right.controlFields())
        && AptRegistryDao.SOURCE_PROXY.equals(left.sourceKind());
  }
}
