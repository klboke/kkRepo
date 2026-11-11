package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.MavenSnapshotDao.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenSnapshotBackfillWorker;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Snapshot-specific policy evaluation. Inventory and candidates are bounded independently. */
@Service
public class MavenSnapshotCleanupService {
  public static final String KIND = "MAVEN_SNAPSHOT_BUILD";
  public static final String PHASE = "MAVEN_SNAPSHOT";
  static final int MAX_BUILD_ASSETS = 4096;
  static final int MAX_SCAN_ASSETS = 20000;
  static final int MAX_SCAN_METADATA = 64;
  static final int MAX_METADATA_BYTES = 1024 * 1024;
  private volatile Duration writeGrace = Duration.ofHours(1);

  @org.springframework.beans.factory.annotation.Value("${kkrepo.maven.snapshot-cleanup.write-grace:PT1H}")
  void setWriteGrace(Duration grace) {
    if (grace.isNegative()) throw new IllegalArgumentException("snapshot write grace must not be negative");
    this.writeGrace = grace;
  }
  private final MavenSnapshotDao snapshots;
  private final AssetDao assets;
  private final CleanupPolicyDao cleanup;
  private final MaintenanceCursorDao cursors;
  private final BlobStorageRegistry storage;
  private final CleanupUsageTrackingService tracking;
  private final CleanupRuntimeProperties properties;

  public MavenSnapshotCleanupService(MavenSnapshotDao snapshots, AssetDao assets,
      CleanupPolicyDao cleanup, MaintenanceCursorDao cursors, BlobStorageRegistry storage,
      CleanupUsageTrackingService tracking, CleanupRuntimeProperties properties) {
    this.snapshots = snapshots; this.assets = assets; this.cleanup = cleanup;
    this.cursors = cursors; this.storage = storage; this.tracking = tracking; this.properties = properties;
  }

  public ScanResult scan(RepositoryRecord repository, Map<String, Object> raw, int limit,
      Instant cutoff, CleanupScanCursor requested) {
    requireHosted(repository);
    if (requested != null && requested.repositoryId() != repository.id()) {
      throw new IllegalArgumentException("cleanup cursor repository does not match target");
    }
    CleanupCriteria criteria = CleanupCriteria.parse(raw);
    CleanupScanCursor start = cursor(requested, repository.id(),
        requested != null && PHASE.equals(requested.phase()) ? requested.subjectId() : 0, false);
    String safety = safety(repository.id(), criteria, cutoff);
    if (cursors.minimumLastSeenId(MavenSnapshotBackfillWorker.READY).orElse(0) != 1) safety = "SNAPSHOT_INDEX_WARMING_UP";
    if (safety != null) return new ScanResult(0, false, null, List.of(), safety, start, start, null);
    List<Build> page = snapshots.page(repository.id(), start.subjectId(), limit + 1);
    Map<Long, Family> families = new HashMap<>();
    Map<Long, Optional<Build>> retained = new HashMap<>();
    Map<Long, Metadata> metadata = new HashMap<>();
    Map<String, Integer> kept = new LinkedHashMap<>();
    List<Candidate> candidates = new ArrayList<>();
    long after = start.subjectId();
    int scanned = 0;
    int assetBudget = MAX_SCAN_ASSETS;
    for (Build build : page) {
      if (scanned == limit) break;
      if (!metadata.containsKey(build.familyId()) && metadata.size() >= MAX_SCAN_METADATA) break;
      if (build.assetCount() <= MAX_BUILD_ASSETS && build.assetCount() > assetBudget) break;
      scanned++;
      after = build.id();
      Family family = families.computeIfAbsent(build.familyId(), id -> snapshots.findFamily(id).orElse(null));
      if (family == null) { keep(kept, "changed"); continue; }
      if (!criteria.matchesPattern(family.artifactId())
          && !criteria.matchesPattern(family.groupId() + ":" + family.artifactId() + ":" + family.baseVersion())
          && !criteria.matchesPattern(family.path())) {
        keep(kept, "pattern"); continue;
      }
      Optional<Build> boundary = retained.computeIfAbsent(family.id(), id ->
          snapshots.retentionBoundary(id, criteria.retainCount()));
      if (isRetained(build, boundary)) { keep(kept, "newest"); continue; }
      if (build.assetCount() > MAX_BUILD_ASSETS) { keep(kept, "oversized_build"); continue; }
      if (build.primaryCount() == 0) { keep(kept, "no_primary_artifact"); continue; }
      if (build.mutatedAt().isAfter(cutoff.minus(writeGrace))) { keep(kept, "recent_write"); continue; }
      Metadata references = metadata.computeIfAbsent(family.id(), id -> metadata(family));
      if (!references.valid()) { keep(kept, "metadata_unavailable"); continue; }
      if (references.versions().contains(buildKey(build.timestamp(), build.buildNumber())) || snapshots.hasLatestVariant(build.id())) {
        keep(kept, "metadata_or_latest_classifier"); continue;
      }
      List<Long> ids = snapshots.assetIds(build.id(), MAX_BUILD_ASSETS + 1);
      assetBudget -= ids.size();
      if (ids.size() != build.assetCount()) { keep(kept, "changed"); continue; }
      Map<Long, AssetRecord> rows = assets.findAssetsByIds(ids);
      if (rows.size() != ids.size()) { keep(kept, "changed"); continue; }
      Subject subject = subject(build, family, rows, references.token());
      if (!matches(criteria, subject, cutoff)) { keep(kept, "age_or_download"); continue; }
      CleanupProtection protection = protection(repository.id(), subject, rows, cutoff);
      Map<String, Object> reason = new LinkedHashMap<>(criteria.matchReason(null));
      reason.remove("versionRank");
      reason.put("unit", "snapshot-build");
      reason.put("baseVersion", family.baseVersion());
      candidates.add(new Candidate(subject, Map.copyOf(reason), protection == null ? null : protection.id()));
    }
    boolean truncated = scanned < page.size();
    return new ScanResult(scanned, truncated, null, List.copyOf(candidates), null, start,
        cursor(start, repository.id(), truncated ? after : 0, !truncated),
        kept.isEmpty() ? null : "Snapshot builds retained: " + kept);
  }

