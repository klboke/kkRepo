package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Portable MySQL/PostgreSQL implementation of Galaxy v3 shared registry state. */
@Repository
public class JdbcAnsibleGalaxyRegistryDao implements AnsibleGalaxyRegistryDao {
  private static final String REVISION_PREFIX = "ansible:repository:";
  private static final String GROUP_CONFIG_REVISION_PREFIX = "ansible:group-config:";
  private static final int MAX_VERSION_METADATA_JSON_BYTES = 64 * 1024;
  private static final int MAX_DEPENDENCIES_JSON_BYTES = 192 * 1024;
  private static final int MAX_PROXY_PROTOCOL_METADATA_JSON_BYTES = 256 * 1024;
  private static final TypeReference<List<Map<String, Object>>> MESSAGE_LIST =
      new TypeReference<>() { };

  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final CoordinationPersistenceDialect coordination;

  public JdbcAnsibleGalaxyRegistryDao(
      JdbcTemplate jdbc, JsonColumns json, DatabaseDialect databaseDialect) {
    this.jdbc = jdbc;
    this.json = json;
    this.coordination = databaseDialect.coordination();
  }

  @Override
  public long nextRepositoryRevision(long repositoryId) {
    return coordination.bumpCacheVersion(jdbc, revisionKey(repositoryId));
  }

  @Override
  public long currentRepositoryRevision(long repositoryId) {
    return jdbc.query(
            "SELECT version FROM cache_version WHERE name = ?",
            (rs, row) -> rs.getLong("version"),
            revisionKey(repositoryId))
        .stream()
        .findFirst()
        .orElse(0L);
  }

  @Override
  public Map<Long, Long> currentRepositoryRevisions(Collection<Long> repositoryIds) {
    return currentRevisions(repositoryIds, REVISION_PREFIX);
  }

  @Override
  public long nextGroupConfigRevision(long repositoryId) {
    return coordination.bumpCacheVersion(jdbc, groupConfigRevisionKey(repositoryId));
  }

  @Override
  public long currentGroupConfigRevision(long repositoryId) {
    return currentRevision(groupConfigRevisionKey(repositoryId));
  }

  @Override
  public Map<Long, Long> currentGroupConfigRevisions(Collection<Long> repositoryIds) {
    return currentRevisions(repositoryIds, GROUP_CONFIG_REVISION_PREFIX);
  }

  @Override
  @Transactional
  public long nextCoordinateRevision(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized) {
    long revision = nextRepositoryRevision(repositoryId);
    invalidateContainingGroups(repositoryId, namespaceLc, nameLc, versionNormalized);
    return revision;
  }

  @Override
  public Optional<String> currentCoordinateSha256(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized) {
    List<String> materialized = jdbc.queryForList("""
        SELECT artifact_sha256 FROM ansible_collection_version
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ? AND state = 'READY'
        """, String.class, repositoryId, namespaceLc, nameLc, versionNormalized);
    if (!materialized.isEmpty()) return Optional.ofNullable(materialized.getFirst());
    List<String> projected = jdbc.queryForList("""
        SELECT artifact_sha256 FROM ansible_proxy_version_state
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ? AND artifact_sha256 IS NOT NULL
        """, String.class, repositoryId, namespaceLc, nameLc, versionNormalized);
    if (!projected.isEmpty()) return Optional.ofNullable(projected.getFirst());
    return jdbc.queryForList("""
            SELECT artifact_sha256 FROM ansible_group_binding
            WHERE group_repository_id = ? AND namespace_lc = ? AND name_lc = ?
              AND version_normalized = ?
            """, String.class, repositoryId, namespaceLc, nameLc, versionNormalized)
        .stream().findFirst();
  }

