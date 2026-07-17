package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSwiftRegistryDao implements SwiftRegistryDao {
  private static final String REVISION_PREFIX = "swift:repository:";
  private static final TypeReference<Map<String, List<ProxyTag>>> PROXY_PAGES =
      new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> PROXY_PAGE_ETAGS =
      new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final JsonColumns jsonColumns;
  private final CoordinationPersistenceDialect coordination;

  public JdbcSwiftRegistryDao(
      JdbcTemplate jdbc, JsonColumns jsonColumns, DatabaseDialect databaseDialect) {
    this.jdbc = jdbc;
    this.jsonColumns = jsonColumns;
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
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    if (repositoryIds != null) {
      repositoryIds.stream().filter(java.util.Objects::nonNull).forEach(ids::add);
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<Long, Long> revisions = new LinkedHashMap<>();
    ids.forEach(id -> revisions.put(id, 0L));
    List<String> names = ids.stream().map(JdbcSwiftRegistryDao::revisionKey).toList();
    String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
    jdbc.query(
        "SELECT name, version FROM cache_version WHERE name IN (" + placeholders + ")",
        rs -> {
          String name = rs.getString("name");
          if (name != null && name.startsWith(REVISION_PREFIX)) {
            revisions.put(
                Long.parseLong(name.substring(REVISION_PREFIX.length())),
                rs.getLong("version"));
          }
        },
        names.toArray());
    return revisions;
  }

  @Override
  @Transactional
  public Release insertRelease(
      Release release, List<Manifest> manifests, List<RepositoryUrl> repositoryUrls) {
    Instant createdAt = release.createdAt() == null ? Instant.now() : release.createdAt();
    Instant updatedAt = release.updatedAt() == null ? createdAt : release.updatedAt();
    String metadataJson = release.metadataJson() == null || release.metadataJson().isBlank()
        ? "{}"
        : release.metadataJson();
    String status = release.status() == null ? RELEASE_READY : release.status();
    long releaseId = JdbcInserts.insert(jdbc, """
        INSERT INTO swift_release
          (repository_id, component_id, scope_lc, scope_display, name_lc, name_display,
           version, published_at, metadata_json, archive_sha256, archive_asset_id,
           signature_format, source_signature_asset_id, metadata_signature_asset_id,
           source_kind, revision, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, statement -> {
      statement.setLong(1, release.repositoryId());
      statement.setLong(2, release.componentId());
      statement.setString(3, release.scopeLc());
      statement.setString(4, release.scopeDisplay());
      statement.setString(5, release.nameLc());
      statement.setString(6, release.nameDisplay());
      statement.setString(7, release.version());
      statement.setTimestamp(8, nullableTimestamp(release.publishedAt()));
      jsonColumns.bindSerialized(statement, 9, metadataJson);
      statement.setString(10, release.archiveSha256());
      statement.setLong(11, release.archiveAssetId());
      statement.setString(12, release.signatureFormat());
      statement.setObject(13, release.sourceSignatureAssetId());
      statement.setObject(14, release.metadataSignatureAssetId());
      statement.setString(15, release.sourceKind());
      statement.setLong(16, release.revision());
      statement.setString(17, status);
      statement.setTimestamp(18, nullableTimestamp(createdAt));
      statement.setTimestamp(19, nullableTimestamp(updatedAt));
    });

    for (Manifest manifest : safeList(manifests)) {
      jdbc.update("""
          INSERT INTO swift_manifest
            (release_id, filename, tools_version, asset_id, sha256, declared_tools_version)
          VALUES (?, ?, ?, ?, ?, ?)
          """, releaseId, manifest.filename(), normalizeToolsVersion(manifest.toolsVersion()),
          manifest.assetId(), manifest.sha256(),
          normalizeDeclaredToolsVersion(manifest.declaredToolsVersion()));
    }
    for (RepositoryUrl repositoryUrl : safeList(repositoryUrls)) {
      jdbc.update("""
          INSERT INTO swift_repository_url
            (release_id, repository_id, scope_lc, name_lc, normalized_url,
             normalized_url_hash, display_url)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """, releaseId, release.repositoryId(), release.scopeLc(), release.nameLc(),
          repositoryUrl.normalizedUrl(), sha256(repositoryUrl.normalizedUrl()),
          repositoryUrl.displayUrl());
    }
    invalidateContainingGroups(release.repositoryId());
    return new Release(
        releaseId, release.repositoryId(), release.componentId(), release.scopeLc(),
        release.scopeDisplay(), release.nameLc(), release.nameDisplay(), release.version(),
        release.publishedAt(), metadataJson, release.archiveSha256(), release.archiveAssetId(),
        release.signatureFormat(), release.sourceSignatureAssetId(),
        release.metadataSignatureAssetId(), release.sourceKind(), release.revision(), status,
        createdAt, updatedAt);
  }

  @Override
  public Optional<Release> findRelease(
      long repositoryId, String scopeLc, String nameLc, String version) {
    return jdbc.query("""
        SELECT * FROM swift_release
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
          AND status = 'READY'
        """, this::mapRelease, repositoryId, scopeLc, nameLc, version).stream().findFirst();
  }

  @Override
  public Optional<Release> findReleaseById(long releaseId) {
    return jdbc.query(
            "SELECT * FROM swift_release WHERE id = ?", this::mapRelease, releaseId)
        .stream()
        .findFirst();
  }

  @Override
  public List<Release> listReleases(long repositoryId, String scopeLc, String nameLc) {
    return jdbc.query("""
        SELECT * FROM swift_release
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND status = 'READY'
        ORDER BY revision DESC, version DESC
        """, this::mapRelease, repositoryId, scopeLc, nameLc);
  }

  @Override
  public List<Manifest> listManifests(long releaseId) {
    return jdbc.query("""
        SELECT * FROM swift_manifest WHERE release_id = ? ORDER BY tools_version, filename
        """, (rs, row) -> new Manifest(
        rs.getLong("release_id"), rs.getString("filename"), rs.getString("tools_version"),
        rs.getLong("asset_id"), rs.getString("sha256"),
        rs.getString("declared_tools_version")), releaseId);
  }

  @Override
  public Optional<Manifest> findManifest(long releaseId, String toolsVersion) {
    return jdbc.query("""
        SELECT * FROM swift_manifest WHERE release_id = ? AND tools_version = ?
        """, (rs, row) -> new Manifest(
        rs.getLong("release_id"), rs.getString("filename"), rs.getString("tools_version"),
        rs.getLong("asset_id"), rs.getString("sha256"),
        rs.getString("declared_tools_version")), releaseId,
        normalizeToolsVersion(toolsVersion)).stream().findFirst();
  }

  @Override
  public List<RepositoryUrl> listRepositoryUrls(long releaseId) {
    return jdbc.query("""
        SELECT * FROM swift_repository_url WHERE release_id = ? ORDER BY id
        """, (rs, row) -> new RepositoryUrl(
        rs.getLong("id"), rs.getLong("release_id"), rs.getLong("repository_id"),
        rs.getString("scope_lc"), rs.getString("name_lc"),
        rs.getString("normalized_url"), rs.getString("display_url")), releaseId);
  }

  @Override
  public List<RepositoryUrl> listRepositoryUrls(
      long repositoryId, String scopeLc, String nameLc) {
    return jdbc.query("""
        SELECT u.*
        FROM swift_repository_url u
        JOIN swift_release r ON r.id = u.release_id
        WHERE r.repository_id = ? AND r.scope_lc = ? AND r.name_lc = ?
          AND r.status = 'READY'
        ORDER BY r.revision DESC, r.version DESC, u.id
        """, (rs, row) -> new RepositoryUrl(
        rs.getLong("id"), rs.getLong("release_id"), rs.getLong("repository_id"),
        rs.getString("scope_lc"), rs.getString("name_lc"),
        rs.getString("normalized_url"), rs.getString("display_url")),
        repositoryId, scopeLc, nameLc);
  }

  @Override
  public List<PackageIdentity> findIdentities(long repositoryId, String normalizedUrl) {
    return jdbc.query("""
        SELECT DISTINCT r.scope_lc, r.scope_display, r.name_lc, r.name_display
        FROM swift_repository_url u
        JOIN swift_release r ON r.id = u.release_id
        WHERE u.repository_id = ? AND u.normalized_url_hash = ? AND u.normalized_url = ?
          AND r.status = 'READY'
        ORDER BY r.scope_lc, r.name_lc
        """, (rs, row) -> new PackageIdentity(
        rs.getString("scope_lc"), rs.getString("scope_display"),
        rs.getString("name_lc"), rs.getString("name_display")),
        repositoryId, sha256(normalizedUrl), normalizedUrl);
  }

  @Override
  public ProxySource bindProxySource(ProxySource candidate) {
    Instant checkedAt = candidate.lastCheckedAt() == null ? Instant.now() : candidate.lastCheckedAt();
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE swift_proxy_source
            SET observed_count = observed_count + 1, last_checked_at = ?, updated_at = ?
            WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
            """,
        new Object[]{
            nullableTimestamp(checkedAt), nullableTimestamp(checkedAt), candidate.repositoryId(),
            candidate.scopeLc(), candidate.nameLc(), candidate.version()
        },
        """
            INSERT INTO swift_proxy_source
              (repository_id, scope_lc, name_lc, version, upstream_repository_url, upstream_tag,
               commit_sha, generation_profile, archive_sha256, cache_state, release_id,
               verified_at, revision, observed_count, last_checked_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            """,
        new Object[]{
            candidate.repositoryId(), candidate.scopeLc(), candidate.nameLc(), candidate.version(),
            candidate.upstreamRepositoryUrl(), candidate.upstreamTag(), candidate.commitSha(),
            candidate.generationProfile(), candidate.archiveSha256(), candidate.cacheState(),
            candidate.releaseId(), nullableTimestamp(candidate.verifiedAt()), candidate.revision(),
            nullableTimestamp(checkedAt), nullableTimestamp(checkedAt)
        });
    return findProxySource(
            candidate.repositoryId(), candidate.scopeLc(), candidate.nameLc(), candidate.version())
        .orElseThrow();
  }

  @Override
  @Transactional
  public List<ProxySource> bindProxySources(List<ProxySource> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    ProxySource first = candidates.getFirst();
    return bindProxySources(
        first.repositoryId(), first.scopeLc(), first.nameLc(), candidates, false);
  }

  @Override
  @Transactional
  public List<ProxySource> replaceProxySources(
      long repositoryId, String scopeLc, String nameLc, List<ProxySource> candidates) {
    return bindProxySources(
        repositoryId,
        scopeLc,
        nameLc,
        candidates == null ? List.of() : candidates,
        true);
  }

  private List<ProxySource> bindProxySources(
      long repositoryId,
      String scopeLc,
      String nameLc,
      List<ProxySource> candidates,
      boolean replaceInventory) {
    LinkedHashMap<String, ProxySource> unique = new LinkedHashMap<>();
    for (ProxySource candidate : candidates) {
      if (candidate.repositoryId() != repositoryId
          || !candidate.scopeLc().equals(scopeLc)
          || !candidate.nameLc().equals(nameLc)) {
        throw new IllegalArgumentException("Bulk Swift proxy bindings must target one package");
      }
      unique.putIfAbsent(candidate.version(), candidate);
    }

    Map<String, ProxySource> existing = new LinkedHashMap<>();
    listProxySources(repositoryId, scopeLc, nameLc)
        .forEach(source -> existing.put(source.version(), source));
    Instant defaultCheckedAt = Instant.now();
    Map<String, Instant> checkedAtByVersion = new LinkedHashMap<>();
    List<Object[]> updates = new java.util.ArrayList<>();
    List<Object[]> inserts = new java.util.ArrayList<>();
    for (ProxySource candidate : unique.values()) {
      Instant checkedAt = candidate.lastCheckedAt() == null
          ? defaultCheckedAt
          : candidate.lastCheckedAt();
      checkedAtByVersion.put(candidate.version(), checkedAt);
      if (existing.containsKey(candidate.version())) {
        if (!replaceInventory) {
          updates.add(new Object[]{
              nullableTimestamp(checkedAt), nullableTimestamp(checkedAt), candidate.repositoryId(),
              candidate.scopeLc(), candidate.nameLc(), candidate.version()
          });
        }
      } else {
        inserts.add(new Object[]{
            candidate.repositoryId(), candidate.scopeLc(), candidate.nameLc(), candidate.version(),
            candidate.upstreamRepositoryUrl(), candidate.upstreamTag(), candidate.commitSha(),
            candidate.generationProfile(), candidate.archiveSha256(), candidate.cacheState(),
            candidate.releaseId(), nullableTimestamp(candidate.verifiedAt()), candidate.revision(),
            nullableTimestamp(checkedAt), nullableTimestamp(checkedAt)
        });
      }
    }
    if (!updates.isEmpty()) {
      jdbc.batchUpdate("""
          UPDATE swift_proxy_source
          SET observed_count = observed_count + 1, last_checked_at = ?, updated_at = ?
          WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
          """, updates);
    }
    if (!inserts.isEmpty()) {
      jdbc.batchUpdate("""
          INSERT INTO swift_proxy_source
            (repository_id, scope_lc, name_lc, version, upstream_repository_url, upstream_tag,
             commit_sha, generation_profile, archive_sha256, cache_state, release_id,
             verified_at, revision, observed_count, last_checked_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
          """, inserts);
    }
    if (replaceInventory) {
      List<Object[]> removals = existing.keySet().stream()
          .filter(version -> !unique.containsKey(version))
          .map(version -> new Object[]{repositoryId, scopeLc, nameLc, version})
          .toList();
      if (!removals.isEmpty()) {
        jdbc.batchUpdate("""
            DELETE FROM swift_proxy_source
            WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
            """, removals);
      }
    }
    if (unique.isEmpty()) {
      return List.of();
    }

    return unique.values().stream()
        .map(candidate -> {
          ProxySource previous = existing.get(candidate.version());
          Instant checkedAt = checkedAtByVersion.get(candidate.version());
          if (previous == null) {
            return new ProxySource(
                candidate.repositoryId(),
                candidate.scopeLc(),
                candidate.nameLc(),
                candidate.version(),
                candidate.upstreamRepositoryUrl(),
                candidate.upstreamTag(),
                candidate.commitSha(),
                candidate.generationProfile(),
                candidate.archiveSha256(),
                candidate.cacheState(),
                candidate.releaseId(),
                candidate.verifiedAt(),
                candidate.revision(),
                1L,
                checkedAt);
          }
          if (replaceInventory) {
            return previous;
          }
          return new ProxySource(
              previous.repositoryId(),
              previous.scopeLc(),
              previous.nameLc(),
              previous.version(),
              previous.upstreamRepositoryUrl(),
              previous.upstreamTag(),
              previous.commitSha(),
              previous.generationProfile(),
              previous.archiveSha256(),
              previous.cacheState(),
              previous.releaseId(),
              previous.verifiedAt(),
              previous.revision(),
              previous.observedCount() + 1L,
              checkedAt);
        })
        .toList();
  }

  @Override
  public Optional<ProxySource> findProxySource(
      long repositoryId, String scopeLc, String nameLc, String version) {
    return jdbc.query("""
        SELECT * FROM swift_proxy_source
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
        """, (rs, row) -> new ProxySource(
        rs.getLong("repository_id"), rs.getString("scope_lc"), rs.getString("name_lc"),
        rs.getString("version"), rs.getString("upstream_repository_url"),
        rs.getString("upstream_tag"), rs.getString("commit_sha"),
        rs.getString("generation_profile"), rs.getString("archive_sha256"),
        rs.getString("cache_state"), nullableLong(rs, "release_id"),
        nullableInstant(rs, "verified_at"), rs.getLong("revision"),
        rs.getLong("observed_count"), nullableInstant(rs, "last_checked_at")),
        repositoryId, scopeLc, nameLc, version).stream().findFirst();
  }

  @Override
  public List<ProxySource> listProxySources(
      long repositoryId, String scopeLc, String nameLc) {
    return jdbc.query("""
        SELECT * FROM swift_proxy_source
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ?
        ORDER BY version
        """, (rs, row) -> new ProxySource(
        rs.getLong("repository_id"), rs.getString("scope_lc"), rs.getString("name_lc"),
        rs.getString("version"), rs.getString("upstream_repository_url"),
        rs.getString("upstream_tag"), rs.getString("commit_sha"),
        rs.getString("generation_profile"), rs.getString("archive_sha256"),
        rs.getString("cache_state"), nullableLong(rs, "release_id"),
        nullableInstant(rs, "verified_at"), rs.getLong("revision"),
        rs.getLong("observed_count"), nullableInstant(rs, "last_checked_at")),
        repositoryId, scopeLc, nameLc);
  }

  @Override
  public Optional<ProxyInventory> findProxyInventory(
      long repositoryId, String scopeLc, String nameLc) {
    return jdbc.query("""
        SELECT * FROM swift_proxy_inventory
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ?
        """, (rs, row) -> new ProxyInventory(
        rs.getLong("repository_id"),
        rs.getString("scope_lc"),
        rs.getString("name_lc"),
        rs.getLong("revision"),
        nullableInstant(rs, "last_checked_at"),
        safeProxyPages(jsonColumns.readValue(rs.getString("pages_json"), PROXY_PAGES)),
        safeProxyEtags(jsonColumns.readValue(rs.getString("page_etags_json"), PROXY_PAGE_ETAGS))),
        repositoryId, scopeLc, nameLc).stream().findFirst();
  }

  @Override
  public void upsertProxyInventory(ProxyInventory inventory) {
    if (inventory == null) {
      return;
    }
    Instant checkedAt = inventory.lastCheckedAt() == null
        ? Instant.now() : inventory.lastCheckedAt();
    int updated = jdbc.update("""
        UPDATE swift_proxy_inventory
        SET revision = ?, last_checked_at = ?, pages_json = ?, page_etags_json = ?, updated_at = ?
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ?
        """,
        inventory.revision(),
        nullableTimestamp(checkedAt),
        jsonColumns.serializedParameter(jsonColumns.writeValue(inventory.pages())),
        jsonColumns.serializedParameter(jsonColumns.writeValue(inventory.pageEtags())),
        nullableTimestamp(Instant.now()),
        inventory.repositoryId(),
        inventory.scopeLc(),
        inventory.nameLc());
    if (updated == 0) {
      jdbc.update("""
          INSERT INTO swift_proxy_inventory
            (repository_id, scope_lc, name_lc, revision, last_checked_at, pages_json,
             page_etags_json, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """,
          inventory.repositoryId(),
          inventory.scopeLc(),
          inventory.nameLc(),
          inventory.revision(),
          nullableTimestamp(checkedAt),
          jsonColumns.serializedParameter(jsonColumns.writeValue(inventory.pages())),
          jsonColumns.serializedParameter(jsonColumns.writeValue(inventory.pageEtags())),
          nullableTimestamp(Instant.now()));
    }
  }

  @Override
  public boolean completeProxySource(
      long repositoryId,
      String scopeLc,
      String nameLc,
      String version,
      String expectedCommitSha,
      String archiveSha256,
      String cacheState,
      Long releaseId,
      Instant verifiedAt,
      long revision,
      String leaseKey,
      String leaseOwner,
      long fencingToken) {
    Instant now = Instant.now();
    return jdbc.update("""
        UPDATE swift_proxy_source
        SET archive_sha256 = ?, cache_state = ?, release_id = ?, verified_at = ?,
            revision = ?, last_checked_at = ?, updated_at = ?
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
          AND commit_sha = ?
          AND EXISTS (
            SELECT 1 FROM swift_coordinate_lease l
            WHERE l.lease_key = ? AND l.owner = ? AND l.fencing_token = ?
              AND l.expires_at >= ?)
        """, archiveSha256, cacheState, releaseId, nullableTimestamp(verifiedAt), revision,
        nullableTimestamp(now), nullableTimestamp(now), repositoryId, scopeLc, nameLc, version,
        expectedCommitSha, leaseKey, leaseOwner, fencingToken, nullableTimestamp(now)) > 0;
  }

  @Override
  public Optional<GroupSourceBinding> findGroupSourceBinding(
      long groupRepositoryId, String scopeLc, String nameLc, String version) {
    return jdbc.query("""
        SELECT * FROM swift_group_source_binding
        WHERE group_repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
        """, (rs, row) -> new GroupSourceBinding(
        rs.getLong("group_repository_id"), rs.getString("scope_lc"),
        rs.getString("name_lc"), rs.getString("version"),
        rs.getLong("member_repository_id"), rs.getLong("member_release_id"),
        rs.getLong("member_revision"), rs.getLong("group_config_revision"),
        nullableInstant(rs, "bound_at")), groupRepositoryId, scopeLc, nameLc, version)
        .stream().findFirst();
  }

  @Override
  @Transactional
  public boolean upsertGroupSourceBindingIfCurrent(GroupSourceBinding binding) {
    List<Long> currentRevisions = jdbc.query(
        "SELECT version FROM cache_version WHERE name = ? FOR UPDATE",
        (rs, row) -> rs.getLong("version"),
        revisionKey(binding.groupRepositoryId()));
    if (currentRevisions.isEmpty()
        || currentRevisions.getFirst() != binding.groupConfigRevision()) {
      return false;
    }

    Instant updatedAt = Instant.now();
    long configRevision = binding.groupConfigRevision();
    int updated = jdbc.update(
        """
            UPDATE swift_group_source_binding
            SET member_repository_id = CASE WHEN group_config_revision = ?
                  THEN member_repository_id ELSE ? END,
                member_release_id = CASE WHEN group_config_revision = ?
                  THEN member_release_id ELSE ? END,
                member_revision = CASE WHEN group_config_revision = ?
                  THEN member_revision ELSE ? END,
                bound_at = CASE WHEN group_config_revision = ? THEN bound_at ELSE ? END,
                observed_count = CASE WHEN group_config_revision = ?
                  THEN observed_count + 1 ELSE 1 END,
                updated_at = ?,
                group_config_revision = ?
            WHERE group_repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
            """,
        configRevision, binding.memberRepositoryId(), configRevision,
        binding.memberReleaseId(), configRevision, binding.memberRevision(), configRevision,
        nullableTimestamp(binding.boundAt()), configRevision, nullableTimestamp(updatedAt),
        configRevision, binding.groupRepositoryId(), binding.scopeLc(), binding.nameLc(),
        binding.version());
    if (updated > 0) {
      return true;
    }

    jdbc.update("""
        INSERT INTO swift_group_source_binding
          (group_repository_id, scope_lc, name_lc, version, member_repository_id,
           member_release_id, member_revision, group_config_revision, observed_count,
           bound_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
        """, binding.groupRepositoryId(), binding.scopeLc(), binding.nameLc(), binding.version(),
        binding.memberRepositoryId(), binding.memberReleaseId(), binding.memberRevision(),
        configRevision, nullableTimestamp(binding.boundAt()), nullableTimestamp(updatedAt));
    return true;
  }

  @Override
  public void deleteGroupSourceBindings(long groupRepositoryId) {
    jdbc.update(
        "DELETE FROM swift_group_source_binding WHERE group_repository_id = ?",
        groupRepositoryId);
  }

  @Override
  public Optional<Lease> tryAcquireLease(String leaseKey, String owner, Instant expiresAt) {
    Instant now = Instant.now();
    if (expiresAt == null || !expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Lease expiry must be in the future");
    }
    int updated = jdbc.update("""
        UPDATE swift_coordinate_lease
        SET owner = ?, fencing_token = fencing_token + 1, expires_at = ?, updated_at = ?,
            attempt_count = attempt_count + 1
        WHERE lease_key = ? AND expires_at < ?
        """, owner, nullableTimestamp(expiresAt), nullableTimestamp(now), leaseKey,
        nullableTimestamp(now));
    if (updated == 0) {
      try {
        jdbc.update("""
            INSERT INTO swift_coordinate_lease
              (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
            VALUES (?, ?, 1, 1, ?, ?)
            """, leaseKey, owner, nullableTimestamp(expiresAt), nullableTimestamp(now));
      } catch (DuplicateKeyException ignored) {
        // Another replica either owns the live lease or won the expired-row race. The durable
        // owner check below decides whether this caller acquired it.
      }
    }
    return findLease(leaseKey).filter(
        lease -> lease.owner().equals(owner) && lease.expiresAt().isAfter(now));
  }

  @Override
  public boolean renewLease(
      String leaseKey, String owner, long fencingToken, Instant expiresAt) {
    Instant now = Instant.now();
    return jdbc.update("""
        UPDATE swift_coordinate_lease
        SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?
        """, nullableTimestamp(expiresAt), nullableTimestamp(now), leaseKey, owner, fencingToken,
        nullableTimestamp(now)) > 0;
  }

  @Override
  public void releaseLease(String leaseKey, String owner, long fencingToken) {
    Instant now = Instant.now();
    jdbc.update("""
        UPDATE swift_coordinate_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ?
        """, nullableTimestamp(now.minusMillis(1)), nullableTimestamp(now), leaseKey, owner,
        fencingToken);
  }

  @Override
  @Transactional
  public void tombstoneRelease(Tombstone tombstone) {
    jdbc.update("""
        UPDATE swift_release SET status = 'TOMBSTONED', revision = ?, updated_at = ?
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
        """, tombstone.revision(), nullableTimestamp(tombstone.deletedAt()),
        tombstone.repositoryId(), tombstone.scopeLc(), tombstone.nameLc(), tombstone.version());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE swift_release_tombstone
            SET reason = ?, revision = ?, deleted_at = ?
            WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
            """,
        new Object[]{
            tombstone.reason(), tombstone.revision(), nullableTimestamp(tombstone.deletedAt()),
            tombstone.repositoryId(), tombstone.scopeLc(), tombstone.nameLc(), tombstone.version()
        },
        """
            INSERT INTO swift_release_tombstone
              (repository_id, scope_lc, name_lc, version, reason, revision, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[]{
            tombstone.repositoryId(), tombstone.scopeLc(), tombstone.nameLc(), tombstone.version(),
            tombstone.reason(), tombstone.revision(), nullableTimestamp(tombstone.deletedAt())
        });
    invalidateContainingGroups(tombstone.repositoryId());
  }

  @Override
  @Transactional
  public Optional<DeletedRelease> tombstoneAndDeleteReleaseState(
      long repositoryId,
      String scopeLc,
      String nameLc,
      String version,
      String reason,
      Instant deletedAt) {
    lockCoordinateForDeletion(repositoryId, scopeLc, nameLc, version);
    Optional<Release> existing = jdbc.query("""
        SELECT * FROM swift_release
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
        FOR UPDATE
        """, this::mapRelease, repositoryId, scopeLc, nameLc, version).stream().findFirst();
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    Release release = existing.orElseThrow();
    LinkedHashSet<Long> assetIds = new LinkedHashSet<>();
    assetIds.add(release.archiveAssetId());
    listManifests(release.id()).stream().map(Manifest::assetId).forEach(assetIds::add);
    if (release.sourceSignatureAssetId() != null) {
      assetIds.add(release.sourceSignatureAssetId());
    }
    if (release.metadataSignatureAssetId() != null) {
      assetIds.add(release.metadataSignatureAssetId());
    }

    Instant deleted = deletedAt == null ? Instant.now() : deletedAt;
    long revision = nextRepositoryRevision(repositoryId);
    tombstoneRelease(new Tombstone(
        repositoryId, scopeLc, nameLc, version, reason, revision, deleted));
    jdbc.update("DELETE FROM swift_release WHERE id = ?", release.id());
    return Optional.of(new DeletedRelease(
        release.componentId(), List.copyOf(assetIds), revision));
  }

  /**
   * Serializes deletion with the fenced publish transaction. The update is intentionally a no-op:
   * its row lock is held until the surrounding transaction commits. A defensive expired row is
   * created for releases imported by older code paths that predate coordinate leases.
   */
  private void lockCoordinateForDeletion(
      long repositoryId, String scopeLc, String nameLc, String version) {
    String leaseKey = "swift:" + repositoryId + ":" + scopeLc + ":" + nameLc + ":" + version;
    Instant now = Instant.now();
    JdbcUpserts.updateThenInsert(
        jdbc,
        "UPDATE swift_coordinate_lease SET updated_at = updated_at WHERE lease_key = ?",
        new Object[]{leaseKey},
        """
            INSERT INTO swift_coordinate_lease
              (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
            VALUES (?, ?, 0, 0, ?, ?)
            """,
        new Object[]{
            leaseKey,
            "administrative-delete-lock",
            nullableTimestamp(now.minusMillis(1)),
            nullableTimestamp(now)
        });
  }

  @Override
  public Optional<Tombstone> findTombstone(
      long repositoryId, String scopeLc, String nameLc, String version) {
    return jdbc.query("""
        SELECT * FROM swift_release_tombstone
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ? AND version = ?
        """, (rs, row) -> new Tombstone(
        rs.getLong("repository_id"), rs.getString("scope_lc"), rs.getString("name_lc"),
        rs.getString("version"), rs.getString("reason"), rs.getLong("revision"),
        nullableInstant(rs, "deleted_at")), repositoryId, scopeLc, nameLc, version)
        .stream().findFirst();
  }

  @Override
  public List<Tombstone> listTombstones(
      long repositoryId, String scopeLc, String nameLc) {
    return jdbc.query("""
        SELECT * FROM swift_release_tombstone
        WHERE repository_id = ? AND scope_lc = ? AND name_lc = ?
        ORDER BY revision DESC, version DESC
        """, (rs, row) -> new Tombstone(
        rs.getLong("repository_id"), rs.getString("scope_lc"), rs.getString("name_lc"),
        rs.getString("version"), rs.getString("reason"), rs.getLong("revision"),
        nullableInstant(rs, "deleted_at")), repositoryId, scopeLc, nameLc);
  }

  @Override
  public void putNegativeCache(NegativeCache entry) {
    Instant updatedAt = entry.updatedAt() == null ? Instant.now() : entry.updatedAt();
    byte[] keyHash = sha256(entry.cacheKey());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE swift_proxy_negative_cache
            SET cache_key = ?, status_code = ?, retry_after = ?, expires_at = ?, updated_at = ?
            WHERE repository_id = ? AND cache_key_hash = ?
            """,
        new Object[]{
            entry.cacheKey(), entry.statusCode(), nullableTimestamp(entry.retryAfter()),
            nullableTimestamp(entry.expiresAt()), nullableTimestamp(updatedAt),
            entry.repositoryId(), keyHash
        },
        """
            INSERT INTO swift_proxy_negative_cache
              (repository_id, cache_key, cache_key_hash, status_code, retry_after, expires_at,
               updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[]{
            entry.repositoryId(), entry.cacheKey(), keyHash, entry.statusCode(),
            nullableTimestamp(entry.retryAfter()), nullableTimestamp(entry.expiresAt()),
            nullableTimestamp(updatedAt)
        });
  }

  @Override
  public Optional<NegativeCache> findNegativeCache(long repositoryId, String cacheKey) {
    return jdbc.query("""
        SELECT * FROM swift_proxy_negative_cache
        WHERE repository_id = ? AND cache_key_hash = ? AND cache_key = ? AND expires_at > ?
        """, (rs, row) -> new NegativeCache(
        rs.getLong("repository_id"), rs.getString("cache_key"), rs.getInt("status_code"),
        nullableInstant(rs, "retry_after"), nullableInstant(rs, "expires_at"),
        nullableInstant(rs, "updated_at")), repositoryId, sha256(cacheKey), cacheKey,
        nullableTimestamp(Instant.now())).stream().findFirst();
  }

  @Override
  public int deleteExpiredNegativeCache(Instant expiresBefore) {
    return jdbc.update(
        "DELETE FROM swift_proxy_negative_cache WHERE expires_at <= ?",
        nullableTimestamp(expiresBefore));
  }

  private Optional<Lease> findLease(String leaseKey) {
    return jdbc.query("""
        SELECT * FROM swift_coordinate_lease WHERE lease_key = ?
        """, (rs, row) -> new Lease(
        rs.getString("lease_key"), rs.getString("owner"), rs.getLong("fencing_token"),
        nullableInstant(rs, "expires_at"), nullableInstant(rs, "updated_at")), leaseKey)
        .stream().findFirst();
  }

  private Release mapRelease(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Release(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getLong("component_id"),
        rs.getString("scope_lc"), rs.getString("scope_display"), rs.getString("name_lc"),
        rs.getString("name_display"), rs.getString("version"),
        nullableInstant(rs, "published_at"), rs.getString("metadata_json"),
        rs.getString("archive_sha256"), rs.getLong("archive_asset_id"),
        rs.getString("signature_format"), nullableLong(rs, "source_signature_asset_id"),
        nullableLong(rs, "metadata_signature_asset_id"), rs.getString("source_kind"),
        rs.getLong("revision"), rs.getString("status"), nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
  }

  /**
   * Invalidates every direct and transitive Swift group that contains {@code memberRepositoryId}.
   * Callers are transactional release mutations, so revision fencing and binding deletion commit
   * atomically with the member change.
   */
  private void invalidateContainingGroups(long memberRepositoryId) {
    LinkedHashSet<Long> visited = new LinkedHashSet<>();
    visited.add(memberRepositoryId);
    ArrayDeque<Long> pending = new ArrayDeque<>();
    pending.add(memberRepositoryId);
    while (!pending.isEmpty()) {
      long memberId = pending.removeFirst();
      List<Long> containingGroups = jdbc.queryForList(
          """
          SELECT rm.repository_id
          FROM repository_member rm
          JOIN repository r ON r.id = rm.repository_id
          WHERE rm.member_repository_id = ? AND r.format = ? AND r.type = ?
          ORDER BY rm.repository_id
          """,
          Long.class,
          memberId,
          EnumColumns.write(RepositoryFormat.SWIFT),
          EnumColumns.write(RepositoryType.GROUP));
      for (Long groupId : containingGroups) {
        if (groupId == null || !visited.add(groupId)) {
          continue;
        }
        nextRepositoryRevision(groupId);
        deleteGroupSourceBindings(groupId);
        pending.addLast(groupId);
      }
    }
  }

  private static String normalizeToolsVersion(String toolsVersion) {
    return toolsVersion == null ? "" : toolsVersion;
  }

  private static String normalizeDeclaredToolsVersion(String toolsVersion) {
    return toolsVersion == null ? "" : toolsVersion;
  }

  private static Map<String, List<ProxyTag>> safeProxyPages(
      Map<String, List<ProxyTag>> pages) {
    return pages == null ? Map.of() : pages;
  }

  private static Map<String, String> safeProxyEtags(Map<String, String> etags) {
    return etags == null ? Map.of() : etags;
  }

  private static String revisionKey(long repositoryId) {
    return REVISION_PREFIX + repositoryId;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> List<T> safeList(List<T> values) {
    return values == null ? List.of() : values;
  }
}
