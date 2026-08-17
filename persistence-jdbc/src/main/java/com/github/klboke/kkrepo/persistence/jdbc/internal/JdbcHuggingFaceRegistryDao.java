package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.HuggingFacePersistenceDialect;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Portable MySQL/PostgreSQL implementation of Hugging Face proxy state. */
@Repository
public class JdbcHuggingFaceRegistryDao implements HuggingFaceRegistryDao {
  private final JdbcTemplate jdbc;
  private final HuggingFacePersistenceDialect dialect;

  public JdbcHuggingFaceRegistryDao(JdbcTemplate jdbc, DatabaseDialect databaseDialect) {
    this.jdbc = jdbc;
    this.dialect = databaseDialect.huggingFace();
  }

  @Override
  @Transactional
  public ModelRevision upsertRevision(ModelRevision revision) {
    Instant now = when(revision.updatedAt());
    Instant observed = revision.observedAt() == null ? now : revision.observedAt();
    byte[] repoHash = hash("repo", revision.repoId());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE huggingface_model_revision
            SET repo_id = ?, component_id = ?, raw_metadata_asset_id = ?, author_name = ?,
                committed_at = ?, private_model = ?, gated_model = ?, library_name = ?,
                pipeline_tag = ?, license_name = ?, observed_at = ?, updated_at = ?
            WHERE repository_id = ? AND repo_id_hash = ? AND repo_id = ? AND commit_hash = ?
            """,
        new Object[] {
          revision.repoId(), revision.componentId(), revision.rawMetadataAssetId(), revision.author(),
          nullableTimestamp(revision.committedAt()), revision.privateModel(), revision.gated(),
          revision.libraryName(), revision.pipelineTag(), revision.license(),
          nullableTimestamp(observed), nullableTimestamp(now), revision.repositoryId(), repoHash,
          revision.repoId(), revision.commitHash()
        },
        """
            INSERT INTO huggingface_model_revision
              (repository_id, repo_id, repo_id_hash, commit_hash, component_id,
               raw_metadata_asset_id, author_name, committed_at, private_model, gated_model,
               library_name, pipeline_tag, license_name, observed_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          revision.repositoryId(), revision.repoId(), repoHash, revision.commitHash(),
          revision.componentId(), revision.rawMetadataAssetId(), revision.author(),
          nullableTimestamp(revision.committedAt()), revision.privateModel(), revision.gated(),
          revision.libraryName(), revision.pipelineTag(), revision.license(),
          nullableTimestamp(observed), nullableTimestamp(now)
        });
    return findRevision(revision.repositoryId(), revision.repoId(), revision.commitHash())
        .orElseThrow();
  }

  @Override
  public Optional<ModelRevision> findRevision(
      long repositoryId, String repoId, String commitHash) {
    return jdbc.query(
            """
            SELECT * FROM huggingface_model_revision
            WHERE repository_id = ? AND repo_id_hash = ? AND repo_id = ? AND commit_hash = ?
            """,
            this::mapRevision,
            repositoryId,
            hash("repo", repoId),
            repoId,
            commitHash)
        .stream().findFirst();
  }

  @Override
  @Transactional
  public RevisionRef upsertRef(RevisionRef ref) {
    Instant now = when(ref.updatedAt());
    Instant observed = ref.observedAt() == null ? now : ref.observedAt();
    long generation = ref.generation();
    Optional<RevisionRef> existing = findRef(ref.repositoryId(), ref.repoId(), ref.requestedRef());
    if (generation <= 0) {
      generation = existing
          .map(row -> row.commitHash().equals(ref.commitHash())
              ? Math.max(1L, row.generation()) : row.generation() + 1L)
          .orElse(1L);
    }
    byte[] repoHash = hash("repo", ref.repoId());
    byte[] refHash = hash("ref", ref.requestedRef());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE huggingface_revision_ref
            SET repo_id = ?, requested_ref = ?, commit_hash = ?, binding_generation = ?,
                expires_at = ?, observed_at = ?, updated_at = ?
            WHERE repository_id = ? AND repo_id_hash = ? AND repo_id = ?
              AND ref_hash = ? AND requested_ref = ?
            """,
        new Object[] {
          ref.repoId(), ref.requestedRef(), ref.commitHash(), generation,
          nullableTimestamp(ref.expiresAt()), nullableTimestamp(observed), nullableTimestamp(now),
          ref.repositoryId(), repoHash, ref.repoId(), refHash, ref.requestedRef()
        },
        """
            INSERT INTO huggingface_revision_ref
              (repository_id, repo_id, repo_id_hash, requested_ref, ref_hash, commit_hash,
               binding_generation, expires_at, observed_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          ref.repositoryId(), ref.repoId(), repoHash, ref.requestedRef(), refHash,
          ref.commitHash(), generation, nullableTimestamp(ref.expiresAt()),
          nullableTimestamp(observed), nullableTimestamp(now)
        });
    return findRef(ref.repositoryId(), ref.repoId(), ref.requestedRef()).orElseThrow();
  }

  @Override
  public Optional<RevisionRef> findRef(
      long repositoryId, String repoId, String requestedRef) {
    return jdbc.query(
            """
            SELECT * FROM huggingface_revision_ref
            WHERE repository_id = ? AND repo_id_hash = ? AND repo_id = ?
              AND ref_hash = ? AND requested_ref = ?
            """,
            this::mapRef,
            repositoryId,
            hash("repo", repoId),
            repoId,
            hash("ref", requestedRef),
            requestedRef)
        .stream().findFirst();
  }

  @Override
  @Transactional
  public ModelFile upsertFileMetadata(ModelFile file) {
    Instant now = when(file.updatedAt());
    byte[] repoHash = hash("repo", file.repoId());
    byte[] pathHash = hash("path", file.path());
    String state = file.state() == null || file.state().isBlank() ? FILE_DISCOVERED : file.state();
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE huggingface_file
            SET revision_id = ?, repo_id = ?, component_id = COALESCE(?, component_id),
                git_oid = ?, lfs_sha256 = ?, xet_hash = ?, expected_size = ?,
                content_type = COALESCE(?, content_type), file_kind = ?, updated_at = ?
            WHERE repository_id = ? AND repo_id_hash = ? AND repo_id = ?
              AND commit_hash = ? AND path_hash = ? AND file_path = ?
            """,
        new Object[] {
          file.revisionId(), file.repoId(), file.componentId(), file.gitOid(), file.lfsSha256(),
          file.xetHash(), file.expectedSize(), file.contentType(), file.fileKind(),
          nullableTimestamp(now), file.repositoryId(), repoHash, file.repoId(), file.commitHash(),
          pathHash, file.path()
        },
        """
            INSERT INTO huggingface_file
              (revision_id, repository_id, repo_id, repo_id_hash, commit_hash, file_path,
               path_hash, asset_id, component_id, git_oid, lfs_sha256, xet_hash, expected_size,
               internal_sha256, content_type, file_kind, file_state, fencing_token, failure_code,
               next_attempt_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          file.revisionId(), file.repositoryId(), file.repoId(), repoHash, file.commitHash(),
          file.path(), pathHash, file.assetId(), file.componentId(), file.gitOid(),
          file.lfsSha256(), file.xetHash(), file.expectedSize(), file.internalSha256(),
          file.contentType(), file.fileKind(), state, file.fencingToken(), file.failureCode(),
          nullableTimestamp(file.nextAttemptAt()), nullableTimestamp(now)
        });
    // Discovery refreshes may enrich immutable metadata, but must never reset an in-flight or
    // READY row. Migration is the one caller that supplies a complete READY binding; apply that
    // binding explicitly so an interrupted migration can resume from a prior DISCOVERED/FAILED
    // row without leaving the registry inconsistent with the already persisted asset.
    if (FILE_READY.equals(state)) {
      if (file.assetId() == null || file.componentId() == null
          || file.internalSha256() == null || file.internalSha256().isBlank()) {
        throw new IllegalArgumentException(
            "A READY Hugging Face file requires asset, component, and SHA-256 bindings");
      }
      jdbc.update(
          """
          UPDATE huggingface_file
          SET asset_id = ?, component_id = ?, internal_sha256 = ?, content_type = ?,
              file_state = ?, fencing_token = ?, failure_code = NULL, next_attempt_at = NULL,
              updated_at = ?
          WHERE repository_id = ? AND repo_id_hash = ? AND repo_id = ?
            AND commit_hash = ? AND path_hash = ? AND file_path = ?
          """,
          file.assetId(), file.componentId(), file.internalSha256(), file.contentType(),
          FILE_READY, file.fencingToken(), nullableTimestamp(now), file.repositoryId(), repoHash,
          file.repoId(), file.commitHash(), pathHash, file.path());
    }
    return findFile(file.repositoryId(), file.repoId(), file.commitHash(), file.path())
        .orElseThrow();
  }

  @Override
  public Optional<ModelFile> findFile(
      long repositoryId, String repoId, String commitHash, String path) {
    return jdbc.query(
            """
            SELECT * FROM huggingface_file
            WHERE repository_id = ? AND repo_id_hash = ? AND repo_id = ?
              AND commit_hash = ? AND path_hash = ? AND file_path = ?
            """,
            this::mapFile,
            repositoryId,
            hash("repo", repoId),
            repoId,
            commitHash,
            hash("path", path),
            path)
        .stream().findFirst();
  }

  @Override
  public boolean markFileFetching(long fileId, long fencingToken, Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE huggingface_file
        SET file_state = ?, fencing_token = ?, failure_code = NULL, next_attempt_at = NULL,
            updated_at = ?
        WHERE id = ? AND fencing_token <= ? AND (asset_id IS NULL OR file_state <> ?)
        """,
        FILE_FETCHING, fencingToken, nullableTimestamp(when(updatedAt)), fileId, fencingToken,
        FILE_READY) > 0;
  }

  @Override
  public boolean updateFetchingFileMetadata(
      long fileId,
      long fencingToken,
      String gitOid,
      String lfsSha256,
      String xetHash,
      Long expectedSize,
      String contentType,
      String fileKind,
      Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE huggingface_file
        SET git_oid = ?, lfs_sha256 = ?, xet_hash = ?, expected_size = ?,
            content_type = ?, file_kind = ?, updated_at = ?
        WHERE id = ? AND fencing_token = ? AND file_state = ?
        """,
        bounded(gitOid, 128), bounded(lfsSha256, 64), bounded(xetHash, 128), expectedSize,
        bounded(contentType, 255), bounded(fileKind, 32), nullableTimestamp(when(updatedAt)),
        fileId, fencingToken, FILE_FETCHING) > 0;
  }

  @Override
  public boolean markFileReady(
      long fileId, long fencingToken, long assetId, long componentId,
      String internalSha256, String contentType, Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE huggingface_file
        SET asset_id = ?, component_id = ?, internal_sha256 = ?, content_type = ?,
            file_state = ?, failure_code = NULL, next_attempt_at = NULL, updated_at = ?
        WHERE id = ? AND fencing_token = ? AND file_state = ?
        """,
        assetId, componentId, internalSha256, contentType, FILE_READY,
        nullableTimestamp(when(updatedAt)), fileId, fencingToken, FILE_FETCHING) > 0;
  }

  @Override
  public boolean markFileFailed(
      long fileId, long fencingToken, String failureCode, Instant nextAttemptAt, Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE huggingface_file
        SET file_state = ?, failure_code = ?, next_attempt_at = ?, updated_at = ?
        WHERE id = ? AND fencing_token = ? AND file_state = ?
        """,
        FILE_FAILED, bounded(failureCode, 64), nullableTimestamp(nextAttemptAt),
        nullableTimestamp(when(updatedAt)), fileId, fencingToken, FILE_FETCHING) > 0;
  }

  @Override
  @Transactional
  public ApiCacheEntry upsertApiCache(ApiCacheEntry entry) {
    Instant now = when(entry.updatedAt());
    String query = empty(entry.query());
    String request = empty(entry.requestFingerprint());
    byte[] routeHash = hash("route", entry.route());
    byte[] queryHash = hash("query", query);
    byte[] requestHash = hash("request", request);
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE huggingface_api_cache
            SET route_path = ?, query_string = ?, request_fingerprint = ?, raw_asset_id = ?,
                derived_asset_id = ?, upstream_etag = ?, derived_etag = ?, next_link = ?,
                transform_version = ?, expires_at = ?, updated_at = ?
            WHERE repository_id = ? AND route_hash = ? AND route_path = ?
              AND query_hash = ? AND query_string = ? AND request_hash = ?
              AND request_fingerprint = ?
            """,
        new Object[] {
          entry.route(), query, request, entry.rawAssetId(), entry.derivedAssetId(),
          entry.upstreamEtag(), entry.derivedEtag(), entry.nextLink(), entry.transformVersion(),
          nullableTimestamp(entry.expiresAt()), nullableTimestamp(now), entry.repositoryId(),
          routeHash, entry.route(), queryHash, query, requestHash, request
        },
        """
            INSERT INTO huggingface_api_cache
              (repository_id, route_path, route_hash, query_string, query_hash,
               request_fingerprint, request_hash, raw_asset_id, derived_asset_id, upstream_etag,
               derived_etag, next_link, transform_version, expires_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          entry.repositoryId(), entry.route(), routeHash, query, queryHash, request, requestHash,
          entry.rawAssetId(), entry.derivedAssetId(), entry.upstreamEtag(), entry.derivedEtag(),
          entry.nextLink(), entry.transformVersion(), nullableTimestamp(entry.expiresAt()),
          nullableTimestamp(now)
        });
    return findApiCache(entry.repositoryId(), entry.route(), query, request).orElseThrow();
  }

  @Override
  public Optional<ApiCacheEntry> findApiCache(
      long repositoryId, String route, String query, String requestFingerprint) {
    String normalizedQuery = empty(query);
    String normalizedRequest = empty(requestFingerprint);
    return jdbc.query(
            """
            SELECT * FROM huggingface_api_cache
            WHERE repository_id = ? AND route_hash = ? AND route_path = ?
              AND query_hash = ? AND query_string = ? AND request_hash = ?
              AND request_fingerprint = ?
            """,
            this::mapApiCache,
            repositoryId, hash("route", route), route,
            hash("query", normalizedQuery), normalizedQuery,
            hash("request", normalizedRequest), normalizedRequest)
        .stream().findFirst();
  }

  @Override
  public void upsertRouteProjection(RouteProjection projection) {
    Instant now = when(projection.updatedAt());
    byte[] routeHash = hash("route", projection.route());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE huggingface_route_projection
            SET route_path = ?, file_id = ?, requested_ref = ?, ref_generation = ?, updated_at = ?
            WHERE repository_id = ? AND route_hash = ? AND route_path = ?
            """,
        new Object[] {
          projection.route(), projection.fileId(), projection.requestedRef(),
          projection.refGeneration(), nullableTimestamp(now), projection.repositoryId(),
          routeHash, projection.route()
        },
        """
            INSERT INTO huggingface_route_projection
              (repository_id, route_path, route_hash, file_id, requested_ref, ref_generation,
               updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          projection.repositoryId(), projection.route(), routeHash, projection.fileId(),
          projection.requestedRef(), projection.refGeneration(), nullableTimestamp(now)
        });
  }

  @Override
  public Optional<RouteProjection> findRouteProjection(long repositoryId, String route) {
    return jdbc.query(
            """
            SELECT * FROM huggingface_route_projection
            WHERE repository_id = ? AND route_hash = ? AND route_path = ?
            """,
            (rs, row) -> new RouteProjection(
                rs.getLong("repository_id"), rs.getString("route_path"), rs.getLong("file_id"),
                rs.getString("requested_ref"), rs.getLong("ref_generation"),
                nullableInstant(rs, "updated_at")),
            repositoryId, hash("route", route), route)
        .stream().findFirst();
  }

  @Override
  public List<ModelFile> listRevisionFiles(long revisionId, long afterId, int limit) {
    return jdbc.query(
        """
        SELECT * FROM huggingface_file
        WHERE revision_id = ? AND id > ? ORDER BY id LIMIT ?
        """,
        this::mapFile,
        revisionId, Math.max(0, afterId), Math.min(Math.max(1, limit), 1_000));
  }

  @Override
  public boolean isRevisionProtected(long repositoryId, long componentId) {
    Integer count = jdbc.queryForObject(
        """
        SELECT COUNT(*)
        FROM huggingface_model_revision revision
        WHERE revision.repository_id = ? AND revision.component_id = ?
          AND (
            EXISTS (
              SELECT 1 FROM huggingface_revision_ref ref
              WHERE ref.repository_id = revision.repository_id
                AND ref.repo_id_hash = revision.repo_id_hash
                AND ref.repo_id = revision.repo_id
                AND ref.commit_hash = revision.commit_hash
            )
            OR EXISTS (
              SELECT 1 FROM huggingface_file file
              WHERE file.revision_id = revision.id AND file.file_state = ?
            )
          )
        """,
        Integer.class,
        repositoryId,
        componentId,
        FILE_FETCHING);
    return count != null && count > 0;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<FetchLease> tryAcquireLease(
      long repositoryId, String fetchKey, String owner, Instant expiresAt) {
    Instant now = Instant.now();
    if (expiresAt == null || !expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Lease expiry must be in the future");
    }
    byte[] fetchHash = hash("fetch", fetchKey);
    String storedFetchKey = storedFetchKey(fetchKey);
    int updated = jdbc.update(
        """
        UPDATE proxy_fetch_lease
        SET fetch_key = ?, owner = ?, fencing_token = fencing_token + 1,
            attempt_count = attempt_count + 1, expires_at = ?, updated_at = ?
        WHERE repository_id = ? AND fetch_key_hash = ? AND fetch_key = ? AND expires_at < ?
        """,
        storedFetchKey, owner, nullableTimestamp(expiresAt), nullableTimestamp(now), repositoryId,
        fetchHash, storedFetchKey, nullableTimestamp(now));
    if (updated == 0) {
      jdbc.update(
          dialect.insertFetchLeaseIfAbsentSql(), repositoryId, storedFetchKey, fetchHash, owner,
          nullableTimestamp(expiresAt), nullableTimestamp(now));
    }
    return findLease(repositoryId, fetchKey)
        .filter(lease -> lease.owner().equals(owner) && lease.expiresAt().isAfter(now));
  }

  @Override
  public boolean renewLease(
      long repositoryId, String fetchKey, String owner, long fencingToken, Instant expiresAt) {
    Instant now = Instant.now();
    if (expiresAt == null || !expiresAt.isAfter(now)) return false;
    return jdbc.update(
        """
        UPDATE proxy_fetch_lease SET expires_at = ?, updated_at = ?
        WHERE repository_id = ? AND fetch_key_hash = ? AND fetch_key = ? AND owner = ?
          AND fencing_token = ? AND expires_at >= ?
        """,
        nullableTimestamp(expiresAt), nullableTimestamp(now), repositoryId,
        hash("fetch", fetchKey), storedFetchKey(fetchKey), owner, fencingToken,
        nullableTimestamp(now)) > 0;
  }

  @Override
  public void releaseLease(
      long repositoryId, String fetchKey, String owner, long fencingToken) {
    jdbc.update(
        """
        UPDATE proxy_fetch_lease SET expires_at = ?, updated_at = ?
        WHERE repository_id = ? AND fetch_key_hash = ? AND fetch_key = ?
          AND owner = ? AND fencing_token = ?
        """,
        nullableTimestamp(Instant.EPOCH), nullableTimestamp(Instant.now()), repositoryId,
        hash("fetch", fetchKey), storedFetchKey(fetchKey), owner, fencingToken);
  }

  @Override
  @Transactional
  public int deleteExpiredLeases(Instant expiredBefore, int limit) {
    // Expired rows are durable per-key fencing watermarks. Deleting one would let a later insert
    // restart at token 1 and allow a superseded writer to publish. Repository deletion removes the
    // bounded set transactionally; ordinary release only expires the ownership.
    return 0;
  }

  @Override
  @Transactional
  public void deleteRepositoryState(long repositoryId) {
    jdbc.update("DELETE FROM proxy_fetch_lease WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM huggingface_api_cache WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM huggingface_route_projection WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM huggingface_file WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM huggingface_revision_ref WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM huggingface_model_revision WHERE repository_id = ?", repositoryId);
  }

  private Optional<FetchLease> findLease(long repositoryId, String fetchKey) {
    return jdbc.query(
            """
            SELECT * FROM proxy_fetch_lease
            WHERE repository_id = ? AND fetch_key_hash = ? AND fetch_key = ?
            """,
            this::mapLease,
            repositoryId, hash("fetch", fetchKey), storedFetchKey(fetchKey))
        .stream().findFirst();
  }

  private ModelRevision mapRevision(ResultSet rs, int row) throws SQLException {
    return new ModelRevision(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getString("repo_id"),
        rs.getString("commit_hash"), nullableLong(rs, "component_id"),
        nullableLong(rs, "raw_metadata_asset_id"), rs.getString("author_name"),
        nullableInstant(rs, "committed_at"), rs.getBoolean("private_model"),
        rs.getBoolean("gated_model"), rs.getString("library_name"),
        rs.getString("pipeline_tag"), rs.getString("license_name"),
        nullableInstant(rs, "observed_at"), nullableInstant(rs, "updated_at"));
  }

  private RevisionRef mapRef(ResultSet rs, int row) throws SQLException {
    return new RevisionRef(
        rs.getLong("repository_id"), rs.getString("repo_id"), rs.getString("requested_ref"),
        rs.getString("commit_hash"), rs.getLong("binding_generation"),
        nullableInstant(rs, "expires_at"), nullableInstant(rs, "observed_at"),
        nullableInstant(rs, "updated_at"));
  }

  private ModelFile mapFile(ResultSet rs, int row) throws SQLException {
    return new ModelFile(
        rs.getLong("id"), rs.getLong("revision_id"), rs.getLong("repository_id"),
        rs.getString("repo_id"), rs.getString("commit_hash"), rs.getString("file_path"),
        nullableLong(rs, "asset_id"), nullableLong(rs, "component_id"), rs.getString("git_oid"),
        rs.getString("lfs_sha256"), rs.getString("xet_hash"), nullableLong(rs, "expected_size"),
        rs.getString("internal_sha256"), rs.getString("content_type"),
        rs.getString("file_kind"), rs.getString("file_state"), rs.getLong("fencing_token"),
        rs.getString("failure_code"), nullableInstant(rs, "next_attempt_at"),
        nullableInstant(rs, "updated_at"));
  }

  private ApiCacheEntry mapApiCache(ResultSet rs, int row) throws SQLException {
    return new ApiCacheEntry(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getString("route_path"),
        rs.getString("query_string"), rs.getString("request_fingerprint"),
        nullableLong(rs, "raw_asset_id"), nullableLong(rs, "derived_asset_id"),
        rs.getString("upstream_etag"), rs.getString("derived_etag"), rs.getString("next_link"),
        rs.getInt("transform_version"), nullableInstant(rs, "expires_at"),
        nullableInstant(rs, "updated_at"));
  }

  private FetchLease mapLease(ResultSet rs, int row) throws SQLException {
    return new FetchLease(
        rs.getLong("repository_id"), rs.getString("fetch_key"), rs.getString("owner"),
        rs.getLong("fencing_token"), nullableInstant(rs, "expires_at"),
        nullableInstant(rs, "updated_at"));
  }

  private static Instant when(Instant value) {
    return value == null ? Instant.now() : value;
  }

  private static byte[] hash(String domain, String value) {
    return PersistenceHashes.sha256("huggingface", domain, empty(value));
  }

  private static String empty(String value) {
    return value == null ? "" : value;
  }

  /** PostgreSQL text rejects NUL while model paths may contain any protocol-safe Unicode. */
  private static String storedFetchKey(String value) {
    return "b64:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(empty(value).getBytes(StandardCharsets.UTF_8));
  }

  private static String bounded(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }
}