  @Override
  @Transactional
  public CollectionVersion insertVersion(CollectionVersion version) {
    Instant createdAt = version.createdAt() == null ? Instant.now() : version.createdAt();
    Instant updatedAt = version.updatedAt() == null ? createdAt : version.updatedAt();
    Instant publishedAt = version.publishedAt() == null ? createdAt : version.publishedAt();
    String state = version.state() == null ? VERSION_READY : version.state();
    String metadataJson = boundedJson(
        safeMap(version.metadata()), MAX_VERSION_METADATA_JSON_BYTES, "version metadata");
    String dependenciesJson = boundedJson(
        safeMap(version.dependencies()), MAX_DEPENDENCIES_JSON_BYTES, "dependencies");
    long revision = nextRepositoryRevision(version.repositoryId());
    long id = JdbcInserts.insert(jdbc, """
        INSERT INTO ansible_collection_version
          (repository_id, component_id, artifact_asset_id, namespace_lc, namespace_display,
           name_lc, name_display, version_original, version_normalized, artifact_filename,
           artifact_sha256, artifact_size, metadata_json, dependencies_json, requires_ansible,
           source_kind, import_task_uuid, revision, state, published_at,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, statement -> {
      statement.setLong(1, version.repositoryId());
      statement.setLong(2, version.componentId());
      statement.setLong(3, version.artifactAssetId());
      statement.setString(4, version.namespaceLc());
      statement.setString(5, version.namespaceDisplay());
      statement.setString(6, version.nameLc());
      statement.setString(7, version.nameDisplay());
      statement.setString(8, version.versionOriginal());
      statement.setString(9, version.versionNormalized());
      statement.setString(10, version.artifactFilename());
      statement.setString(11, version.artifactSha256());
      statement.setLong(12, version.artifactSize());
      json.bindSerialized(statement, 13, metadataJson);
      json.bindSerialized(statement, 14, dependenciesJson);
      statement.setString(15, version.requiresAnsible());
      statement.setString(16, version.sourceKind());
      statement.setString(17, version.importTaskId());
      statement.setLong(18, revision);
      statement.setString(19, state);
      statement.setTimestamp(20, nullableTimestamp(publishedAt));
      statement.setTimestamp(21, nullableTimestamp(createdAt));
      statement.setTimestamp(22, nullableTimestamp(updatedAt));
    });
    invalidateContainingGroups(
        version.repositoryId(), version.namespaceLc(), version.nameLc(),
        version.versionNormalized());
    return new CollectionVersion(
        id, version.repositoryId(), version.componentId(), version.artifactAssetId(),
        version.namespaceLc(), version.namespaceDisplay(), version.nameLc(), version.nameDisplay(),
        version.versionOriginal(), version.versionNormalized(), version.artifactFilename(),
        version.artifactSha256(), version.artifactSize(), safeMap(version.metadata()),
        safeMap(version.dependencies()),
        version.requiresAnsible(), version.sourceKind(), version.importTaskId(), revision,
        state, publishedAt, createdAt, updatedAt);
  }

  @Override
  public Optional<CollectionVersion> findVersion(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized) {
    return jdbc.query("""
        SELECT * FROM ansible_collection_version
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ? AND state = 'READY'
        """, this::mapVersion, repositoryId, namespaceLc, nameLc, versionNormalized)
        .stream().findFirst();
  }

  @Override
  public Optional<CollectionVersion> findVersionById(long versionId) {
    return jdbc.query(
            "SELECT * FROM ansible_collection_version WHERE id = ? AND state = 'READY'",
            this::mapVersion,
            versionId)
        .stream().findFirst();
  }

  @Override
  public Optional<CollectionVersion> findVersionByArtifactFilename(
      long repositoryId, String artifactFilename) {
    return jdbc.query("""
        SELECT * FROM ansible_collection_version
        WHERE repository_id = ? AND artifact_filename = ? AND state = 'READY'
        """, this::mapVersion, repositoryId, artifactFilename).stream().findFirst();
  }

  @Override
  public Optional<ArtifactRef> findArtifactByVersionId(long versionId) {
    return jdbc.query("""
        SELECT id, repository_id, artifact_asset_id, namespace_lc, name_lc,
               version_normalized, artifact_filename, artifact_sha256, revision
        FROM ansible_collection_version
        WHERE id = ? AND state = 'READY'
        """, this::mapArtifactRef, versionId).stream().findFirst();
  }

  @Override
  public Optional<ArtifactRef> findArtifactByFilename(
      long repositoryId, String artifactFilename) {
    return jdbc.query("""
        SELECT id, repository_id, artifact_asset_id, namespace_lc, name_lc,
               version_normalized, artifact_filename, artifact_sha256, revision
        FROM ansible_collection_version
        WHERE repository_id = ? AND artifact_filename = ? AND state = 'READY'
        """, this::mapArtifactRef, repositoryId, artifactFilename).stream().findFirst();
  }

  @Override
  @Transactional
  public boolean deleteVersion(long repositoryId, long versionId, long artifactAssetId) {
    Optional<CollectionVersion> existing = findVersionById(versionId)
        .filter(version -> version.repositoryId() == repositoryId)
        .filter(version -> version.artifactAssetId() == artifactAssetId);
    if (existing.isEmpty()) {
      return false;
    }
    CollectionVersion version = existing.orElseThrow();
    int deleted = jdbc.update("""
        DELETE FROM ansible_collection_version
        WHERE id = ? AND repository_id = ? AND artifact_asset_id = ? AND state = 'READY'
        """, versionId, repositoryId, artifactAssetId);
    if (deleted == 0) {
      return false;
    }
    jdbc.update("""
        DELETE FROM ansible_proxy_version_state
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ?
        """, repositoryId, version.namespaceLc(), version.nameLc(),
        version.versionNormalized());
    nextCoordinateRevision(
        repositoryId, version.namespaceLc(), version.nameLc(), version.versionNormalized());
    return true;
  }

  @Override
  public List<CollectionVersion> listVersions(
      long repositoryId, String namespaceLc, String nameLc) {
    List<CollectionVersion> versions = new ArrayList<>(jdbc.query("""
        SELECT * FROM ansible_collection_version
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ? AND state = 'READY'
        """, this::mapVersion, repositoryId, namespaceLc, nameLc));
    versions.sort(Comparator.comparingLong(CollectionVersion::revision).reversed()
        .thenComparing(CollectionVersion::versionNormalized, Comparator.reverseOrder()));
    return List.copyOf(versions);
  }

  @Override
  public List<String> listVersionNames(
      long repositoryId, String namespaceLc, String nameLc) {
    // Keep bounded metadata projections out of the hot version-list filesort path.
    return jdbc.queryForList("""
        SELECT version_normalized FROM ansible_collection_version
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ? AND state = 'READY'
        ORDER BY revision DESC, version_normalized DESC
        """, String.class, repositoryId, namespaceLc, nameLc);
  }

  @Override
  public Signature insertSignature(Signature signature) {
    Instant createdAt = signature.createdAt() == null ? Instant.now() : signature.createdAt();
    long id = JdbcInserts.insert(jdbc, """
        INSERT INTO ansible_collection_signature
          (collection_version_id, signature_asset_id, sha256, key_fingerprint, source_kind,
           created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, statement -> {
      statement.setLong(1, signature.collectionVersionId());
      statement.setObject(2, signature.signatureAssetId());
      statement.setString(3, signature.sha256());
      statement.setString(4, signature.keyFingerprint());
      statement.setString(5, signature.sourceKind());
      statement.setTimestamp(6, nullableTimestamp(createdAt));
    });
    return new Signature(
        id, signature.collectionVersionId(), signature.signatureAssetId(), signature.sha256(),
        signature.keyFingerprint(), signature.sourceKind(), createdAt);
  }

  @Override
  public List<Signature> listSignatures(long collectionVersionId) {
    return jdbc.query("""
        SELECT * FROM ansible_collection_signature
        WHERE collection_version_id = ? ORDER BY id
        """, (rs, row) -> new Signature(
        rs.getLong("id"), rs.getLong("collection_version_id"),
        nullableLong(rs, "signature_asset_id"), rs.getString("sha256"),
        rs.getString("key_fingerprint"), rs.getString("source_kind"),
        nullableInstant(rs, "created_at")), collectionVersionId);
  }

  @Override
  @Transactional
  public ImportTask createTask(ImportTask task) {
    return insertTask(task);
  }

  @Override
  @Transactional
  public ImportTask createClaimedTask(
      ImportTask task, String owner, Instant leaseExpiresAt, Instant now) {
    if (task == null || owner == null || owner.isBlank()) {
      throw new IllegalArgumentException("A task and lease owner are required");
    }
    if (leaseExpiresAt == null || now == null || !leaseExpiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Task lease expiry must be after claim time");
    }
    Instant createdAt = task.createdAt() == null ? now : task.createdAt();
    return insertTask(new ImportTask(
        task.taskId(), task.repositoryId(), task.requester(), TASK_RUNNING,
        safeMessages(task.messages()), task.errorCode(), task.errorDetail(), task.namespaceLc(),
        task.nameLc(), task.versionNormalized(), task.artifactFilename(), task.expectedSha256(),
        task.actualSha256(), task.stagingAssetId(), 1, owner, leaseExpiresAt, 1L,
        createdAt, now, null, now));
  }

  private ImportTask insertTask(ImportTask task) {
    Instant createdAt = task.createdAt() == null ? Instant.now() : task.createdAt();
    Instant updatedAt = task.updatedAt() == null ? createdAt : task.updatedAt();
    String state = task.state() == null ? TASK_WAITING : task.state();
    jdbc.update("""
        INSERT INTO ansible_import_task
          (task_uuid, repository_id, requester, state, messages_json, error_code, error_detail,
           namespace_lc, name_lc, version_normalized, artifact_filename, expected_sha256,
           actual_sha256, staging_asset_id, attempt_count, lease_owner, lease_expires_at,
           fencing_token, created_at, started_at, finished_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, task.taskId(), task.repositoryId(), task.requester(), state,
        json.serializedParameter(json.writeValue(safeMessages(task.messages()))),
        task.errorCode(), task.errorDetail(), task.namespaceLc(), task.nameLc(),
        task.versionNormalized(), task.artifactFilename(), task.expectedSha256(),
        task.actualSha256(), task.stagingAssetId(), task.attemptCount(), task.leaseOwner(),
        nullableTimestamp(task.leaseExpiresAt()), task.fencingToken(), nullableTimestamp(createdAt),
        nullableTimestamp(task.startedAt()), nullableTimestamp(task.finishedAt()),
        nullableTimestamp(updatedAt));
    return findTask(task.taskId()).orElseThrow();
  }

  @Override
  public Optional<ImportTask> findTask(String taskId) {
    return jdbc.query(
            "SELECT * FROM ansible_import_task WHERE task_uuid = ?", this::mapTask, taskId)
        .stream().findFirst();
  }

  @Override
  public List<ImportTask> listClaimableTasks(Instant now, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid task claim limit");
    return jdbc.query("""
        SELECT * FROM ansible_import_task
        WHERE state = 'WAITING' OR (state = 'RUNNING' AND lease_expires_at < ?)
        ORDER BY created_at
        LIMIT ?
        """, this::mapTask, nullableTimestamp(now), limit);
  }

  @Override
  @Transactional
  public List<ImportTask> claimTasks(
      String owner, Instant leaseExpiresAt, Instant now, int limit) {
    if (owner == null || owner.isBlank() || leaseExpiresAt == null || now == null
        || !leaseExpiresAt.isAfter(now) || limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("Invalid Ansible task batch claim");
    }
    List<String> taskIds = jdbc.queryForList("""
        SELECT task_uuid FROM ansible_import_task
        WHERE state = 'WAITING' OR (state = 'RUNNING' AND lease_expires_at < ?)
        ORDER BY created_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, String.class, nullableTimestamp(now), limit);
    if (taskIds.isEmpty()) return List.of();
    List<ImportTask> claimed = new ArrayList<>(taskIds.size());
    for (String taskId : taskIds) {
      int updated = jdbc.update("""
          UPDATE ansible_import_task
          SET state = 'RUNNING', attempt_count = attempt_count + 1, lease_owner = ?,
              lease_expires_at = ?, fencing_token = fencing_token + 1,
              started_at = COALESCE(started_at, ?), updated_at = ?
          WHERE task_uuid = ?
            AND (state = 'WAITING' OR (state = 'RUNNING' AND lease_expires_at < ?))
          """, owner, nullableTimestamp(leaseExpiresAt), nullableTimestamp(now),
          nullableTimestamp(now), taskId, nullableTimestamp(now));
      if (updated > 0) findTask(taskId).ifPresent(claimed::add);
    }
    return List.copyOf(claimed);
  }

  @Override
  @Transactional
  public Optional<ImportTask> claimTask(
      String taskId, String owner, Instant leaseExpiresAt, Instant now) {
    if (leaseExpiresAt == null || now == null || !leaseExpiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Task lease expiry must be after claim time");
    }
    int updated = jdbc.update("""
        UPDATE ansible_import_task
        SET state = 'RUNNING', attempt_count = attempt_count + 1, lease_owner = ?,
            lease_expires_at = ?, fencing_token = fencing_token + 1,
            started_at = COALESCE(started_at, ?), updated_at = ?
        WHERE task_uuid = ?
          AND (state = 'WAITING' OR (state = 'RUNNING' AND lease_expires_at < ?))
        """, owner, nullableTimestamp(leaseExpiresAt), nullableTimestamp(now),
        nullableTimestamp(now), taskId, nullableTimestamp(now));
    if (updated == 0) return Optional.empty();
    return findTask(taskId).filter(task -> owner.equals(task.leaseOwner()));
  }

  @Override
  public boolean renewTaskLease(
      String taskId, String owner, long fencingToken, Instant leaseExpiresAt) {
    Instant now = Instant.now();
    if (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Task lease expiry must be in the future");
    }
    return jdbc.update("""
        UPDATE ansible_import_task SET lease_expires_at = ?, updated_at = ?
        WHERE task_uuid = ? AND state = 'RUNNING' AND lease_owner = ? AND fencing_token = ?
          AND lease_expires_at >= ?
        """, nullableTimestamp(leaseExpiresAt), nullableTimestamp(now), taskId, owner,
        fencingToken, nullableTimestamp(now)) > 0;
  }

  @Override
  public boolean finishTask(
      String taskId,
      String owner,
      long fencingToken,
      String state,
      List<Map<String, Object>> messages,
      String errorCode,
      String errorDetail,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      String artifactFilename,
      String actualSha256,
      Instant finishedAt) {
    if (!TASK_COMPLETED.equals(state) && !TASK_FAILED.equals(state)) {
      throw new IllegalArgumentException("Import task terminal state must be COMPLETED or FAILED");
    }
    return jdbc.update("""
        UPDATE ansible_import_task
        SET state = ?, messages_json = ?, error_code = ?, error_detail = ?, namespace_lc = ?,
            name_lc = ?, version_normalized = ?, artifact_filename = ?, actual_sha256 = ?,
            lease_expires_at = ?, finished_at = ?, updated_at = ?
        WHERE task_uuid = ? AND state = 'RUNNING' AND lease_owner = ? AND fencing_token = ?
        """, state, json.serializedParameter(json.writeValue(safeMessages(messages))), errorCode,
        errorDetail, namespaceLc, nameLc, versionNormalized, artifactFilename, actualSha256,
        nullableTimestamp(finishedAt), nullableTimestamp(finishedAt), nullableTimestamp(finishedAt),
        taskId, owner, fencingToken) > 0;
  }

  @Override
  @Transactional
  public long upsertProxyState(ProxyVersionState state) {
    Instant updatedAt = state.updatedAt() == null ? Instant.now() : state.updatedAt();
    Object identity = json.serializedParameter(boundedJson(
        safeMap(state.upstreamIdentity()), MAX_PROXY_PROTOCOL_METADATA_JSON_BYTES,
        "proxy protocol metadata"));
    long revision = nextRepositoryRevision(state.repositoryId());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE ansible_proxy_version_state
        SET artifact_filename = ?, upstream_href = ?, upstream_download_url = ?, artifact_sha256 = ?,
            metadata_etag = ?, metadata_last_modified = ?, cache_until = ?, verified_at = ?,
            negative_status = ?, negative_expires_at = ?, protocol_metadata_json = ?,
            revision = ?, updated_at = ?
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ?
        """,
        new Object[]{state.artifactFilename(), state.upstreamHref(), state.upstreamDownloadUrl(), state.artifactSha256(),
            state.metadataEtag(), state.metadataLastModified(), nullableTimestamp(state.cacheUntil()),
            nullableTimestamp(state.verifiedAt()), state.negativeStatus(),
            nullableTimestamp(state.negativeExpiresAt()), identity, revision,
            nullableTimestamp(updatedAt), state.repositoryId(), state.namespaceLc(), state.nameLc(),
            state.versionNormalized()},
        """
        INSERT INTO ansible_proxy_version_state
          (repository_id, namespace_lc, name_lc, version_normalized, artifact_filename, upstream_href,
           upstream_download_url, artifact_sha256, metadata_etag, metadata_last_modified,
           cache_until, verified_at, negative_status, negative_expires_at,
           protocol_metadata_json, revision, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[]{state.repositoryId(), state.namespaceLc(), state.nameLc(),
            state.versionNormalized(), state.artifactFilename(), state.upstreamHref(), state.upstreamDownloadUrl(),
            state.artifactSha256(), state.metadataEtag(), state.metadataLastModified(),
            nullableTimestamp(state.cacheUntil()), nullableTimestamp(state.verifiedAt()),
            state.negativeStatus(), nullableTimestamp(state.negativeExpiresAt()), identity,
            revision, nullableTimestamp(updatedAt)});
    if (state.versionNormalized() != null && !state.versionNormalized().startsWith("@")) {
      invalidateContainingGroups(
          state.repositoryId(), state.namespaceLc(), state.nameLc(), state.versionNormalized());
    }
    return revision;
  }

  @Override
  public boolean touchProxyState(
      long repositoryId,
      String namespaceLc,
      String nameLc,
      String versionNormalized,
      String metadataEtag,
      String metadataLastModified,
      Instant cacheUntil,
      Instant verifiedAt) {
    return jdbc.update("""
        UPDATE ansible_proxy_version_state
        SET metadata_etag = COALESCE(?, metadata_etag),
            metadata_last_modified = COALESCE(?, metadata_last_modified),
            cache_until = ?, verified_at = ?, updated_at = ?
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ?
        """, metadataEtag, metadataLastModified, nullableTimestamp(cacheUntil),
        nullableTimestamp(verifiedAt), nullableTimestamp(verifiedAt), repositoryId,
        namespaceLc, nameLc, versionNormalized) > 0;
  }

  @Override
  public Optional<ProxyVersionState> findProxyState(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized) {
    return jdbc.query("""
        SELECT * FROM ansible_proxy_version_state
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ?
        """, this::mapProxyState, repositoryId, namespaceLc, nameLc, versionNormalized)
        .stream().findFirst();
  }

  @Override
  public Optional<ProxyVersionState> findProxyStateByArtifactFilename(
      long repositoryId, String artifactFilename) {
    return jdbc.query("""
        SELECT * FROM ansible_proxy_version_state
        WHERE repository_id = ? AND artifact_filename = ?
        ORDER BY revision DESC
        LIMIT 1
        """, this::mapProxyState, repositoryId, artifactFilename).stream().findFirst();
  }

  @Override
  public Optional<ProxyInventory> findProxyInventory(
      long repositoryId, String namespaceLc, String nameLc) {
    return jdbc.query("""
        SELECT * FROM ansible_proxy_inventory
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
        """, this::mapProxyInventory, repositoryId, namespaceLc, nameLc)
        .stream().findFirst();
  }

  @Override
  public Map<Long, Instant> currentProxyInventoryCacheUntil(
      Collection<Long> repositoryIds, String namespaceLc, String nameLc) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    if (repositoryIds != null) {
      repositoryIds.stream().filter(java.util.Objects::nonNull).forEach(ids::add);
    }
    if (ids.isEmpty()) return Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    List<Object> arguments = new ArrayList<>(ids.size() + 2);
    arguments.add(namespaceLc);
    arguments.add(nameLc);
    arguments.addAll(ids);
    LinkedHashMap<Long, Instant> cacheUntil = new LinkedHashMap<>();
    jdbc.query(
        "SELECT repository_id, cache_until FROM ansible_proxy_inventory "
            + "WHERE namespace_lc = ? AND name_lc = ? AND repository_id IN ("
            + placeholders + ")",
        rs -> {
          Instant value = nullableInstant(rs, "cache_until");
          if (value != null) cacheUntil.put(rs.getLong("repository_id"), value);
        },
        arguments.toArray());
    return Map.copyOf(cacheUntil);
  }

