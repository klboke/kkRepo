package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Shared Conda package metadata, revisions, group bindings, and cross-replica leases. */
public interface CondaRegistryDao {
  String SOURCE_HOSTED = "HOSTED";
  String SOURCE_PROXY = "PROXY";

  long nextRepositoryRevision(long repositoryId);

  long currentRepositoryRevision(long repositoryId);

  default Map<Long, Long> currentRepositoryRevisions(Collection<Long> repositoryIds) {
    LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>();
    if (repositoryIds != null) {
      repositoryIds.stream()
          .filter(java.util.Objects::nonNull)
          .distinct()
          .forEach(id -> revisions.put(id, currentRepositoryRevision(id)));
    }
    return Map.copyOf(revisions);
  }

  PackageRecord saveHostedPackage(PackageRecord record);

  long replaceProxyPackages(
      long repositoryId,
      String channel,
      String subdir,
      String metadataSha256,
      String packageBaseUrl,
      List<PackageRecord> records,
      Instant indexedAt);

  /**
   * Reconciles a replayable, bounded-memory proxy inventory with the durable package index.
   *
   * <p>The source may be visited twice when records changed: once to compute the delta and once
   * to write only changed rows. Implementations must not retain the complete inventory.
   */
  default long replaceProxyPackages(
      long repositoryId,
      String channel,
      String subdir,
      String metadataSha256,
      String packageBaseUrl,
      PackageRecordSource records,
      Instant indexedAt) {
    ArrayList<PackageRecord> collected = new ArrayList<>();
    if (records != null) records.visit(collected::add);
    return replaceProxyPackages(
        repositoryId,
        channel,
        subdir,
        metadataSha256,
        packageBaseUrl,
        List.copyOf(collected),
        indexedAt);
  }

  Optional<PackageRecord> findPackage(
      long repositoryId, String channel, String subdir, String filename);

  List<PackageRecord> listPackages(long repositoryId, String channel, String subdir);

  List<PackageRecord> listPackagesByChannel(long repositoryId, String channel);

  default Instant latestChannelUpdatedAt(long repositoryId, String channel) {
    return listPackagesByChannel(repositoryId, channel).stream()
        .map(PackageRecord::updatedAt)
        .filter(java.util.Objects::nonNull)
        .max(java.util.Comparator.naturalOrder())
        .orElse(Instant.EPOCH);
  }

  /** Streams one archive collection in filename order without retaining the full channel. */
  default void visitPackages(
      long repositoryId,
      String channel,
      String subdir,
      String archiveFormat,
      Consumer<PackageRecord> visitor) {
    listPackages(repositoryId, channel, subdir).stream()
        .filter(record -> archiveFormat.equals(record.archiveFormat()))
        .forEach(visitor);
  }

  /**
   * Streams the first member's record for every filename using the supplied repository priority.
   */
  default void visitPreferredPackages(
      List<Long> repositoryIds,
      String channel,
      String subdir,
      String archiveFormat,
      Consumer<PackageRecord> visitor) {
    java.util.TreeMap<String, PackageRecord> selected = new java.util.TreeMap<>();
    for (Long repositoryId : repositoryIds == null ? List.<Long>of() : repositoryIds) {
      if (repositoryId == null) continue;
      listPackages(repositoryId, channel, subdir).stream()
          .filter(record -> archiveFormat.equals(record.archiveFormat()))
          .forEach(record -> selected.putIfAbsent(record.filename(), record));
    }
    selected.values().forEach(visitor);
  }

  /** Streams every package in one channel ordered by package name and coordinate. */
  default void visitPackagesByChannel(
      long repositoryId, String channel, Consumer<PackageRecord> visitor) {
    listPackagesByChannel(repositoryId, channel).stream()
        .sorted(java.util.Comparator.comparing(PackageRecord::name)
            .thenComparing(PackageRecord::subdir)
            .thenComparing(PackageRecord::filename))
        .forEach(visitor);
  }

