package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao.ArtifactChange;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao.ChangeKind;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeEventMode;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.BlobReconcileWindow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.HelmIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.PypiProjectIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final ArtifactChangeDao artifactChanges;
  private final boolean artifactChangeEventsEnabled;
  private final String materializedCteModifier;
  private final String unboundAssetRepositoryExpression;
  private final RowMapper<AssetBlobRecord> blobRowMapper;
  private final RowMapper<AssetRecord> assetRowMapper;

  public JdbcAssetDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this(
        jdbcTemplate,
        jsonColumns,
        new JdbcArtifactChangeDao(jdbcTemplate),
        true,
        "",
        "repository_id");
  }

  public JdbcAssetDao(
      JdbcTemplate jdbcTemplate, JsonColumns jsonColumns, DatabaseDialect databaseDialect) {
    this(
        jdbcTemplate,
        jsonColumns,
        new JdbcArtifactChangeDao(jdbcTemplate),
        true,
        databaseDialect.materializedCteModifier(),
        databaseDialect.unboundAssetRepositoryExpression());
  }

  @Autowired
  public JdbcAssetDao(
      JdbcTemplate jdbcTemplate,
      JsonColumns jsonColumns,
      ArtifactChangeDao artifactChanges,
      ArtifactChangeEventMode artifactChangeEventMode,
      DatabaseDialect databaseDialect) {
    this(
        jdbcTemplate,
        jsonColumns,
        artifactChanges,
        artifactChangeEventMode.enabled(),
        databaseDialect.materializedCteModifier(),
        databaseDialect.unboundAssetRepositoryExpression());
  }

  JdbcAssetDao(
      JdbcTemplate jdbcTemplate,
      JsonColumns jsonColumns,
      ArtifactChangeDao artifactChanges,
      boolean artifactChangeEventsEnabled) {
    this(
        jdbcTemplate,
        jsonColumns,
        artifactChanges,
        artifactChangeEventsEnabled,
        "",
        "repository_id");
  }

  private JdbcAssetDao(
      JdbcTemplate jdbcTemplate,
      JsonColumns jsonColumns,
      ArtifactChangeDao artifactChanges,
      boolean artifactChangeEventsEnabled,
      String materializedCteModifier,
      String unboundAssetRepositoryExpression) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.artifactChanges = artifactChanges;
    this.artifactChangeEventsEnabled = artifactChangeEventsEnabled;
    this.materializedCteModifier = materializedCteModifier;
    this.unboundAssetRepositoryExpression = unboundAssetRepositoryExpression;
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

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AssetBlobRecord> restoreDeletedBlobById(long assetBlobId) {
    return lockDeletedBlobById(assetBlobId)
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

  @Transactional
  public long insertAsset(AssetRecord record) {
    OptionalLong inserted = tryInsertAsset(record);
    if (inserted.isPresent()) {
      return inserted.getAsLong();
    }
    return lockAssetIdByPathHash(record.repositoryId(), record.pathHash())
        .orElseThrow(() -> new DuplicateKeyException("Duplicate asset path"));
  }

  @Transactional
  public OptionalLong tryInsertAsset(AssetRecord record) {
    OptionalLong inserted = JdbcInserts.tryInsert(jdbcTemplate, """
        INSERT INTO asset
          (repository_id, component_id, asset_blob_id, format, path, path_hash,
           name, kind, content_type, size, last_downloaded_at, last_updated_at, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> setAssetInsertParameters(ps, record));
    if (inserted.isPresent() && record.assetBlobId() != null) {
      appendArtifactChange(new ArtifactChange(
          null,
          record.repositoryId(),
          inserted.getAsLong(),
          null,
          record.assetBlobId(),
          ChangeKind.CONTENT_CREATED,
          null));
    }
    return inserted;
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

  @Override
  public Optional<AssetWithBlob> findAssetWithBlobById(long assetId) {
    return jdbcTemplate.query("""
        SELECT a.*,
               b.id AS joined_blob_id,
               b.blob_store_id AS joined_blob_store_id,
               b.blob_ref AS joined_blob_ref,
               b.blob_ref_hash AS joined_blob_ref_hash,
               b.object_key AS joined_object_key,
               b.object_key_hash AS joined_object_key_hash,
               b.sha1 AS joined_sha1,
               b.sha256 AS joined_sha256,
               b.md5 AS joined_md5,
               b.size AS joined_size,
               b.content_type AS joined_content_type,
               b.created_by AS joined_created_by,
               b.created_by_ip AS joined_created_by_ip,
               b.blob_created_at AS joined_blob_created_at,
               b.blob_updated_at AS joined_blob_updated_at,
               b.attributes_json AS joined_blob_attributes_json
        FROM asset a
        LEFT JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE a.id = ?
        """, this::mapAssetWithBlob, assetId).stream().findFirst();
  }

  @Override
  public List<AssetWithBlob> listAssetWithBlobPage(
      long repositoryId, long afterAssetId, int maxItems) {
    return jdbcTemplate.query("""
        SELECT a.*,
               b.id AS joined_blob_id,
               b.blob_store_id AS joined_blob_store_id,
               b.blob_ref AS joined_blob_ref,
               b.blob_ref_hash AS joined_blob_ref_hash,
               b.object_key AS joined_object_key,
               b.object_key_hash AS joined_object_key_hash,
               b.sha1 AS joined_sha1,
               b.sha256 AS joined_sha256,
               b.md5 AS joined_md5,
               b.size AS joined_size,
               b.content_type AS joined_content_type,
               b.created_by AS joined_created_by,
               b.created_by_ip AS joined_created_by_ip,
               b.blob_created_at AS joined_blob_created_at,
               b.blob_updated_at AS joined_blob_updated_at,
               b.attributes_json AS joined_blob_attributes_json
        FROM asset a
        LEFT JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE a.repository_id = ?
          AND a.id > ?
        ORDER BY a.id
        LIMIT ?
        """, this::mapAssetWithBlob, repositoryId, Math.max(0, afterAssetId), Math.max(1, maxItems));
  }

  @Override
  public List<AssetWithBlob> listUnboundAssetWithBlobPage(
      long repositoryId, long afterAssetId, int maxItems) {
    return jdbcTemplate.query("""
        WITH bounded AS %s (
          SELECT id
          FROM asset
          WHERE %s = ?
            AND component_id IS NULL
            AND id > ?
          ORDER BY %s, id
          LIMIT ?
        )
        SELECT a.*,
               b.id AS joined_blob_id,
               b.blob_store_id AS joined_blob_store_id,
               b.blob_ref AS joined_blob_ref,
               b.blob_ref_hash AS joined_blob_ref_hash,
               b.object_key AS joined_object_key,
               b.object_key_hash AS joined_object_key_hash,
               b.sha1 AS joined_sha1,
               b.sha256 AS joined_sha256,
               b.md5 AS joined_md5,
               b.size AS joined_size,
               b.content_type AS joined_content_type,
               b.created_by AS joined_created_by,
               b.created_by_ip AS joined_created_by_ip,
               b.blob_created_at AS joined_blob_created_at,
               b.blob_updated_at AS joined_blob_updated_at,
               b.attributes_json AS joined_blob_attributes_json
        FROM bounded
        JOIN asset a ON a.id = bounded.id
        LEFT JOIN asset_blob b ON b.id = a.asset_blob_id
        ORDER BY a.id
        """.formatted(
            materializedCteModifier,
            unboundAssetRepositoryExpression,
            unboundAssetRepositoryExpression), this::mapAssetWithBlob,
        repositoryId, Math.max(0, afterAssetId), Math.max(1, maxItems));
  }

  @Override
  public List<AssetWithBlob> listAssetWithBlobPageByComponentName(
      long repositoryId, String componentName, long afterAssetId, int maxItems) {
    return jdbcTemplate.query("""
        SELECT a.*,
               b.id AS joined_blob_id,
               b.blob_store_id AS joined_blob_store_id,
               b.blob_ref AS joined_blob_ref,
               b.blob_ref_hash AS joined_blob_ref_hash,
               b.object_key AS joined_object_key,
               b.object_key_hash AS joined_object_key_hash,
               b.sha1 AS joined_sha1,
               b.sha256 AS joined_sha256,
               b.md5 AS joined_md5,
               b.size AS joined_size,
               b.content_type AS joined_content_type,
               b.created_by AS joined_created_by,
               b.created_by_ip AS joined_created_by_ip,
               b.blob_created_at AS joined_blob_created_at,
               b.blob_updated_at AS joined_blob_updated_at,
               b.attributes_json AS joined_blob_attributes_json
        FROM component c
        JOIN asset a ON a.component_id = c.id
        LEFT JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE c.repository_id = ?
          AND c.name = ?
          AND a.id > ?
        ORDER BY a.id
        LIMIT ?
        """, this::mapAssetWithBlob, repositoryId, componentName,
        Math.max(0, afterAssetId), Math.max(1, maxItems));
  }

  private AssetWithBlob mapAssetWithBlob(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    AssetRecord asset = assetRowMapper.mapRow(rs, rowNum);
    if (asset.assetBlobId() == null || rs.getObject("joined_blob_id") == null) {
      return new AssetWithBlob(asset, null);
    }
    AssetBlobRecord blob = new AssetBlobRecord(
        rs.getLong("joined_blob_id"),
        rs.getLong("joined_blob_store_id"),
        rs.getString("joined_blob_ref"),
        rs.getBytes("joined_blob_ref_hash"),
        rs.getString("joined_object_key"),
        rs.getBytes("joined_object_key_hash"),
        rs.getString("joined_sha1"),
        rs.getString("joined_sha256"),
        rs.getString("joined_md5"),
        rs.getLong("joined_size"),
        rs.getString("joined_content_type"),
        rs.getString("joined_created_by"),
        rs.getString("joined_created_by_ip"),
        nullableInstant(rs, "joined_blob_created_at"),
        nullableInstant(rs, "joined_blob_updated_at"),
        jsonColumns.read(rs.getString("joined_blob_attributes_json")));
    return new AssetWithBlob(asset, blob);
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

  @Override
  public Set<String> findExistingAssetPaths(long repositoryId, Collection<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return Set.of();
    }
    List<String> unique = paths.stream()
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (unique.isEmpty()) {
      return Set.of();
    }
    Set<String> existing = new LinkedHashSet<>();
    for (int offset = 0; offset < unique.size(); offset += 500) {
      List<String> batch = unique.subList(offset, Math.min(unique.size(), offset + 500));
      String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
      Object[] args = new Object[batch.size() + 1];
      args[0] = repositoryId;
      for (int i = 0; i < batch.size(); i++) {
        args[i + 1] = PersistenceHashes.pathHash(batch.get(i));
      }
      existing.addAll(jdbcTemplate.queryForList(
          "SELECT path FROM asset WHERE repository_id = ? AND path_hash IN (" + placeholders + ")",
          String.class, args));
    }
    existing.retainAll(new java.util.HashSet<>(unique));
    return existing;
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

  @Transactional(propagation = Propagation.MANDATORY)
  public List<AssetRecord> claimStaleAssetsByPrefix(
      long repositoryId, String pathPrefix, Instant updatedBefore, int maxItems) {
    if (pathPrefix == null || pathPrefix.isBlank()) {
      throw new IllegalArgumentException("Cleanup path prefix is required");
    }
    if (updatedBefore == null) {
      throw new IllegalArgumentException("Cleanup cutoff is required");
    }
    return jdbcTemplate.query("""
        SELECT *
        FROM asset
        WHERE repository_id = ?
          AND path LIKE ? ESCAPE '!'
          AND last_updated_at < ?
        ORDER BY last_updated_at, id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, assetRowMapper,
        repositoryId,
        escapeLikeLiteral(pathPrefix) + "%",
        nullableTimestamp(updatedBefore),
        Math.max(1, maxItems));
  }

  public List<AssetRecord> listAssetsByComponent(long componentId) {
    return jdbcTemplate.query("""
        SELECT * FROM asset
        WHERE component_id = ?
        ORDER BY path
        """, assetRowMapper, componentId);
  }

  @Override
  public List<AssetRecord> listAssetsByComponents(Collection<Long> componentIds) {
    List<Long> ids = distinctPositiveIds(componentIds);
    if (ids.isEmpty()) return List.of();
    List<AssetRecord> result = new ArrayList<>();
    for (int offset = 0; offset < ids.size(); offset += 500) {
      List<Long> batch = ids.subList(offset, Math.min(ids.size(), offset + 500));
      result.addAll(jdbcTemplate.query("""
          SELECT * FROM asset
          WHERE component_id IN (""" + placeholders(batch.size()) + """
            )
          ORDER BY component_id, path
          """, assetRowMapper, batch.toArray()));
    }
    return List.copyOf(result);
  }

  @Override
  public Map<Long, AssetRecord> findAssetsByIds(Collection<Long> assetIds) {
    List<Long> ids = distinctPositiveIds(assetIds);
    if (ids.isEmpty()) return Map.of();
    Map<Long, AssetRecord> result = new LinkedHashMap<>(ids.size() * 2);
    for (int offset = 0; offset < ids.size(); offset += 500) {
      List<Long> batch = ids.subList(offset, Math.min(ids.size(), offset + 500));
      jdbcTemplate.query(
              "SELECT * FROM asset WHERE id IN (" + placeholders(batch.size()) + ")",
              assetRowMapper,
              batch.toArray())
          .forEach(asset -> result.put(asset.id(), asset));
    }
    return Map.copyOf(result);
  }

  @Override
  public Map<String, AssetRecord> findAssetsByPaths(
      long repositoryId, Collection<String> paths) {
    List<String> unique = paths == null
        ? List.of()
        : paths.stream().filter(Objects::nonNull).distinct().toList();
    if (unique.isEmpty()) return Map.of();
    Map<String, AssetRecord> result = new LinkedHashMap<>(unique.size() * 2);
    for (int offset = 0; offset < unique.size(); offset += 500) {
      List<String> batch = unique.subList(offset, Math.min(unique.size(), offset + 500));
      List<Object> args = new ArrayList<>(batch.size() + 1);
      args.add(repositoryId);
      batch.forEach(path -> args.add(PersistenceHashes.pathHash(path)));
      jdbcTemplate.query("""
          SELECT * FROM asset
          WHERE repository_id = ?
            AND path_hash IN (""" + placeholders(batch.size()) + ")",
          assetRowMapper,
          args.toArray()).forEach(asset -> result.put(asset.path(), asset));
    }
    result.keySet().retainAll(new java.util.HashSet<>(unique));
    return Map.copyOf(result);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AssetRecord> findAssetByIdForUpdate(long assetId) {
    return jdbcTemplate.query("""
        SELECT * FROM asset WHERE id = ? FOR UPDATE
        """, assetRowMapper, assetId).stream().findFirst();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<AssetRecord> listAssetsByComponentForUpdate(long componentId) {
    return jdbcTemplate.query("""
        SELECT * FROM asset
        WHERE component_id = ?
        ORDER BY path
        FOR UPDATE
        """, assetRowMapper, componentId);
  }

  private static List<Long> distinctPositiveIds(Collection<Long> ids) {
    return ids == null
        ? List.of()
        : ids.stream()
            .filter(Objects::nonNull)
            .filter(id -> id > 0)
            .distinct()
            .toList();
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
          AND external_reference_count = 0
        """, reason, assetBlobId);
  }

  @Transactional
  public int markBlobDeletedIfUnreferenced(long assetBlobId, String reason) {
    if (!lockBlobIds(List.of(assetBlobId)).contains(assetBlobId)
        || !findUnreferencedBlobIds(List.of(assetBlobId), 1).contains(assetBlobId)) {
      return 0;
    }
    return markBlobIdsDeleted(List.of(assetBlobId), reason);
  }

  public int hardDeleteBlobById(long assetBlobId) {
    return jdbcTemplate.update("DELETE FROM asset_blob WHERE id = ?", assetBlobId);
  }

  public int hardDeleteBlobByIdIfDeleted(long assetBlobId) {
    return jdbcTemplate.update("""
        DELETE FROM asset_blob
        WHERE id = ?
          AND deleted_at IS NOT NULL
          AND NOT EXISTS (
            SELECT 1 FROM blob_reference r WHERE r.blob_id = asset_blob.id
          )
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
          AND NOT EXISTS (SELECT 1 FROM blob_reference r WHERE r.blob_id = asset_blob.id)
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

    // Discover likely orphans without holding a broad scan-window lock, then lock only that bounded
    // candidate set and recheck ownership in a fresh statement. PostgreSQL does not re-evaluate a
    // NOT EXISTS subquery after an UPDATE waits on a row lock, so the explicit lock-then-recheck
    // sequence is required to serialize this marker with asset and scanner-document publication.
    List<Long> candidates = findUnreferencedBlobIds(scannedIds, safeMarkBatchSize);
    List<Long> lockedCandidates = lockBlobIds(candidates);
    List<Long> orphanIds =
        findUnreferencedBlobIds(lockedCandidates, Math.max(1, lockedCandidates.size()));
    int marked = markBlobIdsDeleted(orphanIds, reason);
    long nextLastSeenId = candidates.size() >= safeMarkBatchSize
        ? candidates.get(candidates.size() - 1)
        : scannedIds.get(scannedIds.size() - 1);
    return new BlobReconcileWindow(marked, scannedIds.size(), nextLastSeenId, false);
  }

  private List<Long> lockBlobIds(List<Long> blobIds) {
    if (blobIds.isEmpty()) return List.of();
    return jdbcTemplate.queryForList("""
        SELECT id
        FROM asset_blob
        WHERE id IN (""" + placeholders(blobIds.size()) + """
          )
          AND deleted_at IS NULL
        ORDER BY id
        FOR UPDATE SKIP LOCKED
        """, Long.class, blobIds.toArray());
  }

  private List<Long> findUnreferencedBlobIds(List<Long> scannedIds, int maxItems) {
    if (scannedIds.isEmpty()) return List.of();
    List<Object> args = new ArrayList<>(scannedIds);
    args.add(Math.max(1, maxItems));
    return jdbcTemplate.queryForList("""
        SELECT b.id
        FROM asset_blob b
        WHERE b.id IN (""" + placeholders(scannedIds.size()) + """
          )
          AND b.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.asset_blob_id = b.id)
          AND NOT EXISTS (SELECT 1 FROM blob_reference r WHERE r.blob_id = b.id)
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
          AND NOT EXISTS (SELECT 1 FROM blob_reference r WHERE r.blob_id = b.id)
        """, args.toArray());
  }

  public long countDeletedBlobsAwaitingGc() {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM asset_blob b
        WHERE deleted_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM blob_reference r WHERE r.blob_id = b.id)
        """, Long.class);
    return count == null ? 0 : count;
  }

  public long countUnreferencedLiveBlobs() {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM asset_blob b
        WHERE b.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.asset_blob_id = b.id)
          AND NOT EXISTS (SELECT 1 FROM blob_reference r WHERE r.blob_id = b.id)
        """, Long.class);
    return count == null ? 0 : count;
  }

  @Transactional
  public int updateAssetBlobBinding(long assetId, long assetBlobId, String contentType,
      long size, Instant lastUpdatedAt) {
    AssetContentBinding previous = artifactChangeEventsEnabled
        ? lockAssetContentBinding(assetId).orElse(null) : null;
    int updated = jdbcTemplate.update("""
        UPDATE asset
        SET asset_blob_id = ?, content_type = ?, size = ?, last_updated_at = ?
        WHERE id = ?
        """,
        assetBlobId,
        contentType,
        size,
        nullableTimestamp(lastUpdatedAt),
        assetId);
    if (updated == 1 && previous != null
        && !Objects.equals(previous.assetBlobId(), assetBlobId)) {
      appendArtifactChange(new ArtifactChange(
          null,
          previous.repositoryId(),
          assetId,
          previous.assetBlobId(),
          assetBlobId,
          previous.assetBlobId() == null
              ? ChangeKind.CONTENT_CREATED : ChangeKind.CONTENT_REPLACED,
          null));
    }
    return updated;
  }

  @Transactional
  public int updateAssetBlobBindingAndMetadata(long assetId, Long componentId, long assetBlobId,
      String kind, String contentType, long size, Instant lastUpdatedAt,
      java.util.Map<String, Object> attributes) {
    AssetContentBinding previous = artifactChangeEventsEnabled
        ? lockAssetContentBinding(assetId).orElse(null) : null;
    int updated = jdbcTemplate.update("""
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
    if (updated == 1 && previous != null
        && !Objects.equals(previous.assetBlobId(), assetBlobId)) {
      appendArtifactChange(new ArtifactChange(
          null,
          previous.repositoryId(),
          assetId,
          previous.assetBlobId(),
          assetBlobId,
          previous.assetBlobId() == null
              ? ChangeKind.CONTENT_CREATED : ChangeKind.CONTENT_REPLACED,
          null));
    }
    return updated;
  }

  void appendArtifactChange(ArtifactChange change) {
    if (artifactChangeEventsEnabled) {
      artifactChanges.append(change);
    }
  }

  private Optional<AssetContentBinding> lockAssetContentBinding(long assetId) {
    return jdbcTemplate.query("""
        SELECT repository_id, asset_blob_id
        FROM asset
        WHERE id = ?
        FOR UPDATE
        """, (rs, rowNum) -> new AssetContentBinding(
        rs.getLong("repository_id"),
        nullableLong(rs, "asset_blob_id")), assetId).stream().findFirst();
  }

  private record AssetContentBinding(long repositoryId, Long assetBlobId) {
  }

  public int updateAssetComponentBinding(long assetId, Long componentId) {
    return jdbcTemplate.update(
        "UPDATE asset SET component_id = ? WHERE id = ?",
        componentId,
        assetId);
  }

  public int touchLastDownloaded(long assetId, Instant when) {
    return jdbcTemplate.update("""
        UPDATE asset
        SET last_downloaded_at = CASE
          WHEN last_downloaded_at IS NULL OR last_downloaded_at < ? THEN ?
          ELSE last_downloaded_at
        END
        WHERE id = ?
        """, nullableTimestamp(when), nullableTimestamp(when), assetId);
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

  @Override
  public int putAssetStringAttributeIfAbsent(
      long assetId, String attributeName, String value) {
    String attribute = jsonColumns.extractText("attributes_json", attributeName);
    String updated = jsonColumns.setText("attributes_json", attributeName);
    return jdbcTemplate.update(
        "UPDATE asset SET attributes_json = " + updated
            + " WHERE id = ? AND " + attribute + " IS NULL",
        value,
        assetId);
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
