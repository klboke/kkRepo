package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePackageInfo;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePath;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Verifies upstream signed indexes and package identities before exposing trusted projections. */
@Service
final class AlpineProxyProjectionService {
  private static final Logger log = LoggerFactory.getLogger(AlpineProxyProjectionService.class);
  private static final int MAX_PASSTHROUGH_OBSERVATION_PACKAGES = 2_000;
  private static final int MAX_RESIGN_PACKAGES = 50_000;
  private static final long MAX_RESIGN_PACKAGE_BYTES = 50L * 1024 * 1024 * 1024;
  private static final String UNKNOWN_SHA256 = "0".repeat(64);

  private final AlpineRegistryDao registry;
  private final RawProxyService proxy;
  private final AlpineAssetSupport assets;
  private final AlpinePackageInspector inspector;
  private final AlpineComponentFactory components;
  private final AlpinePathParser paths = new AlpinePathParser();

  AlpineProxyProjectionService(
      AlpineRegistryDao registry,
      RawProxyService proxy,
      AlpineAssetSupport assets,
      AlpinePackageInspector inspector,
      AlpineComponentFactory components) {
    this.registry = registry;
    this.proxy = proxy;
    this.assets = assets;
    this.inspector = inspector;
    this.components = components;
  }

  /** Projection is best effort in passthrough mode and never rewrites the response bytes. */
  void observePassthrough(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      AlpinePath requested) {
    try {
      if (requested.kind() == AlpinePath.Kind.INDEX) {
        projectIndex(
            runtime, settings, requested, false, MAX_PASSTHROUGH_OBSERVATION_PACKAGES);
      } else if (requested.kind() == AlpinePath.Kind.PACKAGE) {
        verifyAndBindKnownPackage(runtime, settings, requested);
      }
    } catch (RuntimeException failure) {
      log.warn(
          "Alpine passthrough projection skipped for repository {} path {}: {}",
          runtime.name(), requested.normalized(), failure.getMessage());
    }
  }

  void refreshForResign(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      String namespace) {
    AlpinePath indexPath = paths.parse(namespace + "/APKINDEX.tar.gz");
    AlpineIndexArchive.Parsed parsed = projectIndex(
        runtime, settings, indexPath, true, MAX_RESIGN_PACKAGES);
    if (!parsed.signatureVerified()) {
      throw new MavenExceptions.BadUpstreamException(
          "Alpine re-sign mode requires a verified upstream index");
    }
    long total = 0;
    for (AlpineIndexRecord record : parsed.records()) {
      try {
        total = Math.addExact(total, record.downloadSize());
      } catch (ArithmeticException overflow) {
        throw new MavenExceptions.BadUpstreamException(
            "Alpine re-sign package size overflow");
      }
    }
    if (total > MAX_RESIGN_PACKAGE_BYTES) {
      throw new MavenExceptions.BadUpstreamException(
          "Alpine re-sign projection exceeds cached byte limit: " + total);
    }

    LinkedHashSet<String> current = new LinkedHashSet<>();
    for (AlpineIndexRecord record : parsed.records()) {
      String path = packagePath(namespace, record);
      fetchPackage(runtime, path);
      AlpineRegistryDao.PackageRecord verified = bindVerifiedPackage(
          runtime,
          expected(runtime, indexPath, record),
          path);
      AlpineRegistryDao.PackageRecord saved = saveIfChanged(verified);
      registry.replacePackageRelations(
          saved.repositoryId(), saved.id(), relations(saved.id(), record));
      current.add(coordinate(saved));
    }
    removeStalePackages(runtime, namespace, current);
  }