  /**
   * Streams the first member's record for every channel coordinate using repository priority.
   */
  default void visitPreferredPackagesByChannel(
      List<Long> repositoryIds, String channel, Consumer<PackageRecord> visitor) {
    java.util.TreeMap<String, PackageRecord> selected = new java.util.TreeMap<>();
    for (Long repositoryId : repositoryIds == null ? List.<Long>of() : repositoryIds) {
      if (repositoryId == null) continue;
      listPackagesByChannel(repositoryId, channel)
          .forEach(record -> selected.putIfAbsent(
              record.subdir() + "\u0000" + record.filename(), record));
    }
    selected.values().stream()
        .sorted(java.util.Comparator.comparing(PackageRecord::name)
            .thenComparing(PackageRecord::subdir)
            .thenComparing(PackageRecord::filename))
        .forEach(visitor);
  }

  default Optional<PackageRecord> findPreferredPackage(
      List<Long> repositoryIds, String channel, String subdir, String filename) {
    for (Long repositoryId : repositoryIds == null ? List.<Long>of() : repositoryIds) {
      if (repositoryId == null) continue;
      Optional<PackageRecord> record = findPackage(repositoryId, channel, subdir, filename);
      if (record.isPresent()) return record;
    }
    return Optional.empty();
  }

  default Set<String> findPreferredPackageFilenames(
      List<Long> repositoryIds,
      String channel,
      String subdir,
      Collection<String> filenames) {
    LinkedHashSet<String> existing = new LinkedHashSet<>();
    for (String filename : filenames == null ? List.<String>of() : filenames) {
      if (filename != null
          && findPreferredPackage(repositoryIds, channel, subdir, filename).isPresent()) {
        existing.add(filename);
      }
    }
    return Set.copyOf(existing);
  }

  List<String> listChannels(long repositoryId);

  Optional<ChannelState> findChannelState(long repositoryId, String channel, String subdir);

  void ensureChannelState(ChannelState state);

  Optional<PackageRecord> tombstoneAndDeletePackage(
      long repositoryId,
      String channel,
      String subdir,
      String filename,
      String reason,
      long revision,
      Instant deletedAt);

  List<Tombstone> listTombstones(long repositoryId, String channel, String subdir);

  Optional<GroupSourceBinding> findGroupSourceBinding(
      long groupRepositoryId, String channel, String subdir, String filename);

  void upsertGroupSourceBinding(GroupSourceBinding binding);

  void deleteGroupSourceBindings(long groupRepositoryId);

  Optional<Lease> tryAcquireLease(String leaseKey, String owner, Instant expiresAt);

  boolean renewLease(String leaseKey, String owner, long fencingToken, Instant expiresAt);

  void releaseLease(String leaseKey, String owner, long fencingToken);

  default int deleteExpiredLeases(Instant expiredBefore, int limit) {
    return 0;
  }

  void deleteRepositoryState(long repositoryId);

  record PackageRecord(
      Long id,
      long repositoryId,
      String channel,
      String subdir,
      String filename,
      String name,
      String version,
      String build,
      long buildNumber,
      String archiveFormat,
      Map<String, Object> metadata,
      String recordSha256,
      String md5,
      String sha256,
      long size,
      Long assetId,
      Long componentId,
      String sourceKind,
      long revision,
      Instant indexedAt,
      Instant updatedAt) {
    public PackageRecord {
      metadata = metadata == null
          ? Map.of()
          : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public PackageRecord withRevision(long newRevision, Instant when) {
      return new PackageRecord(
          id, repositoryId, channel, subdir, filename, name, version, build, buildNumber,
          archiveFormat, metadata, recordSha256, md5, sha256, size, assetId, componentId, sourceKind,
          newRevision, indexedAt == null ? when : indexedAt, when);
    }
  }

  record ChannelState(
      long repositoryId,
      String channel,
      String subdir,
      String metadataSha256,
      String packageBaseUrl,
      long revision,
      Instant indexedAt,
      Instant updatedAt) {}

  record Tombstone(
      long repositoryId,
      String channel,
      String subdir,
      String filename,
      String reason,
      long revision,
      Instant deletedAt) {}

  record GroupSourceBinding(
      long groupRepositoryId,
      String channel,
      String subdir,
      String filename,
      long memberRepositoryId,
      long memberRevision,
      String sha256,
      long groupConfigRevision,
      Instant boundAt,
      Instant updatedAt) {}

  record Lease(
      String leaseKey,
      String owner,
      long fencingToken,
      Instant expiresAt,
      Instant updatedAt) {}

  @FunctionalInterface
  interface PackageRecordSource {
    void visit(Consumer<PackageRecord> visitor);
  }
}
