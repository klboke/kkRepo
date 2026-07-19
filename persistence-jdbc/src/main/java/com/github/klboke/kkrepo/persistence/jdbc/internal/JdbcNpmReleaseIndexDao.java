package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNpmReleaseIndexDao implements NpmReleaseIndexDao {
  private static final int RELEASE_INSERT_CHUNK_SIZE = 250;
  private static final String RELEASE_INSERT_PREFIX = """
      INSERT INTO npm_release_index_entry
        (package_root_asset_id, source_blob_id, ordinal, version, version_hash,
         published_at, invalid_reason, tarball_name, tarball_name_hash)
      VALUES
      """;
  private static final String RELEASE_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbc;

  public JdbcNpmReleaseIndexDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Status> findStatus(long packageRootAssetId, long sourceBlobId) {
    return jdbc.query("""
        SELECT package_root_asset_id, source_blob_id, complete_publish_times,
               release_count, indexed_at
        FROM npm_release_index_revision
        WHERE package_root_asset_id = ? AND source_blob_id = ?
        """, (rs, row) -> new Status(
        rs.getLong("package_root_asset_id"),
        rs.getLong("source_blob_id"),
        rs.getBoolean("complete_publish_times"),
        rs.getInt("release_count"),
        nullableInstant(rs, "indexed_at")), packageRootAssetId, sourceBlobId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Snapshot> findSnapshot(long packageRootAssetId, long sourceBlobId) {
    Optional<Status> status = findStatus(packageRootAssetId, sourceBlobId);
    if (status.isEmpty()) {
      return Optional.empty();
    }
    List<Release> releases = jdbc.query("""
        SELECT ordinal, version, published_at, invalid_reason, tarball_name
        FROM npm_release_index_entry
        WHERE package_root_asset_id = ? AND source_blob_id = ?
        ORDER BY ordinal
        """, (rs, row) -> mapRelease(rs), packageRootAssetId, sourceBlobId);
    if (releases.size() != status.get().releaseCount()) {
      return Optional.empty();
    }
    return Optional.of(new Snapshot(status.get(), releases));
  }

  @Override
  public Optional<List<Release>> findByTarball(
      long packageRootAssetId,
      long sourceBlobId,
      String tarballName) {
    return findTarballPolicy(
        packageRootAssetId, sourceBlobId, tarballName, null, null)
        .map(TarballPolicy::releases);
  }

  @Override
  public Optional<TarballPolicy> findTarballPolicy(
      long packageRootAssetId,
      long sourceBlobId,
      String tarballName,
      Instant publishedAfterExclusive,
      Instant publishedAtOrBefore) {
    byte[] hash = PersistenceHashes.sha256(tarballName);
    boolean checkBoundary = publishedAfterExclusive != null
        && publishedAtOrBefore != null
        && publishedAtOrBefore.isAfter(publishedAfterExclusive);
    return jdbc.query("""
        SELECT r.package_root_asset_id, r.source_blob_id, r.complete_publish_times,
               r.release_count, r.indexed_at,
               CASE WHEN ? AND EXISTS (
                 SELECT 1
                 FROM npm_release_index_entry boundary_entry
                 WHERE boundary_entry.package_root_asset_id = r.package_root_asset_id
                   AND boundary_entry.source_blob_id = r.source_blob_id
                   AND boundary_entry.invalid_reason IS NULL
                   AND boundary_entry.published_at > ?
                   AND boundary_entry.published_at <= ?
               ) THEN TRUE ELSE FALSE END AS maturity_boundary_crossed,
               e.ordinal, e.version, e.published_at, e.invalid_reason, e.tarball_name
        FROM npm_release_index_revision r
        LEFT JOIN npm_release_index_entry e
          ON e.package_root_asset_id = r.package_root_asset_id
         AND e.source_blob_id = r.source_blob_id
         AND e.tarball_name_hash = ?
        WHERE r.package_root_asset_id = ? AND r.source_blob_id = ?
        ORDER BY e.ordinal
        """, rs -> {
      if (!rs.next()) {
        return Optional.empty();
      }
      Status status = new Status(
          rs.getLong("package_root_asset_id"),
          rs.getLong("source_blob_id"),
          rs.getBoolean("complete_publish_times"),
          rs.getInt("release_count"),
          nullableInstant(rs, "indexed_at"));
      boolean maturityBoundaryCrossed = rs.getBoolean("maturity_boundary_crossed");
      java.util.ArrayList<Release> releases = new java.util.ArrayList<>();
      do {
        if (rs.getObject("ordinal") != null) {
          Release release = mapRelease(rs);
          if (Objects.equals(tarballName, release.tarballName())) {
            releases.add(release);
          }
        }
      } while (rs.next());
      return Optional.of(new TarballPolicy(
          status, maturityBoundaryCrossed, List.copyOf(releases)));
    }, checkBoundary, nullableTimestamp(publishedAfterExclusive),
        nullableTimestamp(publishedAtOrBefore), hash, packageRootAssetId, sourceBlobId);
  }

  @Override
  public boolean hasMaturityBoundary(
      long packageRootAssetId,
      long sourceBlobId,
      Instant publishedAfterExclusive,
      Instant publishedAtOrBefore) {
    if (publishedAfterExclusive == null || publishedAtOrBefore == null
        || !publishedAtOrBefore.isAfter(publishedAfterExclusive)) {
      return false;
    }
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM npm_release_index_revision r
        JOIN npm_release_index_entry e
          ON e.package_root_asset_id = r.package_root_asset_id
         AND e.source_blob_id = r.source_blob_id
        WHERE r.package_root_asset_id = ? AND r.source_blob_id = ?
          AND e.invalid_reason IS NULL
          AND e.published_at > ? AND e.published_at <= ?
        """, Integer.class, packageRootAssetId, sourceBlobId,
        nullableTimestamp(publishedAfterExclusive), nullableTimestamp(publishedAtOrBefore));
    return count != null && count > 0;
  }

  @Override
  public Optional<Instant> findNextPublishedAfter(
      long packageRootAssetId,
      long sourceBlobId,
      Instant publishedAfterExclusive) {
    if (publishedAfterExclusive == null) {
      return Optional.empty();
    }
    return jdbc.query("""
        SELECT MIN(e.published_at) AS next_published_at
        FROM npm_release_index_revision r
        JOIN npm_release_index_entry e
          ON e.package_root_asset_id = r.package_root_asset_id
         AND e.source_blob_id = r.source_blob_id
        WHERE r.package_root_asset_id = ? AND r.source_blob_id = ?
          AND e.invalid_reason IS NULL AND e.published_at > ?
        """, rs -> {
      if (!rs.next()) {
        return Optional.<Instant>empty();
      }
      return Optional.ofNullable(nullableInstant(rs, "next_published_at"));
    }, packageRootAssetId, sourceBlobId, nullableTimestamp(publishedAfterExclusive));
  }

  @Override
  @Transactional
  public boolean replaceIfCurrent(
      long packageRootAssetId,
      long sourceBlobId,
      boolean completePublishTimes,
      List<Release> releases,
      Instant indexedAt) {
    Long currentBlobId = jdbc.query("""
        SELECT asset_blob_id FROM asset WHERE id = ? FOR UPDATE
        """, rs -> {
      if (!rs.next()) {
        return null;
      }
      Number value = (Number) rs.getObject("asset_blob_id");
      return value == null ? null : value.longValue();
    },
        packageRootAssetId);
    if (currentBlobId == null || currentBlobId != sourceBlobId) {
      return false;
    }

    List<Release> safeReleases = releases == null ? List.of() : List.copyOf(releases);
    jdbc.update(
        "DELETE FROM npm_release_index_revision WHERE package_root_asset_id = ?",
        packageRootAssetId);
    jdbc.update("""
        INSERT INTO npm_release_index_revision
          (package_root_asset_id, source_blob_id, complete_publish_times,
           release_count, indexed_at)
        VALUES (?, ?, ?, ?, ?)
        """, packageRootAssetId, sourceBlobId, completePublishTimes,
        safeReleases.size(), nullableTimestamp(indexedAt == null ? Instant.now() : indexedAt));
    insertReleases(packageRootAssetId, sourceBlobId, safeReleases);
    return true;
  }

  /** Uses portable multi-row inserts so performance does not depend on JDBC rewrite flags. */
  private void insertReleases(
      long packageRootAssetId,
      long sourceBlobId,
      List<Release> releases) {
    for (int start = 0; start < releases.size(); start += RELEASE_INSERT_CHUNK_SIZE) {
      int end = Math.min(start + RELEASE_INSERT_CHUNK_SIZE, releases.size());
      int count = end - start;
      StringBuilder sql = new StringBuilder(
          RELEASE_INSERT_PREFIX.length() + count * (RELEASE_VALUES.length() + 2));
      sql.append(RELEASE_INSERT_PREFIX);
      Object[] arguments = new Object[count * 9];
      int argument = 0;
      for (int index = start; index < end; index++) {
        if (index > start) {
          sql.append(", ");
        }
        sql.append(RELEASE_VALUES);
        Release release = releases.get(index);
        arguments[argument++] = packageRootAssetId;
        arguments[argument++] = sourceBlobId;
        arguments[argument++] = release.ordinal();
        arguments[argument++] = release.version();
        arguments[argument++] = PersistenceHashes.sha256(release.version());
        arguments[argument++] = nullableTimestamp(release.publishedAt());
        arguments[argument++] = release.invalidReason();
        arguments[argument++] = release.tarballName();
        arguments[argument++] = release.tarballName() == null
            ? null
            : PersistenceHashes.sha256(release.tarballName());
      }
      jdbc.update(sql.toString(), arguments);
    }
  }

  private static Release mapRelease(java.sql.ResultSet rs) throws SQLException {
    return new Release(
        rs.getInt("ordinal"),
        rs.getString("version"),
        nullableInstant(rs, "published_at"),
        rs.getString("invalid_reason"),
        rs.getString("tarball_name"));
  }
}
