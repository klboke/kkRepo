package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.BlobReconcileWindow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.HelmIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.PypiProjectIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAssetDao implements com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao {
  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<AssetBlobRecord> blobRowMapper;
  private final RowMapper<AssetRecord> assetRowMapper;

  public JdbcAssetDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.blobRowMapper = (rs, rowNum) -> new AssetBlobRecord(
        rs.getLong("id"),
        rs.getLong("blob_store_id"),
        rs.getString("blob_ref"),
        rs.getBytes("blob_ref_hash"),
        rs.getString("object_key"),
        rs.getBytes("object_key_hash"),
        rs.getString("sha1"),
        rs.getString("sha256"),
        rs.getString("md5"),
        rs.getLong("size"),
        rs.getString("content_type"),
        rs.getString("created_by"),
        rs.getString("created_by_ip"),
        nullableInstant(rs, "blob_created_at"),
        nullableInstant(rs, "blob_updated_at"),
        jsonColumns.read(rs.getString("attributes_json")));
    this.assetRowMapper = (rs, rowNum) -> new AssetRecord(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        nullableLong(rs, "component_id"),
        nullableLong(rs, "asset_blob_id"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        rs.getString("path"),
        rs.getBytes("path_hash"),
        rs.getString("name"),
        rs.getString("kind"),
        rs.getString("content_type"),
        nullableLong(rs, "size"),
        nullableInstant(rs, "last_downloaded_at"),
        nullableInstant(rs, "last_updated_at"),
        jsonColumns.read(rs.getString("attributes_json")));
  }

  public long insertBlob(AssetBlobRecord record) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO asset_blob
          (blob_store_id, blob_ref, blob_ref_hash, object_key, object_key_hash,
           sha1, sha256, md5, size, content_type, created_by, created_by_ip,
           blob_created_at, blob_updated_at, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, record.blobStoreId());
      ps.setString(2, record.blobRef());
      ps.setBytes(3, record.blobRefHash());
      ps.setString(4, record.objectKey());
      ps.setBytes(5, record.objectKeyHash());
      ps.setString(6, record.sha1());
      ps.setString(7, record.sha256());
      ps.setString(8, record.md5());
      ps.setLong(9, record.size());
      ps.setString(10, record.contentType());
      ps.setString(11, record.createdBy());
      ps.setString(12, record.createdByIp());
      ps.setTimestamp(13, nullableTimestamp(record.blobCreatedAt()));
      ps.setTimestamp(14, nullableTimestamp(record.blobUpdatedAt()));
      jsonColumns.bind(ps, 15, record.attributes());
    });
  }

  public AssetBlobRecord insertBlobOrFindExisting(AssetBlobRecord record) {
    try {
      return record.withId(insertBlob(record));
    } catch (DuplicateKeyException e) {
      return findDuplicateBlob(record)
          .filter(existing -> sameBlobIdentity(existing, record))
          .orElseThrow(() -> new DuplicateKeyException("Duplicate asset blob was not visible after insert conflict", e));
    }
  }

  public Optional<AssetBlobRecord> findBlobByBlobRefHash(long blobStoreId, byte[] blobRefHash) {
    return jdbcTemplate.query("""
        SELECT * FROM asset_blob
        WHERE blob_store_id = ? AND blob_ref_hash = ?
        """, blobRowMapper, blobStoreId, blobRefHash).stream().findFirst();
  }

  public Optional<AssetBlobRecord> findBlobByObjectKeyHash(long blobStoreId, byte[] objectKeyHash) {
    return jdbcTemplate.query("""
        SELECT * FROM asset_blob
        WHERE blob_store_id = ? AND object_key_hash = ?
        """, blobRowMapper, blobStoreId, objectKeyHash).stream().findFirst();
  }

  private Optional<AssetBlobRecord> findDuplicateBlob(AssetBlobRecord record) {
    return lockBlobByBlobRefHash(record.blobStoreId(), record.blobRefHash())
        .or(() -> lockBlobByObjectKeyHash(record.blobStoreId(), record.objectKeyHash()));
  }

  private Optional<AssetBlobRecord> lockBlobByBlobRefHash(long blobStoreId, byte[] blobRefHash) {
    return jdbcTemplate.query("""
        SELECT * FROM asset_blob
        WHERE blob_store_id = ? AND blob_ref_hash = ?
        FOR UPDATE
        """, blobRowMapper, blobStoreId, blobRefHash).stream().findFirst();
  }

  private Optional<AssetBlobRecord> lockBlobByObjectKeyHash(long blobStoreId, byte[] objectKeyHash) {
    return jdbcTemplate.query("""
        SELECT * FROM asset_blob
        WHERE blob_store_id = ? AND object_key_hash = ?
        FOR UPDATE
        """, blobRowMapper, blobStoreId, objectKeyHash).stream().findFirst();
  }

  private static boolean sameBlobIdentity(AssetBlobRecord existing, AssetBlobRecord record) {
    return existing.blobStoreId() == record.blobStoreId()
        && existing.size() == record.size()
        && java.util.Objects.equals(existing.blobRef(), record.blobRef())
        && Arrays.equals(existing.blobRefHash(), record.blobRefHash())
        && java.util.Objects.equals(existing.objectKey(), record.objectKey())
        && Arrays.equals(existing.objectKeyHash(), record.objectKeyHash())
        && java.util.Objects.equals(existing.sha1(), record.sha1())
        && java.util.Objects.equals(existing.sha256(), record.sha256())
        && java.util.Objects.equals(existing.md5(), record.md5());
  }

  public Optional<AssetBlobRecord> findReusableBlobBySha256(long blobStoreId, String sha256, long size) {
    if (sha256 == null || sha256.isBlank()) return Optional.empty();
    return findReusableBlobIdBySha256(blobStoreId, sha256, size, false)
        .flatMap(this::lockLiveBlobById);
  }

  public Optional<AssetBlobRecord> recoverDeletedBlobBySha256(long blobStoreId, String sha256, long size) {
    if (sha256 == null || sha256.isBlank()) return Optional.empty();
    return findReusableBlobIdBySha256(blobStoreId, sha256, size, true)
        .flatMap(this::lockDeletedBlobById)
        .map(blob -> {
          restoreBlobIfDeleted(blob.id());
          return findBlobById(blob.id()).orElse(blob);
        });
  }

  private Optional<Long> findReusableBlobIdBySha256(
      long blobStoreId, String sha256, long size, boolean deletedOnly) {
    return jdbcTemplate.queryForList(reusableBlobIdSql(deletedOnly),
            Long.class, blobStoreId, sha256, size)
        .stream()
        .findFirst();
  }

  static String reusableBlobIdSql(boolean deletedOnly) {
    String deletedPredicate = deletedOnly
        ? "  AND deleted_at IS NOT NULL\n"
        : "  AND deleted_at IS NULL\n";
    return """
        SELECT id
        FROM asset_blob
        WHERE blob_store_id = ?
          AND sha256 = ?
          AND size = ?
        """ + deletedPredicate + """
        ORDER BY id
        LIMIT 1
        """;
  }

  public long insertAsset(AssetRecord record) {
    OptionalLong inserted = tryInsertAsset(record);
    if (inserted.isPresent()) {
      return inserted.getAsLong();
    }
    return lockAssetIdByPathHash(record.repositoryId(), record.pathHash())
        .orElseThrow(() -> new DuplicateKeyException("Duplicate asset path"));
  }

  public OptionalLong tryInsertAsset(AssetRecord record) {
    return JdbcInserts.tryInsert(jdbcTemplate, """
        INSERT INTO asset
          (repository_id, component_id, asset_blob_id, format, path, path_hash,
           name, kind, content_type, size, last_downloaded_at, last_updated_at, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> setAssetInsertParameters(ps, record));
  }

  public Optional<AssetRecord> findAssetByPathHash(long repositoryId, byte[] pathHash) {
    return jdbcTemplate.query("""
        SELECT * FROM asset
        WHERE repository_id = ? AND path_hash = ?
        """, assetRowMapper, repositoryId, pathHash).stream().findFirst();
  }

  public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
    return findAssetByPathHash(repositoryId, HashColumns.pathHash(path));
  }

  public Optional<AssetRecord> findAssetById(long assetId) {
    return jdbcTemplate.query("SELECT * FROM asset WHERE id = ?", assetRowMapper, assetId)
        .stream()
        .findFirst();
  }

  public Optional<AssetRecord> findDockerBlobAssetBySha256(long repositoryId, String sha256) {
    if (sha256 == null || sha256.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        SELECT a.*
        FROM asset a
        JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE a.repository_id = ?
          AND a.format = 'docker'
          AND a.kind = 'BLOB'
          AND b.sha256 = ?
          AND b.deleted_at IS NULL
        ORDER BY a.id
        LIMIT 1
        """, assetRowMapper, repositoryId, sha256).stream().findFirst();
  }

  private Optional<Long> lockAssetIdByPathHash(long repositoryId, byte[] pathHash) {
    return jdbcTemplate.queryForList("""
        SELECT id
        FROM asset
        WHERE repository_id = ? AND path_hash = ?
        FOR UPDATE
        """, Long.class, repositoryId, pathHash).stream().findFirst();
  }

  private void setAssetInsertParameters(java.sql.PreparedStatement ps, AssetRecord record)
      throws java.sql.SQLException {
    ps.setLong(1, record.repositoryId());
    ps.setObject(2, record.componentId());
    ps.setObject(3, record.assetBlobId());
    ps.setString(4, EnumColumns.write(record.format()));
    ps.setString(5, record.path());
    ps.setBytes(6, record.pathHash());
    ps.setString(7, record.name());
    ps.setString(8, record.kind());
    ps.setString(9, record.contentType());
    ps.setObject(10, record.size());
    ps.setTimestamp(11, nullableTimestamp(record.lastDownloadedAt()));
    ps.setTimestamp(12, nullableTimestamp(record.lastUpdatedAt()));
    jsonColumns.bind(ps, 13, record.attributes());
  }

  /**
   * Looks up the asset with {@code pathHash} across {@code repositoryIds} in a single round trip.
   * Returns a map keyed by {@code repository_id}; absent keys mean no asset at that path in that
   * repository. Replaces N sequential point queries on the {@code uk_asset_path} unique index.
   */
  public Map<Long, AssetRecord> findAssetsByPathHash(Collection<Long> repositoryIds, byte[] pathHash) {
    if (repositoryIds.isEmpty()) {
      return Map.of();
    }
    StringBuilder placeholders = new StringBuilder(repositoryIds.size() * 2);
    Object[] args = new Object[repositoryIds.size() + 1];
    int i = 0;
    for (Long id : repositoryIds) {
      if (i > 0) {
        placeholders.append(',');
      }
      placeholders.append('?');
      args[i++] = id;
    }
    args[i] = pathHash;
    String sql = "SELECT * FROM asset WHERE repository_id IN (" + placeholders
        + ") AND path_hash = ?";
    Map<Long, AssetRecord> byRepository = new HashMap<>(repositoryIds.size() * 2);
    jdbcTemplate.query(sql, assetRowMapper, args)
        .forEach(record -> byRepository.put(record.repositoryId(), record));
    return byRepository;
  }

  public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
    return jdbcTemplate.query("SELECT * FROM asset_blob WHERE id = ?", blobRowMapper, assetBlobId)
        .stream()
        .findFirst();
  }

  public Map<Long, AssetBlobRecord> findBlobsByIds(Collection<Long> assetBlobIds) {
    if (assetBlobIds == null || assetBlobIds.isEmpty()) {
      return Map.of();
    }
    Set<Long> ids = new LinkedHashSet<>();
    for (Long id : assetBlobIds) {
      if (id != null) ids.add(id);
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    StringBuilder placeholders = new StringBuilder(ids.size() * 2);
    Object[] args = new Object[ids.size()];
    int i = 0;
    for (Long id : ids) {
      if (i > 0) placeholders.append(',');
      placeholders.append('?');
      args[i++] = id;
    }
    Map<Long, AssetBlobRecord> byId = new LinkedHashMap<>(ids.size() * 2);
    jdbcTemplate.query("SELECT * FROM asset_blob WHERE id IN (" + placeholders + ")",
            blobRowMapper, args)
        .forEach(blob -> byId.put(blob.id(), blob));
    return byId;
  }

  public Optional<AssetBlobRecord> lockLiveBlobById(long assetBlobId) {
    return jdbcTemplate.query("""
        SELECT *
        FROM asset_blob
        WHERE id = ?
          AND deleted_at IS NULL
        FOR UPDATE
        """, blobRowMapper, assetBlobId).stream().findFirst();
  }

  public Optional<AssetBlobRecord> lockDeletedBlobById(long assetBlobId) {
    return jdbcTemplate.query("""
        SELECT *
        FROM asset_blob
        WHERE id = ?
          AND deleted_at IS NOT NULL
        FOR UPDATE
        """, blobRowMapper, assetBlobId).stream().findFirst();
  }

  public List<AssetRecord> listAssetsByPrefix(long repositoryId, String pathPrefix) {
    String prefix = pathPrefix == null ? "" : pathPrefix;
    if (prefix.isEmpty()) {
      return jdbcTemplate.query("""
          SELECT * FROM asset
          WHERE repository_id = ?
          ORDER BY path
          """, assetRowMapper, repositoryId);
    }
    return jdbcTemplate.query("""
        SELECT * FROM asset
        WHERE repository_id = ? AND path LIKE ? ESCAPE '!'
        ORDER BY path
        """, assetRowMapper, repositoryId, escapeLikeLiteral(prefix) + "%");
  }

  public List<AssetRecord> listAssetsByComponent(long componentId) {
    return jdbcTemplate.query("""
        SELECT * FROM asset
        WHERE component_id = ?
        ORDER BY path
        """, assetRowMapper, componentId);
  }

  public int deleteAssetById(long assetId) {
    return jdbcTemplate.update("DELETE FROM asset WHERE id = ?", assetId);
  }

  public int deleteBlobById(long assetBlobId) {
    return markBlobDeletedById(assetBlobId, "asset unlinked");
  }

  public int markBlobDeletedById(long assetBlobId, String reason) {
    return jdbcTemplate.update("""
        UPDATE asset_blob
        SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
            delete_reason = COALESCE(delete_reason, ?),
            delete_claimed_at = NULL
        WHERE id = ?
        """, reason, assetBlobId);
  }

  public int markBlobDeletedIfUnreferenced(long assetBlobId, String reason) {
    return jdbcTemplate.update("""
        UPDATE asset_blob b
        SET deleted_at = COALESCE(b.deleted_at, CURRENT_TIMESTAMP),
            delete_reason = COALESCE(b.delete_reason, ?),
            delete_claimed_at = NULL
        WHERE b.id = ?
          AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.asset_blob_id = b.id)
        """, reason, assetBlobId);
  }

  public int hardDeleteBlobById(long assetBlobId) {
    return jdbcTemplate.update("DELETE FROM asset_blob WHERE id = ?", assetBlobId);
  }

  public int hardDeleteBlobByIdIfDeleted(long assetBlobId) {
    return jdbcTemplate.update("""
        DELETE FROM asset_blob
        WHERE id = ?
          AND deleted_at IS NOT NULL
        """, assetBlobId);
  }

  public boolean hasLiveBlobForObjectKeyHash(long blobStoreId, byte[] objectKeyHash) {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM asset_blob
        WHERE blob_store_id = ?
          AND object_key_hash = ?
          AND deleted_at IS NULL
        """, Long.class, blobStoreId, objectKeyHash);
    return count != null && count > 0;
  }

  private int restoreBlobIfDeleted(long assetBlobId) {
    return jdbcTemplate.update("""
        UPDATE asset_blob
        SET deleted_at = NULL,
            delete_reason = NULL,
            delete_claimed_at = NULL
        WHERE id = ?
        """, assetBlobId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<AssetBlobRecord> claimDeletedBlobsForGc(int maxItems, Instant deletedBefore, Instant claimRetryBefore) {
    List<Object> args = new ArrayList<>();
    args.add(nullableTimestamp(deletedBefore));
    String retryPredicate = "";
    if (claimRetryBefore != null) {
      retryPredicate = " OR delete_claimed_at < ?";
      args.add(nullableTimestamp(claimRetryBefore));
    }
    args.add(Math.max(1, maxItems));
    List<AssetBlobRecord> rows = jdbcTemplate.query("""
        SELECT *
        FROM asset_blob
        WHERE deleted_at IS NOT NULL
          AND deleted_at < ?
          AND (delete_claimed_at IS NULL""" + retryPredicate + """
        )
        ORDER BY deleted_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, blobRowMapper, args.toArray());
    if (rows.isEmpty()) return rows;
    List<Object[]> updateArgs = rows.stream()
        .map(row -> new Object[]{row.id()})
        .toList();
    jdbcTemplate.batchUpdate("UPDATE asset_blob SET delete_claimed_at = CURRENT_TIMESTAMP WHERE id = ?", updateArgs);
    return rows;
  }

  public int releaseBlobGcClaim(long assetBlobId) {
    return jdbcTemplate.update("""
        UPDATE asset_blob
        SET delete_claimed_at = NULL
        WHERE id = ?
        """, assetBlobId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public BlobReconcileWindow markUnreferencedBlobsDeletedAfter(
      long lastSeenId,
      int scanBatchSize,
      int markBatchSize,
      String reason) {
    int safeScanBatchSize = Math.max(1, scanBatchSize);
    int safeMarkBatchSize = Math.max(1, markBatchSize);
    List<Long> scannedIds = jdbcTemplate.queryForList("""
        SELECT id
        FROM asset_blob
        WHERE id > ? AND deleted_at IS NULL
        ORDER BY id
        LIMIT ?
        """, Long.class, Math.max(0, lastSeenId), safeScanBatchSize);
    if (scannedIds.isEmpty()) {
      return new BlobReconcileWindow(0, 0, 0, true);
    }

    List<Long> orphanIds = findUnreferencedBlobIds(scannedIds, safeMarkBatchSize);
    int marked = markBlobIdsDeleted(orphanIds, reason);
    long nextLastSeenId = orphanIds.size() >= safeMarkBatchSize
        ? orphanIds.get(orphanIds.size() - 1)
        : scannedIds.get(scannedIds.size() - 1);
    return new BlobReconcileWindow(marked, scannedIds.size(), nextLastSeenId, false);
  }

  private List<Long> findUnreferencedBlobIds(List<Long> scannedIds, int maxItems) {
    if (scannedIds.isEmpty()) return List.of();
    List<Object> args = new ArrayList<>(scannedIds);
    args.add(Math.max(1, maxItems));
    return jdbcTemplate.queryForList("""
        SELECT b.id
        FROM asset_blob b
        LEFT JOIN asset a ON a.asset_blob_id = b.id
        WHERE b.id IN (""" + placeholders(scannedIds.size()) + """
          )
          AND b.deleted_at IS NULL
          AND a.id IS NULL
        ORDER BY b.id
        LIMIT ?
        """, Long.class, args.toArray());
  }

  private int markBlobIdsDeleted(List<Long> orphanIds, String reason) {
    if (orphanIds.isEmpty()) return 0;
    List<Object> args = new ArrayList<>();
    args.add(reason);
    args.addAll(orphanIds);
    return jdbcTemplate.update("""
        UPDATE asset_blob b
        SET deleted_at = CURRENT_TIMESTAMP,
            delete_reason = COALESCE(?, 'unreferenced blob reconcile'),
            delete_claimed_at = NULL
        WHERE b.id IN (""" + placeholders(orphanIds.size()) + """
          )
          AND b.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.asset_blob_id = b.id)
        """, args.toArray());
  }

  public long countDeletedBlobsAwaitingGc() {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM asset_blob WHERE deleted_at IS NOT NULL
        """, Long.class);
    return count == null ? 0 : count;
  }

  public long countUnreferencedLiveBlobs() {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM asset_blob b
        WHERE b.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.asset_blob_id = b.id)
        """, Long.class);
    return count == null ? 0 : count;
  }

  public int updateAssetBlobBinding(long assetId, long assetBlobId, String contentType,
      long size, Instant lastUpdatedAt) {
    return jdbcTemplate.update("""
        UPDATE asset
        SET asset_blob_id = ?, content_type = ?, size = ?, last_updated_at = ?
        WHERE id = ?
        """,
        assetBlobId,
        contentType,
        size,
        nullableTimestamp(lastUpdatedAt),
        assetId);
  }

  public int updateAssetBlobBindingAndMetadata(long assetId, Long componentId, long assetBlobId,
      String kind, String contentType, long size, Instant lastUpdatedAt,
      java.util.Map<String, Object> attributes) {
    return jdbcTemplate.update("""
        UPDATE asset
        SET component_id = ?, asset_blob_id = ?, kind = ?, content_type = ?, size = ?,
            last_updated_at = ?, attributes_json = ?
        WHERE id = ?
        """,
        componentId,
        assetBlobId,
        kind,
        contentType,
        size,
        nullableTimestamp(lastUpdatedAt),
        jsonColumns.parameter(attributes),
        assetId);
  }

  public int touchLastDownloaded(long assetId, Instant when) {
    return jdbcTemplate.update("""
        UPDATE asset SET last_downloaded_at = ? WHERE id = ?
        """, nullableTimestamp(when), assetId);
  }

  public int touchAssetLastUpdated(long assetId, Instant when) {
    return jdbcTemplate.update("""
        UPDATE asset SET last_updated_at = ? WHERE id = ?
        """, nullableTimestamp(when), assetId);
  }

  public int touchAssetLastUpdatedAndAttributes(long assetId, Instant when, java.util.Map<String, Object> attributes) {
    return jdbcTemplate.update("""
        UPDATE asset SET last_updated_at = ?, attributes_json = ? WHERE id = ?
        """, nullableTimestamp(when), jsonColumns.parameter(attributes), assetId);
  }

  public int updateAssetAttributes(long assetId, java.util.Map<String, Object> attributes) {
    return jdbcTemplate.update("""
        UPDATE asset SET attributes_json = ? WHERE id = ?
        """, jsonColumns.parameter(attributes), assetId);
  }

  public int updateBlobAttributes(long blobId, java.util.Map<String, Object> attributes) {
    return jdbcTemplate.update("""
        UPDATE asset_blob SET attributes_json = ? WHERE id = ?
        """, jsonColumns.parameter(attributes), blobId);
  }

  public long countAssetsByRepositoryId(long repositoryId) {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM asset WHERE repository_id = ?",
        Long.class,
        repositoryId);
    return count == null ? 0 : count;
  }

  public List<HelmIndexRow> listHelmIndexRows(long repositoryId) {
    return jdbcTemplate.query("""
        SELECT a.path, a.last_updated_at, a.attributes_json, b.sha256
        FROM asset a
        JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE a.repository_id = ?
          AND a.format = ?
          AND a.kind = 'PACKAGE'
          AND b.deleted_at IS NULL
        ORDER BY a.name, a.path
        """, (rs, rowNum) -> new HelmIndexRow(
            rs.getString("path"),
            nullableInstant(rs, "last_updated_at"),
            rs.getString("sha256"),
            jsonColumns.read(rs.getString("attributes_json"))),
        repositoryId,
        EnumColumns.write(RepositoryFormat.HELM));
  }

  public List<PypiProjectIndexRow> listPypiProjectIndexRows(long repositoryId, String normalizedName) {
    String prefix = "packages/" + normalizedName + "/";
    return jdbcTemplate.query("""
        SELECT a.path, a.kind, a.attributes_json, b.md5
        FROM asset a
        JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE a.repository_id = ?
          AND a.format = ?
          AND a.path LIKE ? ESCAPE '!'
          AND a.kind IN ('package', 'package-signature')
          AND b.deleted_at IS NULL
        ORDER BY a.path
        """, (rs, rowNum) -> new PypiProjectIndexRow(
            rs.getString("path"),
            rs.getString("kind"),
            rs.getString("md5"),
            jsonColumns.read(rs.getString("attributes_json"))),
        repositoryId,
        EnumColumns.write(RepositoryFormat.PYPI),
        escapeLikeLiteral(prefix) + "%");
  }

  private static String escapeLikeLiteral(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static String placeholders(int count) {
    return String.join(",", Collections.nCopies(Math.max(1, count), "?"));
  }




}