  public Optional<Subject> resolve(RepositoryRecord repository, long buildId, boolean lock) {
    requireHosted(repository);
    Build build = snapshots.findBuild(buildId).orElse(null);
    if (build == null || build.repositoryId() != repository.id()) return Optional.empty();
    Family family = snapshots.findFamily(build.familyId()).orElse(null);
    if (family == null) return Optional.empty();
    if (lock) {
      snapshots.lockFamily(repository.id(), family.path(), family.groupId(), family.artifactId(), family.baseVersion());
      build = snapshots.findBuild(buildId).orElse(null);
      if (build == null) return Optional.empty();
    }
    if (build.assetCount() > MAX_BUILD_ASSETS) return Optional.empty();
    List<Long> ids = snapshots.assetIds(build.id(), MAX_BUILD_ASSETS + 1);
    if (ids.size() != build.assetCount()) return Optional.empty();
    Map<Long, AssetRecord> rows = lock ? assets.findAssetsByIdsForUpdate(ids) : assets.findAssetsByIds(ids);
    if (rows.size() != ids.size()) return Optional.empty();
    // Only metadata identity is needed under lock. Blob XML was read before the transaction;
    // any replacement changes this token and invalidates that candidate's reference decisions.
    return Optional.of(subject(build, family, rows, metadataToken(family)));
  }

  public String revalidate(RepositoryRecord repository, Map<String, Object> raw, Subject subject, Instant cutoff) {
    if (!CleanupCriteria.snapshotMode(raw)) return "snapshot policy mode changed";
    if (cursors.minimumLastSeenId(MavenSnapshotBackfillWorker.READY).orElse(0) != 1) return "snapshot inventory is not ready";
    CleanupCriteria criteria = CleanupCriteria.parse(raw);
    if (safety(repository.id(), criteria, cutoff) != null) return "download observation is not ready";
    Build build = snapshots.findBuild(subject.identityId()).orElse(null);
    if (build == null || build.primaryCount() == 0) return "snapshot build disappeared";
    if (isRetained(build, snapshots.retentionBoundary(build.familyId(), criteria.retainCount()))) return "newest build retained";
    if (snapshots.hasLatestVariant(build.id())) return "latest classifier retained";
    if (build.mutatedAt().isAfter(cutoff.minus(writeGrace))) return "recent snapshot mutation";
    if (!matches(criteria, subject, cutoff)) return "snapshot age or download changed";
    return null;
  }