  @Override
  public List<String> listProxyInventoryVersionNames(
      long repositoryId, String namespaceLc, String nameLc) {
    return jdbc.queryForList("""
        SELECT version_normalized FROM ansible_proxy_inventory_version
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
        ORDER BY version_normalized
        """, String.class, repositoryId, namespaceLc, nameLc);
  }

  @Override
  @Transactional
  public long replaceProxyInventory(ProxyInventory inventory, List<String> versions) {
    if (inventory == null) throw new IllegalArgumentException("Proxy inventory is required");
    List<String> normalized = versions == null
        ? List.of() : versions.stream().distinct().toList();
    long revision = nextRepositoryRevision(inventory.repositoryId());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE ansible_proxy_inventory
        SET cache_until = ?, checked_at = ?, revision = ?, version_count = ?, updated_at = ?
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
        """,
        new Object[]{nullableTimestamp(inventory.cacheUntil()), nullableTimestamp(inventory.checkedAt()),
            revision, normalized.size(), nullableTimestamp(inventory.updatedAt()),
            inventory.repositoryId(), inventory.namespaceLc(), inventory.nameLc()},
        """
        INSERT INTO ansible_proxy_inventory
          (repository_id, namespace_lc, name_lc, cache_until, checked_at, revision,
           version_count, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[]{inventory.repositoryId(), inventory.namespaceLc(), inventory.nameLc(),
            nullableTimestamp(inventory.cacheUntil()), nullableTimestamp(inventory.checkedAt()),
            revision, normalized.size(), nullableTimestamp(inventory.updatedAt())});
    jdbc.update("""
        DELETE FROM ansible_proxy_inventory_version
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
        """, inventory.repositoryId(), inventory.namespaceLc(), inventory.nameLc());
    jdbc.batchUpdate("""
        INSERT INTO ansible_proxy_inventory_version
          (repository_id, namespace_lc, name_lc, version_normalized)
        VALUES (?, ?, ?, ?)
        """, normalized, 500, (statement, version) -> {
          statement.setLong(1, inventory.repositoryId());
          statement.setString(2, inventory.namespaceLc());
          statement.setString(3, inventory.nameLc());
          statement.setString(4, version);
        });
    return revision;
  }

