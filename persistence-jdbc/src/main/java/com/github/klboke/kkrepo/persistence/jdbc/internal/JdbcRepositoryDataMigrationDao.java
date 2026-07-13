package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao.AssetClaim;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao.MigrationJobProgress;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao.TargetAssetRef;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationJobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationRepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRepositoryDataMigrationDao implements com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao {
  public static final String REPOSITORY_DISCOVERING = "discovering";
  public static final String REPOSITORY_READY = "ready";
  public static final String REPOSITORY_MIGRATING = "migrating";
  public static final String REPOSITORY_FINISHED = "finished";
  public static final String REPOSITORY_FINISHED_WITH_FAILURES = "finished_with_failures";

  public static final String ASSET_PENDING = "pending";
  public static final String ASSET_MIGRATING = "migrating";
  public static final String ASSET_MIGRATED = "migrated";
  public static final String ASSET_FAILED = "failed";

  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<RepositoryDataMigrationRepositoryRecord> repositoryRowMapper;
  private final RowMapper<RepositoryDataMigrationAssetRecord> assetRowMapper;
  private final RowMapper<AssetClaim> assetClaimRowMapper;

  public JdbcRepositoryDataMigrationDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.repositoryRowMapper = (rs, rowNum) -> new RepositoryDataMigrationRepositoryRecord(
        rs.getLong("id"),
        rs.getLong("migration_job_id"),
        rs.getString("source_repository_name"),
        rs.getString("target_repository_name"),
        rs.getLong("target_repository_id"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        rs.getString("status"),
        rs.getString("cursor_path"),
        rs.getInt("page_size"),
        rs.getLong("total_assets"),
        rs.getLong("discovered_assets"),
        rs.getLong("migrated_assets"),
        rs.getLong("failed_assets"),
        nullableInstant(rs, "claimed_at"),
        rs.getString("last_error"),
        jsonColumns.read(rs.getString("options_json")),
        nullableInstant(rs, "started_at"),
        nullableInstant(rs, "finished_at"));
    this.assetRowMapper = (rs, rowNum) -> new RepositoryDataMigrationAssetRecord(
        rs.getLong("id"),
        rs.getLong("repository_job_id"),
        rs.getString("source_asset_id"),
        rs.getString("source_component_id"),
        rs.getString("source_path"),
        rs.getBytes("source_path_hash"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        rs.getString("namespace"),
        rs.getString("name"),
        rs.getString("version"),
        rs.getString("asset_kind"),
        rs.getString("content_type"),
        nullableLong(rs, "size"),
        rs.getString("source_blob_ref"),
        nullableInstant(rs, "source_last_updated_at"),
        nullableInstant(rs, "source_last_downloaded_at"),
        nullableInstant(rs, "source_blob_created_at"),
        nullableInstant(rs, "source_blob_updated_at"),
        rs.getString("source_created_by"),
        rs.getString("source_created_by_ip"),
        rs.getString("status"),
        rs.getInt("attempts"),
        nullableInstant(rs, "claimed_at"),
        nullableInstant(rs, "migrated_at"),
        nullableLong(rs, "target_component_id"),
        nullableLong(rs, "target_asset_id"),
        nullableLong(rs, "target_asset_blob_id"),
        rs.getString("last_error"),
        jsonColumns.read(rs.getString("metadata_json")),
        nullableInstant(rs, "discovered_at"));
    this.assetClaimRowMapper = (rs, rowNum) -> new AssetClaim(
        assetRowMapper.mapRow(rs, rowNum),
        rs.getLong("migration_job_id"),
        rs.getString("source_repository_name"),
        rs.getString("target_repository_name"),
        rs.getLong("target_repository_id"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("repository_format")),
        rs.getString("source_data_path"),
        jsonColumns.read(rs.getString("job_options_json")));
  }

  public long createRepositoryJob(
      long migrationJobId,
      String sourceRepositoryName,
      String targetRepositoryName,
      long targetRepositoryId,
      RepositoryFormat format,
      int pageSize,
      Map<String, Object> options) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO repository_data_migration_repository
          (migration_job_id, source_repository_name, target_repository_name, target_repository_id,
           format, status, page_size, options_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, migrationJobId);
      ps.setString(2, sourceRepositoryName);
      ps.setString(3, targetRepositoryName);
      ps.setLong(4, targetRepositoryId);
      ps.setString(5, EnumColumns.write(format));
      ps.setString(6, REPOSITORY_DISCOVERING);
      ps.setInt(7, pageSize);
      ps.setString(8, jsonColumns.write(options));
    });
  }

  public List<RepositoryDataMigrationRepositoryRecord> listRepositories(long migrationJobId) {
    return jdbcTemplate.query("""
        SELECT *
        FROM repository_data_migration_repository
        WHERE migration_job_id = ?
        ORDER BY source_repository_name
        """, repositoryRowMapper, migrationJobId);
  }

  public Optional<RepositoryDataMigrationRepositoryRecord> findRepositoryJob(long repositoryJobId) {
    return jdbcTemplate.query("""
        SELECT *
        FROM repository_data_migration_repository
        WHERE id = ?
        """, repositoryRowMapper, repositoryJobId).stream().findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RepositoryDataMigrationRepositoryRecord> claimRepositoryForDiscovery(Instant retryBefore) {
    return claimRepositoryForDiscovery(null, retryBefore);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RepositoryDataMigrationRepositoryRecord> claimRepositoryForDiscovery(
      Long migrationJobId,
      Instant retryBefore) {
    List<Object> args = new ArrayList<>();
    args.add(REPOSITORY_DISCOVERING);
    args.add(nullableTimestamp(retryBefore));
    String jobPredicate = "";
    if (migrationJobId != null) {
      jobPredicate = "  AND migration_job_id = ?\n";
      args.add(migrationJobId);
    }
    List<RepositoryDataMigrationRepositoryRecord> rows = jdbcTemplate.query("""
        SELECT *
        FROM repository_data_migration_repository
        WHERE status = ?
          AND (claimed_at IS NULL OR claimed_at < ?)
        """ + jobPredicate + """
        ORDER BY id
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, repositoryRowMapper, args.toArray());
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    RepositoryDataMigrationRepositoryRecord row = rows.get(0);
    jdbcTemplate.update("""
        UPDATE repository_data_migration_repository
        SET claimed_at = NOW(3)
        WHERE id = ?
        """, row.id());
    return Optional.of(row);
  }

  public Map<ByteBuffer, TargetAssetRef> findTargetAssetsByPathHash(
      long targetRepositoryId,
      Collection<byte[]> pathHashes) {
    List<byte[]> hashes = pathHashes.stream()
        .filter(hash -> hash != null && hash.length > 0)
        .toList();
    if (hashes.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(hashes.size(), "?"));
    List<Object> args = new ArrayList<>(hashes.size() + 1);
    args.add(targetRepositoryId);
    args.addAll(hashes);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        SELECT id, component_id, asset_blob_id, path_hash
        FROM asset
        WHERE repository_id = ?
          AND path_hash IN (
        """ + placeholders + """
          )
        """, args.toArray());
    LinkedHashMap<ByteBuffer, TargetAssetRef> refs = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      byte[] hash = (byte[]) row.get("path_hash");
      refs.put(ByteBuffer.wrap(hash), new TargetAssetRef(
          nullableNumber(row.get("component_id")),
          ((Number) row.get("id")).longValue(),
          nullableNumber(row.get("asset_blob_id"))));
    }
    return refs;
  }

  public void upsertDiscoveredAssets(
      long repositoryJobId,
      List<RepositoryDataMigrationAssetRecord> assets,
      Map<ByteBuffer, TargetAssetRef> existingTargets) {
    if (assets == null || assets.isEmpty()) {
      return;
    }
    Map<ByteBuffer, TargetAssetRef> targetRefs = existingTargets == null ? Map.of() : existingTargets;
    Instant migratedAt = Instant.now();
    jdbcTemplate.batchUpdate("""
        INSERT INTO repository_data_migration_asset
          (repository_job_id, source_asset_id, source_component_id, source_path, source_path_hash,
           format, namespace, name, version, asset_kind, content_type, size, source_blob_ref,
           source_last_updated_at, source_last_downloaded_at, source_blob_created_at,
           source_blob_updated_at, source_created_by, source_created_by_ip, status,
           migrated_at, target_component_id, target_asset_id, target_asset_blob_id, metadata_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          source_asset_id = VALUES(source_asset_id),
          source_component_id = VALUES(source_component_id),
          format = VALUES(format),
          namespace = VALUES(namespace),
          name = VALUES(name),
          version = VALUES(version),
          asset_kind = VALUES(asset_kind),
          content_type = VALUES(content_type),
          size = VALUES(size),
          source_blob_ref = VALUES(source_blob_ref),
          source_last_updated_at = VALUES(source_last_updated_at),
          source_last_downloaded_at = VALUES(source_last_downloaded_at),
          source_blob_created_at = VALUES(source_blob_created_at),
          source_blob_updated_at = VALUES(source_blob_updated_at),
          source_created_by = VALUES(source_created_by),
          source_created_by_ip = VALUES(source_created_by_ip),
          status = IF(status = 'migrated', status, VALUES(status)),
          attempts = IF(status = 'migrated' OR VALUES(status) = 'migrated', attempts, 0),
          claimed_at = IF(status = 'migrated' OR VALUES(status) = 'migrated', claimed_at, NULL),
          migrated_at = IF(status = 'migrated', migrated_at, VALUES(migrated_at)),
          target_component_id = IF(status = 'migrated' AND target_component_id IS NOT NULL,
              target_component_id, VALUES(target_component_id)),
          target_asset_id = IF(status = 'migrated' AND target_asset_id IS NOT NULL,
              target_asset_id, VALUES(target_asset_id)),
          target_asset_blob_id = IF(status = 'migrated' AND target_asset_blob_id IS NOT NULL,
              target_asset_blob_id, VALUES(target_asset_blob_id)),
          last_error = IF(status = 'migrated', last_error, NULL),
          metadata_json = VALUES(metadata_json)
        """, assets, Math.min(assets.size(), 500), (ps, record) ->
        setAssetInsertParameters(ps, repositoryJobId, record,
            targetRefs.get(ByteBuffer.wrap(sourcePathHash(record))), migratedAt));
  }

  private void setAssetInsertParameters(
      PreparedStatement ps,
      long repositoryJobId,
      RepositoryDataMigrationAssetRecord record,
      TargetAssetRef target,
      Instant migratedAt) throws SQLException {
    ps.setLong(1, repositoryJobId);
    ps.setString(2, record.sourceAssetId());
    ps.setString(3, record.sourceComponentId());
    ps.setString(4, record.sourcePath());
    ps.setBytes(5, sourcePathHash(record));
    ps.setString(6, EnumColumns.write(record.format()));
    ps.setString(7, record.namespace());
    ps.setString(8, record.name());
    ps.setString(9, record.version());
    ps.setString(10, record.assetKind());
    ps.setString(11, record.contentType());
    ps.setObject(12, record.size());
    ps.setString(13, record.sourceBlobRef());
    ps.setTimestamp(14, nullableTimestamp(record.sourceLastUpdatedAt()));
    ps.setTimestamp(15, nullableTimestamp(record.sourceLastDownloadedAt()));
    ps.setTimestamp(16, nullableTimestamp(record.sourceBlobCreatedAt()));
    ps.setTimestamp(17, nullableTimestamp(record.sourceBlobUpdatedAt()));
    ps.setString(18, record.sourceCreatedBy());
    ps.setString(19, record.sourceCreatedByIp());
    ps.setString(20, target == null ? ASSET_PENDING : ASSET_MIGRATED);
    ps.setTimestamp(21, nullableTimestamp(target == null ? null : migratedAt));
    setNullableLong(ps, 22, target == null ? null : target.componentId());
    setNullableLong(ps, 23, target == null ? null : target.assetId());
    setNullableLong(ps, 24, target == null ? null : target.assetBlobId());
    ps.setString(25, jsonColumns.write(record.metadata()));
  }

  private static byte[] sourcePathHash(RepositoryDataMigrationAssetRecord record) {
    byte[] hash = record.sourcePathHash();
    if (hash != null && hash.length > 0) {
      return hash;
    }
    return HashColumns.pathHash(record.sourcePath());
  }

  public void finishDiscoveryPage(long repositoryJobId, String nextCursor, boolean complete) {
    long total = countAssets(repositoryJobId);
    Map<String, Long> counts = assetStatusCounts(repositoryJobId);
    long migrated = counts.getOrDefault(ASSET_MIGRATED, 0L);
    long failed = counts.getOrDefault(ASSET_FAILED, 0L);
    long pending = counts.getOrDefault(ASSET_PENDING, 0L);
    long migrating = counts.getOrDefault(ASSET_MIGRATING, 0L);
    String status = discoveryStatus(complete, total, pending, migrating, failed);
    jdbcTemplate.update("""
        UPDATE repository_data_migration_repository
        SET cursor_path = ?, status = ?, claimed_at = NULL, last_error = NULL,
            discovered_assets = ?, total_assets = IF(?, ?, total_assets),
            migrated_assets = ?, failed_assets = ?,
            finished_at = IF(? IN (?, ?), CURRENT_TIMESTAMP(3), NULL)
        WHERE id = ?
        """,
        nextCursor,
        status,
        total,
        complete,
        complete ? total : 0,
        migrated,
        failed,
        status,
        REPOSITORY_FINISHED,
        REPOSITORY_FINISHED_WITH_FAILURES,
        repositoryJobId);
  }

  private static String discoveryStatus(
      boolean complete,
      long total,
      long pending,
      long migrating,
      long failed) {
    if (!complete) {
      return REPOSITORY_DISCOVERING;
    }
    if (total == 0 || (pending == 0 && migrating == 0)) {
      return failed == 0 ? REPOSITORY_FINISHED : REPOSITORY_FINISHED_WITH_FAILURES;
    }
    return REPOSITORY_READY;
  }

  public void markDiscoveryFailure(long repositoryJobId, String error) {
    jdbcTemplate.update("""
        UPDATE repository_data_migration_repository
        SET claimed_at = NOW(3), last_error = ?
        WHERE id = ?
        """, truncate(error), repositoryJobId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<AssetClaim> claimAssetsForMigration(int limit, int maxAttempts, Instant retryBefore) {
    return claimAssetsForMigration(null, limit, maxAttempts, retryBefore);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<AssetClaim> claimAssetsForMigration(Long migrationJobId, int limit, int maxAttempts, Instant retryBefore) {
    int safeLimit = Math.max(1, limit);
    List<Object> args = new ArrayList<>();
    args.add(REPOSITORY_READY);
    args.add(REPOSITORY_MIGRATING);
    args.add(ASSET_PENDING);
    args.add(ASSET_MIGRATING);
    args.add(nullableTimestamp(retryBefore));
    args.add(maxAttempts);
    args.add(nullableTimestamp(retryBefore));
    String jobPredicate = "";
    if (migrationJobId != null) {
      jobPredicate = "  AND r.migration_job_id = ?\n";
      args.add(migrationJobId);
    }
    args.add(safeLimit);
    List<AssetClaim> claims = jdbcTemplate.query("""
        SELECT a.*,
               r.migration_job_id,
               r.source_repository_name,
               r.target_repository_name,
               r.target_repository_id,
               r.format AS repository_format,
               mj.source_data_path,
               mj.options_json AS job_options_json
        FROM repository_data_migration_asset a
        JOIN repository_data_migration_repository r ON r.id = a.repository_job_id
        JOIN migration_job mj ON mj.id = r.migration_job_id
        WHERE r.status IN (?, ?)
          AND JSON_UNQUOTE(JSON_EXTRACT(mj.options_json, '$.packageMigrationEnabled')) = 'true'
          AND (
            a.status = ?
            OR (a.status = ? AND (a.claimed_at IS NULL OR a.claimed_at < ?))
          )
          AND a.attempts < ?
          AND (a.claimed_at IS NULL OR a.claimed_at < ?)
        """ + jobPredicate + """
        ORDER BY a.id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, assetClaimRowMapper, args.toArray());
    for (AssetClaim claim : claims) {
      jdbcTemplate.update("""
          UPDATE repository_data_migration_asset
          SET status = ?, attempts = attempts + 1, claimed_at = NOW(3)
          WHERE id = ?
          """, ASSET_MIGRATING, claim.asset().id());
      jdbcTemplate.update("""
          UPDATE repository_data_migration_repository
          SET status = ?
          WHERE id = ? AND status = ?
          """, REPOSITORY_MIGRATING, claim.asset().repositoryJobId(), REPOSITORY_READY);
    }
    return claims;
  }

  public void markAssetMigrated(long assetId, long repositoryJobId,
      Long targetComponentId, Long targetAssetId, Long targetAssetBlobId) {
    jdbcTemplate.update("""
        UPDATE repository_data_migration_asset
        SET status = ?, claimed_at = NULL, migrated_at = CURRENT_TIMESTAMP(3),
            target_component_id = ?, target_asset_id = ?, target_asset_blob_id = ?,
            last_error = NULL
        WHERE id = ?
        """, ASSET_MIGRATED, targetComponentId, targetAssetId, targetAssetBlobId, assetId);
  }

  public void markAssetFailed(long assetId, long repositoryJobId, int maxAttempts, String error) {
    jdbcTemplate.update("""
        UPDATE repository_data_migration_asset
        SET status = IF(attempts >= ?, ?, ?),
            claimed_at = IF(attempts >= ?, NULL, NOW(3)),
            last_error = ?
        WHERE id = ?
        """, maxAttempts, ASSET_FAILED, ASSET_PENDING, maxAttempts, truncate(error), assetId);
  }

  public int retryFailedAssets(long migrationJobId) {
    List<Long> repositoryJobIds = jdbcTemplate.queryForList("""
        SELECT DISTINCT r.id
        FROM repository_data_migration_repository r
        JOIN repository_data_migration_asset a ON a.repository_job_id = r.id
        WHERE r.migration_job_id = ?
          AND a.status = ?
        ORDER BY r.id
        """, Long.class, migrationJobId, ASSET_FAILED);
    if (repositoryJobIds.isEmpty()) {
      return 0;
    }
    int updated = jdbcTemplate.update("""
        UPDATE repository_data_migration_asset a
        JOIN repository_data_migration_repository r ON r.id = a.repository_job_id
        SET a.status = ?,
            a.attempts = 0,
            a.claimed_at = NULL,
            a.last_error = NULL,
            a.migrated_at = NULL,
            a.target_component_id = NULL,
            a.target_asset_id = NULL,
            a.target_asset_blob_id = NULL
        WHERE r.migration_job_id = ?
          AND a.status = ?
        """, ASSET_PENDING, migrationJobId, ASSET_FAILED);
    for (Long repositoryJobId : repositoryJobIds) {
      refreshRepositoryProgress(repositoryJobId);
    }
    return updated;
  }

  public void refreshRepositoryProgress(long repositoryJobId) {
    Map<String, Long> counts = assetStatusCounts(repositoryJobId);
    long migrated = counts.getOrDefault(ASSET_MIGRATED, 0L);
    long failed = counts.getOrDefault(ASSET_FAILED, 0L);
    long pending = counts.getOrDefault(ASSET_PENDING, 0L);
    long migrating = counts.getOrDefault(ASSET_MIGRATING, 0L);
    long total = countAssets(repositoryJobId);
    String status = pending == 0 && migrating == 0
        ? failed == 0 ? REPOSITORY_FINISHED : REPOSITORY_FINISHED_WITH_FAILURES
        : REPOSITORY_MIGRATING;
    jdbcTemplate.update("""
        UPDATE repository_data_migration_repository
        SET status = ?, total_assets = ?, discovered_assets = ?, migrated_assets = ?,
            failed_assets = ?, claimed_at = NULL,
            finished_at = IF(? IN (?, ?), CURRENT_TIMESTAMP(3), NULL)
        WHERE id = ?
          AND status IN (?, ?, ?, ?)
        """,
        status,
        total,
        total,
        migrated,
        failed,
        status,
        REPOSITORY_FINISHED,
        REPOSITORY_FINISHED_WITH_FAILURES,
        repositoryJobId,
        REPOSITORY_READY,
        REPOSITORY_MIGRATING,
        REPOSITORY_FINISHED_WITH_FAILURES,
        REPOSITORY_FINISHED);
  }

  public MigrationJobProgress jobProgress(long migrationJobId) {
    List<RepositoryDataMigrationRepositoryRecord> repositories = listRepositories(migrationJobId);
    long discovered = 0;
    long total = 0;
    long migrated = 0;
    long failed = 0;
    boolean active = false;
    boolean failedRepos = false;
    for (RepositoryDataMigrationRepositoryRecord repository : repositories) {
      discovered += repository.discoveredAssets();
      total += repository.totalAssets();
      migrated += repository.migratedAssets();
      failed += repository.failedAssets();
      active = active
          || REPOSITORY_DISCOVERING.equals(repository.status())
          || REPOSITORY_READY.equals(repository.status())
          || REPOSITORY_MIGRATING.equals(repository.status());
      failedRepos = failedRepos || REPOSITORY_FINISHED_WITH_FAILURES.equals(repository.status());
    }
    long pending = Math.max(0, total - migrated - failed);
    return new MigrationJobProgress(repositories.size(), discovered, total, migrated, failed, pending, active, failedRepos);
  }

  public void updateMigrationJobSummary(long migrationJobId, String status, Map<String, Object> summary) {
    jdbcTemplate.update("""
        UPDATE migration_job
        SET status = ?, summary_json = ?, finished_at = IF(? = 'running', NULL, CURRENT_TIMESTAMP(3))
        WHERE id = ?
        """, status, jsonColumns.write(summary), status, migrationJobId);
  }

  public void setPackageMigrationEnabled(long migrationJobId, boolean enabled) {
    String literal = enabled ? "true" : "false";
    jdbcTemplate.update("""
        UPDATE migration_job
        SET options_json = JSON_SET(options_json, '$.packageMigrationEnabled',
        """ + literal + """
        )
        WHERE id = ?
        """, migrationJobId);
  }

  public List<MigrationJobRecord> listRepositoryDataJobs(int limit) {
    return jdbcTemplate.query("""
        SELECT *
        FROM migration_job
        WHERE JSON_UNQUOTE(JSON_EXTRACT(options_json, '$.scope')) = 'repository-data'
        ORDER BY id DESC
        LIMIT ?
        """, (rs, rowNum) -> new MigrationJobRecord(
            rs.getLong("id"),
            rs.getString("source_nexus_version"),
            rs.getString("source_data_path"),
            rs.getString("status"),
            jsonColumns.read(rs.getString("options_json")),
            jsonColumns.read(rs.getString("summary_json")),
            nullableInstant(rs, "started_at"),
            nullableInstant(rs, "finished_at")),
        Math.max(1, Math.min(limit, 100)));
  }

  private long countAssets(long repositoryJobId) {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM repository_data_migration_asset
        WHERE repository_job_id = ?
        """, Long.class, repositoryJobId);
    return count == null ? 0 : count;
  }

  private Map<String, Long> assetStatusCounts(long repositoryJobId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        SELECT status, COUNT(*) AS count
        FROM repository_data_migration_asset
        WHERE repository_job_id = ?
        GROUP BY status
        """, repositoryJobId);
    LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Object count = row.get("count");
      counts.put(String.valueOf(row.get("status")),
          count instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(count)));
    }
    return counts;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 4000 ? value : value.substring(0, 4000);
  }

  private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.BIGINT);
    } else {
      ps.setLong(index, value);
    }
  }

  private static Long nullableNumber(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }



}
