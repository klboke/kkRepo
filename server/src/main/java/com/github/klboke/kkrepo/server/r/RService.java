package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.r.RPackageMetadata;
import com.github.klboke.kkrepo.protocol.r.RPath;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.protocol.r.RVersions;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** CRAN-style hosted, proxy and group behavior backed by fenced PACKAGES.gz snapshots. */
@Service
public class RService {
  public static final String SOURCE_NAMESPACE = "src/contrib";
  static final String COMPONENT = "source";
  static final String ARCHITECTURE = "source";
  private static final int MAX_GROUP_DEPTH = 8;
  private static final int GROUP_BINDING_BATCH_SIZE = 512;

  private final RRegistryDao registry;
  private final RPublishedSnapshotCache publishedSnapshots;
  private final RSourcePackageInspector inspector;
  private final RComponentFactory components;
  private final RAssetSupport assets;
  private final RIndexBuilder indexBuilder;
  private final RLeaseManager leases;
  private final RProxyProjectionService proxyProjection;
  private final RepositoryRuntimeRegistry runtimes;
  private final RPathParser paths = new RPathParser();

  RService(
      RRegistryDao registry,
      RPublishedSnapshotCache publishedSnapshots,
      RSourcePackageInspector inspector,
      RComponentFactory components,
      RAssetSupport assets,
      RIndexBuilder indexBuilder,
      RLeaseManager leases,
      RProxyProjectionService proxyProjection,
      RepositoryRuntimeRegistry runtimes) {
    this.registry = registry;
    this.publishedSnapshots = publishedSnapshots;
    this.inspector = inspector;
    this.components = components;
    this.assets = assets;
    this.indexBuilder = indexBuilder;
    this.leases = leases;
    this.proxyProjection = proxyProjection;
    this.runtimes = runtimes;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    requireR(runtime);
    RPath path = requirePath(rawPath);
    return switch (runtime.type()) {
      case HOSTED -> getHosted(runtime, path, headOnly);
      case PROXY -> proxyProjection.get(runtime, path, headOnly);
      case GROUP -> getGroup(runtime, path, headOnly);
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
    RPath path = requirePath(rawPath);
    if (path.kind() != RPath.Kind.SOURCE_PACKAGE) {
      throw new MavenExceptions.MethodNotAllowed(
          "R hosted PUT only accepts src/contrib source package paths");
    }
    try (RSourcePackageInspector.InspectedPackage inspected =
        inspector.inspect(body, path.filename())) {
      PublishedPackage published = publishInspected(runtime, path, inspected, actor, ip);
      publishPending(runtime, SOURCE_NAMESPACE, true);
      return MavenResponse.noBody(200).withHeader("Location", published.path());
    }
  }

  public PublishedPackage publish(
      RepositoryRuntime runtime,
      String filename,
      InputStream body,
      String actor,
      String ip) {
    requireHosted(runtime);
    RPath path = requirePath(SOURCE_NAMESPACE + "/" + filename);
    if (path.kind() != RPath.Kind.SOURCE_PACKAGE) {
      throw new MavenExceptions.BadRequestException("Invalid R source package filename");
    }
    try (RSourcePackageInspector.InspectedPackage inspected = inspector.inspect(body, filename)) {
      PublishedPackage published = publishInspected(runtime, path, inspected, actor, ip);
      publishPending(runtime, SOURCE_NAMESPACE, true);
      return published;
    }
  }

  PublishedPackage restoreHostedPackageForMigration(
      RepositoryRuntime runtime,
      RPath path,
      RSourcePackageInspector.InspectedPackage inspected,
      String actor,
      String ip) {
    requireHosted(runtime);
    if (path == null || path.kind() != RPath.Kind.SOURCE_PACKAGE
        || !path.normalized().startsWith(SOURCE_NAMESPACE + "/")
        || !path.filename().equals(inspected.filename())) {
      throw new IllegalArgumentException("Invalid R migration package path");
    }
    // savePackage advances the desired revision. Migration restores all verified package rows
    // first, then the normal durable publisher exposes one complete target-side snapshot.
    return publishInspected(runtime, path, inspected, actor, ip);
  }

  private PublishedPackage publishInspected(
      RepositoryRuntime runtime,
      RPath path,
      RSourcePackageInspector.InspectedPackage inspected,
      String actor,
      String ip) {
    RPackageMetadata metadata = inspected.metadata();
    String leaseKey = coordinateLease(runtime, metadata.packageName(), metadata.version());
    try (RLeaseManager.Lease lease = leases.acquire(leaseKey)) {
      lease.assertHeld();
      Optional<RRegistryDao.PackageRecord> existing = registry.findPackage(
          runtime.id(), SOURCE_NAMESPACE, COMPONENT, metadata.packageName(),
          metadata.version(), ARCHITECTURE);
      if (existing.isPresent()) {
        RRegistryDao.PackageRecord row = existing.orElseThrow();
        if (!row.sha256().equalsIgnoreCase(inspected.sha256())) {
          throw new MavenExceptions.WritePolicyDenied(
              "R package coordinates are immutable; publish a new version");
        }
        if (!row.path().equals(path.normalized())) {
          throw new MavenExceptions.WritePolicyDenied(
              "R package coordinate is already bound to another path");
        }
        return published(row);
      }
      enforceWritePolicy(runtime, false);
      registry.findPackageByPath(runtime.id(), path.normalized()).ifPresent(row -> {
        throw new MavenExceptions.WritePolicyDenied(
            "R package path is already bound to another coordinate");
      });
      Instant now = Instant.now();
      ComponentRecord component = components.component(
          runtime, metadata, inspected.filename(), path.normalized(), inspected.md5(),
          inspected.sha256(), inspected.size(), now);
      AssetRecord asset = assets.storePackage(
          runtime,
          path.normalized(),
          components.browsePath(
              metadata.packageName(), metadata.version(), inspected.filename()),
          inspected.file(),
          RProxyProjectionService.packageAttributes(inspected, "hosted"),
          actor,
          ip,
          component);
      lease.assertHeld();
      RRegistryDao.PackageRecord stored = registry.savePackage(
          new RRegistryDao.PackageRecord(
              null,
              runtime.id(),
              SOURCE_NAMESPACE,
              COMPONENT,
              ARCHITECTURE,
              metadata.packageName(),
              metadata.version(),
              RVersions.orderKey(metadata.version()),
              ARCHITECTURE,
              inspected.filename(),
              path.normalized(),
              objectFields(metadata.indexFields(inspected.md5(), inspected.filename())),
              inspected.md5(),
              inspected.sha256(),
              inspected.sha256(),
              inspected.size(),
              asset.id(),
              asset.componentId(),
              RRegistryDao.SOURCE_HOSTED,
              0,
              now,
              now,
              now));
      registry.replacePackageRelations(
          stored.repositoryId(), stored.id(),
          RProxyProjectionService.relations(stored.id(), metadata));
      lease.assertHeld();
      return published(stored);
    }
  }

  public MavenResponse delete(
      RepositoryRuntime runtime, String rawPath, String reason, boolean enforcePolicy) {
    requireHosted(runtime);
    RPath path = requirePath(rawPath);
    if (path.kind() != RPath.Kind.SOURCE_PACKAGE) {
      throw new MavenExceptions.MethodNotAllowed("Only R source packages can be deleted");
    }
    if (enforcePolicy && !WritePolicy.parse(runtime.writePolicy()).checkDeleteAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy forbids deletion");
    }
    RRegistryDao.PackageRecord existing = registry.findPackageByPath(
        runtime.id(), path.normalized()).orElseThrow(
            () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    try (RLeaseManager.Lease lease = leases.acquire(
        coordinateLease(runtime, existing.packageName(), existing.version()))) {
      lease.assertHeld();
      RRegistryDao.PackageRecord removed = registry.deletePackage(
          runtime.id(), SOURCE_NAMESPACE, COMPONENT, existing.packageName(), existing.version(),
          ARCHITECTURE, reason, Instant.now()).orElseThrow(
              () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
      lease.assertHeld();
      publishPending(runtime, SOURCE_NAMESPACE, true);
      assets.retirePackageProjection(removed.assetId());
      return MavenResponse.noBody(204);
    }
  }

  public List<Integer> deleteComponentsForCleanup(
      RepositoryRuntime runtime, List<Long> componentIds, String reason) {
    requireHosted(runtime);
    if (componentIds == null || componentIds.isEmpty()) return List.of();
    ArrayList<Integer> counts = new ArrayList<>();
    boolean changed = false;
    for (Long componentId : componentIds) {
      int deleted = 0;
      if (componentId != null && componentId > 0) {
        for (AssetRecord asset : assets.listAssetsByComponent(componentId)) {
          if (asset.repositoryId() != runtime.id() || asset.format() != RepositoryFormat.R) continue;
          RRegistryDao.PackageRecord row = registry.findPackageByPath(
              runtime.id(), asset.path()).orElse(null);
          if (row == null) continue;
          Optional<RRegistryDao.PackageRecord> removed = registry.deletePackage(
              row.repositoryId(), row.distribution(), row.component(), row.packageName(),
              row.version(), row.architecture(), reason, Instant.now());
          if (removed.isPresent()) {
            deleted++;
            changed = true;
            assets.retirePackageProjection(row.assetId());
          }
        }
      }
      counts.add(deleted);
    }
    if (changed) publishPending(runtime, SOURCE_NAMESPACE, true);
    return List.copyOf(counts);
  }

  public void rebuild(RepositoryRuntime runtime) {
    requireR(runtime);
    registry.markSuiteDirty(runtime.id(), SOURCE_NAMESPACE, Instant.now());
    publishPending(runtime, SOURCE_NAMESPACE, true);
  }

  public Status status(RepositoryRuntime runtime) {
    requireR(runtime);
    List<NamespaceStatus> namespaces = registry.listSuites(runtime.id()).stream()
        .map(state -> new NamespaceStatus(
            state.distribution(), state.desiredRevision(), state.publishedRevision(),
            state.codecRevision(), state.lastPublishedAt(), state.lastError(), state.lastErrorAt()))
        .toList();
    List<ProxyStatus> proxies = registry.listProxyDistributions(runtime.id()).stream()
        .map(row -> new ProxyStatus(
            row.distribution(), row.releaseIdentity(), row.projectionVerified(), row.observedAt()))
        .toList();
    return new Status(namespaces, proxies);
  }

  boolean publishPendingIfAvailable(RepositoryRuntime runtime, String namespace) {
    requireR(runtime);
    return publishPending(runtime, requireNamespace(namespace), false);
  }

  private MavenResponse getHosted(
      RepositoryRuntime runtime, RPath path, boolean headOnly) {
    if (path.kind() == RPath.Kind.SOURCE_PACKAGE) {
      return assets.serve(runtime, path.normalized(), headOnly)
          .withHeader("Cache-Control", "public, max-age=31536000, immutable");
    }
    if (path.kind() != RPath.Kind.PACKAGES_GZIP) {
      throw new MavenExceptions.MavenNotFoundException(path.normalized());
    }
    RRegistryDao.Snapshot snapshot = ensureSnapshot(runtime, SOURCE_NAMESPACE);
    String hidden = snapshot.manifest().get(path.normalized());
    if (hidden == null) throw new MavenExceptions.MavenNotFoundException(path.normalized());
    return assets.serve(runtime, hidden, headOnly)
        .withHeader("Cache-Control", "public, max-age=0, must-revalidate");
  }

  private MavenResponse getGroup(
      RepositoryRuntime runtime, RPath path, boolean headOnly) {
    if (!path.gzip()) throw new MavenExceptions.MavenNotFoundException(path.normalized());
    if (path.kind() == RPath.Kind.PACKAGES_GZIP) {
      RRegistryDao.Snapshot snapshot = ensureGroupSnapshot(runtime, SOURCE_NAMESPACE);
      String hidden = snapshot.manifest().get(path.normalized());
      if (hidden == null) throw new MavenExceptions.MavenNotFoundException(path.normalized());
      return assets.serve(runtime, hidden, headOnly)
          .withHeader("Cache-Control", "public, max-age=0, must-revalidate");
    }
    // Package bytes are pinned by the group binding created with the published index. Do not
    // contact an upstream proxy again here: the next PACKAGES.gz request refreshes the projection,
    // while this immutable download continues to resolve against one coherent snapshot.
    RRegistryDao.Snapshot snapshot = publishedSnapshots.find(runtime.id(), SOURCE_NAMESPACE)
        .orElseGet(() -> ensureGroupSnapshot(runtime, SOURCE_NAMESPACE));
    RRegistryDao.GroupBinding binding = registry.findGroupBinding(
        runtime.id(), SOURCE_NAMESPACE, snapshot.revision(), path.normalized()).orElseThrow(
            () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    RepositoryRuntime source = runtimes.resolveById(binding.memberRepositoryId()).orElseThrow(
        () -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    MavenResponse response = source.isHosted()
        ? assets.serve(source, binding.memberPath(), headOnly)
        : proxyProjection.getBoundGroupPackage(source, binding, headOnly);
    return response.withHeader("Cache-Control", "public, max-age=31536000, immutable")
        .withHeader("X-kkRepo-Source-Repository", source.name());
  }

  private RRegistryDao.Snapshot ensureSnapshot(
      RepositoryRuntime runtime, String namespace) {
    Optional<RRegistryDao.Snapshot> published = publishedSnapshots.find(runtime.id(), namespace);
    if (published.isPresent()) return published.orElseThrow();
    RRegistryDao.SuiteState state = registry.findSuite(runtime.id(), namespace)
        .orElseGet(() -> registry.ensureSuite(runtime.id(), namespace, Instant.now()));
    if (state.desiredRevision() == state.publishedRevision()) {
      registry.markSuiteDirty(runtime.id(), namespace, Instant.now());
    }
    publishPending(runtime, namespace, true);
    return publishedSnapshots.find(runtime.id(), namespace).orElseThrow(
        () -> new MavenExceptions.MavenNotFoundException(namespace + "/PACKAGES.gz"));
  }

  private RRegistryDao.Snapshot ensureGroupSnapshot(
      RepositoryRuntime runtime, String namespace) {
    // Refresh proxy projections before comparing revisions. A repository configuration change or
    // an upstream PACKAGES.gz update can leave the last published proxy revision unchanged until
    // it is observed; comparing that stale revision first would incorrectly reuse the group index.
    String fingerprint = resolveGroup(runtime, namespace).memberFingerprint();
    RRegistryDao.Snapshot current = publishedSnapshots.find(runtime.id(), namespace).orElse(null);
    if (current == null || !fingerprint.equals(current.manifest().get("@members"))) {
      registry.ensureSuite(runtime.id(), namespace, Instant.now());
      registry.markSuiteDirty(runtime.id(), namespace, Instant.now());
      publishPending(runtime, namespace, true);
    }
    return publishedSnapshots.find(runtime.id(), namespace).orElseThrow(
        () -> new MavenExceptions.MavenNotFoundException(namespace + "/PACKAGES.gz"));
  }

  private boolean publishPending(
      RepositoryRuntime runtime, String namespace, boolean waitForLease) {
    RRegistryDao.SuiteState before = registry.findSuite(runtime.id(), namespace)
        .orElseGet(() -> registry.ensureSuite(runtime.id(), namespace, Instant.now()));
    if (before.desiredRevision() == before.publishedRevision()
        && publishedSnapshots.find(runtime.id(), namespace).isPresent()) return true;
    Optional<RLeaseManager.Lease> acquired = waitForLease
        ? Optional.of(leases.acquire("r:publish:" + runtime.id() + ":" + namespace))
        : leases.tryAcquire("r:publish:" + runtime.id() + ":" + namespace);
    if (acquired.isEmpty()) return false;
    try (RLeaseManager.Lease lease = acquired.orElseThrow()) {
      for (int attempt = 0; attempt < 4; attempt++) {
        lease.assertHeld();
        RRegistryDao.SuiteState state = registry.findSuite(runtime.id(), namespace).orElseThrow();
        if (state.desiredRevision() == state.publishedRevision()
            && publishedSnapshots.find(runtime.id(), namespace).isPresent()) return true;
        try {
          if (runtime.isGroup()) {
            GroupProjection group = resolveGroup(runtime, namespace);
            long revision = state.desiredRevision();
            long token = lease.fencingToken();
            registry.beginGroupSnapshot(runtime.id(), namespace, revision, token);
            boolean published = false;
            try {
              RIndexBuilder.BuiltSnapshot built = indexBuilder.build(
                  runtime, state, visitor -> visitGroupPackages(
                      runtime, namespace, revision, token, group, lease, visitor));
              LinkedHashMap<String, String> manifest = new LinkedHashMap<>(built.manifest());
              manifest.put("@members", group.memberFingerprint());
              RRegistryDao.Snapshot snapshot = new RRegistryDao.Snapshot(
                  runtime.id(), namespace, revision, built.codecRevision(),
                  Map.copyOf(manifest), built.indexSha256(), built.createdAt());
              lease.assertHeld();
              if (registry.publishGroupSnapshot(snapshot, lease.owner(), token)) {
                published = true;
                publishedSnapshots.published(snapshot);
                return true;
              }
            } finally {
              if (!published) {
                registry.discardGroupSnapshot(runtime.id(), namespace, revision, token);
              }
            }
          } else {
            RIndexBuilder.BuiltSnapshot built = indexBuilder.build(runtime, state);
            RRegistryDao.Snapshot snapshot = new RRegistryDao.Snapshot(
                runtime.id(), namespace, state.desiredRevision(), built.codecRevision(),
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
          "R namespace changed repeatedly during publication; retry the request");
    }
    return false;
  }

  private GroupProjection resolveGroup(RepositoryRuntime group, String namespace) {
    ArrayList<ResolvedMember> members = new ArrayList<>();
    LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>();
    collectMembers(group, namespace, new LinkedHashSet<>(), revisions, members, 0);
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
      throw new IllegalStateException("R group membership contains a cycle or exceeds depth");
    }
    try {
      for (RepositoryRuntime member : group.members()) {
        if (member.format() != RepositoryFormat.R || !member.online()) continue;
        if (member.isGroup()) {
          collectMembers(member, namespace, visiting, revisions, members, depth + 1);
          continue;
        }
        if (member.isProxy()) proxyProjection.prepareGroupMember(member, Instant.now());
        long revision = ensureSnapshot(member, namespace).revision();
        revisions.put(member.id(), revision);
        members.add(new ResolvedMember(member.id(), revision));
      }
    } finally {
      visiting.remove(group.id());
    }
  }

  private void visitGroupPackages(
      RepositoryRuntime group,
      String namespace,
      long snapshotRevision,
      long token,
      GroupProjection projection,
      RLeaseManager.Lease lease,
      Consumer<RRegistryDao.PackageRecord> visitor) {
    ArrayList<MemberPackageCursor> cursors = new ArrayList<>();
    projection.members().forEach(member -> cursors.add(new MemberPackageCursor(member, namespace)));
    ArrayList<RRegistryDao.GroupBinding> bindings = new ArrayList<>(GROUP_BINDING_BATCH_SIZE);
    while (true) {
      String packageName = null;
      for (MemberPackageCursor cursor : cursors) {
        RRegistryDao.PackageRecord row = cursor.peek();
        if (row != null && (packageName == null
            || row.packageName().compareTo(packageName) < 0)) packageName = row.packageName();
      }
      if (packageName == null) break;
      ResolvedPackage selected = null;
      for (MemberPackageCursor cursor : cursors) {
        while (cursor.peek() != null && cursor.peek().packageName().equals(packageName)) {
          RRegistryDao.PackageRecord row = cursor.take();
          ResolvedPackage candidate = new ResolvedPackage(
              row, cursor.member().repositoryId(), cursor.member().snapshotRevision(), row.path());
          if (selected == null || RVersions.compare(
              candidate.record().version(), selected.record().version()) > 0) {
            selected = candidate;
          }
        }
      }
      if (selected != null) {
        if (RRegistryDao.SOURCE_PROXY.equals(selected.record().sourceKind())
            && !RProxyProjectionService.validMd5(selected.record().identity())) {
          throw new MavenExceptions.BadUpstreamException(
              "R group requires an upstream MD5sum for " + selected.record().path());
        }
        visitor.accept(selected.record());
        bindings.add(binding(group, namespace, snapshotRevision, selected));
        if (bindings.size() == GROUP_BINDING_BATCH_SIZE) {
          flushBindings(lease, token, bindings);
        }
      }
    }
    flushBindings(lease, token, bindings);
  }

  private void flushBindings(
      RLeaseManager.Lease lease,
      long token,
      List<RRegistryDao.GroupBinding> bindings) {
    if (bindings.isEmpty()) return;
    lease.assertHeld();
    registry.appendGroupBindings(token, List.copyOf(bindings));
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
      throw new IllegalStateException("R group membership contains a cycle or exceeds depth");
    }
    try {
      for (RepositoryRuntime member : group.members()) {
        if (member.format() != RepositoryFormat.R || !member.online()) continue;
        if (member.isGroup()) {
          collectMemberRevisions(member, namespace, visiting, revisions, depth + 1);
        } else {
          RRegistryDao.SuiteState state = registry.findSuite(member.id(), namespace).orElse(null);
          revisions.put(member.id(), state == null ? 0 : state.publishedRevision());
        }
      }
    } finally {
      visiting.remove(group.id());
    }
  }

  private static RRegistryDao.GroupBinding binding(
      RepositoryRuntime group,
      String namespace,
      long snapshotRevision,
      ResolvedPackage selected) {
    RRegistryDao.PackageRecord row = selected.record();
    return new RRegistryDao.GroupBinding(
        null, group.id(), namespace, snapshotRevision, row.path(),
        selected.sourceRepositoryId(), selected.sourceSnapshotRevision(), selected.sourcePath(),
        row.identity(), row.sha256(), row.size(), Instant.now());
  }

  static String fingerprint(Map<Long, Long> revisions) {
    StringBuilder value = new StringBuilder();
    revisions.forEach((repository, revision) -> value.append(repository).append(':')
        .append(revision).append(';'));
    return HexFormat.of().formatHex(PersistenceHashes.sha256(value.toString()));
  }

  private RPath requirePath(String rawPath) {
    RPath path = paths.parse(rawPath);
    if (path.kind() == RPath.Kind.UNKNOWN || path.kind() == RPath.Kind.ROOT) {
      throw new MavenExceptions.MavenNotFoundException(rawPath == null ? "" : rawPath);
    }
    return path;
  }

  private static String requireNamespace(String namespace) {
    if (!SOURCE_NAMESPACE.equals(namespace)) {
      throw new MavenExceptions.BadRequestException(
          "R namespace must be src/contrib");
    }
    return namespace;
  }

  private static void enforceWritePolicy(RepositoryRuntime runtime, boolean exists) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy is DENY");
    }
    if (policy == WritePolicy.ALLOW_ONCE && exists) {
      throw new MavenExceptions.WritePolicyDenied("R package coordinate already exists");
    }
  }

  private static String coordinateLease(
      RepositoryRuntime runtime, String packageName, String version) {
    return "r:coordinate:" + runtime.id() + ":" + HexFormat.of().formatHex(
        PersistenceHashes.sha256(SOURCE_NAMESPACE, packageName, version, ARCHITECTURE));
  }

  private static Map<String, Object> objectFields(Map<String, String> fields) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.putAll(fields);
    return Map.copyOf(result);
  }

  private static PublishedPackage published(RRegistryDao.PackageRecord row) {
    return new PublishedPackage(
        row.path(), row.packageName(), row.version(), row.identity(), row.sha256(), row.size());
  }

  private static void requireR(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.R) {
      throw new MavenExceptions.MethodNotAllowed("Repository is not an R repository");
    }
  }

  private static void requireHosted(RepositoryRuntime runtime) {
    requireR(runtime);
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed(
          "R upload is only valid on hosted repositories");
    }
  }

  public record PublishedPackage(
      String path,
      String packageName,
      String version,
      String md5,
      String sha256,
      long size) { }

  public record Status(
      List<NamespaceStatus> namespaces,
      List<ProxyStatus> proxyNamespaces) { }

  public record NamespaceStatus(
      String namespace,
      long desiredRevision,
      long publishedRevision,
      int codecRevision,
      Instant lastPublishedAt,
      String lastError,
      Instant lastErrorAt) { }

  public record ProxyStatus(
      String namespace,
      String indexIdentity,
      boolean projectionVerified,
      Instant observedAt) { }

  private final class MemberPackageCursor {
    private final ResolvedMember member;
    private final String namespace;
    private List<RRegistryDao.PackageRecord> page = List.of();
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

    private RRegistryDao.PackageRecord peek() {
      if (offset < page.size()) return page.get(offset);
      if (finalPage) return null;
      page = registry.listPackagePage(
          member.repositoryId(), namespace, afterName, afterId, RRegistryDao.PACKAGE_PAGE_SIZE);
      offset = 0;
      if (page.isEmpty()) {
        finalPage = true;
        return null;
      }
      RRegistryDao.PackageRecord cursor = page.getLast();
      afterName = cursor.packageName();
      afterId = cursor.id();
      finalPage = page.size() < RRegistryDao.PACKAGE_PAGE_SIZE;
      return page.getFirst();
    }

    private RRegistryDao.PackageRecord take() {
      RRegistryDao.PackageRecord value = peek();
      if (value == null) throw new IllegalStateException("R group package cursor is exhausted");
      offset++;
      return value;
    }
  }

  private record ResolvedMember(long repositoryId, long snapshotRevision) { }

  private record ResolvedPackage(
      RRegistryDao.PackageRecord record,
      long sourceRepositoryId,
      long sourceSnapshotRevision,
      String sourcePath) { }

  private record GroupProjection(
      List<ResolvedMember> members,
      String memberFingerprint) { }
}
