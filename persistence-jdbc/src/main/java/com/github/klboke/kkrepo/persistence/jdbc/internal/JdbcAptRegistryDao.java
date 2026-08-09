package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Portable MySQL/PostgreSQL implementation of APT durable publication state. */
@Repository
public class JdbcAptRegistryDao implements AptRegistryDao {
  private static final String REVISION_PREFIX = "apt:repository:";

  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final CoordinationPersistenceDialect coordination;
  private final int streamingFetchSize;
  private final RowMapper<PackageRecord> packageMapper = this::mapPackage;

  public JdbcAptRegistryDao(
      JdbcTemplate jdbc, JsonColumns json, DatabaseDialect databaseDialect) {
    this.jdbc = jdbc;
    this.json = json;
    this.coordination = databaseDialect.coordination();
    this.streamingFetchSize = databaseDialect.streamingFetchSize();
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
            UPDATE apt_package_record
            SET distribution_name = ?, component_name = ?, architecture = ?, package_name = ?,
                package_version = ?, source_package = ?, filename = ?, asset_path = ?,
                control_fields = ?, md5 = ?, sha1 = ?, sha256 = ?, size_bytes = ?, asset_id = ?,
                component_id = ?, source_kind = ?, revision = ?, indexed_at = ?, updated_at = ?
            WHERE repository_id = ? AND coordinate_hash = ?
            """,
        new Object[] {
          stored.distribution(), stored.component(), stored.architecture(), stored.packageName(),
          stored.version(), stored.sourcePackage(), stored.filename(), stored.path(),
          json.parameter(stored.controlFields()), stored.md5(), stored.sha1(), stored.sha256(),
          stored.size(), stored.assetId(), stored.componentId(), stored.sourceKind(), revision,
          nullableTimestamp(stored.indexedAt()), nullableTimestamp(now), stored.repositoryId(),
          coordinateHash
        },
        """
            INSERT INTO apt_package_record
              (repository_id, coordinate_hash, distribution_name, component_name, architecture,
               package_name, package_version, source_package, filename, asset_path, control_fields,
               md5, sha1, sha256, size_bytes, asset_id, component_id, source_kind, revision,
               indexed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          stored.repositoryId(), coordinateHash, stored.distribution(), stored.component(),
          stored.architecture(), stored.packageName(), stored.version(), stored.sourcePackage(),
          stored.filename(), stored.path(), json.parameter(stored.controlFields()), stored.md5(),
          stored.sha1(), stored.sha256(), stored.size(), stored.assetId(), stored.componentId(),
          stored.sourceKind(), revision, nullableTimestamp(stored.indexedAt()),
          nullableTimestamp(stored.createdAt()), nullableTimestamp(now)
        });
    jdbc.update(
        "DELETE FROM apt_package_tombstone WHERE repository_id = ? AND coordinate_hash = ?",
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
        "SELECT * FROM apt_package_record WHERE repository_id = ? AND coordinate_hash = ?",
        packageMapper, repositoryId, hash).stream().findFirst();
  }

  @Override
  public Optional<PackageRecord> findPackageByPath(long repositoryId, String path) {
    return jdbc.query(
        "SELECT * FROM apt_package_record WHERE repository_id = ? AND asset_path = ?",
        packageMapper, repositoryId, path).stream().findFirst();
  }

  @Override
  public List<PackageRecord> listPackages(
      long repositoryId, String distribution, String component, String architecture) {
    return jdbc.query(
        """
        SELECT * FROM apt_package_record
        WHERE repository_id = ? AND distribution_name = ? AND component_name = ?
          AND architecture = ?
        ORDER BY package_name, package_version, architecture, filename
        """,
        packageMapper, repositoryId, distribution, component, architecture);
  }

  @Override
  @Transactional(readOnly = true)
  public void visitPackages(
      long repositoryId,
      String distribution,
      String component,
      String architecture,
      Consumer<PackageRecord> visitor) {
    if (visitor == null) return;
    int[] row = {0};
    jdbc.query(
        connection -> {
          PreparedStatement statement = connection.prepareStatement(
              """
              SELECT * FROM apt_package_record
              WHERE repository_id = ? AND distribution_name = ? AND component_name = ?
                AND architecture = ?
              ORDER BY package_name, id
              """,
              ResultSet.TYPE_FORWARD_ONLY,
              ResultSet.CONCUR_READ_ONLY);
          statement.setFetchSize(streamingFetchSize);
          statement.setLong(1, repositoryId);
          statement.setString(2, distribution);
          statement.setString(3, component);
          statement.setString(4, architecture);
          return statement;
        },
        (RowCallbackHandler) result -> visitor.accept(mapPackage(result, row[0]++)));
  }

  @Override
  public List<PackageRecord> listPackages(long repositoryId, String distribution) {
    return jdbc.query(
        """
        SELECT * FROM apt_package_record
        WHERE repository_id = ? AND distribution_name = ?
        ORDER BY component_name, architecture, package_name, package_version, filename
        """,
        packageMapper, repositoryId, distribution);
  }

  @Override
  public List<String> listDistributions(long repositoryId) {
    return jdbc.queryForList(
        "SELECT distribution_name FROM apt_suite_state WHERE repository_id = ? ORDER BY distribution_name",
        String.class, repositoryId);
  }

  @Override
  public List<String> listComponents(long repositoryId, String distribution) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT component_name FROM apt_package_record
        WHERE repository_id = ? AND distribution_name = ? ORDER BY component_name
        """, String.class, repositoryId, distribution);
  }

  @Override
  public List<String> listArchitectures(
      long repositoryId, String distribution, String component) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT architecture FROM apt_package_record
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
        "DELETE FROM apt_package_record WHERE repository_id = ? AND coordinate_hash = ?",
        repositoryId, hash);
    if (deleted == 0) return Optional.empty();
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE apt_package_tombstone SET reason = ?, revision = ?, deleted_at = ?
        WHERE repository_id = ? AND coordinate_hash = ?
        """,
        new Object[] {reason, revision, nullableTimestamp(now), repositoryId, hash},
        """
        INSERT INTO apt_package_tombstone
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
        SELECT t.* FROM apt_package_tombstone t
        WHERE t.deleted_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM apt_snapshot s
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
        DELETE FROM apt_package_tombstone
        WHERE repository_id = ? AND coordinate_hash = ? AND revision = ?
          AND NOT EXISTS (
            SELECT 1 FROM apt_package_record p
            WHERE p.repository_id = apt_package_tombstone.repository_id
              AND p.coordinate_hash = apt_package_tombstone.coordinate_hash)
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
        UPDATE apt_suite_state SET distribution_name = distribution_name
        WHERE repository_id = ? AND distribution_name = ?
        """,
        new Object[] {repositoryId, distribution},
        """
        INSERT INTO apt_suite_state
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
        "SELECT * FROM apt_suite_state WHERE repository_id = ? AND distribution_name = ?",
        this::mapSuite, repositoryId, distribution).stream().findFirst();
  }

  @Override
  public List<SuiteState> listSuites(long repositoryId) {
    return jdbc.query(
        "SELECT * FROM apt_suite_state WHERE repository_id = ? ORDER BY distribution_name",
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
        """
        SELECT suite.* FROM apt_suite_state suite
        JOIN repository r ON r.id = suite.repository_id
        WHERE suite.desired_revision > suite.published_revision
          AND (suite.desired_at <= ? OR COALESCE(suite.pending_since, suite.desired_at) <= ?)
          AND (suite.last_error_at IS NULL OR suite.last_error_at <= ?)
          AND r.online = true AND r.format = 'apt' AND r.type = 'hosted'
        ORDER BY suite.desired_at, suite.repository_id, suite.distribution_name
        LIMIT ?
        """,
        this::mapSuite,
        nullableTimestamp(ready),
        nullableTimestamp(forced),
        nullableTimestamp(retry),
        boundedLimit);
  }

  @Override
  @Transactional
  public boolean publishSnapshot(Snapshot snapshot, String leaseOwner, long fencingToken) {
    if (snapshot == null || snapshot.manifest() == null || snapshot.manifest().isEmpty()) {
      throw new IllegalArgumentException("APT snapshot manifest is required");
    }
    Instant current = Instant.now();
    Instant createdAt = snapshot.createdAt() == null ? current : snapshot.createdAt();
    String leaseKey = publishLeaseKey(snapshot.repositoryId(), snapshot.distribution());
    if (!leaseHeld(leaseKey, leaseOwner, fencingToken, current)) return false;
    LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
    snapshot.manifest().forEach(manifest::put);
    String insertSnapshot = """
        INSERT INTO apt_snapshot
          (repository_id, distribution_name, revision, signing_key_revision, manifest_json,
           release_sha256, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    Object[] snapshotArguments = {
      snapshot.repositoryId(), snapshot.distribution(), snapshot.revision(),
      snapshot.signingKeyRevision(), json.parameter(manifest), snapshot.releaseSha256(),
      nullableTimestamp(createdAt)
    };
    if (!JdbcInserts.tryUpdate(
        jdbc, insertSnapshot, new ArgumentPreparedStatementSetter(snapshotArguments))) {
      Snapshot existing = findSnapshot(
          snapshot.repositoryId(), snapshot.distribution(), snapshot.revision()).orElseThrow();
      if (!existing.releaseSha256().equalsIgnoreCase(snapshot.releaseSha256())
          || !existing.manifest().equals(snapshot.manifest())) {
        throw new IllegalStateException(
            "APT snapshot revision already exists with different immutable content");
      }
    }
    int updated = jdbc.update(
        """
        UPDATE apt_suite_state
        SET published_revision = ?, signing_key_revision = ?, last_published_at = ?,
            pending_since = NULL, last_error = NULL, last_error_at = NULL, updated_at = ?
        WHERE repository_id = ? AND distribution_name = ? AND desired_revision = ?
          AND published_revision <= ?
          AND EXISTS (
            SELECT 1 FROM apt_publish_lease
            WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?)
        """,
        snapshot.revision(), snapshot.signingKeyRevision(), nullableTimestamp(current),
        nullableTimestamp(current), snapshot.repositoryId(), snapshot.distribution(),
        snapshot.revision(), snapshot.revision(), leaseKey, leaseOwner, fencingToken,
        nullableTimestamp(current));
    if (updated != 1) {
      jdbc.update(
          """
          DELETE FROM apt_snapshot
          WHERE repository_id = ? AND distribution_name = ? AND revision = ?
            AND published_at IS NULL
          """,
          snapshot.repositoryId(), snapshot.distribution(), snapshot.revision());
      return false;
    }
    int markedPublished = jdbc.update(
        """
        UPDATE apt_snapshot SET published_at = COALESCE(published_at, ?)
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
        """,
        nullableTimestamp(current), snapshot.repositoryId(), snapshot.distribution(),
        snapshot.revision());
    if (markedPublished != 1) {
      throw new IllegalStateException("APT published snapshot row is missing");
    }
    return true;
  }

  @Override
  public Optional<Snapshot> findPublishedSnapshot(long repositoryId, String distribution) {
    return jdbc.query(
        """
        SELECT s.* FROM apt_snapshot s
        JOIN apt_suite_state p ON p.repository_id = s.repository_id
          AND p.distribution_name = s.distribution_name AND p.published_revision = s.revision
        WHERE s.repository_id = ? AND s.distribution_name = ? AND s.published_at IS NOT NULL
        """, this::mapSnapshot, repositoryId, distribution).stream().findFirst();
  }

  @Override
  public Optional<Snapshot> findSnapshot(long repositoryId, String distribution, long revision) {
    return jdbc.query(
        """
        SELECT * FROM apt_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
        """, this::mapSnapshot, repositoryId, distribution, revision).stream().findFirst();
  }

  @Override
  public List<Snapshot> listSnapshots(long repositoryId, String distribution, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 100));
    return jdbc.query(
        """
        SELECT * FROM apt_snapshot
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
        """
        SELECT candidate.* FROM (
          SELECT s.*,
            ROW_NUMBER() OVER (
              PARTITION BY s.repository_id, s.distribution_name
              ORDER BY s.revision DESC) AS retention_rank
          FROM apt_snapshot s
          WHERE s.published_at IS NOT NULL
        ) candidate
        JOIN apt_suite_state suite
          ON suite.repository_id = candidate.repository_id
          AND suite.distribution_name = candidate.distribution_name
        WHERE candidate.retention_rank > ? AND candidate.created_at < ?
          AND candidate.revision <> suite.published_revision
        ORDER BY candidate.created_at, candidate.repository_id,
          candidate.distribution_name, candidate.revision
        LIMIT ?
        """,
        this::mapSnapshot,
        retained,
        nullableTimestamp(cutoff),
        boundedLimit);
  }

  @Override
  @Transactional
  public boolean deleteSnapshot(long repositoryId, String distribution, long revision) {
    return jdbc.update(
        """
        DELETE FROM apt_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
          AND published_at IS NOT NULL
          AND revision <> (
            SELECT published_revision FROM apt_suite_state
            WHERE repository_id = ? AND distribution_name = ?)
        """,
        repositoryId,
        distribution,
        revision,
        repositoryId,
        distribution) == 1;
  }

  @Override
  public void recordBuildFailure(
      long repositoryId, String distribution, long revision, String message, Instant failedAt) {
    Instant now = failedAt == null ? Instant.now() : failedAt;
    jdbc.update(
        """
        UPDATE apt_suite_state SET last_error = ?, last_error_at = ?, updated_at = ?
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
      throw new IllegalArgumentException("APT lease expiry must be in the future");
    }
    int updated = jdbc.update(
        """
        UPDATE apt_publish_lease
        SET owner = ?, fencing_token = fencing_token + 1, attempt_count = attempt_count + 1,
            expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND (expires_at < ? OR owner = ?)
        """, owner, nullableTimestamp(expiresAt), nullableTimestamp(current), leaseKey,
        nullableTimestamp(current), owner);
    if (updated == 0) {
      String insertLease = """
          INSERT INTO apt_publish_lease
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
        UPDATE apt_publish_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?
        """, nullableTimestamp(expiresAt), nullableTimestamp(current), leaseKey, owner,
        fencingToken, nullableTimestamp(current)) == 1;
  }

  @Override
  public void releaseLease(String leaseKey, String owner, long fencingToken) {
    jdbc.update(
        """
        UPDATE apt_publish_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ?
        """, nullableTimestamp(Instant.now().minusMillis(1)), nullableTimestamp(Instant.now()),
        leaseKey, owner, fencingToken);
  }

  @Override
  public Optional<SigningKey> findActiveSigningKey(long repositoryId) {
    return jdbc.query(
        """
        SELECT * FROM apt_signing_key
        WHERE repository_id = ? AND active = TRUE ORDER BY revision DESC
        """, this::mapSigningKey, repositoryId).stream().findFirst();
  }

  @Override
  public Optional<SigningKey> findSigningKey(long repositoryId, int revision) {
    return jdbc.query(
        "SELECT * FROM apt_signing_key WHERE repository_id = ? AND revision = ?",
        this::mapSigningKey, repositoryId, revision).stream().findFirst();
  }

  @Override
  public List<SigningKey> listSigningKeys(long repositoryId, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 16));
    return jdbc.query(
        """
        SELECT * FROM apt_signing_key
        WHERE repository_id = ? ORDER BY revision DESC LIMIT ?
        """, this::mapSigningKey, repositoryId, boundedLimit);
  }

  @Override
  @Transactional
  public void insertSigningKey(SigningKey key) {
    jdbc.update("UPDATE apt_signing_key SET active = FALSE WHERE repository_id = ?", key.repositoryId());
    jdbc.update(
        """
        INSERT INTO apt_signing_key
          (repository_id, revision, key_id, fingerprint, encrypted_private_key, public_key,
           active, created_at)
        VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)
        """, key.repositoryId(), key.revision(), key.keyId(), key.fingerprint(),
        key.encryptedPrivateKey(), key.publicKey(), nullableTimestamp(key.createdAt()));
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
        UPDATE apt_proxy_distribution
        SET release_identity = ?, release_manifest_json = ?, signature_verified = ?,
            observed_at = ?, updated_at = ?
        WHERE repository_id = ? AND distribution_name = ?
        """,
        new Object[] {
          releaseIdentity, json.parameter(manifest), signatureVerified, nullableTimestamp(now),
          nullableTimestamp(now), repositoryId, distribution
        },
        """
        INSERT INTO apt_proxy_distribution
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
        SELECT * FROM apt_proxy_distribution
        WHERE repository_id = ? AND distribution_name = ?
        """, this::mapProxyDistribution, repositoryId, distribution).stream().findFirst();
  }

  @Override
  public List<ProxyDistribution> listProxyDistributions(long repositoryId) {
    return jdbc.query(
        """
        SELECT * FROM apt_proxy_distribution
        WHERE repository_id = ? ORDER BY distribution_name
        """, this::mapProxyDistribution, repositoryId);
  }

  @Override
  @Transactional
  public void deleteRepositoryState(long repositoryId) {
    jdbc.update("DELETE FROM apt_package_tombstone WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM apt_package_record WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM apt_snapshot WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM apt_suite_state WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM apt_signing_key WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM apt_proxy_distribution WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM apt_publish_lease WHERE lease_key LIKE ?", "apt:publish:" + repositoryId + ":%");
    jdbc.update("DELETE FROM apt_publish_lease WHERE lease_key LIKE ?", "apt:coordinate:" + repositoryId + ":%");
    jdbc.update("DELETE FROM apt_publish_lease WHERE lease_key = ?", "apt:key:" + repositoryId);
    jdbc.update("DELETE FROM cache_version WHERE name = ?", revisionKey(repositoryId));
  }

  public static String publishLeaseKey(long repositoryId, String distribution) {
    return "apt:publish:" + repositoryId + ":" + distribution;
  }

  private long nextRevision(long repositoryId) {
    return coordination.bumpCacheVersion(jdbc, revisionKey(repositoryId));
  }

  private void advanceSuite(long repositoryId, String distribution, long revision, Instant now) {
    jdbc.update(
        """
        UPDATE apt_suite_state
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
        "SELECT * FROM apt_publish_lease WHERE lease_key = ?",
        (rs, row) -> new Lease(
            rs.getString("lease_key"), rs.getString("owner"), rs.getLong("fencing_token"),
            rs.getLong("attempt_count"), nullableInstant(rs, "expires_at"),
            nullableInstant(rs, "updated_at")), leaseKey).stream().findFirst();
  }

  private boolean leaseHeld(String leaseKey, String owner, long token, Instant now) {
    Integer count = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM apt_publish_lease
        WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?
        """, Integer.class, leaseKey, owner, token, nullableTimestamp(now));
    return count != null && count == 1;
  }

  private PackageRecord mapPackage(ResultSet rs, int row) throws SQLException {
    return new PackageRecord(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getString("distribution_name"),
        rs.getString("component_name"), rs.getString("architecture"),
        rs.getString("package_name"), rs.getString("package_version"),
        rs.getString("source_package"), rs.getString("filename"), rs.getString("asset_path"),
        json.read(rs.getString("control_fields")), rs.getString("md5"), rs.getString("sha1"),
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
        rs.getString("release_sha256"), nullableInstant(rs, "created_at"));
  }

  private SigningKey mapSigningKey(ResultSet rs, int row) throws SQLException {
    return new SigningKey(
        rs.getLong("repository_id"), rs.getInt("revision"), rs.getString("key_id"),
        rs.getString("fingerprint"), rs.getString("encrypted_private_key"),
        rs.getString("public_key"), rs.getBoolean("active"), nullableInstant(rs, "created_at"));
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

  private static byte[] coordinateHash(PackageRecord record) {
    return PersistenceHashes.sha256(
        record.distribution(), record.component(), record.packageName(), record.version(),
        record.architecture());
  }

  private static void requirePackage(PackageRecord record) {
    if (record == null) throw new IllegalArgumentException("APT package record is required");
    requireSegment("distribution", record.distribution());
    requireSegment("component", record.component());
    requireSegment("architecture", record.architecture());
    requireSegment("package", record.packageName());
    requireSegment("version", record.version());
    requireSegment("asset path", record.path());
    if (record.size() < 0 || record.sha256() == null || record.sha256().length() != 64) {
      throw new IllegalArgumentException("APT package checksum and size are required");
    }
    if (!SOURCE_HOSTED.equals(record.sourceKind()) && !SOURCE_PROXY.equals(record.sourceKind())) {
      throw new IllegalArgumentException("Unsupported APT package source: " + record.sourceKind());
    }
  }

  private static void requireSegment(String label, String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("APT " + label + " is required");
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
