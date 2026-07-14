package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.BrowseImageRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.BrowseReferenceRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.CleanupManifestCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.CleanupPolicyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.CleanupTagCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao.DeletedManifest;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDockerRegistryDao implements com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao {
  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<DockerManifestRecord> manifestMapper;
  private final RowMapper<DockerTagRecord> tagMapper;
  private final RowMapper<DockerManifestReferenceRecord> referenceMapper;

  public JdbcDockerRegistryDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.manifestMapper = (rs, rowNum) -> new DockerManifestRecord(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        rs.getString("image_name"),
        rs.getBytes("image_name_hash"),
        rs.getString("digest_algorithm"),
        rs.getString("digest"),
        rs.getBytes("digest_hash"),
        rs.getString("media_type"),
        rs.getString("artifact_type"),
        rs.getString("subject_digest"),
        rs.getBytes("subject_digest_hash"),
        rs.getLong("asset_id"),
        rs.getLong("size"),
        rs.getString("pushed_by"),
        rs.getString("pushed_by_ip"),
        nullableInstant(rs, "deleted_at"),
        jsonColumns.read(rs.getString("attributes_json")),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.tagMapper = (rs, rowNum) -> new DockerTagRecord(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        rs.getString("image_name"),
        rs.getBytes("image_name_hash"),
        rs.getString("tag"),
        rs.getBytes("tag_hash"),
        rs.getLong("manifest_id"),
        rs.getString("manifest_digest"),
        rs.getString("pushed_by"),
        rs.getString("pushed_by_ip"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.referenceMapper = (rs, rowNum) -> new DockerManifestReferenceRecord(
        rs.getLong("id"),
        rs.getLong("manifest_id"),
        rs.getLong("repository_id"),
        rs.getString("image_name"),
        rs.getString("digest"),
        rs.getBytes("digest_hash"),
        rs.getString("reference_kind"),
        rs.getString("media_type"),
        rs.getObject("size") == null ? null : rs.getLong("size"),
        jsonColumns.read(rs.getString("platform_json")),
        jsonColumns.read(rs.getString("annotations_json")));
  }

  public Optional<DockerManifestRecord> findManifestByDigest(
      long repositoryId, String imageName, String digest) {
    return jdbcTemplate.query("""
        SELECT *
        FROM docker_manifest
        WHERE repository_id = ?
          AND image_name_hash = ?
          AND digest_hash = ?
          AND deleted_at IS NULL
        """, manifestMapper, repositoryId, hash(imageName), hash(digest)).stream().findFirst();
  }

  public Optional<DockerManifestRecord> findManifestByTag(
      long repositoryId, String imageName, String tag) {
    return jdbcTemplate.query("""
        SELECT m.*
        FROM docker_tag t
        JOIN docker_manifest m ON m.id = t.manifest_id
        WHERE t.repository_id = ?
          AND t.image_name_hash = ?
          AND t.tag_hash = ?
          AND m.deleted_at IS NULL
        """, manifestMapper, repositoryId, hash(imageName), hash(tag)).stream().findFirst();
  }

  public Optional<DockerManifestRecord> findManifestByReference(
      long repositoryId, String imageName, String reference) {
    if (reference != null && reference.contains(":")) {
      Optional<DockerManifestRecord> byDigest = findManifestByDigest(repositoryId, imageName, reference);
      if (byDigest.isPresent()) {
        return byDigest;
      }
    }
    return findManifestByTag(repositoryId, imageName, reference);
  }

  public Map<Long, DockerManifestRecord> findManifestsByAssetIds(Collection<Long> assetIds) {
    if (assetIds == null || assetIds.isEmpty()) {
      return Map.of();
    }
    List<Long> ids = assetIds.stream().filter(id -> id != null).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    List<Object> args = new ArrayList<>(ids);
    Map<Long, DockerManifestRecord> byAssetId = new LinkedHashMap<>();
    jdbcTemplate.query("""
        SELECT *
        FROM docker_manifest
        WHERE asset_id IN (""" + placeholders(ids.size()) + """
          )
          AND deleted_at IS NULL
        """, manifestMapper, args.toArray()).forEach(row -> byAssetId.put(row.assetId(), row));
    return byAssetId;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public DockerManifestRecord upsertManifest(DockerManifestRecord record) {
    OptionalLong inserted = tryInsertManifest(record);
    if (inserted.isPresent()) {
      return findManifestById(inserted.getAsLong()).orElseThrow();
    }
    jdbcTemplate.update("""
        UPDATE docker_manifest
        SET media_type = ?, artifact_type = ?, subject_digest = ?, subject_digest_hash = ?,
            asset_id = ?, size = ?, pushed_by = ?, pushed_by_ip = ?, deleted_at = NULL,
            attributes_json = ?
        WHERE repository_id = ?
          AND image_name_hash = ?
          AND digest_hash = ?
        """,
        record.mediaType(),
        record.artifactType(),
        record.subjectDigest(),
        record.subjectDigestHash(),
        record.assetId(),
        record.size(),
        record.pushedBy(),
        record.pushedByIp(),
        jsonColumns.parameter(record.attributes()),
        record.repositoryId(),
        record.imageNameHash(),
        record.digestHash());
    return findManifestByDigest(record.repositoryId(), record.imageName(), record.digest())
        .orElseThrow(() -> new DuplicateKeyException("Docker manifest conflict was not visible"));
  }

  private OptionalLong tryInsertManifest(DockerManifestRecord record) {
    return JdbcInserts.tryInsert(jdbcTemplate, """
        INSERT INTO docker_manifest
          (repository_id, image_name, image_name_hash, digest_algorithm, digest, digest_hash,
           media_type, artifact_type, subject_digest, subject_digest_hash, asset_id, size,
           pushed_by, pushed_by_ip, deleted_at, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, record.repositoryId());
      ps.setString(2, record.imageName());
      ps.setBytes(3, record.imageNameHash());
      ps.setString(4, record.digestAlgorithm());
      ps.setString(5, record.digest());
      ps.setBytes(6, record.digestHash());
      ps.setString(7, record.mediaType());
      ps.setString(8, record.artifactType());
      ps.setString(9, record.subjectDigest());
      ps.setBytes(10, record.subjectDigestHash());
      ps.setLong(11, record.assetId());
      ps.setLong(12, record.size());
      ps.setString(13, record.pushedBy());
      ps.setString(14, record.pushedByIp());
      ps.setTimestamp(15, nullableTimestamp(record.deletedAt()));
      jsonColumns.bind(ps, 16, record.attributes());
    });
  }

  public Optional<DockerManifestRecord> findManifestById(long id) {
    return jdbcTemplate.query("SELECT * FROM docker_manifest WHERE id = ?", manifestMapper, id)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void replaceManifestReferences(long manifestId, List<DockerManifestReferenceRecord> references) {
    jdbcTemplate.update("DELETE FROM docker_manifest_reference WHERE manifest_id = ?", manifestId);
    if (references == null || references.isEmpty()) {
      return;
    }
    List<Object[]> args = references.stream()
        .map(record -> new Object[] {
            manifestId,
            record.repositoryId(),
            record.imageName(),
            record.digest(),
            record.digestHash(),
            record.referenceKind(),
            record.mediaType(),
            record.size(),
            jsonColumns.parameter(record.platform()),
            jsonColumns.parameter(record.annotations())
        })
        .toList();
    jdbcTemplate.batchUpdate("""
        INSERT INTO docker_manifest_reference
          (manifest_id, repository_id, image_name, digest, digest_hash, reference_kind,
           media_type, size, platform_json, annotations_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, args);
  }

  public List<DockerManifestReferenceRecord> listReferences(long manifestId) {
    return jdbcTemplate.query("""
        SELECT *
        FROM docker_manifest_reference
        WHERE manifest_id = ?
        ORDER BY id
        """, referenceMapper, manifestId);
  }

  public List<DockerManifestRecord> listReferrers(
      long repositoryId, String subjectDigest, String artifactType) {
    String artifactPredicate = artifactType == null || artifactType.isBlank()
        ? ""
        : " AND artifact_type = ?";
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    args.add(hash(subjectDigest));
    if (!artifactPredicate.isEmpty()) {
      args.add(artifactType);
    }
    return jdbcTemplate.query("""
        SELECT *
        FROM docker_manifest
        WHERE repository_id = ?
          AND subject_digest_hash = ?
          AND deleted_at IS NULL
        """ + artifactPredicate + """
        ORDER BY image_name, digest
        """, manifestMapper, args.toArray());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void upsertTag(DockerTagRecord record) {
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE docker_tag
        SET manifest_id = ?, manifest_digest = ?, pushed_by = ?, pushed_by_ip = ?
        WHERE repository_id = ? AND image_name_hash = ? AND tag_hash = ?
        """,
        new Object[]{record.manifestId(), record.manifestDigest(), record.pushedBy(),
            record.pushedByIp(), record.repositoryId(), record.imageNameHash(), record.tagHash()},
        """
        INSERT INTO docker_tag
          (repository_id, image_name, image_name_hash, tag, tag_hash, manifest_id,
           manifest_digest, pushed_by, pushed_by_ip)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[]{record.repositoryId(), record.imageName(), record.imageNameHash(), record.tag(),
            record.tagHash(), record.manifestId(), record.manifestDigest(), record.pushedBy(),
            record.pushedByIp()});
  }

  public List<String> listTags(long repositoryId, String imageName, String last, int limit) {
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    args.add(hash(imageName));
    String after = "";
    if (last != null && !last.isBlank()) {
      after = " AND tag > ?";
      args.add(last);
    }
    args.add(Math.max(1, limit));
    return jdbcTemplate.queryForList("""
        SELECT tag
        FROM docker_tag
        WHERE repository_id = ?
          AND image_name_hash = ?
        """ + after + """
        ORDER BY tag
        LIMIT ?
        """, String.class, args.toArray());
  }

  public boolean imageExists(long repositoryId, String imageName) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM docker_manifest
        WHERE repository_id = ?
          AND image_name_hash = ?
          AND deleted_at IS NULL
        """, Integer.class, repositoryId, hash(imageName));
    return count != null && count > 0;
  }

  public int countTags(long repositoryId, String imageName) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM docker_tag
        WHERE repository_id = ?
          AND image_name_hash = ?
        """, Integer.class, repositoryId, hash(imageName));
    return count == null ? 0 : count;
  }

  public boolean tagExists(long repositoryId, String imageName, String tag) {
    Integer count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM docker_tag
        WHERE repository_id = ?
          AND image_name_hash = ?
          AND tag_hash = ?
        """, Integer.class, repositoryId, hash(imageName), hash(tag));
    return count != null && count > 0;
  }

  public List<DockerTagRecord> listTagsForManifest(long manifestId) {
    return jdbcTemplate.query("""
        SELECT *
        FROM docker_tag
        WHERE manifest_id = ?
        ORDER BY tag
        """, tagMapper, manifestId);
  }

  public int deleteTag(long repositoryId, String imageName, String tag) {
    return jdbcTemplate.update("""
        DELETE FROM docker_tag
        WHERE repository_id = ?
          AND image_name_hash = ?
          AND tag_hash = ?
        """, repositoryId, hash(imageName), hash(tag));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public DeletedManifest deleteManifest(long repositoryId, String imageName, String digest) {
    Optional<DockerManifestRecord> manifest = findManifestByDigest(repositoryId, imageName, digest);
    if (manifest.isEmpty()) {
      return DeletedManifest.notFound();
    }
    long id = manifest.get().id();
    Long assetBlobId = jdbcTemplate.queryForObject(
        "SELECT asset_blob_id FROM asset WHERE id = ?",
        (rs, rowNum) -> nullableLong(rs, "asset_blob_id"),
        manifest.get().assetId());
    jdbcTemplate.update("DELETE FROM docker_tag WHERE manifest_id = ?", id);
    int deleted = jdbcTemplate.update("""
        UPDATE docker_manifest
        SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP)
        WHERE id = ?
        """, id);
    return new DeletedManifest(deleted, manifest.get().assetId(), assetBlobId);
  }

  public boolean referencedDigestExists(long repositoryId, String imageName, String digest) {
    byte[] digestHash = hash(digest);
    String sha256 = sha256Hex(digest);
    String storedDigest = jsonColumns.extractText("a.attributes_json", "docker", "digest");
    String assetSql = """
        SELECT COUNT(*)
        FROM asset a
        JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE a.repository_id = ?
          AND a.format = 'docker'
          AND a.kind = 'BLOB'
          AND b.deleted_at IS NULL
          AND (
            %s = ?
            OR (? IS NOT NULL AND b.sha256 = ?)
          )
        """.formatted(storedDigest);
    Integer assetCount = jdbcTemplate.queryForObject(
        assetSql, Integer.class, repositoryId, digest, sha256, sha256);
    if (assetCount != null && assetCount > 0) {
      return true;
    }
    Integer manifestCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM docker_manifest
        WHERE repository_id = ?
          AND image_name_hash = ?
          AND digest_hash = ?
          AND deleted_at IS NULL
        """, Integer.class, repositoryId, hash(imageName), digestHash);
    return manifestCount != null && manifestCount > 0;
  }

  public boolean imageReferencesDigest(long repositoryId, String imageName, String digest) {
    Integer referenceCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM docker_manifest_reference r
        JOIN docker_manifest m ON m.id = r.manifest_id
        WHERE r.repository_id = ?
          AND r.image_name = ?
          AND r.digest_hash = ?
          AND m.deleted_at IS NULL
        """, Integer.class, repositoryId, imageName, hash(digest));
    if (referenceCount != null && referenceCount > 0) {
      return true;
    }
    Integer manifestCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM docker_manifest
        WHERE repository_id = ?
          AND image_name_hash = ?
          AND digest_hash = ?
          AND deleted_at IS NULL
        """, Integer.class, repositoryId, hash(imageName), hash(digest));
    return manifestCount != null && manifestCount > 0;
  }

  public OptionalLong findUnreferencedBlobAssetIdForCleanup(
      long repositoryId, long afterAssetId, int maxCandidates, Instant updatedBefore) {
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    args.add(Math.max(0, afterAssetId));
    String updatedBeforePredicate = "";
    if (updatedBefore != null) {
      updatedBeforePredicate = "  AND a.last_updated_at < ?\n";
      args.add(nullableTimestamp(updatedBefore));
    }
    args.add(Math.max(1, maxCandidates));
    String storedDigest = jsonColumns.extractText("a.attributes_json", "docker", "digest");
    String cleanupSql = """
        SELECT a.id
        FROM asset a
        JOIN asset_blob b ON b.id = a.asset_blob_id
        WHERE a.repository_id = ?
          AND a.id > ?
          AND a.format = 'docker'
          AND a.kind = 'BLOB'
          AND b.deleted_at IS NULL
        %s
          AND NOT EXISTS (
            SELECT 1
            FROM docker_manifest_reference r
            JOIN docker_manifest m ON m.id = r.manifest_id
            WHERE r.repository_id = a.repository_id
              AND m.deleted_at IS NULL
              AND (
                r.digest = %s
                OR (
                  b.sha256 IS NOT NULL
                  AND r.digest = CONCAT('sha256:', b.sha256)
                )
              )
          )
          AND NOT EXISTS (
            SELECT 1
            FROM docker_manifest m
            WHERE m.repository_id = a.repository_id
              AND m.deleted_at IS NULL
              AND (
                m.digest = %s
                OR (
                  b.sha256 IS NOT NULL
                  AND m.digest = CONCAT('sha256:', b.sha256)
                )
              )
          )
        ORDER BY a.id
        LIMIT ?
        """.formatted(updatedBeforePredicate, storedDigest, storedDigest);
    return jdbcTemplate.queryForList(cleanupSql, Long.class, args.toArray())
        .stream()
        .findFirst()
        .map(OptionalLong::of)
        .orElseGet(OptionalLong::empty);
  }

  public List<CleanupPolicyRecord> listCleanupPolicies(long repositoryId) {
    return jdbcTemplate.query("""
        SELECT p.id, p.name, p.criteria_json
        FROM repository_cleanup_policy rp
        JOIN cleanup_policy p ON p.id = rp.cleanup_policy_id
        WHERE rp.repository_id = ?
          AND p.format = 'docker'
        ORDER BY p.name
        """, (rs, rowNum) -> new CleanupPolicyRecord(
            rs.getLong("id"),
            rs.getString("name"),
            jsonColumns.read(rs.getString("criteria_json"))),
        repositoryId);
  }

  public List<CleanupTagCandidate> listTagCleanupCandidates(long repositoryId, int limit) {
    return jdbcTemplate.query("""
        SELECT t.image_name, t.tag
        FROM docker_tag t
        JOIN docker_manifest m ON m.id = t.manifest_id
        WHERE t.repository_id = ?
          AND m.deleted_at IS NULL
        ORDER BY t.updated_at, t.id
        LIMIT ?
        """, (rs, rowNum) -> new CleanupTagCandidate(
            rs.getString("image_name"),
            rs.getString("tag")),
        repositoryId,
        Math.max(1, limit));
  }

  public List<CleanupManifestCandidate> listManifestCleanupCandidates(
      long repositoryId,
      boolean untaggedOnly,
      Instant lastDownloadedBefore,
      Instant lastUpdatedBefore,
      int limit) {
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    StringBuilder predicate = new StringBuilder();
    if (untaggedOnly) {
      predicate.append("""
          AND NOT EXISTS (
            SELECT 1
            FROM docker_tag t
            WHERE t.manifest_id = m.id
          )
          """);
    }
    if (lastDownloadedBefore != null) {
      predicate.append("  AND (a.last_downloaded_at IS NULL OR a.last_downloaded_at < ?)\n");
      args.add(nullableTimestamp(lastDownloadedBefore));
    }
    if (lastUpdatedBefore != null) {
      predicate.append("  AND m.updated_at < ?\n");
      args.add(nullableTimestamp(lastUpdatedBefore));
    }
    args.add(Math.max(1, limit));
    return jdbcTemplate.query("""
        SELECT m.image_name, m.digest
        FROM docker_manifest m
        JOIN asset a ON a.id = m.asset_id
        WHERE m.repository_id = ?
          AND m.deleted_at IS NULL
        """ + predicate + """
        ORDER BY m.updated_at, m.id
        LIMIT ?
        """, (rs, rowNum) -> new CleanupManifestCandidate(
            rs.getString("image_name"),
            rs.getString("digest")),
        args.toArray());
  }

  public List<String> listCatalog(long repositoryId, String last, int limit) {
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    String after = "";
    if (last != null && !last.isBlank()) {
      after = " AND image_name > ?";
      args.add(last);
    }
    args.add(Math.max(1, limit));
    return jdbcTemplate.queryForList("""
        SELECT DISTINCT image_name
        FROM docker_manifest
        WHERE repository_id = ?
          AND deleted_at IS NULL
        """ + after + """
        ORDER BY image_name
        LIMIT ?
        """, String.class, args.toArray());
  }

  public List<BrowseImageRow> listBrowseImages(long repositoryId, String parentPath) {
    String normalized = parentPath == null ? "" : parentPath.trim();
    return jdbcTemplate.query("""
        SELECT image_name,
               MAX(updated_at) AS updated_at,
               MAX(size) AS size,
               MAX(media_type) AS media_type
        FROM docker_manifest
        WHERE repository_id = ?
          AND deleted_at IS NULL
          AND (? = '' OR image_name = ? OR image_name LIKE CONCAT(?, '/%'))
        GROUP BY image_name
        ORDER BY image_name
        """, (rs, rowNum) -> new BrowseImageRow(
            rs.getString("image_name"),
            nullableInstant(rs, "updated_at"),
            rs.getObject("size") == null ? null : rs.getLong("size"),
            rs.getString("media_type")),
        repositoryId, normalized, normalized, normalized);
  }

  public List<BrowseReferenceRow> listBrowseReferences(long repositoryId, String imageName) {
    return jdbcTemplate.query("""
        SELECT t.tag AS reference,
               t.manifest_digest AS digest,
               m.asset_id,
               m.size,
               m.media_type,
               m.updated_at
        FROM docker_tag t
        JOIN docker_manifest m ON m.id = t.manifest_id
        WHERE t.repository_id = ?
          AND t.image_name_hash = ?
          AND m.deleted_at IS NULL
        UNION ALL
        SELECT m.digest AS reference,
               m.digest AS digest,
               m.asset_id,
               m.size,
               m.media_type,
               m.updated_at
        FROM docker_manifest m
        WHERE m.repository_id = ?
          AND m.image_name_hash = ?
          AND m.deleted_at IS NULL
        ORDER BY reference
        """, (rs, rowNum) -> new BrowseReferenceRow(
            rs.getString("reference"),
            rs.getString("digest"),
            rs.getLong("asset_id"),
            rs.getObject("size") == null ? null : rs.getLong("size"),
            rs.getString("media_type"),
            nullableInstant(rs, "updated_at")),
        repositoryId, hash(imageName), repositoryId, hash(imageName));
  }

  public Optional<DockerManifestRecord> findBrowseManifestByReferencePath(
      long repositoryId, String path) {
    BrowseReferencePath ref = BrowseReferencePath.parse(path);
    if (ref == null) {
      return Optional.empty();
    }
    return findManifestByReference(repositoryId, ref.imageName(), ref.reference());
  }

  public static byte[] hash(String value) {
    return HashColumns.sha256(value == null ? "" : value);
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(Math.max(1, count), "?"));
  }

  private static String sha256Hex(String digest) {
    if (digest == null || digest.length() <= "sha256:".length()) {
      return null;
    }
    return digest.regionMatches(true, 0, "sha256:", 0, "sha256:".length())
        ? digest.substring("sha256:".length()).toLowerCase(Locale.ROOT)
        : null;
  }







  private record BrowseReferencePath(String imageName, String reference) {
    private static BrowseReferencePath parse(String path) {
      String normalized = path == null ? "" : path.trim();
      if (normalized.startsWith("docker/manifests/")) {
        normalized = normalized.substring("docker/manifests/".length());
      }
      int marker = normalized.lastIndexOf("/manifests/");
      if (marker <= 0 || marker + "/manifests/".length() >= normalized.length()) {
        return null;
      }
      String imageName = normalized.substring(0, marker);
      String reference = normalized.substring(marker + "/manifests/".length());
      return imageName.isBlank() || reference.isBlank()
          ? null
          : new BrowseReferencePath(imageName, reference);
    }
  }
}
