package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineMediaTypes;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePackageInfo;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePath;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser;
import com.github.klboke.kkrepo.protocol.alpine.AlpineVersions;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** APK v2 hosted, proxy, and group behavior with fenced signed snapshots. */
@Service
public class AlpineService {
  private static final int MAX_GROUP_DEPTH = 8;
  private static final int GROUP_BINDING_BATCH_SIZE = 512;
  private static final Comparator<AlpineRegistryDao.PackageRecord> GROUP_ORDER =
      Comparator.comparing(AlpineRegistryDao.PackageRecord::packageName)
          .thenComparing(AlpineRegistryDao.PackageRecord::version, AlpineVersions.COMPARATOR)
          .thenComparing(AlpineRegistryDao.PackageRecord::packageArchitecture)
          .thenComparing(AlpineRegistryDao.PackageRecord::path);

  private final AlpineRegistryDao registry;
  private final AlpinePublishedSnapshotCache publishedSnapshots;
  private final AlpineRepositorySettings repositorySettings;
  private final AlpinePackageInspector inspector;
  private final AlpineComponentFactory components;
  private final AlpineAssetSupport assets;
  private final AlpineIndexBuilder indexBuilder;
  private final AlpineSigningService signing;
  private final AlpineLeaseManager leases;
  private final RawProxyService proxy;
  private final AlpineProxyProjectionService proxyProjection;
  private final RepositoryRuntimeRegistry runtimes;
  private final AlpinePathParser paths = new AlpinePathParser();

  AlpineService(
      AlpineRegistryDao registry,
      AlpinePublishedSnapshotCache publishedSnapshots,
      AlpineRepositorySettings repositorySettings,
      AlpinePackageInspector inspector,
      AlpineComponentFactory components,
      AlpineAssetSupport assets,
      AlpineIndexBuilder indexBuilder,
      AlpineSigningService signing,
      AlpineLeaseManager leases,
      RawProxyService proxy,
      AlpineProxyProjectionService proxyProjection,
      RepositoryRuntimeRegistry runtimes) {
    this.registry = registry;
    this.publishedSnapshots = publishedSnapshots;
    this.repositorySettings = repositorySettings;
    this.inspector = inspector;
    this.components = components;
    this.assets = assets;
    this.indexBuilder = indexBuilder;
    this.signing = signing;
    this.leases = leases;
    this.proxy = proxy;
    this.proxyProjection = proxyProjection;
    this.runtimes = runtimes;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    requireAlpine(runtime);
    AlpinePath path = requirePath(rawPath, false);
    AlpineRepositorySettings.Settings settings = repositorySettings.get(runtime);
    enforceNamespace(settings, path);
    if (path.kind() == AlpinePath.Kind.V3_INDEX) {
      throw new MavenExceptions.MavenNotFoundException(
          "APK v3 Packages.adb is not supported by this repository generation");
    }
    return switch (runtime.type()) {
      case HOSTED -> getSnapshotRepository(runtime, settings, path, headOnly);
      case PROXY -> settings.resign()
          ? getSnapshotRepository(runtime, settings, path, headOnly)
          : getProxyPassthrough(runtime, path, headOnly);
      case GROUP -> getGroup(runtime, settings, path, headOnly);
    };
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    requireHosted(runtime);
    AlpinePath path = requirePath(rawPath, true);
    if (path.kind() != AlpinePath.Kind.PACKAGE) {
      throw new MavenExceptions.MethodNotAllowed("Alpine PUT only accepts APK v2 package paths");
    }
    AlpineRepositorySettings.Settings settings = repositorySettings.get(runtime);
    enforceNamespace(settings, path);
    try (AlpinePackageInspector.InspectedPackage inspected =
        inspector.inspect(body, path.filename())) {
      PublishedPackage published = publishInspected(runtime, path, inspected, actor, ip);
      publishPending(runtime, path.namespace(), true);
      return MavenResponse.noBody(200).withHeader("Location", published.path());
    }
  }

  public PublishedPackage publish(
      RepositoryRuntime runtime,
      String distribution,
      String channel,
      String repositoryArchitecture,
      String filename,
      InputStream body,
      String actor,
      String ip) {
    String rawPath = AlpineRegistryDao.namespace(distribution, channel, repositoryArchitecture)
        + "/" + filename;
    AlpinePath path = requirePath(rawPath, true);
    requireHosted(runtime);
    enforceNamespace(repositorySettings.get(runtime), path);
    try (AlpinePackageInspector.InspectedPackage inspected = inspector.inspect(body, filename)) {
      PublishedPackage published = publishInspected(runtime, path, inspected, actor, ip);
      publishPending(runtime, path.namespace(), true);
      return published;
    }
  }

