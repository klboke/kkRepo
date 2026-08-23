package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Durable R package projections, immutable index snapshots and fencing leases. */
public interface RRegistryDao {
  String SOURCE_HOSTED = "HOSTED";
  String SOURCE_PROXY = "PROXY";
  int PACKAGE_PAGE_SIZE = 2_048;

  PackageRecord savePackage(PackageRecord record);

  /**
   * Attaches a lazily fetched proxy Blob to an existing index projection without changing the
   * repository revision. The compare-and-set inputs prevent bytes fetched for an obsolete
   * PACKAGES.gz generation from being attached to a newer projection.
   */
  Optional<PackageRecord> materializeProxyPackage(
      PackageRecord record, String expectedIdentity, long expectedRevision);

  Optional<PackageRecord> findPackage(
      long repositoryId,
      String distribution,
      String component,
      String packageName,
      String version,
      String architecture);

  Optional<PackageRecord> findPackageByPath(long repositoryId, String path);

  Optional<PackageRecord> findLatestPackage(
      long repositoryId, String distribution, String packageName);

  List<PackageRecord> listPackagePage(
      long repositoryId,
      String distribution,
      String component,
      String architecture,
      String afterPackageName,
      long afterId,
      int limit);

  /**
   * Visits one architecture index with a database cursor. Implementations must keep ordering
   * stable by package name so callers can bound in-memory R-version sorting to one package.
   */
  default void visitPackages(
      long repositoryId,
      String distribution,
      String component,
      String architecture,
      Consumer<PackageRecord> visitor) {
    if (visitor == null) return;
    String afterName = "";
    long afterId = 0;
    while (true) {
      List<PackageRecord> page = listPackagePage(
          repositoryId,
          distribution,
          component,
          architecture,
          afterName,
          afterId,
          PACKAGE_PAGE_SIZE);
      page.forEach(visitor);
      if (page.size() < PACKAGE_PAGE_SIZE) return;
      PackageRecord cursor = page.getLast();
      afterName = cursor.packageName();
      afterId = cursor.id();
    }
  }

  List<PackageRecord> listPackagePage(
      long repositoryId,
      String distribution,
      String afterPackageName,
      long afterId,
      int limit);

  default void visitPackages(
      long repositoryId, String distribution, Consumer<PackageRecord> visitor) {
    if (visitor == null) return;
    String afterName = "";
    long afterId = 0;
    while (true) {
      List<PackageRecord> page = listPackagePage(
          repositoryId, distribution, afterName, afterId, PACKAGE_PAGE_SIZE);
      page.forEach(visitor);
      if (page.size() < PACKAGE_PAGE_SIZE) return;
      PackageRecord cursor = page.getLast();
      afterName = cursor.packageName();
      afterId = cursor.id();
    }
  }

  List<String> listDistributions(long repositoryId);

  List<String> listComponents(long repositoryId, String distribution);

  List<String> listArchitectures(long repositoryId, String distribution, String component);

  Optional<PackageRecord> deletePackage(
      long repositoryId,
      String distribution,
      String component,
      String packageName,
      String version,
      String architecture,
      String reason,
      Instant deletedAt);

  /** Package tombstones become collectible only after every referencing snapshot is gone. */
  List<PackageTombstone> listPackageCleanupCandidates(Instant deletedBefore, int limit);

  boolean deletePackageTombstone(PackageTombstone tombstone);

  SuiteState ensureSuite(long repositoryId, String distribution, Instant now);

  long markSuiteDirty(long repositoryId, String distribution, Instant now);

  Optional<SuiteState> findSuite(long repositoryId, String distribution);

  List<SuiteState> listSuites(long repositoryId);

  /** Returns a bounded global queue of hosted, debounced or retryable suites awaiting publication. */
  List<SuiteState> listPendingSuites(
      Instant readyBefore, Instant forceBefore, Instant retryBefore, int limit);

  boolean publishSnapshot(Snapshot snapshot, String leaseOwner, long fencingToken);

  Optional<Snapshot> findPublishedSnapshot(long repositoryId, String distribution);

  Optional<Snapshot> findSnapshot(long repositoryId, String distribution, long revision);

  /** Returns newest snapshots first so immutable by-hash paths remain readable across publishes. */
  List<Snapshot> listSnapshots(long repositoryId, String distribution, int limit);

  /** Returns old published snapshots outside the newest retained window, oldest first. */
  List<Snapshot> listSnapshotCleanupCandidates(
      Instant createdBefore, int minSnapshots, int limit);