  @Override
  public boolean touchProxyInventory(
      long repositoryId,
      String namespaceLc,
      String nameLc,
      Instant cacheUntil,
      Instant checkedAt) {
    return jdbc.update("""
        UPDATE ansible_proxy_inventory
        SET cache_until = ?, checked_at = ?, updated_at = ?
        WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
        """, nullableTimestamp(cacheUntil), nullableTimestamp(checkedAt),
        nullableTimestamp(checkedAt), repositoryId, namespaceLc, nameLc) > 0;
  }

  @Override
  public Optional<GroupBinding> findGroupBinding(
      long groupRepositoryId, String namespaceLc, String nameLc, String versionNormalized) {
    return jdbc.query("""
        SELECT * FROM ansible_group_binding
        WHERE group_repository_id = ? AND namespace_lc = ? AND name_lc = ?
          AND version_normalized = ?
        """, this::mapGroupBinding, groupRepositoryId, namespaceLc, nameLc, versionNormalized)
        .stream().findFirst();
  }

  @Override
  public Optional<GroupBinding> findGroupBindingByArtifactFilename(
      long groupRepositoryId, String artifactFilename) {
    return jdbc.query("""
        SELECT * FROM ansible_group_binding
        WHERE group_repository_id = ? AND artifact_filename = ?
        ORDER BY bound_at
        LIMIT 1
        """, this::mapGroupBinding, groupRepositoryId, artifactFilename).stream().findFirst();
  }

