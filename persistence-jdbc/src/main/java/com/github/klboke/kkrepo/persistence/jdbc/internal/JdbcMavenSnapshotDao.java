package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.MavenSnapshotDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMavenSnapshotDao implements MavenSnapshotDao {
  private final JdbcTemplate jdbc;
  private final String pageTable;
  private final String retentionTable;
  private final com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect dialect;
  private static final RowMapper<Build> BUILD = (rs, n) -> new Build(
      rs.getLong("id"), rs.getLong("family_id"), rs.getLong("repository_id"),
      rs.getString("version"), rs.getLong("snapshot_timestamp"), rs.getInt("build_number"),
      rs.getLong("revision"), rs.getInt("asset_count"), rs.getInt("primary_count"),
      rs.getTimestamp("mutated_at").toInstant());

  public JdbcMavenSnapshotDao(JdbcTemplate jdbc, com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect dialect) {
    this.jdbc = jdbc;
    this.dialect = dialect.coordination();
    this.pageTable = dialect.tableReferenceWithPreferredIndex("maven_snapshot_build", "idx_maven_snapshot_page");
    this.retentionTable = dialect.tableReferenceWithPreferredIndex("maven_snapshot_build", "idx_maven_snapshot_retained");
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public long lockFamily(long repositoryId, String path, String groupId, String artifactId, String baseVersion) {
    byte[] hash = PersistenceHashes.pathHash(path);
    List<Long> ids = jdbc.queryForList(
        "SELECT id FROM maven_snapshot_family WHERE repository_id = ? AND path_hash = ? FOR UPDATE",
        Long.class, repositoryId, hash);
    if (!ids.isEmpty()) return ids.getFirst();
    dialect.ensureMavenSnapshotFamily(jdbc, repositoryId, hash, path, groupId, artifactId, baseVersion);
    return jdbc.queryForObject("""
        SELECT id FROM maven_snapshot_family WHERE repository_id = ? AND path_hash = ? FOR UPDATE
        """, Long.class, repositoryId, hash);
  }

  @Override
  public Optional<Family> findFamily(long id) {
    return jdbc.query("SELECT * FROM maven_snapshot_family WHERE id = ?", (rs, n) ->
        new Family(rs.getLong("id"), rs.getLong("repository_id"), rs.getString("path"),
            rs.getString("group_id"), rs.getString("artifact_id"), rs.getString("base_version")), id).stream().findFirst();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void store(long familyId, long repositoryId, long assetId, String version, long timestamp,
      int buildNumber, String variant, boolean primary, Instant modifiedAt, boolean backfill) {
    List<Long> existing = jdbc.queryForList(
        "SELECT build_id FROM maven_snapshot_asset WHERE asset_id = ?", Long.class, assetId);
    if (!existing.isEmpty()) {
      if (!backfill) jdbc.update("""
          UPDATE maven_snapshot_build SET revision = revision + 1, mutated_at = CURRENT_TIMESTAMP WHERE id = ?
          """, existing.getFirst());
      return;
    }
    List<Long> builds = jdbc.queryForList("""
        SELECT id FROM maven_snapshot_build WHERE family_id = ? AND snapshot_timestamp = ? AND build_number = ?
        """, Long.class, familyId, timestamp, buildNumber);
    long buildId = builds.isEmpty() ? JdbcInserts.insert(jdbc, """
        INSERT INTO maven_snapshot_build
          (family_id, repository_id, version, snapshot_timestamp, build_number, mutated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, familyId); ps.setLong(2, repositoryId); ps.setString(3, version);
      ps.setLong(4, timestamp); ps.setInt(5, buildNumber);
      ps.setTimestamp(6, Timestamp.from(modifiedAt == null ? Instant.now() : modifiedAt));
    }) : builds.getFirst();
    jdbc.update("""
        INSERT INTO maven_snapshot_asset
          (asset_id, build_id, family_id, variant_hash, primary_asset, snapshot_timestamp, build_number)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, assetId, buildId, familyId, PersistenceHashes.sha256(variant), primary, timestamp, buildNumber);
    jdbc.update("""
        UPDATE maven_snapshot_build SET asset_count = asset_count + 1,
          primary_count = primary_count + ?, revision = revision + 1,
          mutated_at = CASE WHEN ? THEN GREATEST(mutated_at, ?) ELSE CURRENT_TIMESTAMP END WHERE id = ?
        """, primary ? 1 : 0, backfill, Timestamp.from(modifiedAt == null ? Instant.now() : modifiedAt), buildId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void remove(long assetId) {
    var rows = jdbc.query("SELECT build_id, primary_asset FROM maven_snapshot_asset WHERE asset_id = ?",
        (rs, n) -> new long[] {rs.getLong(1), rs.getBoolean(2) ? 1 : 0}, assetId);
    if (rows.isEmpty()) return;
    long[] row = rows.getFirst();
    jdbc.update("DELETE FROM maven_snapshot_asset WHERE asset_id = ?", assetId);
    jdbc.update("""
        UPDATE maven_snapshot_build SET asset_count = asset_count - 1,
          primary_count = primary_count - ?, revision = revision + 1, mutated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """, row[1], row[0]);
    jdbc.update("DELETE FROM maven_snapshot_build WHERE id = ? AND asset_count = 0", row[0]);
  }

  @Override
  public Optional<Build> findBuild(long buildId) {
    return jdbc.query("SELECT * FROM maven_snapshot_build WHERE id = ?", BUILD, buildId).stream().findFirst();
  }

  @Override
  public List<Build> page(long repositoryId, long afterBuildId, int limit) {
    return jdbc.query("""
        SELECT * FROM %s WHERE repository_id = ? AND id > ? ORDER BY id LIMIT ?
        """.formatted(pageTable), BUILD, repositoryId, afterBuildId, limit);
  }

  @Override
  public Optional<Build> retentionBoundary(long familyId, int count) {
    return jdbc.query("""
        SELECT * FROM %s WHERE family_id = ? AND has_primary = TRUE
        ORDER BY snapshot_timestamp DESC, build_number DESC LIMIT 1 OFFSET ?
        """.formatted(retentionTable), BUILD, familyId, Math.max(0, count - 1)).stream().findFirst();
  }

  @Override
  public List<Long> assetIds(long buildId, int limit) {
    return jdbc.queryForList("""
        SELECT asset_id FROM maven_snapshot_asset WHERE build_id = ? ORDER BY asset_id LIMIT ?
        """, Long.class, buildId, limit);
  }

  @Override
  public boolean hasLatestVariant(long buildId) {
    // Each anti-join seeks one exact variant and stops at the first newer primary asset.
    return !jdbc.queryForList("""
        SELECT a.asset_id FROM maven_snapshot_asset a
        WHERE a.build_id = ? AND a.primary_asset = TRUE
          AND NOT EXISTS (SELECT 1 FROM maven_snapshot_asset n
            WHERE n.family_id = a.family_id AND n.variant_hash = a.variant_hash
              AND n.primary_asset = TRUE AND n.snapshot_timestamp > a.snapshot_timestamp)
          AND NOT EXISTS (SELECT 1 FROM maven_snapshot_asset n
            WHERE n.family_id = a.family_id AND n.variant_hash = a.variant_hash
              AND n.primary_asset = TRUE AND n.snapshot_timestamp = a.snapshot_timestamp
              AND n.build_number > a.build_number)
        LIMIT 1
        """, Long.class, buildId).isEmpty();
  }

  @Override
  public List<Long> backfillPage(long afterAssetId, int limit) {
    // Primary-key keyset scan: bounded work even if Maven rows are sparse in a large mixed repository.
    return jdbc.queryForList("SELECT id FROM asset WHERE id > ? ORDER BY id LIMIT ?",
        Long.class, afterAssetId, limit);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryLockBlob(long blobId) {
    return !jdbc.queryForList("SELECT id FROM asset_blob WHERE id = ? FOR UPDATE SKIP LOCKED",
        Long.class, blobId).isEmpty();
  }
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryLockComponent(long componentId) {
    return !jdbc.queryForList("SELECT id FROM component WHERE id = ? FOR UPDATE SKIP LOCKED",
        Long.class, componentId).isEmpty();
  }

}