  public CleanupProtection protection(long repositoryId, Subject subject, Instant at) {
    return protection(repositoryId, subject, assets.findAssetsByIds(subject.assetIds()), at);
  }

  private CleanupProtection protection(long repositoryId, Subject subject, Map<Long, AssetRecord> rows, Instant at) {
    // Preserve existing component/asset holds when a policy switches from component to build mode.
    List<CleanupProtectionLookup> lookups = new ArrayList<>();
    lookups.add(lookup(subject.key(), KIND));
    Set<Long> components = new HashSet<>();
    for (AssetRecord row : rows.values()) {
      lookups.add(lookup("asset:" + row.id(), "ASSET"));
      if (row.componentId() != null) components.add(row.componentId());
    }
    components.forEach(id -> lookups.add(lookup("component:" + id, "COMPONENT")));
    return cleanup.findActiveProtections(repositoryId, lookups, at).values().stream().findFirst().orElse(null);
  }

  private static CleanupProtectionLookup lookup(String key, String kind) {
    return new CleanupProtectionLookup(key, kind, key, PersistenceHashes.sha256(key));
  }

  private Subject subject(Build build, Family family, Map<Long, AssetRecord> rows, String metadataToken) {
    List<Long> ids = rows.keySet().stream().sorted().toList();
    Map<Long, CleanupUsage> usage = cleanup.findAssetUsage(ids);
    Instant published = null;
    Instant downloaded = null;
    long bytes = 0;
    long revision = 0;
    StringBuilder generation = new StringBuilder("snapshot|").append(build.id()).append('|')
        .append(build.revision()).append('|').append(metadataToken);
    for (long id : ids) {
      AssetRecord row = rows.get(id);
      published = latest(published, row.lastUpdatedAt());
      downloaded = latest(downloaded, row.lastDownloadedAt());
      CleanupUsage u = usage.get(id);
      if (u != null) {
        downloaded = latest(downloaded, u.lastDownloadedAt());
        revision = Long.MAX_VALUE - revision < u.usageRevision() ? Long.MAX_VALUE : revision + u.usageRevision();
      }
      bytes += row.size() == null ? 0 : row.size();
      generation.append('|').append(id).append(':').append(row.assetBlobId()).append(':').append(row.lastUpdatedAt());
    }
    String key = "maven-snapshot-build:" + build.id();
    return new Subject(build.id(), KIND, key, PersistenceHashes.sha256(key),
        "maven-snapshot-family:" + family.id(), family.artifactId(),
        family.groupId() + ":" + family.artifactId() + ":" + build.version(), build.version(),
        family.path() + "/" + family.artifactId() + "-" + build.version(), downloaded, published,
        ids.size(), bytes, ids, HexFormat.of().formatHex(PersistenceHashes.sha256(generation.toString())), revision);
  }

  private boolean matches(CleanupCriteria criteria, Subject subject, Instant cutoff) {
    return criteria.matchesPublishedAt(subject.publishedAt(), cutoff)
        && criteria.matchesLastDownloadedAt(subject.lastDownloadedAt() == null
            ? subject.publishedAt() : subject.lastDownloadedAt(), cutoff.minus(properties.getUsage().getSafetyLag()));
  }

  private String safety(long repositoryId, CleanupCriteria criteria, Instant cutoff) {
    if (criteria.lastDownloadedOlderThanDays() == null) return null;
    Instant started = tracking.trackingStartedAt(repositoryId);
    if (started == null) return "USAGE_TRACKING_NOT_ACTIVE";
    return started.isAfter(cutoff.minus(Duration.ofDays(criteria.lastDownloadedOlderThanDays()))
        .minus(properties.getUsage().getSafetyLag())) ? "USAGE_TRACKING_WARMING_UP" : null;
  }

