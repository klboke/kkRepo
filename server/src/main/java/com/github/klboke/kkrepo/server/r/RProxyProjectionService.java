package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.r.RPackageIndex;
import com.github.klboke.kkrepo.protocol.r.RPackageMetadata;
import com.github.klboke.kkrepo.protocol.r.RPath;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.protocol.r.RVersions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Builds bounded, rebuildable package projections from an upstream PACKAGES.gz. */
@Service
final class RProxyProjectionService {
  private static final Logger log = LoggerFactory.getLogger(RProxyProjectionService.class);
  private static final int MAX_COMPRESSED_INDEX_BYTES = 64 * 1024 * 1024;
  private static final int MAX_EXPANDED_INDEX_BYTES = 256 * 1024 * 1024;
  static final String UNKNOWN_SHA256 = "0".repeat(64);

  private final RRegistryDao registry;
  private final RawProxyService proxy;
  private final RAssetSupport assets;
  private final RSourcePackageInspector inspector;
  private final RComponentFactory components;

  RProxyProjectionService(
      RRegistryDao registry,
      RawProxyService proxy,
      RAssetSupport assets,
      RSourcePackageInspector inspector,
      RComponentFactory components) {
    this.registry = registry;
    this.proxy = proxy;
    this.assets = assets;
    this.inspector = inspector;
    this.components = components;
  }

  MavenResponse get(RepositoryRuntime runtime, RPath path, boolean headOnly) {
    String remote = RemoteUrlBuilder.repositoryPathString(
        runtime.proxyRemoteUrl(), path.normalized());
    MavenResponse fetched = metadata(path)
        ? proxy.getMetadataFromUrlUnindexed(runtime, path.normalized(), remote, false)
        : proxy.getPinnedAssetFromUrlUnindexed(runtime, path.normalized(), remote, false);
    fetched.closeBodyIfOpen();
    if (path.kind() == RPath.Kind.PACKAGES_GZIP) {
      try {
        projectIndex(runtime, path);
      } catch (RuntimeException error) {
        log.warn("R proxy index projection failed repository={} path={}",
            runtime.name(), path.normalized(), error);
      }
    } else if (path.kind() == RPath.Kind.SOURCE_PACKAGE) {
      verifyAndBindPackage(runtime, path);
    }
    return assets.serve(runtime, path.normalized(), headOnly).withHeader(
        "Cache-Control",
        path.packageArchive()
            ? "public, max-age=31536000, immutable"
            : "public, max-age=0, must-revalidate");
  }

  long prepareGroupMember(RepositoryRuntime runtime, Instant now) {
    RPath index = new RPathParser().parse(RService.SOURCE_NAMESPACE + "/PACKAGES.gz");
    String remote = RemoteUrlBuilder.repositoryPathString(
        runtime.proxyRemoteUrl(), index.normalized());
    MavenResponse fetched = proxy.getMetadataFromUrlUnindexed(
        runtime, index.normalized(), remote, false);
    fetched.closeBodyIfOpen();
    // A group snapshot must never continue with a stale projection after a malformed index.
    // The direct proxy path remains best-effort for arbitrary static mirrors, while group
    // publication deliberately fails closed.
    projectIndex(runtime, index);
    RRegistryDao.SuiteState state = registry.findSuite(runtime.id(), RService.SOURCE_NAMESPACE)
        .orElseGet(() -> registry.ensureSuite(
            runtime.id(), RService.SOURCE_NAMESPACE, now == null ? Instant.now() : now));
    return state.desiredRevision();
  }

  MavenResponse getBoundGroupPackage(
      RepositoryRuntime runtime,
      RRegistryDao.GroupBinding binding,
      boolean headOnly) {
    RRegistryDao.PackageRecord expected = registry.findPackageByPath(
        runtime.id(), binding.memberPath()).orElseThrow(
            () -> new MavenExceptions.MavenNotFoundException(binding.memberPath()));
    if (!expected.identity().equalsIgnoreCase(binding.identity())) {
      throw new MavenExceptions.BadUpstreamException(
          "R proxy package index no longer matches its group snapshot binding: "
              + binding.memberPath());
    }
    if (expected.assetId() == null) {
      MavenResponse response = get(
          runtime, new RPathParser().parse(binding.memberPath()), true);
      response.closeBodyIfOpen();
      expected = registry.findPackageByPath(runtime.id(), binding.memberPath()).orElseThrow();
    }
    boolean deferredMd5Binding = UNKNOWN_SHA256.equals(binding.sha256()) && binding.size() == 0;
    if (!expected.identity().equalsIgnoreCase(binding.identity())
        || (deferredMd5Binding
            ? UNKNOWN_SHA256.equals(expected.sha256()) || expected.size() <= 0
            : !expected.sha256().equals(binding.sha256()) || expected.size() != binding.size())) {
      throw new MavenExceptions.BadUpstreamException(
          "R proxy package no longer matches its group snapshot binding: "
              + binding.memberPath());
    }
    return assets.serve(runtime, binding.memberPath(), headOnly);
  }