  private void removeStalePackages(
      RepositoryRuntime runtime, String namespace, Set<String> current) {
    String afterName = "";
    long afterId = 0;
    while (true) {
      List<AlpineRegistryDao.PackageRecord> page = registry.listPackagePage(
          runtime.id(), namespace, afterName, afterId, AlpineRegistryDao.PACKAGE_PAGE_SIZE);
      if (page.isEmpty()) return;
      AlpineRegistryDao.PackageRecord cursor = page.getLast();
      for (AlpineRegistryDao.PackageRecord stale : page) {
        if (!AlpineRegistryDao.SOURCE_PROXY.equals(stale.sourceKind())
            || current.contains(coordinate(stale))) continue;
        registry.deletePackage(
            stale.repositoryId(), stale.distribution(), stale.component(), stale.packageName(),
            stale.version(), stale.architecture(), "upstream-index-replaced", Instant.now());
        assets.retirePackageProjection(stale.assetId());
      }
      if (page.size() < AlpineRegistryDao.PACKAGE_PAGE_SIZE) return;
      afterName = cursor.packageName();
      afterId = cursor.id();
    }
  }

  boolean refreshDue(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      String namespace,
      Instant now) {
    Optional<AlpineRegistryDao.ProxyDistribution> current =
        registry.findProxyDistribution(runtime.id(), namespace);
    long minutes = Math.max(0, runtime.metadataMaxAgeMinutesOrDefault());
    if (current.isPresent()
        && current.orElseThrow().observedAt().plus(Duration.ofMinutes(minutes)).isAfter(now)) {
      return false;
    }
    refreshForResign(runtime, settings, namespace);
    return true;
  }

  /**
   * Makes a passthrough proxy's durable index projection available to a locally signed group.
   * Package bytes remain lazy and are verified against the bound index identity on first group
   * download.
   */
  long prepareGroupMember(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      String namespace,
      Instant now) {
    Instant observedAt = now == null ? Instant.now() : now;
    Optional<AlpineRegistryDao.ProxyDistribution> current =
        registry.findProxyDistribution(runtime.id(), namespace);
    boolean trustedCurrent = current.isPresent()
        && (!settings.verifyUpstreamSignatures()
            || current.orElseThrow().signatureVerified());
    long minutes = Math.max(0, runtime.metadataMaxAgeMinutesOrDefault());
    if (trustedCurrent
        && current.orElseThrow().observedAt().plus(Duration.ofMinutes(minutes))
            .isAfter(observedAt)) {
      return projectedRevision(runtime, namespace, observedAt);
    }

    try {
      AlpinePath indexPath = paths.parse(namespace + "/APKINDEX.tar.gz");
      AlpineIndexArchive.Parsed parsed = projectIndex(
          runtime,
          settings,
          indexPath,
          settings.verifyUpstreamSignatures(),
          MAX_RESIGN_PACKAGES);
      LinkedHashSet<String> projected = new LinkedHashSet<>();
      for (AlpineIndexRecord record : parsed.records()) {
        projected.add(coordinate(expected(runtime, indexPath, record)));
      }
      removeStalePackages(runtime, namespace, projected);
      return projectedRevision(runtime, namespace, observedAt);
    } catch (RuntimeException failure) {
      if (settings.staleIfError() && trustedCurrent) {
        return projectedRevision(runtime, namespace, observedAt);
      }
      throw failure;
    }
  }

  MavenResponse getVerifiedGroupPackage(
      RepositoryRuntime runtime,
      String path,
      String identity,
      long size,
      boolean headOnly) {
    AlpineRegistryDao.PackageRecord expected = registry.findPackageByPath(runtime.id(), path)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
    requireGroupBinding(expected, identity, size, path);
    if (expected.assetId() == null) {
      fetchPackage(runtime, path);
      expected = bindVerifiedPackage(runtime, expected, path);
      expected = saveIfChanged(expected);
      registry.replacePackageRelations(
          expected.repositoryId(), expected.id(), relations(expected.id(), record(expected)));
      requireGroupBinding(expected, identity, size, path);
    }
    return assets.serve(runtime, path, headOnly);
  }

  private long projectedRevision(
      RepositoryRuntime runtime, String namespace, Instant now) {
    return registry.findSuite(runtime.id(), namespace)
        .orElseGet(() -> registry.ensureSuite(runtime.id(), namespace, now))
        .desiredRevision();
  }