  /** Deletes a non-current immutable snapshot; the published pointer is never removed. */
  boolean deleteSnapshot(long repositoryId, String distribution, long revision);

  void recordBuildFailure(
      long repositoryId, String distribution, long revision, String message, Instant failedAt);

  Optional<Lease> tryAcquireLease(
      String leaseKey, String owner, Instant now, Instant expiresAt);

  boolean renewLease(
      String leaseKey, String owner, long fencingToken, Instant now, Instant expiresAt);

  void releaseLease(String leaseKey, String owner, long fencingToken);

  void replacePackageRelations(
      long repositoryId, long packageId, List<PackageRelation> relations);

  List<PackageRecord> findPackagesByRelation(
      long repositoryId, String relationKind, String token, Long afterId, int limit);

  void beginGroupSnapshot(
      long groupRepositoryId, String namespace, long snapshotRevision, long fencingToken);

  void appendGroupBindings(long fencingToken, List<GroupBinding> bindings);

  void discardGroupSnapshot(
      long groupRepositoryId, String namespace, long snapshotRevision, long fencingToken);

  boolean publishGroupSnapshot(Snapshot snapshot, String leaseOwner, long fencingToken);

  Optional<GroupBinding> findGroupBinding(
      long groupRepositoryId, String namespace, long snapshotRevision, String path);

  List<GroupBinding> listGroupBindings(
      long groupRepositoryId, String namespace, long snapshotRevision, Long afterId, int limit);

  int deleteOrphanGroupBindings(Instant createdBefore, int limit);

  default void observeProxyDistribution(
      long repositoryId, String distribution, String releaseIdentity, Instant observedAt) {
    observeProxyDistribution(
        repositoryId, distribution, releaseIdentity, Map.of(), false, observedAt);
  }

  void observeProxyDistribution(
      long repositoryId,
      String distribution,
      String releaseIdentity,
      Map<String, ProxyIndex> indices,
      boolean projectionVerified,
      Instant observedAt);

  Optional<ProxyDistribution> findProxyDistribution(long repositoryId, String distribution);

  List<ProxyDistribution> listProxyDistributions(long repositoryId);

  void deleteRepositoryState(long repositoryId);

  record PackageRecord(
      Long id,
      long repositoryId,
      String distribution,
      String component,
      String architecture,
      String packageName,
      String version,
      byte[] versionOrderKey,
      String packageArchitecture,
      String filename,
      String path,
      Map<String, Object> controlFields,
      String identity,
      String dataSha256,
      String sha256,
      long size,
      Long assetId,
      Long componentId,
      String sourceKind,
      long revision,
      Instant indexedAt,
      Instant createdAt,
      Instant updatedAt) {
    public PackageRecord withRevision(long nextRevision, Instant now) {
      return new PackageRecord(
          id, repositoryId, distribution, component, architecture, packageName, version,
          versionOrderKey,
          packageArchitecture, filename, path, controlFields, identity, dataSha256, sha256, size, assetId,
          componentId, sourceKind, nextRevision, indexedAt == null ? now : indexedAt,
          createdAt == null ? now : createdAt, now);
    }
  }

  record SuiteState(
      long repositoryId,
      String distribution,
      long desiredRevision,
      Instant desiredAt,
      long publishedRevision,
      int codecRevision,
      Instant lastPublishedAt,
      String lastError,
      Instant lastErrorAt,
      Instant updatedAt) { }

  record PackageTombstone(
      long repositoryId,
      String distribution,
      String component,
      String architecture,
      String packageName,
      String version,
      String path,
      String reason,
      long revision,
      Instant deletedAt) { }

  record Snapshot(
      long repositoryId,
      String distribution,
      long revision,
      int codecRevision,
      Map<String, String> manifest,
      String indexSha256,
      Instant createdAt) { }

  record Lease(
      String leaseKey,
      String owner,
      long fencingToken,
      long attemptCount,
      Instant expiresAt,
      Instant updatedAt) { }

  record PackageRelation(
      long packageId,
      String relationKind,
      String token,
      String expression) { }

  record GroupBinding(
      Long id,
      long groupRepositoryId,
      String namespace,
      long snapshotRevision,
      String path,
      long memberRepositoryId,
      long memberSnapshotRevision,
      String memberPath,
      String identity,
      String sha256,
      long size,
      Instant createdAt) { }

  record ProxyDistribution(
      long repositoryId,
      String distribution,
      String releaseIdentity,
      Map<String, ProxyIndex> indices,
      boolean projectionVerified,
      Instant observedAt,
      Instant updatedAt) { }

  record ProxyIndex(String sha256, long size) { }
}
