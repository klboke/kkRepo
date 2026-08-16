package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.AlpinePersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Portable MySQL/PostgreSQL implementation of Alpine durable publication state. */
@Repository
public class JdbcAlpineRegistryDao implements AlpineRegistryDao {
  private static final String REVISION_PREFIX = "alpine:repository:";

  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final AlpinePersistenceDialect alpine;
  private final CoordinationPersistenceDialect coordination;
  private final String groupBindingPathTable;
  private final String groupBindingPageTable;
  private final RowMapper<PackageRecord> packageMapper = this::mapPackage;

  public JdbcAlpineRegistryDao(
      JdbcTemplate jdbc, JsonColumns json, DatabaseDialect databaseDialect) {
    this.jdbc = jdbc;
    this.json = json;
    this.alpine = databaseDialect.alpine();
    this.coordination = databaseDialect.coordination();
    this.groupBindingPathTable = databaseDialect.tableReferenceWithPreferredIndex(
        "alpine_group_binding", "uk_alpine_group_binding");
    this.groupBindingPageTable = databaseDialect.tableReferenceWithPreferredIndex(
        "alpine_group_binding", "idx_alpine_group_page");
  }

  @Override
  @Transactional
  public PackageRecord savePackage(PackageRecord record) {
    requirePackage(record);
    Instant now = record.updatedAt() == null ? Instant.now() : record.updatedAt();
    long revision = nextRevision(record.repositoryId());
    PackageRecord stored = record.withRevision(revision, now);
    ensureSuite(record.repositoryId(), record.distribution(), now);
    advanceSuite(record.repositoryId(), record.distribution(), revision, now);
    byte[] coordinateHash = coordinateHash(stored);
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE alpine_package_record
            SET distribution_name = ?, component_name = ?, architecture = ?, package_name = ?,
                package_version = ?, package_architecture = ?, filename = ?, asset_path = ?,
                asset_path_hash = ?,
                control_fields = ?, package_identity = ?, data_sha256 = ?, sha256 = ?, size_bytes = ?, asset_id = ?,
                component_id = ?, source_kind = ?, revision = ?, indexed_at = ?, updated_at = ?
            WHERE repository_id = ? AND coordinate_hash = ?
            """,
        new Object[] {
          stored.distribution(), stored.component(), stored.architecture(), stored.packageName(),
          stored.version(), stored.packageArchitecture(), stored.filename(), stored.path(),
          PersistenceHashes.sha256(stored.path()), json.parameter(stored.controlFields()),
          stored.identity(), stored.dataSha256(), stored.sha256(),
          stored.size(), stored.assetId(), stored.componentId(), stored.sourceKind(), revision,
          nullableTimestamp(stored.indexedAt()), nullableTimestamp(now), stored.repositoryId(),
          coordinateHash
        },
        """
            INSERT INTO alpine_package_record
              (repository_id, coordinate_hash, distribution_name, component_name, architecture,
               package_name, package_version, package_architecture, filename, asset_path,
               asset_path_hash, control_fields,
               package_identity, data_sha256, sha256, size_bytes, asset_id, component_id, source_kind, revision,
               indexed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          stored.repositoryId(), coordinateHash, stored.distribution(), stored.component(),
          stored.architecture(), stored.packageName(), stored.version(), stored.packageArchitecture(),
          stored.filename(), stored.path(), PersistenceHashes.sha256(stored.path()),
          json.parameter(stored.controlFields()), stored.identity(),
          stored.dataSha256(), stored.sha256(), stored.size(), stored.assetId(), stored.componentId(),
          stored.sourceKind(), revision, nullableTimestamp(stored.indexedAt()),
          nullableTimestamp(stored.createdAt()), nullableTimestamp(now)
        });
    jdbc.update(
        "DELETE FROM alpine_package_tombstone WHERE repository_id = ? AND coordinate_hash = ?",
        stored.repositoryId(), coordinateHash);
    return findPackage(
        stored.repositoryId(), stored.distribution(), stored.component(), stored.packageName(),
        stored.version(), stored.architecture()).orElseThrow();
  }

  @Override
  public Optional<PackageRecord> findPackage(
      long repositoryId,
      String distribution,
      String component,
      String packageName,
      String version,
      String architecture) {
    byte[] hash = PersistenceHashes.sha256(
        distribution, component, packageName, version, architecture);
    return jdbc.query(
        "SELECT * FROM alpine_package_record WHERE repository_id = ? AND coordinate_hash = ?",
        packageMapper, repositoryId, hash).stream().findFirst();
  }

  @Override
  public Optional<PackageRecord> findPackageByPath(long repositoryId, String path) {
    return jdbc.query(
        """
        SELECT * FROM alpine_package_record
        WHERE repository_id = ? AND asset_path_hash = ? AND asset_path = ?
        """,
        packageMapper, repositoryId, PersistenceHashes.sha256(path), path).stream().findFirst();
  }

  @Override
  public List<PackageRecord> listPackagePage(
      long repositoryId,
      String distribution,
      String component,
      String architecture,
      String afterPackageName,
      long afterId,
      int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, PACKAGE_PAGE_SIZE));
    String cursorName = afterPackageName == null ? "" : afterPackageName;
    AlpinePersistenceDialect.KeysetCursor cursor = alpine.packageNameIdCursor(
        cursorName, Math.max(0, afterId));
    String sql = """
        SELECT * FROM alpine_package_record
        WHERE repository_id = ? AND distribution_name = ? AND component_name = ?
          AND architecture = ?
          AND %s
        ORDER BY package_name, id
        LIMIT ?
        """.formatted(cursor.predicate());
    List<Object> arguments = new ArrayList<>();
    arguments.add(repositoryId);
    arguments.add(distribution);
    arguments.add(component);
    arguments.add(architecture);
    arguments.addAll(cursor.arguments());
    arguments.add(boundedLimit);
    return jdbc.query(sql, packageMapper, arguments.toArray());
  }

  @Override
  public List<PackageRecord> listPackagePage(
      long repositoryId,
      String distribution,
      String afterPackageName,
      long afterId,
      int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, PACKAGE_PAGE_SIZE));
    String cursorName = afterPackageName == null ? "" : afterPackageName;
    AlpinePersistenceDialect.KeysetCursor cursor = alpine.packageNameIdCursor(
        cursorName, Math.max(0, afterId));
    String sql = """
        SELECT * FROM alpine_package_record
        WHERE repository_id = ? AND distribution_name = ?
          AND %s
        ORDER BY package_name, id
        LIMIT ?
        """.formatted(cursor.predicate());
    List<Object> arguments = new ArrayList<>();
    arguments.add(repositoryId);
    arguments.add(distribution);
    arguments.addAll(cursor.arguments());
    arguments.add(boundedLimit);
    return jdbc.query(sql, packageMapper, arguments.toArray());
  }

  @Override
  public List<String> listDistributions(long repositoryId) {
    return jdbc.queryForList(
        "SELECT distribution_name FROM alpine_suite_state WHERE repository_id = ? ORDER BY distribution_name",
        String.class, repositoryId);
  }

  @Override
  public List<String> listComponents(long repositoryId, String distribution) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT component_name FROM alpine_package_record
        WHERE repository_id = ? AND distribution_name = ? ORDER BY component_name
        """, String.class, repositoryId, distribution);
  }

  @Override
  public List<String> listArchitectures(
      long repositoryId, String distribution, String component) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT architecture FROM alpine_package_record
        WHERE repository_id = ? AND distribution_name = ? AND component_name = ?
        ORDER BY architecture
        """, String.class, repositoryId, distribution, component);
  }

  @Override
  @Transactional
  public Optional<PackageRecord> deletePackage(
      long repositoryId,
      String distribution,
      String component,
      String packageName,
      String version,
      String architecture,
      String reason,
      Instant deletedAt) {
    Optional<PackageRecord> current = findPackage(
        repositoryId, distribution, component, packageName, version, architecture);
    if (current.isEmpty()) return Optional.empty();
    Instant now = deletedAt == null ? Instant.now() : deletedAt;
    long revision = nextRevision(repositoryId);
    PackageRecord row = current.orElseThrow();
    byte[] hash = coordinateHash(row);
    int deleted = jdbc.update(
        "DELETE FROM alpine_package_record WHERE repository_id = ? AND coordinate_hash = ?",
        repositoryId, hash);
    if (deleted == 0) return Optional.empty();
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE alpine_package_tombstone SET reason = ?, revision = ?, deleted_at = ?
        WHERE repository_id = ? AND coordinate_hash = ?
        """,
        new Object[] {reason, revision, nullableTimestamp(now), repositoryId, hash},
        """
        INSERT INTO alpine_package_tombstone
          (repository_id, coordinate_hash, distribution_name, component_name, architecture,
           package_name, package_version, asset_path, reason, revision, deleted_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[] {
          repositoryId, hash, distribution, component, architecture, packageName, version,
          row.path(), reason, revision, nullableTimestamp(now)
        });
    ensureSuite(repositoryId, distribution, now);
    advanceSuite(repositoryId, distribution, revision, now);
    return Optional.of(row);
  }

  @Override
  public List<PackageTombstone> listPackageCleanupCandidates(
      Instant deletedBefore, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 256));
    Instant cutoff = deletedBefore == null ? Instant.now() : deletedBefore;
    return jdbc.query(
        """
        SELECT t.* FROM alpine_package_tombstone t
        WHERE t.deleted_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM alpine_snapshot s
            WHERE s.repository_id = t.repository_id
              AND s.distribution_name = t.distribution_name
              AND s.published_at IS NOT NULL
              AND s.revision < t.revision)
        ORDER BY t.deleted_at, t.repository_id, t.revision
        LIMIT ?
        """,
        this::mapPackageTombstone,
        nullableTimestamp(cutoff),
        boundedLimit);
  }

  @Override
  @Transactional
  public boolean deletePackageTombstone(PackageTombstone tombstone) {
    if (tombstone == null) return false;
    byte[] coordinateHash = PersistenceHashes.sha256(
        tombstone.distribution(),
        tombstone.component(),
        tombstone.packageName(),
        tombstone.version(),
        tombstone.architecture());
    return jdbc.update(
        """
        DELETE FROM alpine_package_tombstone
        WHERE repository_id = ? AND coordinate_hash = ? AND revision = ?
          AND NOT EXISTS (
            SELECT 1 FROM alpine_package_record p
            WHERE p.repository_id = alpine_package_tombstone.repository_id
              AND p.coordinate_hash = alpine_package_tombstone.coordinate_hash)
        """,
        tombstone.repositoryId(),
        coordinateHash,
        tombstone.revision()) == 1;
  }

  @Override
  @Transactional
  public SuiteState ensureSuite(long repositoryId, String distribution, Instant now) {
    requireSegment("distribution", distribution);
    Instant when = now == null ? Instant.now() : now;
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE alpine_suite_state SET distribution_name = distribution_name
        WHERE repository_id = ? AND distribution_name = ?
        """,
        new Object[] {repositoryId, distribution},
        """
        INSERT INTO alpine_suite_state
          (repository_id, distribution_name, desired_revision, published_revision,
           desired_at, signing_key_revision, updated_at)
        VALUES (?, ?, 0, 0, ?, 0, ?)
        """,
        new Object[] {
          repositoryId, distribution, nullableTimestamp(when), nullableTimestamp(when)
        });
    return findSuite(repositoryId, distribution).orElseThrow();
  }

  @Override
  @Transactional
  public long markSuiteDirty(long repositoryId, String distribution, Instant now) {
    Instant when = now == null ? Instant.now() : now;
    ensureSuite(repositoryId, distribution, when);
    long revision = nextRevision(repositoryId);
    advanceSuite(repositoryId, distribution, revision, when);
    return revision;
  }

  @Override
  public Optional<SuiteState> findSuite(long repositoryId, String distribution) {
    return jdbc.query(
        "SELECT * FROM alpine_suite_state WHERE repository_id = ? AND distribution_name = ?",
        this::mapSuite, repositoryId, distribution).stream().findFirst();
  }

  @Override
  public List<SuiteState> listSuites(long repositoryId) {
    return jdbc.query(
        "SELECT * FROM alpine_suite_state WHERE repository_id = ? ORDER BY distribution_name",
        this::mapSuite, repositoryId);
  }

  @Override
  public List<SuiteState> listPendingSuites(
      Instant readyBefore, Instant forceBefore, Instant retryBefore, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 256));
    Instant ready = readyBefore == null ? Instant.now() : readyBefore;
    Instant forced = forceBefore == null ? ready : forceBefore;
    Instant retry = retryBefore == null ? ready : retryBefore;
    return jdbc.query(
        alpine.pendingSuitesSql(),
        this::mapSuite,
        nullableTimestamp(ready),
        nullableTimestamp(forced),
        nullableTimestamp(retry),
        boundedLimit);
  }

  @Override
  @Transactional
  public boolean publishSnapshot(Snapshot snapshot, String leaseOwner, long fencingToken) {
    return publishSnapshot(snapshot, leaseOwner, fencingToken, null);
  }

  private boolean publishSnapshot(
      Snapshot snapshot, String leaseOwner, long fencingToken, Long groupBindingToken) {
    if (snapshot == null || snapshot.manifest() == null || snapshot.manifest().isEmpty()) {
      throw new IllegalArgumentException("Alpine snapshot manifest is required");
    }
    Instant current = Instant.now();
    Instant createdAt = snapshot.createdAt() == null ? current : snapshot.createdAt();
    String leaseKey = publishLeaseKey(snapshot.repositoryId(), snapshot.distribution());
    if (!leaseHeld(leaseKey, leaseOwner, fencingToken, current)) return false;
    LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
    snapshot.manifest().forEach(manifest::put);
    String insertSnapshot = """
        INSERT INTO alpine_snapshot
          (repository_id, distribution_name, revision, signing_key_revision, manifest_json,
           index_sha256, group_binding_token, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
    Object[] snapshotArguments = {
      snapshot.repositoryId(), snapshot.distribution(), snapshot.revision(),
      snapshot.signingKeyRevision(), json.parameter(manifest), snapshot.indexSha256(),
      groupBindingToken, nullableTimestamp(createdAt)
    };
    if (!JdbcInserts.tryUpdate(
        jdbc, insertSnapshot, new ArgumentPreparedStatementSetter(snapshotArguments))) {
      Snapshot existing = findSnapshot(
          snapshot.repositoryId(), snapshot.distribution(), snapshot.revision()).orElseThrow();
      if (!existing.indexSha256().equalsIgnoreCase(snapshot.indexSha256())
          || !existing.manifest().equals(snapshot.manifest())) {
        throw new IllegalStateException(
            "Alpine snapshot revision already exists with different immutable content");
      }
    }
    int updated = jdbc.update(
        """
        UPDATE alpine_suite_state
        SET published_revision = ?, signing_key_revision = ?, last_published_at = ?,
            pending_since = NULL, last_error = NULL, last_error_at = NULL, updated_at = ?
        WHERE repository_id = ? AND distribution_name = ? AND desired_revision = ?
          AND published_revision <= ?
          AND EXISTS (
            SELECT 1 FROM alpine_publish_lease
            WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?)
        """,
        snapshot.revision(), snapshot.signingKeyRevision(), nullableTimestamp(current),
        nullableTimestamp(current), snapshot.repositoryId(), snapshot.distribution(),
        snapshot.revision(), snapshot.revision(), leaseKey, leaseOwner, fencingToken,
        nullableTimestamp(current));
    if (updated != 1) {
      jdbc.update(
          """
          DELETE FROM alpine_snapshot
          WHERE repository_id = ? AND distribution_name = ? AND revision = ?
            AND published_at IS NULL
          """,
          snapshot.repositoryId(), snapshot.distribution(), snapshot.revision());
      return false;
    }
    int markedPublished = jdbc.update(
        """
        UPDATE alpine_snapshot SET published_at = COALESCE(published_at, ?)
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
        """,
        nullableTimestamp(current), snapshot.repositoryId(), snapshot.distribution(),
        snapshot.revision());
    if (markedPublished != 1) {
      throw new IllegalStateException("Alpine published snapshot row is missing");
    }
    return true;
  }

  @Override
  public Optional<Snapshot> findPublishedSnapshot(long repositoryId, String distribution) {
    return jdbc.query(
        """
        SELECT s.* FROM alpine_snapshot s
        JOIN alpine_suite_state p ON p.repository_id = s.repository_id
          AND p.distribution_name = s.distribution_name AND p.published_revision = s.revision
        WHERE s.repository_id = ? AND s.distribution_name = ? AND s.published_at IS NOT NULL
        """, this::mapSnapshot, repositoryId, distribution).stream().findFirst();
  }

  @Override
  public Optional<Snapshot> findSnapshot(long repositoryId, String distribution, long revision) {
    return jdbc.query(
        """
        SELECT * FROM alpine_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
        """, this::mapSnapshot, repositoryId, distribution, revision).stream().findFirst();
  }

  @Override
  public List<Snapshot> listSnapshots(long repositoryId, String distribution, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 100));
    return jdbc.query(
        """
        SELECT * FROM alpine_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND published_at IS NOT NULL
        ORDER BY revision DESC
        LIMIT ?
        """, this::mapSnapshot, repositoryId, distribution, boundedLimit);
  }

  @Override
  public List<Snapshot> listSnapshotCleanupCandidates(
      Instant createdBefore, int minSnapshots, int limit) {
    int retained = Math.max(3, Math.min(minSnapshots, 100));
    int boundedLimit = Math.max(1, Math.min(limit, 256));
    Instant cutoff = createdBefore == null ? Instant.now() : createdBefore;
    return jdbc.query(
        alpine.snapshotCleanupCandidatesSql(),
        this::mapSnapshot,
        nullableTimestamp(cutoff),
        retained - 1,
        boundedLimit);
  }

  @Override
  @Transactional
  public boolean deleteSnapshot(long repositoryId, String distribution, long revision) {
    int deleted = jdbc.update(
        """
        DELETE FROM alpine_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
          AND published_at IS NOT NULL
          AND revision <> (
            SELECT published_revision FROM alpine_suite_state
            WHERE repository_id = ? AND distribution_name = ?)
        """,
        repositoryId,
        distribution,
        revision,
        repositoryId,
        distribution);
    if (deleted == 1) {
      jdbc.update(
          """
          DELETE FROM alpine_group_snapshot_stage
          WHERE group_repository_id = ? AND distribution_name = ? AND snapshot_revision = ?
          """,
          repositoryId,
          distribution,
          revision);
    }
    return deleted == 1;
  }

  @Override
  public void recordBuildFailure(
      long repositoryId, String distribution, long revision, String message, Instant failedAt) {
    Instant now = failedAt == null ? Instant.now() : failedAt;
    jdbc.update(
        """
        UPDATE alpine_suite_state SET last_error = ?, last_error_at = ?, updated_at = ?
        WHERE repository_id = ? AND distribution_name = ? AND desired_revision = ?
        """, truncate(message, 2048), nullableTimestamp(now), nullableTimestamp(now),
        repositoryId, distribution, revision);
  }

  @Override
  @Transactional
  public Optional<Lease> tryAcquireLease(
      String leaseKey, String owner, Instant now, Instant expiresAt) {
    requireSegment("lease key", leaseKey);
    requireSegment("lease owner", owner);
    Instant current = now == null ? Instant.now() : now;
    if (expiresAt == null || !expiresAt.isAfter(current)) {
      throw new IllegalArgumentException("Alpine lease expiry must be in the future");
    }
    int updated = jdbc.update(
        """
        UPDATE alpine_publish_lease
        SET owner = ?, fencing_token = fencing_token + 1, attempt_count = attempt_count + 1,
            expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND (expires_at < ? OR owner = ?)
        """, owner, nullableTimestamp(expiresAt), nullableTimestamp(current), leaseKey,
        nullableTimestamp(current), owner);
    if (updated == 0) {
      String insertLease = """
          INSERT INTO alpine_publish_lease
            (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
          VALUES (?, ?, 1, 1, ?, ?)
          """;
      Object[] arguments = {
        leaseKey, owner, nullableTimestamp(expiresAt), nullableTimestamp(current)
      };
      if (!JdbcInserts.tryUpdate(
          jdbc, insertLease, new ArgumentPreparedStatementSetter(arguments))) {
        return Optional.empty();
      }
    }
    return findLease(leaseKey).filter(row -> row.owner().equals(owner));
  }

  @Override
  public boolean renewLease(
      String leaseKey, String owner, long fencingToken, Instant now, Instant expiresAt) {
    Instant current = now == null ? Instant.now() : now;
    return jdbc.update(
        """
        UPDATE alpine_publish_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?
        """, nullableTimestamp(expiresAt), nullableTimestamp(current), leaseKey, owner,
        fencingToken, nullableTimestamp(current)) == 1;
  }

  @Override
  public void releaseLease(String leaseKey, String owner, long fencingToken) {
    jdbc.update(
        """
        UPDATE alpine_publish_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ?
        """, nullableTimestamp(Instant.now().minusMillis(1)), nullableTimestamp(Instant.now()),
        leaseKey, owner, fencingToken);
  }

  @Override
  public Optional<SigningKey> findActiveSigningKey(long repositoryId) {
    return jdbc.query(
        """
        SELECT * FROM alpine_signing_key
        WHERE repository_id = ? AND active = TRUE ORDER BY revision DESC
        """, this::mapSigningKey, repositoryId).stream().findFirst();
  }

  @Override
  public Optional<SigningKey> findSigningKey(long repositoryId, int revision) {
    return jdbc.query(
        "SELECT * FROM alpine_signing_key WHERE repository_id = ? AND revision = ?",
        this::mapSigningKey, repositoryId, revision).stream().findFirst();
  }

  @Override
  public List<SigningKey> listSigningKeys(long repositoryId, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 16));
    return jdbc.query(
        """
        SELECT * FROM alpine_signing_key
        WHERE repository_id = ? ORDER BY revision DESC LIMIT ?
        """, this::mapSigningKey, repositoryId, boundedLimit);
  }

  @Override
  @Transactional
  public void insertSigningKey(SigningKey key) {
    jdbc.update("UPDATE alpine_signing_key SET active = FALSE WHERE repository_id = ?", key.repositoryId());
    jdbc.update(
        """
        INSERT INTO alpine_signing_key
          (repository_id, revision, key_filename, fingerprint, encrypted_private_key, public_key,
           signature_type, active, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?)
        """, key.repositoryId(), key.revision(), key.keyFilename(), key.fingerprint(),
        key.encryptedPrivateKey(), key.publicKey(), key.signatureType(),
        nullableTimestamp(key.createdAt()));
  }

  @Override
  @Transactional
  public void replacePackageRelations(
      long repositoryId, long packageId, List<PackageRelation> relations) {
    jdbc.update("DELETE FROM alpine_package_relation WHERE package_id = ?", packageId);
    if (relations == null || relations.isEmpty()) return;
    for (PackageRelation relation : relations) {
      if (relation == null || relation.packageId() != packageId) {
        throw new IllegalArgumentException("Alpine relation package identity does not match");
      }
      requireSegment("relation kind", relation.relationKind());
      requireSegment("relation token", relation.token());
      requireSegment("relation expression", relation.expression());
    }
    jdbc.batchUpdate(
        """
        INSERT INTO alpine_package_relation
          (repository_id, package_id, relation_kind, token_value, token_hash, expression_value)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        relations,
        Math.min(256, relations.size()),
        (statement, relation) -> {
          statement.setLong(1, repositoryId);
          statement.setLong(2, packageId);
          statement.setString(3, relation.relationKind());
          statement.setString(4, relation.token());
          statement.setBytes(5, PersistenceHashes.sha256(relation.token()));
          statement.setString(6, relation.expression());
        });
  }

  @Override
  public List<PackageRecord> findPackagesByRelation(
      long repositoryId, String relationKind, String token, Long afterId, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 256));
    long cursor = afterId == null ? 0 : Math.max(0, afterId);
    return jdbc.query(
        """
        SELECT p.* FROM alpine_package_relation relation
        JOIN alpine_package_record p ON p.id = relation.package_id
        WHERE relation.repository_id = ? AND p.repository_id = ?
          AND relation.relation_kind = ?
          AND relation.token_hash = ? AND relation.token_value = ? AND p.id > ?
        ORDER BY p.id LIMIT ?
        """,
        packageMapper,
        repositoryId,
        repositoryId,
        relationKind,
        PersistenceHashes.sha256(token),
        token,
        cursor,
        boundedLimit);
  }

  @Override
  @Transactional
  public void beginGroupSnapshot(
      long groupRepositoryId, String namespace, long snapshotRevision, long fencingToken) {
    if (!leaseHeld(
        publishLeaseKey(groupRepositoryId, namespace), fencingToken, Instant.now())) {
      throw new IllegalStateException("Alpine group publication lease is no longer held");
    }
    discardGroupSnapshot(groupRepositoryId, namespace, snapshotRevision, fencingToken);
    jdbc.update(
        """
        INSERT INTO alpine_group_snapshot_stage
          (group_repository_id, distribution_name, snapshot_revision, binding_token, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        groupRepositoryId,
        namespace,
        snapshotRevision,
        fencingToken,
        nullableTimestamp(Instant.now()));
  }

  @Override
  @Transactional
  public void appendGroupBindings(long fencingToken, List<GroupBinding> bindings) {
    if (bindings == null || bindings.isEmpty()) return;
    GroupBinding first = bindings.getFirst();
    for (GroupBinding binding : bindings) {
      requireGroupBinding(binding);
      if (binding.groupRepositoryId() != first.groupRepositoryId()
          || binding.snapshotRevision() != first.snapshotRevision()
          || !binding.namespace().equals(first.namespace())) {
        throw new IllegalArgumentException(
            "Alpine group binding batch must belong to one staged snapshot");
      }
    }
    if (!leaseHeld(
        publishLeaseKey(first.groupRepositoryId(), first.namespace()),
        fencingToken,
        Instant.now())) {
      throw new IllegalStateException("Alpine group publication lease is no longer held");
    }
    jdbc.batchUpdate(
        """
        INSERT INTO alpine_group_binding
          (group_repository_id, distribution_name, snapshot_revision, binding_token,
           path_value, path_hash, member_repository_id, member_snapshot_revision, member_path,
           package_identity, sha256, size_bytes, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        bindings,
        Math.min(512, bindings.size()),
        (statement, binding) -> {
          statement.setLong(1, binding.groupRepositoryId());
          statement.setString(2, binding.namespace());
          statement.setLong(3, binding.snapshotRevision());
          statement.setLong(4, fencingToken);
          statement.setString(5, binding.path());
          statement.setBytes(6, PersistenceHashes.sha256(binding.path()));
          statement.setLong(7, binding.memberRepositoryId());
          statement.setLong(8, binding.memberSnapshotRevision());
          statement.setString(9, binding.memberPath());
          statement.setString(10, binding.identity());
          statement.setString(11, binding.sha256());
          statement.setLong(12, binding.size());
          statement.setTimestamp(
              13,
              nullableTimestamp(binding.createdAt() == null ? Instant.now() : binding.createdAt()));
        });
  }

  @Override
  @Transactional
  public void discardGroupSnapshot(
      long groupRepositoryId, String namespace, long snapshotRevision, long fencingToken) {
    jdbc.update(
        """
        DELETE FROM alpine_group_snapshot_stage
        WHERE group_repository_id = ? AND distribution_name = ? AND snapshot_revision = ?
          AND binding_token = ?
          AND NOT EXISTS (
            SELECT 1 FROM alpine_snapshot
            WHERE repository_id = alpine_group_snapshot_stage.group_repository_id
              AND distribution_name = alpine_group_snapshot_stage.distribution_name
              AND revision = alpine_group_snapshot_stage.snapshot_revision
              AND group_binding_token = alpine_group_snapshot_stage.binding_token)
        """,
        groupRepositoryId, namespace, snapshotRevision, fencingToken);
  }

  @Override
  @Transactional
  public boolean publishGroupSnapshot(
      Snapshot snapshot, String leaseOwner, long fencingToken) {
    if (snapshot == null) throw new IllegalArgumentException("Alpine group snapshot is required");
    if (!lockGroupSnapshotStage(snapshot, fencingToken)) return false;
    boolean published = publishSnapshot(snapshot, leaseOwner, fencingToken, fencingToken);
    boolean tokenPublished = published && groupSnapshotUsesToken(snapshot, fencingToken);
    if (!tokenPublished) {
      discardGroupSnapshot(
          snapshot.repositoryId(), snapshot.distribution(), snapshot.revision(), fencingToken);
    }
    return tokenPublished;
  }

  private boolean lockGroupSnapshotStage(Snapshot snapshot, long fencingToken) {
    return !jdbc.queryForList(
        """
        SELECT binding_token FROM alpine_group_snapshot_stage
        WHERE group_repository_id = ? AND distribution_name = ? AND snapshot_revision = ?
          AND binding_token = ?
        FOR UPDATE
        """,
        Long.class,
        snapshot.repositoryId(),
        snapshot.distribution(),
        snapshot.revision(),
        fencingToken).isEmpty();
  }

  private boolean groupSnapshotUsesToken(Snapshot snapshot, long fencingToken) {
    Integer count = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM alpine_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
          AND group_binding_token = ? AND published_at IS NOT NULL
        """,
        Integer.class,
        snapshot.repositoryId(),
        snapshot.distribution(),
        snapshot.revision(),
        fencingToken);
    return count != null && count == 1;
  }

  @Override
  public Optional<GroupBinding> findGroupBinding(
      long groupRepositoryId, String namespace, long snapshotRevision, String path) {
    Optional<Long> bindingToken = publishedGroupBindingToken(
        groupRepositoryId, namespace, snapshotRevision);
    if (bindingToken.isEmpty()) return Optional.empty();
    String sql = """
        SELECT * FROM %s
        WHERE group_repository_id = ? AND distribution_name = ?
          AND snapshot_revision = ? AND binding_token = ?
          AND path_hash = ? AND path_value = ?
        """.formatted(groupBindingPathTable);
    return jdbc.query(
        sql,
        this::mapGroupBinding,
        groupRepositoryId,
        namespace,
        snapshotRevision,
        bindingToken.get(),
        PersistenceHashes.sha256(path),
        path).stream().findFirst();
  }

  @Override
  public List<GroupBinding> listGroupBindings(
      long groupRepositoryId,
      String namespace,
      long snapshotRevision,
      Long afterId,
      int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 2048));
    Optional<Long> bindingToken = publishedGroupBindingToken(
        groupRepositoryId, namespace, snapshotRevision);
    if (bindingToken.isEmpty()) return List.of();
    String sql = """
        SELECT * FROM %s
        WHERE group_repository_id = ? AND distribution_name = ?
          AND snapshot_revision = ? AND binding_token = ? AND id > ?
        ORDER BY id LIMIT ?
        """.formatted(groupBindingPageTable);
    return jdbc.query(
        sql,
        this::mapGroupBinding,
        groupRepositoryId,
        namespace,
        snapshotRevision,
        bindingToken.get(),
        afterId == null ? 0 : Math.max(0, afterId),
        boundedLimit);
  }

  private Optional<Long> publishedGroupBindingToken(
      long groupRepositoryId, String namespace, long snapshotRevision) {
    return jdbc.query(
        """
        SELECT group_binding_token FROM alpine_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
          AND group_binding_token IS NOT NULL AND published_at IS NOT NULL
        """,
        (rs, row) -> rs.getLong("group_binding_token"),
        groupRepositoryId,
        namespace,
        snapshotRevision).stream().findFirst();
  }

  @Override
  @Transactional
  public int deleteOrphanGroupBindings(Instant createdBefore, int limit) {
    Instant cutoff = createdBefore == null ? Instant.now() : createdBefore;
    int boundedLimit = Math.max(1, Math.min(limit, 256));
    List<GroupSnapshotStage> stages = jdbc.query(
        """
        SELECT stage.* FROM alpine_group_snapshot_stage stage
        WHERE stage.created_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM alpine_snapshot snapshot
            WHERE snapshot.repository_id = stage.group_repository_id
              AND snapshot.distribution_name = stage.distribution_name
              AND snapshot.revision = stage.snapshot_revision
              AND snapshot.group_binding_token = stage.binding_token)
        ORDER BY stage.created_at, stage.group_repository_id,
          stage.distribution_name, stage.snapshot_revision, stage.binding_token
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """,
        (rs, row) -> new GroupSnapshotStage(
            rs.getLong("group_repository_id"),
            rs.getString("distribution_name"),
            rs.getLong("snapshot_revision"),
            rs.getLong("binding_token")),
        nullableTimestamp(cutoff),
        boundedLimit);
    if (stages.isEmpty()) return 0;
    int[][] counts = jdbc.batchUpdate(
        """
        DELETE FROM alpine_group_snapshot_stage
        WHERE group_repository_id = ? AND distribution_name = ? AND snapshot_revision = ?
          AND binding_token = ? AND created_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM alpine_snapshot snapshot
            WHERE snapshot.repository_id = alpine_group_snapshot_stage.group_repository_id
              AND snapshot.distribution_name = alpine_group_snapshot_stage.distribution_name
              AND snapshot.revision = alpine_group_snapshot_stage.snapshot_revision
              AND snapshot.group_binding_token = alpine_group_snapshot_stage.binding_token)
        """,
        stages,
        Math.min(256, stages.size()),
        (statement, stage) -> {
          statement.setLong(1, stage.groupRepositoryId());
          statement.setString(2, stage.distribution());
          statement.setLong(3, stage.snapshotRevision());
          statement.setLong(4, stage.bindingToken());
          statement.setTimestamp(5, nullableTimestamp(cutoff));
        });
    int deleted = 0;
    for (int[] batch : counts) {
      for (int count : batch) {
        if (count > 0) {
          deleted += count;
        } else if (count == Statement.SUCCESS_NO_INFO) {
          deleted++;
        }
      }
    }
    return deleted;
  }

  @Override
  public void observeProxyDistribution(
      long repositoryId,
      String distribution,
      String releaseIdentity,
      Map<String, ProxyIndex> indices,
      boolean signatureVerified,
      Instant observedAt) {
    Instant now = observedAt == null ? Instant.now() : observedAt;
    LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
    if (indices != null) {
      indices.forEach((path, index) -> manifest.put(
          path, Map.of("sha256", index.sha256(), "size", index.size())));
    }
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE alpine_proxy_distribution
        SET release_identity = ?, release_manifest_json = ?, signature_verified = ?,
            observed_at = ?, updated_at = ?
        WHERE repository_id = ? AND distribution_name = ?
        """,
        new Object[] {
          releaseIdentity, json.parameter(manifest), signatureVerified, nullableTimestamp(now),
          nullableTimestamp(now), repositoryId, distribution
        },
        """
        INSERT INTO alpine_proxy_distribution
          (repository_id, distribution_name, release_identity, release_manifest_json,
           signature_verified, observed_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[] {
          repositoryId, distribution, releaseIdentity, json.parameter(manifest), signatureVerified,
          nullableTimestamp(now), nullableTimestamp(now)
        });
  }

  @Override
  public Optional<ProxyDistribution> findProxyDistribution(
      long repositoryId, String distribution) {
    return jdbc.query(
        """
        SELECT * FROM alpine_proxy_distribution
        WHERE repository_id = ? AND distribution_name = ?
        """, this::mapProxyDistribution, repositoryId, distribution).stream().findFirst();
  }

  @Override
  public List<ProxyDistribution> listProxyDistributions(long repositoryId) {
    return jdbc.query(
        """
        SELECT * FROM alpine_proxy_distribution
        WHERE repository_id = ? ORDER BY distribution_name
        """, this::mapProxyDistribution, repositoryId);
  }

  @Override
  @Transactional
  public void deleteRepositoryState(long repositoryId) {
    jdbc.update(
        "DELETE FROM alpine_group_binding WHERE group_repository_id = ?", repositoryId);
    jdbc.update(
        "DELETE FROM alpine_group_binding WHERE member_repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM alpine_package_tombstone WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM alpine_package_record WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM alpine_snapshot WHERE repository_id = ?", repositoryId);
    jdbc.update(
        "DELETE FROM alpine_group_snapshot_stage WHERE group_repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM alpine_suite_state WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM alpine_signing_key WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM alpine_proxy_distribution WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM alpine_publish_lease WHERE lease_key LIKE ?", "alpine:publish:" + repositoryId + ":%");
    jdbc.update("DELETE FROM alpine_publish_lease WHERE lease_key LIKE ?", "alpine:coordinate:" + repositoryId + ":%");
    jdbc.update("DELETE FROM alpine_publish_lease WHERE lease_key = ?", "alpine:key:" + repositoryId);
    jdbc.update("DELETE FROM cache_version WHERE name = ?", revisionKey(repositoryId));
  }

  public static String publishLeaseKey(long repositoryId, String distribution) {
    return "alpine:publish:" + repositoryId + ":" + distribution;
  }

  private long nextRevision(long repositoryId) {
    return coordination.bumpCacheVersion(jdbc, revisionKey(repositoryId));
  }

  private void advanceSuite(long repositoryId, String distribution, long revision, Instant now) {
    jdbc.update(
        """
        UPDATE alpine_suite_state
        SET pending_since = CASE
              WHEN desired_revision <= published_revision THEN ?
              ELSE COALESCE(pending_since, ?)
            END,
            desired_at = CASE WHEN desired_revision < ? THEN ? ELSE desired_at END,
            desired_revision = CASE WHEN desired_revision < ? THEN ? ELSE desired_revision END,
            updated_at = ?
        WHERE repository_id = ? AND distribution_name = ?
        """,
        nullableTimestamp(now), nullableTimestamp(now),
        revision, nullableTimestamp(now), revision, revision, nullableTimestamp(now),
        repositoryId, distribution);
  }

  private Optional<Lease> findLease(String leaseKey) {
    return jdbc.query(
        "SELECT * FROM alpine_publish_lease WHERE lease_key = ?",
        (rs, row) -> new Lease(
            rs.getString("lease_key"), rs.getString("owner"), rs.getLong("fencing_token"),
            rs.getLong("attempt_count"), nullableInstant(rs, "expires_at"),
            nullableInstant(rs, "updated_at")), leaseKey).stream().findFirst();
  }

  private boolean leaseHeld(String leaseKey, String owner, long token, Instant now) {
    Integer count = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM alpine_publish_lease
        WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?
        """, Integer.class, leaseKey, owner, token, nullableTimestamp(now));
    return count != null && count == 1;
  }

  private boolean leaseHeld(String leaseKey, long token, Instant now) {
    Integer count = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM alpine_publish_lease
        WHERE lease_key = ? AND fencing_token = ? AND expires_at >= ?
        """, Integer.class, leaseKey, token, nullableTimestamp(now));
    return count != null && count == 1;
  }

  private PackageRecord mapPackage(ResultSet rs, int row) throws SQLException {
    return new PackageRecord(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getString("distribution_name"),
        rs.getString("component_name"), rs.getString("architecture"),
        rs.getString("package_name"), rs.getString("package_version"),
        rs.getString("package_architecture"), rs.getString("filename"), rs.getString("asset_path"),
        json.read(rs.getString("control_fields")), rs.getString("package_identity"),
        rs.getString("data_sha256"),
        rs.getString("sha256"), rs.getLong("size_bytes"), nullableLong(rs, "asset_id"),
        nullableLong(rs, "component_id"), rs.getString("source_kind"), rs.getLong("revision"),
        nullableInstant(rs, "indexed_at"), nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
  }

  private SuiteState mapSuite(ResultSet rs, int row) throws SQLException {
    return new SuiteState(
        rs.getLong("repository_id"), rs.getString("distribution_name"),
        rs.getLong("desired_revision"), nullableInstant(rs, "desired_at"),
        rs.getLong("published_revision"),
        rs.getInt("signing_key_revision"), nullableInstant(rs, "last_published_at"),
        rs.getString("last_error"), nullableInstant(rs, "last_error_at"),
        nullableInstant(rs, "updated_at"));
  }

  private PackageTombstone mapPackageTombstone(ResultSet rs, int row) throws SQLException {
    return new PackageTombstone(
        rs.getLong("repository_id"),
        rs.getString("distribution_name"),
        rs.getString("component_name"),
        rs.getString("architecture"),
        rs.getString("package_name"),
        rs.getString("package_version"),
        rs.getString("asset_path"),
        rs.getString("reason"),
        rs.getLong("revision"),
        nullableInstant(rs, "deleted_at"));
  }

  private Snapshot mapSnapshot(ResultSet rs, int row) throws SQLException {
    LinkedHashMap<String, String> manifest = new LinkedHashMap<>();
    json.read(rs.getString("manifest_json")).forEach((key, value) -> {
      if (value != null) manifest.put(key, value.toString());
    });
    return new Snapshot(
        rs.getLong("repository_id"), rs.getString("distribution_name"),
        rs.getLong("revision"), rs.getInt("signing_key_revision"), Map.copyOf(manifest),
        rs.getString("index_sha256"), nullableInstant(rs, "created_at"));
  }

  private SigningKey mapSigningKey(ResultSet rs, int row) throws SQLException {
    return new SigningKey(
        rs.getLong("repository_id"), rs.getInt("revision"), rs.getString("key_filename"),
        rs.getString("fingerprint"), rs.getString("encrypted_private_key"),
        rs.getString("public_key"), rs.getString("signature_type"), rs.getBoolean("active"),
        nullableInstant(rs, "created_at"));
  }

  private ProxyDistribution mapProxyDistribution(ResultSet rs, int row) throws SQLException {
    LinkedHashMap<String, ProxyIndex> indices = new LinkedHashMap<>();
    json.read(rs.getString("release_manifest_json")).forEach((path, raw) -> {
      if (!(raw instanceof Map<?, ?> values)) return;
      Object digest = values.get("sha256");
      Object size = values.get("size");
      if (digest == null || size == null) return;
      long bytes = size instanceof Number number
          ? number.longValue() : Long.parseLong(size.toString());
      indices.put(path, new ProxyIndex(digest.toString(), bytes));
    });
    return new ProxyDistribution(
        rs.getLong("repository_id"), rs.getString("distribution_name"),
        rs.getString("release_identity"), Map.copyOf(indices),
        rs.getBoolean("signature_verified"), nullableInstant(rs, "observed_at"),
        nullableInstant(rs, "updated_at"));
  }

  private GroupBinding mapGroupBinding(ResultSet rs, int row) throws SQLException {
    return new GroupBinding(
        rs.getLong("id"),
        rs.getLong("group_repository_id"),
        rs.getString("distribution_name"),
        rs.getLong("snapshot_revision"),
        rs.getString("path_value"),
        rs.getLong("member_repository_id"),
        rs.getLong("member_snapshot_revision"),
        rs.getString("member_path"),
        rs.getString("package_identity"),
        rs.getString("sha256"),
        rs.getLong("size_bytes"),
        nullableInstant(rs, "created_at"));
  }

  private record GroupSnapshotStage(
      long groupRepositoryId,
      String distribution,
      long snapshotRevision,
      long bindingToken) { }

  private static void requireGroupBinding(GroupBinding binding) {
    if (binding == null
        || binding.groupRepositoryId() <= 0
        || binding.snapshotRevision() <= 0
        || binding.namespace() == null
        || binding.memberRepositoryId() <= 0
        || binding.size() < 0) {
      throw new IllegalArgumentException("Invalid Alpine group source binding");
    }
    requireSegment("group package path", binding.path());
    requireSegment("group member path", binding.memberPath());
    requireSegment("group package identity", binding.identity());
    requireSegment("group package SHA-256", binding.sha256());
  }

  private static byte[] coordinateHash(PackageRecord record) {
    return PersistenceHashes.sha256(
        record.distribution(), record.component(), record.packageName(), record.version(),
        record.architecture());
  }

  private static void requirePackage(PackageRecord record) {
    if (record == null) throw new IllegalArgumentException("Alpine package record is required");
    requireSegment("distribution", record.distribution());
    requireSegment("component", record.component());
    requireSegment("architecture", record.architecture());
    requireSegment("package", record.packageName());
    requireSegment("version", record.version());
    requireSegment("asset path", record.path());
    if (record.size() < 0 || record.sha256() == null || record.sha256().length() != 64) {
      throw new IllegalArgumentException("Alpine package checksum and size are required");
    }
    if (!SOURCE_HOSTED.equals(record.sourceKind()) && !SOURCE_PROXY.equals(record.sourceKind())) {
      throw new IllegalArgumentException("Unsupported Alpine package source: " + record.sourceKind());
    }
  }

  private static void requireSegment(String label, String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Alpine " + label + " is required");
    }
  }

  private static String revisionKey(long repositoryId) {
    return REVISION_PREFIX + repositoryId;
  }

  private static String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }
}
