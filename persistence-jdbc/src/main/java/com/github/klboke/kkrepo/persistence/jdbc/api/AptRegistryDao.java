package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Durable APT package projections, signed snapshots, signing keys and fencing leases. */
public interface AptRegistryDao {
  String SOURCE_HOSTED = "HOSTED";
  String SOURCE_PROXY = "PROXY";

  PackageRecord savePackage(PackageRecord record);

  Optional<PackageRecord> findPackage(
      long repositoryId,
      String distribution,
      String component,
      String packageName,
      String version,
      String architecture);

  Optional<PackageRecord> findPackageByPath(long repositoryId, String path);

  List<PackageRecord> listPackages(
      long repositoryId, String distribution, String component, String architecture);

  /**
   * Visits one architecture index with a database cursor. Implementations must keep ordering
   * stable by package name so callers can bound in-memory Debian-version sorting to one package.
   */
  default void visitPackages(
      long repositoryId,
      String distribution,
      String component,
      String architecture,
      Consumer<PackageRecord> visitor) {
    if (visitor != null) {
      listPackages(repositoryId, distribution, component, architecture).forEach(visitor);
    }
  }

  List<PackageRecord> listPackages(long repositoryId, String distribution);

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

  Optional<SigningKey> findActiveSigningKey(long repositoryId);

  Optional<SigningKey> findSigningKey(long repositoryId, int revision);

  /** Returns newest key revisions first; retained public keys verify snapshots during rotation. */
  List<SigningKey> listSigningKeys(long repositoryId, int limit);

  void insertSigningKey(SigningKey key);

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
      boolean signatureVerified,
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
      String sourcePackage,
      String filename,
      String path,
      Map<String, Object> controlFields,
      String md5,
      String sha1,
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
          sourcePackage, filename, path, controlFields, md5, sha1, sha256, size, assetId,
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
      int signingKeyRevision,
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
      int signingKeyRevision,
      Map<String, String> manifest,
      String releaseSha256,
      Instant createdAt) { }

  record Lease(
      String leaseKey,
      String owner,
      long fencingToken,
      long attemptCount,
      Instant expiresAt,
      Instant updatedAt) { }

  record SigningKey(
      long repositoryId,
      int revision,
      String keyId,
      String fingerprint,
      String encryptedPrivateKey,
      String publicKey,
      boolean active,
      Instant createdAt) { }

  record ProxyDistribution(
      long repositoryId,
      String distribution,
      String releaseIdentity,
      Map<String, ProxyIndex> indices,
      boolean signatureVerified,
      Instant observedAt,
      Instant updatedAt) { }

  record ProxyIndex(String sha256, long size) { }
}