  private Metadata metadata(Family family) {
    AssetRecord asset = assets.findAssetByPath(family.repositoryId(), family.path() + "/maven-metadata.xml").orElse(null);
    if (asset == null) return new Metadata("absent", Set.of(), true);
    String token = token(asset);
    if (asset.assetBlobId() == null || asset.size() == null || asset.size() > MAX_METADATA_BYTES) {
      return new Metadata(token, Set.of(), false);
    }
    var blob = assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) return new Metadata(token, Set.of(), false);
    try (var input = storage.forBlobStoreId(blob.blobStoreId()).get(BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size())).orElseThrow()) {
      byte[] xml = input.readNBytes(MAX_METADATA_BYTES + 1);
      if (xml.length > MAX_METADATA_BYTES) return new Metadata(token, Set.of(), false);
      var parsed = MavenMetadataXml.read(xml);
      if (!family.groupId().equals(parsed.groupId) || !family.artifactId().equals(parsed.artifactId)
          || !family.baseVersion().equals(parsed.version)) return new Metadata(token, Set.of(), false);
      Set<String> versions = new HashSet<>();
      for (var snapshot : parsed.snapshotVersions) {
        if (snapshot.value == null || !addReference(versions, family, snapshot.value)) {
          return new Metadata(token, Set.of(), false);
        }
      }
      if (parsed.snapshotTimestamp != null && !addReference(versions, family,
          family.baseVersion().substring(0, family.baseVersion().length() - "SNAPSHOT".length())
              + parsed.snapshotTimestamp + "-" + parsed.snapshotBuildNumber)) {
        return new Metadata(token, Set.of(), false);
      }
      return new Metadata(token, Set.copyOf(versions), true);
    } catch (IOException | RuntimeException e) {
      return new Metadata(token, Set.of(), false);
    }
  }

  private static boolean addReference(Set<String> versions, Family family, String version) {
    var parsed = com.github.klboke.kkrepo.protocol.maven.path.MavenSnapshotBuild.parse(
        family.path() + "/" + family.artifactId() + "-" + version + ".jar");
    parsed.ifPresent(build -> versions.add(buildKey(build.timestamp(), build.buildNumber())));
    return parsed.isPresent();
  }

  private static String buildKey(long timestamp, int number) { return timestamp + ":" + number; }

  private String metadataToken(Family family) {
    return assets.findAssetByPath(family.repositoryId(), family.path() + "/maven-metadata.xml")
        .map(MavenSnapshotCleanupService::token).orElse("absent");
  }

  private static String token(AssetRecord asset) {
    return asset.id() + ":" + asset.assetBlobId() + ":" + asset.lastUpdatedAt();
  }

  private static boolean isRetained(Build build, Optional<Build> boundary) {
    return boundary.isEmpty() || build.timestamp() > boundary.get().timestamp()
        || (build.timestamp() == boundary.get().timestamp() && build.buildNumber() >= boundary.get().buildNumber());
  }

  private static void requireHosted(RepositoryRecord repository) {
    if (repository.format() != RepositoryFormat.MAVEN2 || repository.type() != RepositoryType.HOSTED) {
      throw new CleanupValidationException("snapshot build cleanup requires a Maven hosted repository");
    }
  }

  private static Instant latest(Instant a, Instant b) {
    return a == null ? b : b == null || a.isAfter(b) ? a : b;
  }

  private static void keep(Map<String, Integer> counts, String reason) { counts.merge(reason, 1, Integer::sum); }

  private static CleanupScanCursor cursor(CleanupScanCursor prior, long repositoryId, long after, boolean wrap) {
    return new CleanupScanCursor(prior == null ? 0 : prior.policyId(), repositoryId, PHASE, null, null, null,
        after, prior == null ? 0 : prior.revision(), (prior == null ? 0 : prior.wrappedCount()) + (wrap ? 1 : 0));
  }

  private record Metadata(String token, Set<String> versions, boolean valid) {}
}