  @Override
  @Transactional
  public boolean bindGroupSourceIfCurrent(GroupBinding binding) {
    if (currentGroupConfigRevision(binding.groupRepositoryId()) != binding.groupConfigRevision()) {
      return false;
    }
    if (currentCoordinateSha256(
            binding.memberRepositoryId(), binding.namespaceLc(), binding.nameLc(),
            binding.versionNormalized())
        .filter(binding.artifactSha256()::equalsIgnoreCase)
        .isEmpty()) {
      return false;
    }
    Optional<GroupBinding> existing = findGroupBinding(
        binding.groupRepositoryId(), binding.namespaceLc(), binding.nameLc(),
        binding.versionNormalized());
    if (existing.isPresent()
        && existing.get().groupConfigRevision() == binding.groupConfigRevision()
        && currentCoordinateSha256(
                existing.get().memberRepositoryId(), existing.get().namespaceLc(),
                existing.get().nameLc(), existing.get().versionNormalized())
            .filter(existing.get().artifactSha256()::equalsIgnoreCase)
            .isPresent()) {
      GroupBinding current = existing.get();
      if (current.memberRepositoryId() == binding.memberRepositoryId()
          && current.artifactSha256().equals(binding.artifactSha256())
          && current.memberVersionId() == null && binding.memberVersionId() != null) {
        jdbc.update("""
            UPDATE ansible_group_binding
            SET member_version_id = ?, artifact_filename = ?, member_revision = ?,
                observed_count = observed_count + 1, updated_at = ?
            WHERE group_repository_id = ? AND namespace_lc = ? AND name_lc = ?
              AND version_normalized = ? AND group_config_revision = ?
            """, binding.memberVersionId(), binding.artifactFilename(), binding.memberRevision(),
            nullableTimestamp(Instant.now()), binding.groupRepositoryId(), binding.namespaceLc(),
            binding.nameLc(), binding.versionNormalized(), binding.groupConfigRevision());
        return true;
      }
      jdbc.update("""
          UPDATE ansible_group_binding SET observed_count = observed_count + 1, updated_at = ?
          WHERE group_repository_id = ? AND namespace_lc = ? AND name_lc = ?
            AND version_normalized = ? AND group_config_revision = ?
          """, nullableTimestamp(Instant.now()), binding.groupRepositoryId(), binding.namespaceLc(),
          binding.nameLc(), binding.versionNormalized(), binding.groupConfigRevision());
      return true;
    }
    if (existing.isPresent()) {
      jdbc.update("""
          DELETE FROM ansible_group_binding
          WHERE group_repository_id = ? AND namespace_lc = ? AND name_lc = ?
            AND version_normalized = ?
          """, binding.groupRepositoryId(), binding.namespaceLc(), binding.nameLc(),
          binding.versionNormalized());
    }
    Instant boundAt = binding.boundAt() == null ? Instant.now() : binding.boundAt();
    Instant updatedAt = binding.updatedAt() == null ? boundAt : binding.updatedAt();
    try {
      jdbc.update("""
          INSERT INTO ansible_group_binding
            (group_repository_id, namespace_lc, name_lc, version_normalized,
             member_repository_id, member_version_id, artifact_filename, member_revision,
             group_config_revision,
             artifact_sha256, observed_count, bound_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
          """, binding.groupRepositoryId(), binding.namespaceLc(), binding.nameLc(),
          binding.versionNormalized(), binding.memberRepositoryId(), binding.memberVersionId(),
          binding.artifactFilename(), binding.memberRevision(), binding.groupConfigRevision(),
          binding.artifactSha256(),
          nullableTimestamp(boundAt), nullableTimestamp(updatedAt));
    } catch (DuplicateKeyException ignored) {
      // Another replica established the canonical first-member binding.
    }
    if (currentGroupConfigRevision(binding.groupRepositoryId()) != binding.groupConfigRevision()) {
      jdbc.update("""
          DELETE FROM ansible_group_binding
          WHERE group_repository_id = ? AND namespace_lc = ? AND name_lc = ?
            AND version_normalized = ? AND group_config_revision = ?
          """, binding.groupRepositoryId(), binding.namespaceLc(), binding.nameLc(),
          binding.versionNormalized(), binding.groupConfigRevision());
      return false;
    }
    return findGroupBinding(
            binding.groupRepositoryId(), binding.namespaceLc(), binding.nameLc(),
            binding.versionNormalized())
        .filter(value -> value.groupConfigRevision() == binding.groupConfigRevision())
        .filter(value -> currentCoordinateSha256(
                value.memberRepositoryId(), value.namespaceLc(), value.nameLc(),
                value.versionNormalized())
            .filter(value.artifactSha256()::equalsIgnoreCase)
            .isPresent())
        .isPresent();
  }