  PublishedPackage restoreHostedPackageForMigration(
      RepositoryRuntime runtime,
      AlpinePath path,
      AlpinePackageInspector.InspectedPackage inspected,
      String actor,
      String ip) {
    requireHosted(runtime);
    if (path == null || path.kind() != AlpinePath.Kind.PACKAGE
        || !path.filename().equals(inspected.filename())) {
      throw new IllegalArgumentException("Invalid Alpine migration package path");
    }
    enforceNamespace(repositorySettings.get(runtime), path);
    // savePackage marks the namespace dirty. Migration intentionally leaves it unpublished until
    // an administrator imports the source signing key (or explicitly rotates one) and rebuilds.
    return publishInspected(runtime, path, inspected, actor, ip);
  }

  private PublishedPackage publishInspected(
      RepositoryRuntime runtime,
      AlpinePath path,
      AlpinePackageInspector.InspectedPackage inspected,
      String actor,
      String ip) {
    AlpinePackageInfo info = inspected.info();
    if (!"noarch".equals(info.architecture())
        && !path.repositoryArchitecture().equals(info.architecture())) {
      throw new MavenExceptions.BadRequestException(
          "APK package architecture does not match repository path: " + info.architecture());
    }
    String leaseKey = coordinateLease(runtime, path, info);
    try (AlpineLeaseManager.Lease lease = leases.acquire(leaseKey)) {
      lease.assertHeld();
      Optional<AlpineRegistryDao.PackageRecord> existing = registry.findPackage(
          runtime.id(), path.namespace(), path.channel(), info.name(), info.version(),
          path.repositoryArchitecture());
      if (existing.isPresent()) {
        AlpineRegistryDao.PackageRecord row = existing.orElseThrow();
        if (!row.sha256().equalsIgnoreCase(inspected.sha256())) {
          throw new MavenExceptions.WritePolicyDenied(
              "Alpine package coordinates are immutable; publish a new version");
        }
        if (!row.path().equals(path.normalized())) {
          throw new MavenExceptions.WritePolicyDenied(
              "Alpine package coordinate is already bound to another path");
        }
        return published(row);
      }
      enforceWritePolicy(runtime, false);
      registry.findPackageByPath(runtime.id(), path.normalized()).ifPresent(row -> {
        throw new MavenExceptions.WritePolicyDenied(
            "Alpine package path is already bound to another coordinate");
      });
      Instant now = Instant.now();
      ComponentRecord projected = components.component(
          runtime,
          path.distribution(),
          path.channel(),
          path.repositoryArchitecture(),
          info,
          inspected.filename(),
          path.normalized(),
          inspected.identity(),
          inspected.sha256(),
          now);
      LinkedHashMap<String, Object> attributes = packageAttributes(path, inspected);
      AssetRecord asset = assets.storePackage(
          runtime,
          path.normalized(),
          components.browsePath(
              path.distribution(), path.channel(), path.repositoryArchitecture(), info,
              inspected.filename()),
          inspected.file(),
          Map.copyOf(attributes),
          actor,
          ip,
          projected);
      lease.assertHeld();
      AlpineIndexRecord index = info.indexRecord(inspected.identity(), inspected.size());
      AlpineRegistryDao.PackageRecord stored = registry.savePackage(
          new AlpineRegistryDao.PackageRecord(
              null,
              runtime.id(),
              path.namespace(),
              path.channel(),
              path.repositoryArchitecture(),
              info.name(),
              info.version(),
              info.architecture(),
              inspected.filename(),
              path.normalized(),
              indexFields(index),
              inspected.identity(),
              inspected.dataSha256(),
              inspected.sha256(),
              inspected.size(),
              asset.id(),
              asset.componentId(),
              AlpineRegistryDao.SOURCE_HOSTED,
              0,
              now,
              now,
              now));
      registry.replacePackageRelations(
          stored.repositoryId(), stored.id(), relations(stored.id(), info));
      lease.assertHeld();
      return published(stored);
    }
  }