  private static void requireGroupBinding(
      AlpineRegistryDao.PackageRecord record,
      String identity,
      long size,
      String path) {
    if (!record.path().equals(path)
        || !record.identity().equals(identity)
        || record.size() != size) {
      throw new MavenExceptions.BadUpstreamException(
          "Alpine proxy package no longer matches its group snapshot binding: " + path);
    }
  }

  private AlpineIndexArchive.Parsed projectIndex(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      AlpinePath path,
      boolean verificationRequired,
      int packageLimit) {
    if (path.kind() != AlpinePath.Kind.INDEX) {
      throw new MavenExceptions.BadUpstreamException("Expected an Alpine APKINDEX path");
    }
    MavenResponse response = proxy.getMetadataFromUrlUnindexed(
        runtime,
        path.normalized(),
        RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), path.normalized()),
        false);
    response.closeBodyIfOpen();
    MavenResponse cached = assets.serve(runtime, path.normalized(), false);
    AlpineIndexArchive.Parsed parsed = AlpineIndexArchive.read(
        cached.body(),
        settings.upstreamPublicKeys(),
        verificationRequired || settings.verifyUpstreamSignatures());
    if (parsed.records().size() > packageLimit) {
      throw new MavenExceptions.BadUpstreamException(
          "Alpine index projection exceeds package limit: " + parsed.records().size());
    }
    LinkedHashMap<String, AlpineRegistryDao.ProxyIndex> manifest = new LinkedHashMap<>();
    for (AlpineIndexRecord record : parsed.records()) {
      AlpineRegistryDao.PackageRecord expected = expected(runtime, path, record);
      saveIfChanged(expected);
      manifest.put(
          expected.path(),
          new AlpineRegistryDao.ProxyIndex(expected.identity(), expected.size()));
    }
    registry.observeProxyDistribution(
        runtime.id(),
        path.namespace(),
        parsed.sha256(),
        Map.copyOf(manifest),
        parsed.signatureVerified(),
        Instant.now());
    return parsed;
  }

  private AlpineRegistryDao.PackageRecord verifyAndBindKnownPackage(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      AlpinePath path) {
    AlpineRegistryDao.PackageRecord expected = registry.findPackageByPath(
        runtime.id(), path.normalized()).orElse(null);
    if (expected == null) {
      projectIndex(
          runtime,
          settings,
          paths.parse(path.namespace() + "/APKINDEX.tar.gz"),
          false,
          MAX_RESIGN_PACKAGES);
      expected = registry.findPackageByPath(runtime.id(), path.normalized()).orElse(null);
    }
    if (expected == null) return null;
    AlpineRegistryDao.PackageRecord verified = bindVerifiedPackage(
        runtime, expected, path.normalized());
    AlpineRegistryDao.PackageRecord saved = saveIfChanged(verified);
    registry.replacePackageRelations(
        saved.repositoryId(), saved.id(), relations(saved.id(), record(saved)));
    return saved;
  }

  private AlpineRegistryDao.PackageRecord bindVerifiedPackage(
      RepositoryRuntime runtime,
      AlpineRegistryDao.PackageRecord expected,
      String packagePath) {
    MavenResponse cached = assets.serve(runtime, packagePath, false);
    String filename = packagePath.substring(packagePath.lastIndexOf('/') + 1);
    try (InputStream body = cached.body();
        AlpinePackageInspector.InspectedPackage inspected = inspector.inspect(body, filename)) {
      AlpinePackageInfo info = inspected.info();
      if (!expected.packageName().equals(info.name())
          || !expected.version().equals(info.version())
          || !compatibleArchitecture(expected.packageArchitecture(), info.architecture())
          || !expected.identity().equals(inspected.identity())
          || expected.size() != inspected.size()) {
        throw new MavenExceptions.BadUpstreamException(
            "Upstream Alpine package does not match the signed index: " + packagePath);
      }
      ComponentRecord component = components.component(
          runtime,
          distribution(expected.distribution()),
          expected.component(),
          expected.architecture(),
          info,
          filename,
          packagePath,
          inspected.identity(),
          inspected.sha256(),
          Instant.now());
      AssetRecord asset = assets.bindProxyPackage(
          runtime,
          packagePath,
          component,
          components.browsePath(
              distribution(expected.distribution()),
              expected.component(),
              expected.architecture(),
              filename),
          packageAttributes(expected, inspected));
      Instant now = Instant.now();
      return new AlpineRegistryDao.PackageRecord(
          expected.id(), expected.repositoryId(), expected.distribution(), expected.component(),
          expected.architecture(), expected.packageName(), expected.version(),
          expected.packageArchitecture(), expected.filename(), expected.path(),
          expected.controlFields(), expected.identity(), inspected.dataSha256(),
          inspected.sha256(), expected.size(), asset.id(), asset.componentId(),
          AlpineRegistryDao.SOURCE_PROXY, expected.revision(), expected.indexedAt(),
          expected.createdAt(), now);
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException(
          "Unable to inspect upstream Alpine package: " + packagePath, error);
    } catch (MavenExceptions.BadRequestException invalid) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid upstream Alpine package: " + packagePath, invalid);
    }
  }

  /** Alpine's official per-architecture indexes may rewrite an APK's internal noarch value. */
  private static boolean compatibleArchitecture(
      String indexArchitecture, String packageArchitecture) {
    return indexArchitecture.equals(packageArchitecture) || "noarch".equals(packageArchitecture);
  }

  private void fetchPackage(RepositoryRuntime runtime, String path) {
    MavenResponse response = proxy.getPinnedAssetFromUrlUnindexed(
        runtime,
        path,
        RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), path),
        true);
    response.closeBodyIfOpen();
  }

  private AlpineRegistryDao.PackageRecord saveIfChanged(
      AlpineRegistryDao.PackageRecord candidate) {
    AlpineRegistryDao.PackageRecord existing = registry.findPackage(
        candidate.repositoryId(), candidate.distribution(), candidate.component(),
        candidate.packageName(), candidate.version(), candidate.architecture()).orElse(null);
    if (existing != null && sameProjection(existing, candidate)) return existing;
    if (existing != null && candidate.assetId() == null
        && existing.assetId() != null && existing.identity().equals(candidate.identity())) {
      candidate = new AlpineRegistryDao.PackageRecord(
          existing.id(), candidate.repositoryId(), candidate.distribution(), candidate.component(),
          candidate.architecture(), candidate.packageName(), candidate.version(),
          candidate.packageArchitecture(), candidate.filename(), candidate.path(),
          candidate.controlFields(), candidate.identity(), existing.dataSha256(), existing.sha256(),
          candidate.size(), existing.assetId(), existing.componentId(),
          AlpineRegistryDao.SOURCE_PROXY, existing.revision(), candidate.indexedAt(),
          existing.createdAt(), candidate.updatedAt());
    }
    AlpineRegistryDao.PackageRecord saved = registry.savePackage(candidate);
    if (existing != null && existing.assetId() != null
        && (!Objects.equals(existing.assetId(), saved.assetId())
            || !existing.path().equals(saved.path()))) {
      assets.retirePackageProjection(existing.assetId());
    }
    return saved;
  }

  private static AlpineRegistryDao.PackageRecord expected(
      RepositoryRuntime runtime,
      AlpinePath indexPath,
      AlpineIndexRecord record) {
    String filename = AlpinePathParser.packageFilename(record.packageName(), record.version());
    String path = indexPath.namespace() + "/" + filename;
    Instant now = Instant.now();
    return new AlpineRegistryDao.PackageRecord(
        null,
        runtime.id(),
        indexPath.namespace(),
        indexPath.channel(),
        indexPath.repositoryArchitecture(),
        record.packageName(),
        record.version(),
        record.architecture(),
        filename,
        path,
        fields(record),
        record.identity(),
        UNKNOWN_SHA256,
        UNKNOWN_SHA256,
        record.downloadSize(),
        null,
        null,
        AlpineRegistryDao.SOURCE_PROXY,
        0,
        now,
        now,
        now);
  }

  private static Map<String, Object> packageAttributes(
      AlpineRegistryDao.PackageRecord expected,
      AlpinePackageInspector.InspectedPackage inspected) {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    values.put("alpineDistribution", distribution(expected.distribution()));
    values.put("alpineChannel", expected.component());
    values.put("alpineRepositoryArchitecture", expected.architecture());
    values.put("alpinePackageArchitecture", expected.packageArchitecture());
    values.put("alpinePackage", expected.packageName());
    values.put("alpineVersion", expected.version());
    values.put("alpineIdentity", expected.identity());
    values.put("alpineDataSha256", inspected.dataSha256());
    values.put("alpineSha256", inspected.sha256());
    values.put("alpineSize", inspected.size());
    values.put("alpineSource", "proxy");
    return Map.copyOf(values);
  }

  private static Map<String, Object> fields(AlpineIndexRecord record) {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    record.fields().forEach(field -> values.put(Character.toString(field.name()), field.value()));
    return Map.copyOf(values);
  }

  private static AlpineIndexRecord record(AlpineRegistryDao.PackageRecord row) {
    ArrayList<AlpineIndexRecord.Field> fields = new ArrayList<>();
    row.controlFields().forEach((name, value) -> {
      if (name != null && name.length() == 1 && value != null) {
        fields.add(new AlpineIndexRecord.Field(name.charAt(0), value.toString()));
      }
    });
    return new AlpineIndexRecord(fields);
  }

  private static List<AlpineRegistryDao.PackageRelation> relations(
      long packageId, AlpineIndexRecord record) {
    ArrayList<AlpineRegistryDao.PackageRelation> values = new ArrayList<>();
    addRelations(values, packageId, "DEPEND", record.get('D'));
    addRelations(values, packageId, "PROVIDE", record.get('p'));
    addRelations(values, packageId, "INSTALL_IF", record.get('i'));
    return List.copyOf(values);
  }

  private static void addRelations(
      List<AlpineRegistryDao.PackageRelation> target,
      long packageId,
      String kind,
      String expressions) {
    if (expressions == null || expressions.isBlank()) return;
    for (String expression : expressions.trim().split("\\s+")) {
      String token = relationToken(expression);
      if (!token.isBlank()) {
        target.add(new AlpineRegistryDao.PackageRelation(packageId, kind, token, expression));
      }
    }
  }

  private static String relationToken(String expression) {
    String value = expression == null ? "" : expression.trim();
    if (value.startsWith("!")) value = value.substring(1);
    int end = value.length();
    for (char operator : new char[]{'<', '>', '=', '~'}) {
      int index = value.indexOf(operator);
      if (index >= 0) end = Math.min(end, index);
    }
    return value.substring(0, end).trim();
  }

  private static String packagePath(String namespace, AlpineIndexRecord record) {
    return namespace + "/" + AlpinePathParser.packageFilename(
        record.packageName(), record.version());
  }

  private static String distribution(String namespace) {
    int slash = namespace.indexOf('/');
    return slash < 0 ? namespace : namespace.substring(0, slash);
  }

  private static String coordinate(AlpineRegistryDao.PackageRecord row) {
    return String.join("\0", row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version());
  }

  private static boolean sameProjection(
      AlpineRegistryDao.PackageRecord left,
      AlpineRegistryDao.PackageRecord right) {
    return left.path().equals(right.path())
        && left.identity().equals(right.identity())
        && left.size() == right.size()
        && Objects.equals(left.assetId(), right.assetId())
        && Objects.equals(left.componentId(), right.componentId())
        && left.controlFields().equals(right.controlFields())
        && AlpineRegistryDao.SOURCE_PROXY.equals(left.sourceKind());
  }
}