  @Override
  public void deleteGroupBindings(long groupRepositoryId) {
    jdbc.update(
        "DELETE FROM ansible_group_binding WHERE group_repository_id = ?", groupRepositoryId);
  }

  @Override
  public Optional<Lease> tryAcquireLease(String leaseKey, String owner, Instant expiresAt) {
    Instant now = Instant.now();
    if (expiresAt == null || !expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Lease expiry must be in the future");
    }
    int updated = jdbc.update("""
        UPDATE ansible_registry_lease
        SET owner = ?, fencing_token = fencing_token + 1, attempt_count = attempt_count + 1,
            expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND expires_at < ?
        """, owner, nullableTimestamp(expiresAt), nullableTimestamp(now), leaseKey,
        nullableTimestamp(now));
    boolean acquired = updated > 0;
    if (updated == 0) {
      if (findLease(leaseKey).isPresent()) return Optional.empty();
      try {
        acquired = jdbc.update("""
            INSERT INTO ansible_registry_lease
              (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
            VALUES (?, ?, 1, 1, ?, ?)
            """, leaseKey, owner, nullableTimestamp(expiresAt), nullableTimestamp(now)) > 0;
      } catch (DuplicateKeyException ignored) {
        // The durable owner check below resolves the acquisition race.
        acquired = false;
      }
    }
    if (!acquired) return Optional.empty();
    return findLease(leaseKey)
        .filter(lease -> owner.equals(lease.owner()) && lease.expiresAt().isAfter(now));
  }

  @Override
  public boolean renewLease(
      String leaseKey, String owner, long fencingToken, Instant expiresAt) {
    Instant now = Instant.now();
    return jdbc.update("""
        UPDATE ansible_registry_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?
        """, nullableTimestamp(expiresAt), nullableTimestamp(now), leaseKey, owner, fencingToken,
        nullableTimestamp(now)) > 0;
  }

  @Override
  public void releaseLease(String leaseKey, String owner, long fencingToken) {
    Instant now = Instant.now();
    jdbc.update("""
        UPDATE ansible_registry_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ?
        """, nullableTimestamp(now.minusMillis(1)), nullableTimestamp(now), leaseKey, owner,
        fencingToken);
  }