  public MavenResponse delete(
      RepositoryRuntime runtime, String rawPath, String reason, boolean enforcePolicy) {
    requireHosted(runtime);
    AlpinePath path = requirePath(rawPath, true);
    if (path.kind() != AlpinePath.Kind.PACKAGE) {
      throw new MavenExceptions.MethodNotAllowed("Only Alpine package assets can be deleted");
    }
    if (enforcePolicy && !WritePolicy.parse(runtime.writePolicy()).checkDeleteAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy forbids deletion");
    }
    AlpineRegistryDao.PackageRecord existing = registry.findPackageByPath(
        runtime.id(), path.normalized()).orElseThrow(
            () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    try (AlpineLeaseManager.Lease lease = leases.acquire(
        coordinateLease(runtime, path, packageInfo(existing)))) {
      lease.assertHeld();
      AlpineRegistryDao.PackageRecord removed = registry.deletePackage(
          runtime.id(), existing.distribution(), existing.component(), existing.packageName(),
          existing.version(), existing.architecture(), reason, Instant.now()).orElseThrow(
              () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
      lease.assertHeld();
      publishPending(runtime, path.namespace(), true);
      // Bytes stay tombstone-pinned until every retained signed snapshot can no longer reference
      // them, but Browse/Search stop discovering the deleted package immediately.
      assets.retirePackageProjection(removed.assetId());
      return MavenResponse.noBody(204);
    }
  }

  public List<Integer> deleteComponentsForCleanup(
      RepositoryRuntime runtime, List<Long> componentIds, String reason) {
    requireHosted(runtime);
    if (componentIds == null || componentIds.isEmpty()) return List.of();
    ArrayList<Integer> counts = new ArrayList<>();
    LinkedHashSet<String> changed = new LinkedHashSet<>();
    for (Long componentId : componentIds) {
      int deleted = 0;
      if (componentId != null && componentId > 0) {
        for (AssetRecord asset : assets.listAssetsByComponent(componentId)) {
          if (asset.repositoryId() != runtime.id() || asset.format() != RepositoryFormat.ALPINE) {
            continue;
          }
          AlpineRegistryDao.PackageRecord row = registry.findPackageByPath(
              runtime.id(), asset.path()).orElse(null);
          if (row == null) continue;
          Optional<AlpineRegistryDao.PackageRecord> removed = registry.deletePackage(
              row.repositoryId(), row.distribution(), row.component(), row.packageName(),
              row.version(), row.architecture(), reason, Instant.now());
          if (removed.isPresent()) {
            deleted++;
            changed.add(row.distribution());
            assets.retirePackageProjection(row.assetId());
          }
        }
      }
      counts.add(deleted);
    }
    changed.forEach(namespace -> publishPending(runtime, namespace, true));
    return List.copyOf(counts);
  }

  public MavenResponse publicKey(RepositoryRuntime runtime, boolean headOnly) {
    requireAlpine(runtime);
    AlpineSigningService.SigningMaterial key = signing.active(runtime);
    byte[] bytes = key.publicPem().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    String etag = '"' + sha256(bytes) + '"';
    if (headOnly) return MavenResponse.noBody(
        200, bytes.length, AlpineMediaTypes.PUBLIC_KEY, etag, null);
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, AlpineMediaTypes.PUBLIC_KEY, etag, null)
        .withHeader("Content-Disposition", "attachment; filename=\"" + key.keyFilename() + "\"");
  }

  public AlpineRegistryDao.SigningKey rotateKey(
      RepositoryRuntime runtime,
      String privateKeyPem,
      String keyFilename,
      String signatureType) {
    requireAlpine(runtime);
    AlpineRepositorySettings.Settings settings = repositorySettings.get(runtime);
    AlpineRegistryDao.SigningKey key = signing.rotate(
        runtime,
        privateKeyPem,
        keyFilename == null || keyFilename.isBlank() ? settings.keyFilename() : keyFilename,
        signatureType == null || signatureType.isBlank()
            ? settings.signatureType() : signatureType);
    republishForKeyRotation(runtime);
    return key;
  }

  public AlpineRegistryDao.SigningKey rotateGeneratedKey(RepositoryRuntime runtime) {
    requireAlpine(runtime);
    AlpineRepositorySettings.Settings settings = repositorySettings.get(runtime);
    AlpineRegistryDao.SigningKey key = signing.rotateGenerated(
        runtime, settings.keyFilename(), settings.signatureType());
    republishForKeyRotation(runtime);
    return key;
  }

  public void rebuild(RepositoryRuntime runtime, String namespace) {
    requireAlpine(runtime);
    String value = normalizeNamespace(namespace);
    registry.markSuiteDirty(runtime.id(), value, Instant.now());
    publishPending(runtime, value, true);
  }

  public Status status(RepositoryRuntime runtime) {
    requireAlpine(runtime);
    List<NamespaceStatus> namespaces = registry.listSuites(runtime.id()).stream()
        .map(state -> new NamespaceStatus(
            state.distribution(), state.desiredRevision(), state.publishedRevision(),
            state.signingKeyRevision(), state.lastPublishedAt(), state.lastError(),
            state.lastErrorAt()))
        .toList();
    KeyStatus key = registry.findActiveSigningKey(runtime.id())
        .map(row -> new KeyStatus(
            row.revision(), row.keyFilename(), row.fingerprint(), row.signatureType(),
            row.createdAt()))
        .orElse(null);
    List<ProxyStatus> proxies = registry.listProxyDistributions(runtime.id()).stream()
        .map(row -> new ProxyStatus(
            row.distribution(), row.releaseIdentity(), row.signatureVerified(), row.observedAt()))
        .toList();
    return new Status(namespaces, key, proxies);
  }

  boolean publishPendingIfAvailable(RepositoryRuntime runtime, String namespace) {
    requireAlpine(runtime);
    return publishPending(runtime, normalizeNamespace(namespace), false);
  }

  private MavenResponse getSnapshotRepository(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      AlpinePath path,
      boolean headOnly) {
    if (runtime.isProxy()) {
      refreshResignedProxy(runtime, settings, path.namespace());
    }
    if (path.kind() == AlpinePath.Kind.PACKAGE) {
      if (runtime.isProxy()) {
        AlpineRegistryDao.PackageRecord verified = registry.findPackageByPath(
            runtime.id(), path.normalized()).filter(row -> row.assetId() != null).orElseThrow(
                () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
        return assets.serve(runtime, verified.path(), headOnly)
            .withHeader("Cache-Control", "public, max-age=31536000, immutable");
      }
      return assets.serve(runtime, path.normalized(), headOnly)
          .withHeader("Cache-Control", "public, max-age=31536000, immutable");
    }
    AlpineRegistryDao.Snapshot snapshot = ensureSnapshot(runtime, settings, path.namespace());
    String hidden = snapshot.manifest().get(path.normalized());
    if (hidden == null) throw new MavenExceptions.MavenNotFoundException(path.normalized());
    return assets.serve(runtime, hidden, headOnly)
        .withHeader("Cache-Control", "public, max-age=0, must-revalidate");
  }

  private MavenResponse getProxyPassthrough(
      RepositoryRuntime runtime, AlpinePath path, boolean headOnly) {
    String remote = RemoteUrlBuilder.repositoryPathString(
        runtime.proxyRemoteUrl(), path.normalized());
    MavenResponse response = path.kind() == AlpinePath.Kind.INDEX
        ? proxy.getMetadataFromUrlUnindexed(runtime, path.normalized(), remote, headOnly)
        : proxy.getPinnedAssetFromUrlUnindexed(runtime, path.normalized(), remote, headOnly);
    proxyProjection.observePassthrough(
        runtime, repositorySettings.get(runtime), path);
    return response.withHeader(
        "Cache-Control",
        path.kind() == AlpinePath.Kind.PACKAGE
            ? "public, max-age=31536000, immutable"
            : "public, max-age=0, must-revalidate");
  }

  private void refreshResignedProxy(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      String namespace) {
    try {
      if (proxyProjection.refreshDue(runtime, settings, namespace, Instant.now())) {
        registry.ensureSuite(runtime.id(), namespace, Instant.now());
        registry.markSuiteDirty(runtime.id(), namespace, Instant.now());
      }
    } catch (RuntimeException failure) {
      boolean hasPublished = publishedSnapshots.find(runtime.id(), namespace).isPresent();
      if (!settings.staleIfError() || !hasPublished) throw failure;
    }
  }

  private MavenResponse getGroup(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      AlpinePath path,
      boolean headOnly) {
    AlpineRegistryDao.Snapshot snapshot = ensureGroupSnapshot(runtime, settings, path.namespace());
    if (path.kind() == AlpinePath.Kind.INDEX) {
      String hidden = snapshot.manifest().get(path.normalized());
      if (hidden == null) throw new MavenExceptions.MavenNotFoundException(path.normalized());
      return assets.serve(runtime, hidden, headOnly)
          .withHeader("Cache-Control", "public, max-age=0, must-revalidate");
    }
    AlpineRegistryDao.GroupBinding binding = registry.findGroupBinding(
        runtime.id(), path.namespace(), snapshot.revision(), path.normalized()).orElseThrow(
            () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    RepositoryRuntime source = runtimes.resolveById(binding.memberRepositoryId()).orElseThrow(
        () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    MavenResponse response = source.isHosted()
        ? assets.serve(source, binding.memberPath(), headOnly)
        : proxy.getPinnedAssetFromUrlUnindexed(
            source,
            binding.memberPath(),
            RemoteUrlBuilder.repositoryPathString(source.proxyRemoteUrl(), binding.memberPath()),
            headOnly);
    return response.withHeader("Cache-Control", "public, max-age=31536000, immutable")
        .withHeader("X-kkRepo-Source-Repository", source.name());
  }

  private AlpineRegistryDao.Snapshot ensureSnapshot(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      String namespace) {
    Optional<AlpineRegistryDao.Snapshot> published = publishedSnapshots.find(runtime.id(), namespace);
    if (published.isPresent()) return published.orElseThrow();
    AlpineRegistryDao.SuiteState state = registry.findSuite(runtime.id(), namespace)
        .orElseGet(() -> registry.ensureSuite(runtime.id(), namespace, Instant.now()));
    if (state.desiredRevision() == state.publishedRevision()) {
      registry.markSuiteDirty(runtime.id(), namespace, Instant.now());
    }
    publishPending(runtime, namespace, true);
    return publishedSnapshots.find(runtime.id(), namespace).orElseThrow(
        () -> new MavenExceptions.MavenNotFoundException(namespace + "/APKINDEX.tar.gz"));
  }

  private AlpineRegistryDao.Snapshot ensureGroupSnapshot(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      String namespace) {
    String memberFingerprint = memberFingerprint(runtime, namespace);
    AlpineRegistryDao.Snapshot current = publishedSnapshots.find(runtime.id(), namespace).orElse(null);
    if (current == null || !memberFingerprint.equals(current.manifest().get("@members"))) {
      registry.ensureSuite(runtime.id(), namespace, Instant.now());
      registry.markSuiteDirty(runtime.id(), namespace, Instant.now());
      publishPending(runtime, namespace, true);
    }
    return publishedSnapshots.find(runtime.id(), namespace).orElseThrow(
        () -> new MavenExceptions.MavenNotFoundException(namespace + "/APKINDEX.tar.gz"));
  }

  private boolean publishPending(
      RepositoryRuntime runtime, String namespace, boolean waitForLease) {
    AlpineRegistryDao.SuiteState before = registry.findSuite(runtime.id(), namespace)
        .orElseGet(() -> registry.ensureSuite(runtime.id(), namespace, Instant.now()));
    if (before.desiredRevision() == before.publishedRevision()
        && publishedSnapshots.find(runtime.id(), namespace).isPresent()) return true;
    Optional<AlpineLeaseManager.Lease> acquired = waitForLease
        ? Optional.of(leases.acquire("alpine:publish:" + runtime.id() + ":" + namespace))
        : leases.tryAcquire("alpine:publish:" + runtime.id() + ":" + namespace);
    if (acquired.isEmpty()) return false;
    try (AlpineLeaseManager.Lease lease = acquired.orElseThrow()) {
      for (int attempt = 0; attempt < 4; attempt++) {
        lease.assertHeld();
        AlpineRegistryDao.SuiteState state = registry.findSuite(runtime.id(), namespace).orElseThrow();
        if (state.desiredRevision() == state.publishedRevision()
            && publishedSnapshots.find(runtime.id(), namespace).isPresent()) return true;
        try {
          AlpineSigningService.SigningMaterial key = signing.active(runtime);
          AlpineRepositorySettings.Settings settings = repositorySettings.get(runtime);
          if (runtime.isGroup()) {
            GroupProjection group = resolveGroup(runtime, namespace);
            long revision = state.desiredRevision();
            long bindingToken = lease.fencingToken();
            registry.beginGroupSnapshot(runtime.id(), namespace, revision, bindingToken);
            boolean groupPublished = false;
            try {
              AlpineIndexBuilder.BuiltSnapshot built = indexBuilder.build(
                  runtime,
                  settings,
                  state,
                  key,
                  visitor -> visitGroupPackages(
                      runtime, namespace, revision, bindingToken, group, lease, visitor));
              LinkedHashMap<String, String> manifest = new LinkedHashMap<>(built.manifest());
              manifest.put("@members", group.memberFingerprint());
              AlpineRegistryDao.Snapshot snapshot = new AlpineRegistryDao.Snapshot(
                  runtime.id(), namespace, revision, built.signingKeyRevision(),
                  Map.copyOf(manifest), built.indexSha256(), built.createdAt());
              lease.assertHeld();
              if (registry.publishGroupSnapshot(snapshot, lease.owner(), bindingToken)) {
                groupPublished = true;
                publishedSnapshots.published(snapshot);
                return true;
              }
            } finally {
              if (!groupPublished) {
                registry.discardGroupSnapshot(
                    runtime.id(), namespace, revision, bindingToken);
              }
            }
          } else {
            AlpineIndexBuilder.BuiltSnapshot built = indexBuilder.build(
                runtime, settings, state, key);
            AlpineRegistryDao.Snapshot snapshot = new AlpineRegistryDao.Snapshot(
                runtime.id(), namespace, state.desiredRevision(), built.signingKeyRevision(),
                built.manifest(), built.indexSha256(), built.createdAt());
            lease.assertHeld();
            if (registry.publishSnapshot(snapshot, lease.owner(), lease.fencingToken())) {
              publishedSnapshots.published(snapshot);
              return true;
            }
          }
        } catch (RuntimeException error) {
          registry.recordBuildFailure(
              runtime.id(), namespace, state.desiredRevision(), error.getMessage(), Instant.now());
          throw error;
        }
      }
    }
    if (waitForLease) {
      throw new MavenExceptions.WritePolicyDenied(
          "Alpine namespace changed repeatedly during publication; retry the request");
    }
    return false;
  }

  private GroupProjection resolveGroup(RepositoryRuntime group, String namespace) {
    ArrayList<ResolvedMember> members = new ArrayList<>();
    LinkedHashSet<Long> visiting = new LinkedHashSet<>();
    LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>();
    collectMembers(group, namespace, visiting, revisions, members, 0);
    return new GroupProjection(List.copyOf(members), fingerprint(revisions));
  }

  private void collectMembers(
      RepositoryRuntime group,
      String namespace,
      Set<Long> visiting,
      Map<Long, Long> revisions,
      List<ResolvedMember> members,
      int depth) {
    if (depth > MAX_GROUP_DEPTH || !visiting.add(group.id())) {
      throw new IllegalStateException("Alpine group membership contains a cycle or exceeds depth");
    }
    try {
      for (RepositoryRuntime member : group.members()) {
        if (member.format() != RepositoryFormat.ALPINE || !member.online()) continue;
        if (member.isGroup()) {
          collectMembers(member, namespace, visiting, revisions, members, depth + 1);
          continue;
        }
        AlpineRepositorySettings.Settings memberSettings = repositorySettings.get(member);
        if (member.isProxy() && !memberSettings.resign()) {
          throw new IllegalStateException(
              "Alpine passthrough proxy cannot be a trusted group member: " + member.name());
        }
        AlpineRegistryDao.Snapshot snapshot = ensureSnapshot(member, memberSettings, namespace);
        revisions.put(member.id(), snapshot.revision());
        members.add(new ResolvedMember(member.id(), snapshot.revision()));
      }
    } finally {
      visiting.remove(group.id());
    }
  }

  private void visitGroupPackages(
      RepositoryRuntime group,
      String namespace,
      long snapshotRevision,
      long bindingToken,
      GroupProjection projection,
      AlpineLeaseManager.Lease lease,
      Consumer<AlpineRegistryDao.PackageRecord> visitor) {
    ArrayList<MemberPackageCursor> cursors = new ArrayList<>();
    projection.members().forEach(member -> cursors.add(new MemberPackageCursor(member, namespace)));
    ArrayList<AlpineRegistryDao.GroupBinding> bindings = new ArrayList<>(
        GROUP_BINDING_BATCH_SIZE);
    while (true) {
      String packageName = null;
      for (MemberPackageCursor cursor : cursors) {
        AlpineRegistryDao.PackageRecord candidate = cursor.peek();
        if (candidate != null
            && (packageName == null || candidate.packageName().compareTo(packageName) < 0)) {
          packageName = candidate.packageName();
        }
      }
      if (packageName == null) break;

      LinkedHashMap<String, ResolvedPackage> selected = new LinkedHashMap<>();
      for (MemberPackageCursor cursor : cursors) {
        AlpineRegistryDao.PackageRecord candidate;
        while ((candidate = cursor.peek()) != null
            && candidate.packageName().equals(packageName)) {
          AlpineRegistryDao.PackageRecord row = cursor.take();
          ResolvedPackage resolved = new ResolvedPackage(
              row,
              cursor.member().repositoryId(),
              cursor.member().snapshotRevision(),
              row.path());
          selected.putIfAbsent(groupCoordinate(row), resolved);
        }
      }

      ArrayList<ResolvedPackage> family = new ArrayList<>(selected.values());
      family.sort(Comparator.comparing(ResolvedPackage::record, GROUP_ORDER));
      for (ResolvedPackage resolved : family) {
        visitor.accept(resolved.record());
        bindings.add(binding(group, namespace, snapshotRevision, resolved));
        if (bindings.size() == GROUP_BINDING_BATCH_SIZE) {
          flushGroupBindings(lease, bindingToken, bindings);
        }
      }
    }
    flushGroupBindings(lease, bindingToken, bindings);
  }

  private void flushGroupBindings(
      AlpineLeaseManager.Lease lease,
      long bindingToken,
      List<AlpineRegistryDao.GroupBinding> bindings) {
    if (bindings.isEmpty()) return;
    lease.assertHeld();
    registry.appendGroupBindings(bindingToken, List.copyOf(bindings));
    bindings.clear();
  }

  String memberFingerprint(RepositoryRuntime group, String namespace) {
    LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>();
    collectMemberRevisions(group, namespace, new LinkedHashSet<>(), revisions, 0);
    return fingerprint(revisions);
  }

  private void collectMemberRevisions(
      RepositoryRuntime group,
      String namespace,
      Set<Long> visiting,
      Map<Long, Long> revisions,
      int depth) {
    if (depth > MAX_GROUP_DEPTH || !visiting.add(group.id())) {
      throw new IllegalStateException("Alpine group membership contains a cycle or exceeds depth");
    }
    try {
      for (RepositoryRuntime member : group.members()) {
        if (member.format() != RepositoryFormat.ALPINE || !member.online()) continue;
        if (member.isGroup()) {
          collectMemberRevisions(member, namespace, visiting, revisions, depth + 1);
        } else {
          AlpineRegistryDao.SuiteState state = registry.findSuite(member.id(), namespace).orElse(null);
          revisions.put(member.id(), state == null ? 0 : state.publishedRevision());
        }
      }
    } finally {
      visiting.remove(group.id());
    }
  }

  private void republishForKeyRotation(RepositoryRuntime runtime) {
    for (String namespace : registry.listDistributions(runtime.id())) {
      registry.markSuiteDirty(runtime.id(), namespace, Instant.now());
      publishPending(runtime, namespace, true);
    }
  }

  private static AlpineRegistryDao.GroupBinding binding(
      RepositoryRuntime group,
      String namespace,
      long snapshotRevision,
      ResolvedPackage resolved) {
    AlpineRegistryDao.PackageRecord row = resolved.record();
    return new AlpineRegistryDao.GroupBinding(
        null,
        group.id(),
        namespace,
        snapshotRevision,
        row.path(),
        resolved.sourceRepositoryId(),
        resolved.sourceSnapshotRevision(),
        resolved.sourcePath(),
        row.identity(),
        row.sha256(),
        row.size(),
        Instant.now());
  }

  private static LinkedHashMap<String, Object> packageAttributes(
      AlpinePath path, AlpinePackageInspector.InspectedPackage inspected) {
    AlpinePackageInfo info = inspected.info();
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("alpineDistribution", path.distribution());
    attributes.put("alpineChannel", path.channel());
    attributes.put("alpineRepositoryArchitecture", path.repositoryArchitecture());
    attributes.put("alpinePackageArchitecture", info.architecture());
    attributes.put("alpinePackage", info.name());
    attributes.put("alpineVersion", info.version());
    attributes.put("alpineIdentity", inspected.identity());
    attributes.put("alpineDataSha256", inspected.dataSha256());
    attributes.put("alpineSha256", inspected.sha256());
    attributes.put("alpineSize", inspected.size());
    attributes.put("alpineInputSchema", "alpine-apk-v2");
    attributes.put("alpineSigned", !inspected.signatures().isEmpty());
    if (info.origin() != null) attributes.put("alpineOrigin", info.origin());
    if (info.license() != null) attributes.put("alpineLicense", info.license());
    if (info.maintainer() != null) attributes.put("alpineMaintainer", info.maintainer());
    attributes.put("alpineDependencies", info.dependencies());
    attributes.put("alpineProvides", info.provides());
    return attributes;
  }

  private static Map<String, Object> indexFields(AlpineIndexRecord record) {
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    record.fields().forEach(field -> fields.put(Character.toString(field.name()), field.value()));
    return Map.copyOf(fields);
  }

  private static List<AlpineRegistryDao.PackageRelation> relations(
      long packageId, AlpinePackageInfo info) {
    ArrayList<AlpineRegistryDao.PackageRelation> result = new ArrayList<>();
    addRelations(result, packageId, "DEPEND", info.dependencies());
    addRelations(result, packageId, "PROVIDE", info.provides());
    addRelations(result, packageId, "INSTALL_IF", info.installIf());
    return List.copyOf(result);
  }

  private static void addRelations(
      List<AlpineRegistryDao.PackageRelation> target,
      long packageId,
      String kind,
      List<String> expressions) {
    for (String expression : expressions) {
      String token = relationToken(expression);
      if (!token.isBlank()) {
        target.add(new AlpineRegistryDao.PackageRelation(packageId, kind, token, expression));
      }
    }
  }

  private static String relationToken(String expression) {
    if (expression == null) return "";
    String value = expression.trim();
    if (value.startsWith("!")) value = value.substring(1);
    int end = value.length();
    for (char operator : new char[]{'<', '>', '=', '~'}) {
      int index = value.indexOf(operator);
      if (index >= 0) end = Math.min(end, index);
    }
    return value.substring(0, end).trim();
  }

  private static AlpinePackageInfo packageInfo(AlpineRegistryDao.PackageRecord row) {
    StringBuilder value = new StringBuilder()
        .append("pkgname = ").append(row.packageName()).append('\n')
        .append("pkgver = ").append(row.version()).append('\n')
        .append("size = ").append(row.controlFields().getOrDefault("I", "0")).append('\n')
        .append("arch = ").append(row.packageArchitecture()).append('\n')
        .append("datahash = ").append(row.dataSha256()).append('\n');
    return AlpinePackageInfo.parse(value.toString());
  }

  private AlpinePath requirePath(String rawPath, boolean write) {
    AlpinePath path = paths.parse(rawPath);
    if (path.kind() == AlpinePath.Kind.UNKNOWN || path.kind() == AlpinePath.Kind.ROOT) {
      throw new MavenExceptions.MavenNotFoundException(rawPath == null ? "" : rawPath);
    }
    if (write && path.kind() == AlpinePath.Kind.V3_INDEX) {
      throw new MavenExceptions.BadRequestException("APK v3 uploads are not supported");
    }
    return path;
  }

  private static void enforceNamespace(
      AlpineRepositorySettings.Settings settings, AlpinePath path) {
    if (!settings.allows(
        path.distribution(), path.channel(), path.repositoryArchitecture())) {
      throw new MavenExceptions.MavenNotFoundException(path.normalized());
    }
  }

  private static String normalizeNamespace(String namespace) {
    String value = namespace == null ? "" : namespace.trim();
    String[] parts = value.split("/", -1);
    if (parts.length != 3) throw new MavenExceptions.BadRequestException(
        "Alpine namespace must be distribution/channel/architecture");
    return AlpineRegistryDao.namespace(parts[0], parts[1], parts[2]);
  }

  private static void enforceWritePolicy(RepositoryRuntime runtime, boolean exists) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy is DENY");
    }
    if (policy == WritePolicy.ALLOW_ONCE && exists) {
      throw new MavenExceptions.WritePolicyDenied("Alpine package coordinate already exists");
    }
  }

  private static String coordinateLease(
      RepositoryRuntime runtime, AlpinePath path, AlpinePackageInfo info) {
    return "alpine:coordinate:" + runtime.id() + ":" + HexFormat.of().formatHex(
        PersistenceHashes.sha256(
            path.namespace(), info.name(), info.version(), path.repositoryArchitecture()));
  }

  private static String groupCoordinate(AlpineRegistryDao.PackageRecord row) {
    return row.packageName() + '\0' + row.version() + '\0' + row.packageArchitecture();
  }

  static String fingerprint(Map<Long, Long> revisions) {
    StringBuilder value = new StringBuilder();
    revisions.forEach((repository, revision) -> value.append(repository).append(':')
        .append(revision).append(';'));
    return sha256(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static PublishedPackage published(AlpineRegistryDao.PackageRecord row) {
    return new PublishedPackage(
        row.path(), row.packageName(), row.version(), row.packageArchitecture(),
        row.identity(), row.sha256(), row.size());
  }

  private static void requireAlpine(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.ALPINE) {
      throw new MavenExceptions.MethodNotAllowed("Repository is not an Alpine repository");
    }
  }

  private static void requireHosted(RepositoryRuntime runtime) {
    requireAlpine(runtime);
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Alpine upload is only valid on hosted repositories");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  public record PublishedPackage(
      String path,
      String packageName,
      String version,
      String architecture,
      String identity,
      String sha256,
      long size) {
  }

  public record Status(
      List<NamespaceStatus> namespaces,
      KeyStatus activeKey,
      List<ProxyStatus> proxyNamespaces) {
  }

  public record NamespaceStatus(
      String namespace,
      long desiredRevision,
      long publishedRevision,
      int signingKeyRevision,
      Instant lastPublishedAt,
      String lastError,
      Instant lastErrorAt) {
  }

  public record KeyStatus(
      int revision,
      String filename,
      String fingerprint,
      String signatureType,
      Instant createdAt) {
  }

  public record ProxyStatus(
      String namespace,
      String indexIdentity,
      boolean signatureVerified,
      Instant observedAt) {
  }

  private final class MemberPackageCursor {
    private final ResolvedMember member;
    private final String namespace;
    private List<AlpineRegistryDao.PackageRecord> page = List.of();
    private int offset;
    private String afterName = "";
    private long afterId;
    private boolean finalPage;

    private MemberPackageCursor(ResolvedMember member, String namespace) {
      this.member = member;
      this.namespace = namespace;
    }

    private ResolvedMember member() {
      return member;
    }

    private AlpineRegistryDao.PackageRecord peek() {
      if (offset < page.size()) return page.get(offset);
      if (finalPage) return null;
      page = registry.listPackagePage(
          member.repositoryId(),
          namespace,
          afterName,
          afterId,
          AlpineRegistryDao.PACKAGE_PAGE_SIZE);
      offset = 0;
      if (page.isEmpty()) {
        finalPage = true;
        return null;
      }
      AlpineRegistryDao.PackageRecord cursor = page.getLast();
      afterName = cursor.packageName();
      afterId = cursor.id();
      finalPage = page.size() < AlpineRegistryDao.PACKAGE_PAGE_SIZE;
      return page.getFirst();
    }

    private AlpineRegistryDao.PackageRecord take() {
      AlpineRegistryDao.PackageRecord value = peek();
      if (value == null) throw new IllegalStateException("Alpine group package cursor is exhausted");
      offset++;
      return value;
    }
  }

  private record ResolvedMember(long repositoryId, long snapshotRevision) { }

  private record ResolvedPackage(
      AlpineRegistryDao.PackageRecord record,
      long sourceRepositoryId,
      long sourceSnapshotRevision,
      String sourcePath) {
  }

  private record GroupProjection(
      List<ResolvedMember> members,
      String memberFingerprint) {
  }
}