  private void projectIndex(RepositoryRuntime runtime, RPath path) {
    MavenResponse cached = assets.serve(runtime, path.normalized(), false);
    MessageDigest digest = digest("SHA-256");
    byte[] expanded;
    try (InputStream body = bounded(cached.body(), MAX_COMPRESSED_INDEX_BYTES);
        DigestInputStream hashed = new DigestInputStream(body, digest);
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(hashed, false)) {
      expanded = readBounded(gzip, MAX_EXPANDED_INDEX_BYTES);
      while (hashed.read() >= 0) {
        // Consume any gzip trailer bytes so the release identity covers the complete blob.
      }
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid upstream R PACKAGES.gz", error);
    }
    String releaseIdentity = HexFormat.of().formatHex(digest.digest());
    if (registry.findProxyDistribution(runtime.id(), RService.SOURCE_NAMESPACE)
        .filter(RRegistryDao.ProxyDistribution::projectionVerified)
        .map(RRegistryDao.ProxyDistribution::releaseIdentity)
        .filter(releaseIdentity::equals)
        .isPresent()) {
      return;
    }
    List<RPackageMetadata> packages;
    try {
      packages = RPackageIndex.parse(expanded);
    } catch (IllegalArgumentException error) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid upstream R PACKAGES.gz DCF", error);
    }
    LinkedHashSet<String> current = new LinkedHashSet<>();
    LinkedHashMap<String, RRegistryDao.ProxyIndex> manifest = new LinkedHashMap<>();
    Instant observedAt = Instant.now();
    for (RPackageMetadata metadata : packages) {
      String filename = metadata.fields().getOrDefault(
          "File", RPathParser.sourceFilename(metadata.packageName(), metadata.version()));
      String relative = metadata.fields().get("Path");
      String packagePath = relative == null || relative.isBlank()
          ? RService.SOURCE_NAMESPACE + "/" + filename
          : RService.SOURCE_NAMESPACE + "/" + relative + "/" + filename;
      RPath parsed = new RPathParser().parse(packagePath);
      if (parsed.kind() != RPath.Kind.SOURCE_PACKAGE
          || !parsed.packageName().equals(metadata.packageName())
          || !parsed.version().equals(metadata.version())) {
        continue;
      }
      String md5 = metadata.fields().get("MD5sum");
      String identity = validMd5(md5)
          ? md5.toLowerCase(java.util.Locale.ROOT)
          : HexFormat.of().formatHex(PersistenceHashes.sha256(
              "r-proxy-index", metadata.packageName(), metadata.version(), packagePath));
      RRegistryDao.PackageRecord candidate = new RRegistryDao.PackageRecord(
          null,
          runtime.id(),
          RService.SOURCE_NAMESPACE,
          RService.COMPONENT,
          RService.ARCHITECTURE,
          metadata.packageName(),
          metadata.version(),
          RVersions.orderKey(metadata.version()),
          RService.ARCHITECTURE,
          filename,
          packagePath,
          objectFields(metadata.indexFields(md5, filename)),
          identity,
          UNKNOWN_SHA256,
          UNKNOWN_SHA256,
          0,
          null,
          null,
          RRegistryDao.SOURCE_PROXY,
          0,
          observedAt,
          observedAt,
          observedAt);
      RRegistryDao.PackageRecord stored = saveProjection(candidate);
      current.add(coordinate(stored));
      manifest.put(stored.path(), new RRegistryDao.ProxyIndex(stored.sha256(), stored.size()));
    }
    removeStale(runtime, current);
    registry.observeProxyDistribution(
        runtime.id(), RService.SOURCE_NAMESPACE,
        releaseIdentity, Map.copyOf(manifest), true, observedAt);
  }

  private RRegistryDao.PackageRecord verifyAndBindPackage(
      RepositoryRuntime runtime, RPath path) {
    MavenResponse cached = assets.serve(runtime, path.normalized(), false);
    try (InputStream body = cached.body();
        RSourcePackageInspector.InspectedPackage inspected =
            inspector.inspect(body, path.filename())) {
      RRegistryDao.PackageRecord expected = registry.findPackageByPath(
          runtime.id(), path.normalized()).orElse(null);
      if (expected != null
          && (!expected.packageName().equals(inspected.metadata().packageName())
              || !expected.version().equals(inspected.metadata().version())
              || (validMd5(expected.identity())
                  && !expected.identity().equalsIgnoreCase(inspected.md5())))) {
        throw new MavenExceptions.BadUpstreamException(
            "Upstream R package does not match PACKAGES.gz: " + path.normalized());
      }
      ComponentRecord component = components.component(
          runtime, inspected.metadata(), inspected.filename(), path.normalized(),
          inspected.md5(), inspected.sha256(), inspected.size(), Instant.now());
      AssetRecord asset = assets.bindProxyPackage(
          runtime,
          path.normalized(),
          component,
          components.browsePath(
              inspected.metadata().packageName(), inspected.metadata().version(),
              inspected.filename()),
          packageAttributes(inspected, "proxy"));
      Instant now = Instant.now();
      RRegistryDao.PackageRecord candidate = new RRegistryDao.PackageRecord(
          expected == null ? null : expected.id(),
          runtime.id(),
          RService.SOURCE_NAMESPACE,
          RService.COMPONENT,
          RService.ARCHITECTURE,
          inspected.metadata().packageName(),
          inspected.metadata().version(),
          RVersions.orderKey(inspected.metadata().version()),
          RService.ARCHITECTURE,
          inspected.filename(),
          path.normalized(),
          objectFields(inspected.metadata().indexFields(inspected.md5(), inspected.filename())),
          inspected.md5(),
          inspected.sha256(),
          inspected.sha256(),
          inspected.size(),
          asset.id(),
          asset.componentId(),
          RRegistryDao.SOURCE_PROXY,
          expected == null ? 0 : expected.revision(),
          expected == null ? now : expected.indexedAt(),
          expected == null ? now : expected.createdAt(),
          now);
      if (expected == null) {
        // A direct proxy request is cacheable and browseable, but it must not become part of a
        // group unless the upstream PACKAGES.gz projection declared it.
        return candidate;
      }
      RRegistryDao.PackageRecord stored = registry.materializeProxyPackage(
          candidate, expected.identity(), expected.revision()).orElse(null);
      if (stored == null) {
        assets.retirePackageProjection(asset.id());
        throw new MavenExceptions.BadUpstreamException(
            "R proxy index changed while the package was being fetched: " + path.normalized());
      }
      registry.replacePackageRelations(
          stored.repositoryId(), stored.id(), relations(stored.id(), inspected.metadata()));
      return stored;
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException(
          "Unable to inspect upstream R package: " + path.normalized(), error);
    } catch (MavenExceptions.BadRequestException invalid) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid upstream R package: " + path.normalized(), invalid);
    }
  }

  private RRegistryDao.PackageRecord saveProjection(RRegistryDao.PackageRecord candidate) {
    RRegistryDao.PackageRecord existing = registry.findPackage(
        candidate.repositoryId(), candidate.distribution(), candidate.component(),
        candidate.packageName(), candidate.version(), candidate.architecture()).orElse(null);
    if (existing != null
        && existing.path().equals(candidate.path())
        && candidate.identity().equals(existing.identity())
        && candidate.controlFields().equals(existing.controlFields())) {
      return existing;
    }
    if (existing != null) {
      candidate = new RRegistryDao.PackageRecord(
          existing.id(), candidate.repositoryId(), candidate.distribution(), candidate.component(),
          candidate.architecture(), candidate.packageName(), candidate.version(),
          candidate.versionOrderKey(), candidate.packageArchitecture(), candidate.filename(),
          candidate.path(), candidate.controlFields(), candidate.identity(), candidate.dataSha256(),
          candidate.sha256(), candidate.size(), candidate.assetId(), candidate.componentId(),
          candidate.sourceKind(), existing.revision(), candidate.indexedAt(), existing.createdAt(),
          candidate.updatedAt());
    }
    RRegistryDao.PackageRecord saved = registry.savePackage(candidate);
    if (existing != null && existing.assetId() != null
        && !Objects.equals(existing.assetId(), saved.assetId())) {
      assets.retirePackageProjection(existing.assetId());
    }
    return saved;
  }

  private void removeStale(RepositoryRuntime runtime, Set<String> current) {
    String afterName = "";
    long afterId = 0;
    while (true) {
      List<RRegistryDao.PackageRecord> page = registry.listPackagePage(
          runtime.id(), RService.SOURCE_NAMESPACE, afterName, afterId,
          RRegistryDao.PACKAGE_PAGE_SIZE);
      if (page.isEmpty()) return;
      RRegistryDao.PackageRecord cursor = page.getLast();
      for (RRegistryDao.PackageRecord stale : page) {
        if (!RRegistryDao.SOURCE_PROXY.equals(stale.sourceKind())
            || current.contains(coordinate(stale))) continue;
        registry.deletePackage(
            stale.repositoryId(), stale.distribution(), stale.component(), stale.packageName(),
            stale.version(), stale.architecture(), "upstream-index-replaced", Instant.now());
        assets.retirePackageProjection(stale.assetId());
      }
      if (page.size() < RRegistryDao.PACKAGE_PAGE_SIZE) return;
      afterName = cursor.packageName();
      afterId = cursor.id();
    }
  }

  static Map<String, Object> packageAttributes(
      RSourcePackageInspector.InspectedPackage inspected, String source) {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    values.put("rPackage", inspected.metadata().packageName());
    values.put("rVersion", inspected.metadata().version());
    values.put("rNamespace", RService.SOURCE_NAMESPACE);
    values.put("rMd5", inspected.md5());
    values.put("rSha256", inspected.sha256());
    values.put("rSize", inspected.size());
    values.put("rSource", source);
    values.put("rInputSchema", "r-source-package-v1");
    values.put("rDependencies", inspected.metadata().dependencies());
    String license = inspected.metadata().fields().get("License");
    if (license != null) values.put("rLicense", license);
    return Map.copyOf(values);
  }

  static List<RRegistryDao.PackageRelation> relations(
      long packageId, RPackageMetadata metadata) {
    ArrayList<RRegistryDao.PackageRelation> result = new ArrayList<>();
    metadata.dependencies().forEach((kind, expressions) -> {
      for (String expression : expressions.split(",")) {
        String token = dependencyToken(expression);
        if (!token.isBlank()) {
          result.add(new RRegistryDao.PackageRelation(
              packageId, kind.toUpperCase(java.util.Locale.ROOT), token, expression.trim()));
        }
      }
    });
    return List.copyOf(result);
  }

  private static String dependencyToken(String expression) {
    if (expression == null) return "";
    String value = expression.trim();
    int open = value.indexOf('(');
    if (open >= 0) value = value.substring(0, open).trim();
    int bracket = value.indexOf('[');
    if (bracket >= 0) value = value.substring(0, bracket).trim();
    return RPathParser.validPackageName(value) ? value : "";
  }

  private static Map<String, Object> objectFields(Map<String, String> fields) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.putAll(fields);
    return Map.copyOf(result);
  }

  private static boolean metadata(RPath path) {
    return path.kind() == RPath.Kind.PACKAGES
        || path.kind() == RPath.Kind.PACKAGES_GZIP
        || path.kind() == RPath.Kind.PACKAGES_RDS;
  }

  static boolean validMd5(String value) {
    return value != null && value.matches("(?i)[0-9a-f]{32}");
  }

  private static String coordinate(RRegistryDao.PackageRecord row) {
    return row.packageName() + '\0' + row.version();
  }

  private static InputStream bounded(InputStream delegate, long maxBytes) {
    return new InputStream() {
      private long count;

      @Override
      public int read() throws IOException {
        int value = delegate.read();
        if (value >= 0 && ++count > maxBytes) throw new IOException("R index exceeds limit");
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        int read = delegate.read(bytes, offset, length);
        if (read > 0 && (count += read) > maxBytes) throw new IOException("R index exceeds limit");
        return read;
      }

      @Override
      public void close() throws IOException {
        delegate.close();
      }
    };
  }

  private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[64 * 1024];
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (read == 0) continue;
      if (output.size() + read > maxBytes) throw new IOException("R index expands beyond limit");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