  @Override
  @Transactional
  public int deleteExpiredProxyCache(Instant now, Instant stalePageBefore, int limit) {
    if (now == null || stalePageBefore == null || limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("Invalid Ansible proxy cleanup request");
    }
    List<ProxyCacheKey> keys = jdbc.query("""
        SELECT repository_id, namespace_lc, name_lc, version_normalized
        FROM ansible_proxy_version_state
        WHERE (negative_expires_at IS NOT NULL AND negative_expires_at < ?)
           OR (version_normalized LIKE '@versions-%' AND updated_at < ?)
        ORDER BY updated_at
        LIMIT ?
        """, (rs, row) -> new ProxyCacheKey(
        rs.getLong("repository_id"), rs.getString("namespace_lc"),
        rs.getString("name_lc"), rs.getString("version_normalized")),
        nullableTimestamp(now), nullableTimestamp(stalePageBefore), limit);
    int deleted = 0;
    for (ProxyCacheKey key : keys) {
      deleted += jdbc.update("""
          DELETE FROM ansible_proxy_version_state
          WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
            AND version_normalized = ?
          """, key.repositoryId(), key.namespaceLc(), key.nameLc(), key.versionNormalized());
    }
    int remaining = limit - deleted;
    if (remaining <= 0) return deleted;
    List<ProxyInventoryKey> inventories = jdbc.query("""
        SELECT repository_id, namespace_lc, name_lc
        FROM ansible_proxy_inventory
        WHERE cache_until < ? AND updated_at < ?
        ORDER BY updated_at
        LIMIT ?
        """, (rs, row) -> new ProxyInventoryKey(
        rs.getLong("repository_id"), rs.getString("namespace_lc"), rs.getString("name_lc")),
        nullableTimestamp(now), nullableTimestamp(stalePageBefore), remaining);
    for (ProxyInventoryKey inventory : inventories) {
      deleted += jdbc.update("""
          DELETE FROM ansible_proxy_inventory
          WHERE repository_id = ? AND namespace_lc = ? AND name_lc = ?
            AND cache_until < ? AND updated_at < ?
          """, inventory.repositoryId(), inventory.namespaceLc(), inventory.nameLc(),
          nullableTimestamp(now), nullableTimestamp(stalePageBefore));
    }
    return deleted;
  }

  @Override
  @Transactional
  public int deleteTerminalTasksBefore(Instant finishedBefore, int limit) {
    if (finishedBefore == null || limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("Invalid Ansible task cleanup request");
    }
    List<String> ids = jdbc.queryForList("""
        SELECT task_uuid FROM ansible_import_task
        WHERE state IN ('COMPLETED', 'FAILED') AND finished_at < ?
        ORDER BY finished_at
        LIMIT ?
        """, String.class, nullableTimestamp(finishedBefore), limit);
    int deleted = 0;
    for (String id : ids) {
      deleted += jdbc.update("""
          DELETE FROM ansible_import_task
          WHERE task_uuid = ? AND state IN ('COMPLETED', 'FAILED') AND finished_at < ?
          """, id, nullableTimestamp(finishedBefore));
    }
    return deleted;
  }

  @Override
  @Transactional
  public int deleteExpiredLeasesBefore(Instant expiresBefore, int limit) {
    if (expiresBefore == null || limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("Invalid Ansible lease cleanup request");
    }
    List<String> keys = jdbc.queryForList("""
        SELECT lease_key FROM ansible_registry_lease
        WHERE expires_at < ?
        ORDER BY expires_at
        LIMIT ?
        """, String.class, nullableTimestamp(expiresBefore), limit);
    int deleted = 0;
    for (String key : keys) {
      deleted += jdbc.update(
          "DELETE FROM ansible_registry_lease WHERE lease_key = ? AND expires_at < ?",
          key, nullableTimestamp(expiresBefore));
    }
    return deleted;
  }

  @Override
  @Transactional
  public void deleteRepositoryState(long repositoryId) {
    jdbc.update(
        "DELETE FROM ansible_group_binding WHERE group_repository_id = ? OR member_repository_id = ?",
        repositoryId, repositoryId);
    jdbc.update("DELETE FROM ansible_proxy_inventory WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM ansible_proxy_version_state WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM ansible_import_task WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM ansible_collection_version WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM ansible_registry_lease WHERE lease_key LIKE ?", "ansible:" + repositoryId + ":%");
  }

  private CollectionVersion mapVersion(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new CollectionVersion(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getLong("component_id"),
        rs.getLong("artifact_asset_id"), rs.getString("namespace_lc"),
        rs.getString("namespace_display"), rs.getString("name_lc"),
        rs.getString("name_display"), rs.getString("version_original"),
        rs.getString("version_normalized"), rs.getString("artifact_filename"),
        rs.getString("artifact_sha256"), rs.getLong("artifact_size"),
        json.read(rs.getString("metadata_json")), json.read(rs.getString("dependencies_json")),
        rs.getString("requires_ansible"), rs.getString("source_kind"),
        rs.getString("import_task_uuid"), rs.getLong("revision"), rs.getString("state"),
        nullableInstant(rs, "published_at"),
        nullableInstant(rs, "created_at"), nullableInstant(rs, "updated_at"));
  }

  private ArtifactRef mapArtifactRef(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new ArtifactRef(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getLong("artifact_asset_id"),
        rs.getString("namespace_lc"), rs.getString("name_lc"),
        rs.getString("version_normalized"), rs.getString("artifact_filename"),
        rs.getString("artifact_sha256"), rs.getLong("revision"));
  }

  private ImportTask mapTask(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    List<Map<String, Object>> messages = json.readValue(rs.getString("messages_json"), MESSAGE_LIST);
    return new ImportTask(
        rs.getString("task_uuid"), rs.getLong("repository_id"), rs.getString("requester"),
        rs.getString("state"), safeMessages(messages), rs.getString("error_code"),
        rs.getString("error_detail"), rs.getString("namespace_lc"), rs.getString("name_lc"),
        rs.getString("version_normalized"), rs.getString("artifact_filename"),
        rs.getString("expected_sha256"), rs.getString("actual_sha256"),
        nullableLong(rs, "staging_asset_id"), rs.getInt("attempt_count"),
        rs.getString("lease_owner"), nullableInstant(rs, "lease_expires_at"),
        rs.getLong("fencing_token"), nullableInstant(rs, "created_at"),
        nullableInstant(rs, "started_at"), nullableInstant(rs, "finished_at"),
        nullableInstant(rs, "updated_at"));
  }

  private ProxyVersionState mapProxyState(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    int negative = rs.getInt("negative_status");
    Integer negativeStatus = rs.wasNull() ? null : negative;
    return new ProxyVersionState(
        rs.getLong("repository_id"), rs.getString("namespace_lc"), rs.getString("name_lc"),
        rs.getString("version_normalized"), rs.getString("artifact_filename"), rs.getString("upstream_href"),
        rs.getString("upstream_download_url"), rs.getString("artifact_sha256"),
        rs.getString("metadata_etag"), rs.getString("metadata_last_modified"),
        nullableInstant(rs, "cache_until"), nullableInstant(rs, "verified_at"), negativeStatus,
        nullableInstant(rs, "negative_expires_at"),
        json.read(rs.getString("protocol_metadata_json")), rs.getLong("revision"),
        nullableInstant(rs, "updated_at"));
  }

  private ProxyInventory mapProxyInventory(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    return new ProxyInventory(
        rs.getLong("repository_id"), rs.getString("namespace_lc"), rs.getString("name_lc"),
        nullableInstant(rs, "cache_until"), nullableInstant(rs, "checked_at"),
        rs.getLong("revision"), rs.getInt("version_count"), nullableInstant(rs, "updated_at"));
  }

  private GroupBinding mapGroupBinding(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    long rawMemberVersionId = rs.getLong("member_version_id");
    Long memberVersionId = rs.wasNull() ? null : rawMemberVersionId;
    return new GroupBinding(
        rs.getLong("group_repository_id"), rs.getString("namespace_lc"),
        rs.getString("name_lc"), rs.getString("version_normalized"),
        rs.getLong("member_repository_id"), memberVersionId, rs.getString("artifact_filename"),
        rs.getLong("member_revision"), rs.getLong("group_config_revision"),
        rs.getString("artifact_sha256"), nullableInstant(rs, "bound_at"),
        nullableInstant(rs, "updated_at"));
  }

  private Optional<Lease> findLease(String leaseKey) {
    return jdbc.query("""
        SELECT * FROM ansible_registry_lease WHERE lease_key = ?
        """, (rs, row) -> new Lease(
        rs.getString("lease_key"), rs.getString("owner"), rs.getLong("fencing_token"),
        nullableInstant(rs, "expires_at"), nullableInstant(rs, "updated_at")), leaseKey)
        .stream().findFirst();
  }

  private void invalidateContainingGroups(
      long memberRepositoryId, String namespaceLc, String nameLc, String versionNormalized) {
    LinkedHashSet<Long> visited = new LinkedHashSet<>();
    visited.add(memberRepositoryId);
    ArrayDeque<Long> pending = new ArrayDeque<>();
    pending.add(memberRepositoryId);
    while (!pending.isEmpty()) {
      long memberId = pending.removeFirst();
      List<Long> groupIds = jdbc.queryForList("""
          SELECT rm.repository_id
          FROM repository_member rm
          JOIN repository r ON r.id = rm.repository_id
          WHERE rm.member_repository_id = ? AND r.format = ? AND r.type = ?
          ORDER BY rm.repository_id
          """, Long.class, memberId, EnumColumns.write(RepositoryFormat.ANSIBLEGALAXY),
          EnumColumns.write(RepositoryType.GROUP));
      for (Long groupId : groupIds) {
        if (groupId == null || !visited.add(groupId)) continue;
        nextRepositoryRevision(groupId);
        jdbc.update("""
            DELETE FROM ansible_group_binding
            WHERE group_repository_id = ? AND namespace_lc = ? AND name_lc = ?
              AND version_normalized = ?
            """, groupId, namespaceLc, nameLc, versionNormalized);
        pending.addLast(groupId);
      }
    }
  }

  private long currentRevision(String key) {
    return jdbc.query(
            "SELECT version FROM cache_version WHERE name = ?",
            (rs, row) -> rs.getLong("version"), key)
        .stream().findFirst().orElse(0L);
  }

  private Map<Long, Long> currentRevisions(
      Collection<Long> repositoryIds, String prefix) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    if (repositoryIds != null) {
      repositoryIds.stream().filter(java.util.Objects::nonNull).forEach(ids::add);
    }
    if (ids.isEmpty()) return Map.of();
    LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>();
    ids.forEach(id -> revisions.put(id, 0L));
    List<String> names = ids.stream().map(id -> prefix + id).toList();
    String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
    jdbc.query(
        "SELECT name, version FROM cache_version WHERE name IN (" + placeholders + ")",
        rs -> {
          String name = rs.getString("name");
          if (name != null && name.startsWith(prefix)) {
            revisions.put(Long.parseLong(name.substring(prefix.length())), rs.getLong("version"));
          }
        }, names.toArray());
    return Map.copyOf(revisions);
  }

  private static String revisionKey(long repositoryId) {
    return REVISION_PREFIX + repositoryId;
  }

  private static String groupConfigRevisionKey(long repositoryId) {
    return GROUP_CONFIG_REVISION_PREFIX + repositoryId;
  }

  private record ProxyCacheKey(
      long repositoryId, String namespaceLc, String nameLc, String versionNormalized) {
  }

  private record ProxyInventoryKey(long repositoryId, String namespaceLc, String nameLc) {
  }

  private String boundedJson(Object value, int maxBytes, String label) {
    String serialized = json.writeValue(value);
    if (serialized.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException("Ansible " + label + " exceeds the database limit");
    }
    return serialized;
  }

  private static Map<String, Object> safeMap(Map<String, Object> value) {
    return value == null
        ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value));
  }

  private static List<Map<String, Object>> safeMessages(List<Map<String, Object>> value) {
    return value == null ? List.of() : List.copyOf(value);
  }
}
