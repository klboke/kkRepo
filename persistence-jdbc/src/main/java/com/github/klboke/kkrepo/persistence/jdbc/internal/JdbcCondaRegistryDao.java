package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CondaPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Portable MySQL/PostgreSQL implementation of durable Conda registry state. */
@Repository
public class JdbcCondaRegistryDao implements CondaRegistryDao {
  private static final String REVISION_PREFIX = "conda:repository:";
  private static final int PROXY_BATCH_SIZE = 2_000;

  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final CondaPersistenceDialect conda;
  private final CoordinationPersistenceDialect coordination;

  public JdbcCondaRegistryDao(
      JdbcTemplate jdbc, JsonColumns json, DatabaseDialect databaseDialect) {
    this.jdbc = jdbc;
    this.json = json;
    this.conda = databaseDialect.conda();
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
    List<String> names = ids.stream().map(JdbcCondaRegistryDao::revisionKey).toList();
    String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
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
    return Map.copyOf(revisions);
  }

  @Override
  @Transactional
  public PackageRecord saveHostedPackage(PackageRecord record) {
    requireSource(record, SOURCE_HOSTED);
    Instant now = record.updatedAt() == null ? Instant.now() : record.updatedAt();
    Instant indexedAt = record.indexedAt() == null ? now : record.indexedAt();
    long revision = nextRepositoryRevision(record.repositoryId());
    PackageRecord stored = record.withRevision(revision, now);
    byte[] channelHash = channelHash(record.channel());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE conda_package_record
            SET channel_key = ?, name = ?, version = ?, build_string = ?, build_number = ?,
                archive_format = ?, record_json = ?, record_sha256 = ?, md5 = ?, sha256 = ?, size_bytes = ?,
                asset_id = ?, component_id = ?, source_kind = ?, revision = ?, indexed_at = ?,
                updated_at = ?
            WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
              AND subdir = ? AND filename = ?
            """,
        new Object[] {
          stored.channel(), stored.name(), stored.version(), stored.build(), stored.buildNumber(),
          stored.archiveFormat(), json.parameter(stored.metadata()), fingerprint(stored), stored.md5(),
          stored.sha256(), stored.size(), stored.assetId(), stored.componentId(), stored.sourceKind(), revision,
          nullableTimestamp(indexedAt), nullableTimestamp(now), stored.repositoryId(), channelHash,
          stored.channel(), stored.subdir(), stored.filename()
        },
        """
            INSERT INTO conda_package_record
              (repository_id, channel_key, channel_key_hash, subdir, filename, name, version,
               build_string, build_number, archive_format, record_json, md5, sha256, size_bytes,
               record_sha256, asset_id, component_id, source_kind, revision, indexed_at, created_at,
               updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          stored.repositoryId(), stored.channel(), channelHash, stored.subdir(), stored.filename(),
          stored.name(), stored.version(), stored.build(), stored.buildNumber(),
          stored.archiveFormat(), json.parameter(stored.metadata()), stored.md5(), stored.sha256(),
          stored.size(), fingerprint(stored), stored.assetId(), stored.componentId(), stored.sourceKind(), revision,
          nullableTimestamp(indexedAt), nullableTimestamp(now), nullableTimestamp(now)
        });
    jdbc.update(
        """
        DELETE FROM conda_package_tombstone
        WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
          AND subdir = ? AND filename = ?
        """,
        stored.repositoryId(), channelHash, stored.channel(), stored.subdir(), stored.filename());
    upsertChannelState(new ChannelState(
        stored.repositoryId(), stored.channel(), stored.subdir(), null, null, revision, indexedAt,
        now));
    invalidateContainingGroups(stored.repositoryId());
    return findPackage(
            stored.repositoryId(), stored.channel(), stored.subdir(), stored.filename())
        .orElseThrow();
  }

  @Override
  @Transactional
  public long replaceProxyPackages(
      long repositoryId,
      String channel,
      String subdir,
      String metadataSha256,
      String packageBaseUrl,
      List<PackageRecord> records,
      Instant indexedAt) {
    List<PackageRecord> inventory = records == null ? List.of() : records;
    return replaceProxyPackages(
        repositoryId,
        channel,
        subdir,
        metadataSha256,
        packageBaseUrl,
        visitor -> inventory.forEach(visitor),
        indexedAt);
  }

  @Override
  @Transactional
  public long replaceProxyPackages(
      long repositoryId,
      String channel,
      String subdir,
      String metadataSha256,
      String packageBaseUrl,
      PackageRecordSource records,
      Instant indexedAt) {
    Instant now = indexedAt == null ? Instant.now() : indexedAt;
    Optional<ChannelState> existing = findChannelState(repositoryId, channel, subdir);
    if (existing.isPresent()
        && java.util.Objects.equals(existing.orElseThrow().metadataSha256(), metadataSha256)
        && java.util.Objects.equals(existing.orElseThrow().packageBaseUrl(), packageBaseUrl)) {
      return existing.orElseThrow().revision();
    }

    byte[] hash = channelHash(channel);
    LinkedHashMap<String, ExistingProxyRecord> stored = new LinkedHashMap<>();
    jdbc.query(
        connection -> {
          PreparedStatement statement = streamingStatement(connection,
              """
              SELECT filename, record_sha256
              FROM conda_package_record
              WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
                AND subdir = ? AND source_kind = ?
              """);
          statement.setLong(1, repositoryId);
          statement.setBytes(2, hash);
          statement.setString(3, channel);
          statement.setString(4, subdir);
          statement.setString(5, SOURCE_PROXY);
          return statement;
        },
        (RowCallbackHandler) result -> {
          String filename = result.getString("filename");
          String recordSha256 = result.getString("record_sha256");
          stored.put(
              filename,
              new ExistingProxyRecord(
                  filename, recordSha256, recordSha256 == null || recordSha256.isBlank()));
        });
    if (stored.values().stream().anyMatch(ExistingProxyRecord::fingerprintMissing)) {
      backfillProxyFingerprints(repositoryId, channel, subdir, hash, stored);
    }

    PackageRecordSource inventory = records == null ? visitor -> { } : records;
    ProxyDelta delta = proxyDelta(
        repositoryId, channel, subdir, inventory, stored);
    ArrayList<String> removed = new ArrayList<>(stored.keySet());

    if (delta.changed().isEmpty() && removed.isEmpty()) {
      long revision = existing.map(ChannelState::revision)
          .orElseGet(() -> currentRepositoryRevision(repositoryId));
      upsertChannelState(new ChannelState(
          repositoryId, channel, subdir, metadataSha256, packageBaseUrl, revision, now, now));
      return revision;
    }

    long revision = nextRepositoryRevision(repositoryId);
    ArrayList<Object[]> updates = new ArrayList<>();
    ArrayList<Object[]> inserts = new ArrayList<>();
    inventory.visit(record -> {
      if (!delta.changed().contains(record.filename())) return;
      Object[] values = new Object[] {
        record.name(), record.version(), record.build(), record.buildNumber(), record.archiveFormat(),
        json.parameter(record.metadata()), fingerprint(record), record.md5(), record.sha256(),
        record.size(), revision, nullableTimestamp(now), nullableTimestamp(now), repositoryId,
        hash, channel, subdir, record.filename(), SOURCE_PROXY
      };
      if (delta.previouslyStored().contains(record.filename())) {
        updates.add(values);
      } else {
        inserts.add(new Object[] {
            repositoryId, channel, hash, subdir, record.filename(), record.name(), record.version(),
            record.build(), record.buildNumber(), record.archiveFormat(),
            json.parameter(record.metadata()),
            fingerprint(record), record.md5(), record.sha256(), record.size(), record.assetId(),
            record.componentId(), SOURCE_PROXY, revision, nullableTimestamp(now), nullableTimestamp(now),
            nullableTimestamp(now)
        });
      }
      if (updates.size() >= PROXY_BATCH_SIZE) flushProxyUpdates(updates);
      if (inserts.size() >= PROXY_BATCH_SIZE) flushProxyInserts(inserts);
    });
    flushProxyUpdates(updates);
    flushProxyInserts(inserts);
    if (!removed.isEmpty()) {
      ArrayList<Object[]> deletes = new ArrayList<>(PROXY_BATCH_SIZE);
      for (String filename : removed) {
        deletes.add(new Object[] {
          repositoryId, hash, channel, subdir, filename, SOURCE_PROXY
        });
        if (deletes.size() >= PROXY_BATCH_SIZE) flushProxyDeletes(deletes);
      }
      flushProxyDeletes(deletes);
    }
    upsertChannelState(
        new ChannelState(
            repositoryId, channel, subdir, metadataSha256, packageBaseUrl, revision, now, now));
    invalidateContainingGroups(repositoryId);
    return revision;
  }

  private ProxyDelta proxyDelta(
      long repositoryId,
      String channel,
      String subdir,
      PackageRecordSource records,
      Map<String, ExistingProxyRecord> stored) {
    HashSet<String> seen = new HashSet<>();
    LinkedHashSet<String> changed = new LinkedHashSet<>();
    LinkedHashSet<String> previouslyStored = new LinkedHashSet<>();
    records.visit(record -> {
      requireInventoryCoordinate(repositoryId, channel, subdir, record);
      requireSource(record, SOURCE_PROXY);
      if (!seen.add(record.filename())) {
        throw new IllegalArgumentException(
            "Duplicate Conda proxy inventory filename: " + record.filename());
      }
      ExistingProxyRecord current = stored.remove(record.filename());
      if (current == null
          || !java.util.Objects.equals(current.recordSha256(), fingerprint(record))) {
        changed.add(record.filename());
        if (current != null) previouslyStored.add(record.filename());
      }
    });
    return new ProxyDelta(
        Collections.unmodifiableSet(changed), Collections.unmodifiableSet(previouslyStored));
  }

  private void backfillProxyFingerprints(
      long repositoryId,
      String channel,
      String subdir,
      byte[] channelHash,
    Map<String, ExistingProxyRecord> stored) {
    jdbc.query(
        connection -> {
          PreparedStatement statement = streamingStatement(connection,
              """
              SELECT filename, record_json
              FROM conda_package_record
              WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
                AND subdir = ? AND source_kind = ? AND record_sha256 IS NULL
              """);
          statement.setLong(1, repositoryId);
          statement.setBytes(2, channelHash);
          statement.setString(3, channel);
          statement.setString(4, subdir);
          statement.setString(5, SOURCE_PROXY);
          return statement;
        },
        (RowCallbackHandler) result -> {
          String filename = result.getString("filename");
          ExistingProxyRecord current = stored.get(filename);
          if (current != null && current.fingerprintMissing()) {
            stored.put(
                filename,
                new ExistingProxyRecord(
                    filename,
                    metadataFingerprint(json.read(result.getString("record_json"))),
                    true));
          }
        });
    ArrayList<Object[]> backfills = new ArrayList<>(PROXY_BATCH_SIZE);
    for (ExistingProxyRecord record : stored.values()) {
      if (!record.fingerprintMissing() || record.recordSha256() == null) continue;
      backfills.add(new Object[] {
        record.recordSha256(), repositoryId, channelHash, channel, subdir, record.filename(),
        SOURCE_PROXY
      });
      if (backfills.size() >= PROXY_BATCH_SIZE) flushProxyFingerprintBackfills(backfills);
    }
    flushProxyFingerprintBackfills(backfills);
  }

  private void flushProxyFingerprintBackfills(List<Object[]> values) {
    if (values.isEmpty()) return;
    jdbc.batchUpdate(
        """
        UPDATE conda_package_record
        SET record_sha256 = ?
        WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ? AND subdir = ?
          AND filename = ? AND source_kind = ? AND record_sha256 IS NULL
        """,
        values);
    values.clear();
  }

  private void flushProxyUpdates(List<Object[]> values) {
    if (values.isEmpty()) return;
    jdbc.batchUpdate(
        """
        UPDATE conda_package_record
        SET name = ?, version = ?, build_string = ?, build_number = ?, archive_format = ?,
            record_json = ?, record_sha256 = ?, md5 = ?, sha256 = ?, size_bytes = ?,
            revision = ?, indexed_at = ?, updated_at = ?
        WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ? AND subdir = ?
          AND filename = ? AND source_kind = ?
        """,
        values);
    values.clear();
  }

  private void flushProxyInserts(List<Object[]> values) {
    if (values.isEmpty()) return;
    jdbc.batchUpdate(
        """
        INSERT INTO conda_package_record
          (repository_id, channel_key, channel_key_hash, subdir, filename, name, version,
           build_string, build_number, archive_format, record_json, record_sha256, md5, sha256,
           size_bytes, asset_id, component_id, source_kind, revision, indexed_at, created_at,
           updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        values);
    values.clear();
  }

  private void flushProxyDeletes(List<Object[]> values) {
    if (values.isEmpty()) return;
    jdbc.batchUpdate(
        """
        DELETE FROM conda_package_record
        WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ? AND subdir = ?
          AND filename = ? AND source_kind = ?
        """,
        values);
    values.clear();
  }

  @Override
  public Optional<PackageRecord> findPackage(
      long repositoryId, String channel, String subdir, String filename) {
    return jdbc.query(
            """
            SELECT * FROM conda_package_record
            WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
              AND subdir = ? AND filename = ?
            """,
            this::mapPackage,
            repositoryId,
            channelHash(channel),
            channel,
            subdir,
            filename)
        .stream()
        .findFirst();
  }

  @Override
  public List<PackageRecord> listPackages(long repositoryId, String channel, String subdir) {
    return jdbc.query(
        """
        SELECT * FROM conda_package_record
        WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ? AND subdir = ?
        ORDER BY filename
        """,
        this::mapPackage,
        repositoryId,
        channelHash(channel),
        channel,
        subdir);
  }

  @Override
  public List<PackageRecord> listPackagesByChannel(long repositoryId, String channel) {
    return jdbc.query(
        """
        SELECT * FROM conda_package_record
        WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
        ORDER BY subdir, filename
        """,
        this::mapPackage,
        repositoryId,
        channelHash(channel),
        channel);
  }

  @Override
  public Instant latestChannelUpdatedAt(long repositoryId, String channel) {
    Instant latest = jdbc.query(
            """
            SELECT MAX(updated_at) AS latest
            FROM conda_channel_state
            WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
            """,
            (result, row) -> nullableInstant(result, "latest"),
            repositoryId,
            channelHash(channel),
            channel)
        .stream()
        .findFirst()
        .orElse(null);
    return latest == null ? Instant.EPOCH : latest;
  }

  @Override
  @Transactional(readOnly = true)
  public void visitPackages(
      long repositoryId,
      String channel,
      String subdir,
      String archiveFormat,
      Consumer<PackageRecord> visitor) {
    if (visitor == null) return;
    byte[] hash = channelHash(channel);
    int[] row = {0};
    jdbc.query(
        connection -> {
          PreparedStatement statement = streamingStatement(connection,
              """
              SELECT * FROM conda_package_record
              WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
                AND subdir = ? AND archive_format = ?
              ORDER BY filename
              """);
          statement.setLong(1, repositoryId);
          statement.setBytes(2, hash);
          statement.setString(3, channel);
          statement.setString(4, subdir);
          statement.setString(5, archiveFormat);
          return statement;
        },
        (RowCallbackHandler) result -> visitor.accept(mapPackage(result, row[0]++)));
  }

  @Override
  @Transactional(readOnly = true)
  public void visitPreferredPackages(
      List<Long> repositoryIds,
      String channel,
      String subdir,
      String archiveFormat,
      Consumer<PackageRecord> visitor) {
    List<Long> ids = distinctIds(repositoryIds);
    if (ids.isEmpty() || visitor == null) return;
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    StringBuilder priority = new StringBuilder("CASE candidate.repository_id ");
    for (int i = 0; i < ids.size(); i++) {
      priority.append("WHEN ? THEN ").append(i).append(' ');
    }
    priority.append("ELSE ").append(ids.size()).append(" END");
    String sql = """
        SELECT selected.*
        FROM conda_package_record selected
        JOIN (
          SELECT ranked.id
          FROM (
            SELECT candidate.id,
                   ROW_NUMBER() OVER (
                     PARTITION BY candidate.filename ORDER BY %s
                   ) AS conda_priority
            FROM conda_package_record candidate
            WHERE candidate.repository_id IN (%s)
              AND candidate.channel_key_hash = ? AND candidate.channel_key = ?
              AND candidate.subdir = ? AND candidate.archive_format = ?
          ) ranked
          WHERE ranked.conda_priority = 1
        ) preferred ON preferred.id = selected.id
        ORDER BY selected.filename
        """.formatted(priority, placeholders);
    byte[] hash = channelHash(channel);
    int[] row = {0};
    jdbc.query(
        connection -> {
          PreparedStatement statement = streamingStatement(connection, sql);
          int parameter = 1;
          for (Long id : ids) statement.setLong(parameter++, id);
          for (Long id : ids) statement.setLong(parameter++, id);
          statement.setBytes(parameter++, hash);
          statement.setString(parameter++, channel);
          statement.setString(parameter++, subdir);
          statement.setString(parameter, archiveFormat);
          return statement;
        },
        (RowCallbackHandler) result -> visitor.accept(mapPackage(result, row[0]++)));
  }

  @Override
  @Transactional(readOnly = true)
  public void visitPackagesByChannel(
      long repositoryId, String channel, Consumer<PackageRecord> visitor) {
    if (visitor == null) return;
    byte[] hash = channelHash(channel);
    int[] row = {0};
    jdbc.query(
        connection -> {
          PreparedStatement statement = streamingStatement(connection,
              """
              SELECT * FROM conda_package_record
              WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
              ORDER BY name, subdir, filename
              """);
          statement.setLong(1, repositoryId);
          statement.setBytes(2, hash);
          statement.setString(3, channel);
          return statement;
        },
        (RowCallbackHandler) result -> visitor.accept(mapPackage(result, row[0]++)));
  }

  @Override
  @Transactional(readOnly = true)
  public void visitPreferredPackagesByChannel(
      List<Long> repositoryIds, String channel, Consumer<PackageRecord> visitor) {
    List<Long> ids = distinctIds(repositoryIds);
    if (ids.isEmpty() || visitor == null) return;
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    StringBuilder priority = new StringBuilder("CASE candidate.repository_id ");
    for (int i = 0; i < ids.size(); i++) {
      priority.append("WHEN ? THEN ").append(i).append(' ');
    }
    priority.append("ELSE ").append(ids.size()).append(" END");
    String sql = """
        SELECT selected.*
        FROM conda_package_record selected
        JOIN (
          SELECT ranked.id
          FROM (
            SELECT candidate.id,
                   ROW_NUMBER() OVER (
                     PARTITION BY candidate.subdir, candidate.filename ORDER BY %s
                   ) AS conda_priority
            FROM conda_package_record candidate
            WHERE candidate.repository_id IN (%s)
              AND candidate.channel_key_hash = ? AND candidate.channel_key = ?
          ) ranked
          WHERE ranked.conda_priority = 1
        ) preferred ON preferred.id = selected.id
        ORDER BY selected.name, selected.subdir, selected.filename
        """.formatted(priority, placeholders);
    byte[] hash = channelHash(channel);
    int[] row = {0};
    jdbc.query(
        connection -> {
          PreparedStatement statement = streamingStatement(connection, sql);
          int parameter = 1;
          for (Long id : ids) statement.setLong(parameter++, id);
          for (Long id : ids) statement.setLong(parameter++, id);
          statement.setBytes(parameter++, hash);
          statement.setString(parameter, channel);
          return statement;
        },
        (RowCallbackHandler) result -> visitor.accept(mapPackage(result, row[0]++)));
  }

  @Override
  public Optional<PackageRecord> findPreferredPackage(
      List<Long> repositoryIds, String channel, String subdir, String filename) {
    List<Long> ids = distinctIds(repositoryIds);
    if (ids.isEmpty()) return Optional.empty();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    StringBuilder priority = new StringBuilder("CASE repository_id ");
    for (int i = 0; i < ids.size(); i++) {
      priority.append("WHEN ? THEN ").append(i).append(' ');
    }
    priority.append("ELSE ").append(ids.size()).append(" END");
    String sql = """
        SELECT * FROM conda_package_record
        WHERE repository_id IN (%s) AND channel_key_hash = ? AND channel_key = ?
          AND subdir = ? AND filename = ?
        ORDER BY %s
        LIMIT 1
        """.formatted(placeholders, priority);
    ArrayList<Object> args = new ArrayList<>();
    args.addAll(ids);
    args.add(channelHash(channel));
    args.add(channel);
    args.add(subdir);
    args.add(filename);
    args.addAll(ids);
    return jdbc.query(sql, this::mapPackage, args.toArray()).stream().findFirst();
  }

  @Override
  public Set<String> findPreferredPackageFilenames(
      List<Long> repositoryIds,
      String channel,
      String subdir,
      Collection<String> filenames) {
    List<Long> ids = distinctIds(repositoryIds);
    List<String> requested = filenames == null
        ? List.of()
        : filenames.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (ids.isEmpty() || requested.isEmpty()) return Set.of();
    String repositoryPlaceholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    LinkedHashSet<String> existing = new LinkedHashSet<>();
    for (int offset = 0; offset < requested.size(); offset += 500) {
      List<String> batch = requested.subList(offset, Math.min(offset + 500, requested.size()));
      String filenamePlaceholders = String.join(",", Collections.nCopies(batch.size(), "?"));
      String sql = """
          SELECT DISTINCT filename
          FROM conda_package_record
          WHERE repository_id IN (%s) AND channel_key_hash = ? AND channel_key = ?
            AND subdir = ? AND filename IN (%s)
          """.formatted(repositoryPlaceholders, filenamePlaceholders);
      ArrayList<Object> args = new ArrayList<>();
      args.addAll(ids);
      args.add(channelHash(channel));
      args.add(channel);
      args.add(subdir);
      args.addAll(batch);
      existing.addAll(jdbc.queryForList(sql, String.class, args.toArray()));
    }
    return Set.copyOf(existing);
  }

  @Override
  public List<String> listChannels(long repositoryId) {
    return jdbc.queryForList(
        """
        SELECT channel_key FROM conda_channel_state
        WHERE repository_id = ? GROUP BY channel_key ORDER BY channel_key
        """,
        String.class,
        repositoryId);
  }

  @Override
  public Optional<ChannelState> findChannelState(
      long repositoryId, String channel, String subdir) {
    return jdbc.query(
            """
            SELECT * FROM conda_channel_state
            WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ? AND subdir = ?
            """,
            this::mapChannelState,
            repositoryId,
            channelHash(channel),
            channel,
            subdir)
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public void ensureChannelState(ChannelState state) {
    if (findChannelState(state.repositoryId(), state.channel(), state.subdir()).isPresent()) {
      return;
    }
    Instant now = state.updatedAt() == null ? Instant.now() : state.updatedAt();
    Instant indexedAt = state.indexedAt() == null ? now : state.indexedAt();
    long revision = state.revision() > 0
        ? state.revision()
        : currentRepositoryRevision(state.repositoryId());
    byte[] hash = channelHash(state.channel());
    jdbc.update(
        conda.insertChannelStateIfAbsentSql(),
        state.repositoryId(), state.channel(), hash, state.subdir(), state.metadataSha256(),
        state.packageBaseUrl(), revision, nullableTimestamp(indexedAt), nullableTimestamp(now));
  }

  @Override
  @Transactional
  public Optional<PackageRecord> tombstoneAndDeletePackage(
      long repositoryId,
      String channel,
      String subdir,
      String filename,
      String reason,
      long requestedRevision,
      Instant deletedAt) {
    byte[] hash = channelHash(channel);
    Optional<PackageRecord> existing = jdbc.query(
            """
            SELECT * FROM conda_package_record
            WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
              AND subdir = ? AND filename = ? FOR UPDATE
            """,
            this::mapPackage,
            repositoryId,
            hash,
            channel,
            subdir,
            filename)
        .stream()
        .findFirst();
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    long revision = requestedRevision > 0 ? requestedRevision : nextRepositoryRevision(repositoryId);
    Instant deleted = deletedAt == null ? Instant.now() : deletedAt;
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE conda_package_tombstone
            SET channel_key = ?, reason = ?, revision = ?, deleted_at = ?
            WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ?
              AND subdir = ? AND filename = ?
            """,
        new Object[] {
          channel, reason, revision, nullableTimestamp(deleted), repositoryId, hash, channel,
          subdir, filename
        },
        """
            INSERT INTO conda_package_tombstone
              (repository_id, channel_key, channel_key_hash, subdir, filename, reason, revision,
               deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          repositoryId, channel, hash, subdir, filename, reason, revision,
          nullableTimestamp(deleted)
        });
    jdbc.update(
        "DELETE FROM conda_package_record WHERE id = ?", existing.orElseThrow().id());
    upsertChannelState(new ChannelState(
        repositoryId, channel, subdir, null, null, revision, deleted, deleted));
    invalidateContainingGroups(repositoryId);
    return existing;
  }

  @Override
  public List<Tombstone> listTombstones(long repositoryId, String channel, String subdir) {
    return jdbc.query(
        """
        SELECT * FROM conda_package_tombstone
        WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ? AND subdir = ?
        ORDER BY revision, filename
        """,
        (rs, row) -> new Tombstone(
            rs.getLong("repository_id"), rs.getString("channel_key"), rs.getString("subdir"),
            rs.getString("filename"), rs.getString("reason"), rs.getLong("revision"),
            nullableInstant(rs, "deleted_at")),
        repositoryId,
        channelHash(channel),
        channel,
        subdir);
  }

  @Override
  public Optional<GroupSourceBinding> findGroupSourceBinding(
      long groupRepositoryId, String channel, String subdir, String filename) {
    return jdbc.query(
            """
            SELECT * FROM conda_group_source_binding
            WHERE group_repository_id = ? AND channel_key_hash = ? AND channel_key = ?
              AND subdir = ? AND filename = ?
            """,
            this::mapGroupBinding,
            groupRepositoryId,
            channelHash(channel),
            channel,
            subdir,
            filename)
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public void upsertGroupSourceBinding(GroupSourceBinding binding) {
    Instant now = binding.updatedAt() == null ? Instant.now() : binding.updatedAt();
    Instant boundAt = binding.boundAt() == null ? now : binding.boundAt();
    byte[] hash = channelHash(binding.channel());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE conda_group_source_binding
            SET channel_key = ?, member_repository_id = ?, member_revision = ?, sha256 = ?,
                group_config_revision = ?, bound_at = ?, updated_at = ?
            WHERE group_repository_id = ? AND channel_key_hash = ? AND channel_key = ?
              AND subdir = ? AND filename = ?
            """,
        new Object[] {
          binding.channel(), binding.memberRepositoryId(), binding.memberRevision(), binding.sha256(),
          binding.groupConfigRevision(), nullableTimestamp(boundAt), nullableTimestamp(now),
          binding.groupRepositoryId(), hash, binding.channel(), binding.subdir(), binding.filename()
        },
        """
            INSERT INTO conda_group_source_binding
              (group_repository_id, channel_key, channel_key_hash, subdir, filename,
               member_repository_id, member_revision, sha256, group_config_revision, bound_at,
               updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          binding.groupRepositoryId(), binding.channel(), hash, binding.subdir(), binding.filename(),
          binding.memberRepositoryId(), binding.memberRevision(), binding.sha256(),
          binding.groupConfigRevision(), nullableTimestamp(boundAt), nullableTimestamp(now)
        });
  }

  @Override
  public void deleteGroupSourceBindings(long groupRepositoryId) {
    jdbc.update(
        "DELETE FROM conda_group_source_binding WHERE group_repository_id = ?",
        groupRepositoryId);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Lease> tryAcquireLease(String leaseKey, String owner, Instant expiresAt) {
    Instant now = Instant.now();
    if (expiresAt == null || !expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Lease expiry must be in the future");
    }
    int updated = jdbc.update(
        """
        UPDATE conda_coordinate_lease
        SET owner = ?, fencing_token = fencing_token + 1, expires_at = ?, updated_at = ?,
            attempt_count = attempt_count + 1
        WHERE lease_key = ? AND expires_at < ?
        """,
        owner,
        nullableTimestamp(expiresAt),
        nullableTimestamp(now),
        leaseKey,
        nullableTimestamp(now));
    if (updated == 0) {
      jdbc.update(
          conda.insertCoordinateLeaseIfAbsentSql(),
          leaseKey,
          owner,
          nullableTimestamp(expiresAt),
          nullableTimestamp(now));
    }
    return findLease(leaseKey)
        .filter(lease -> lease.owner().equals(owner) && lease.expiresAt().isAfter(now));
  }

  @Override
  public boolean renewLease(
      String leaseKey, String owner, long fencingToken, Instant expiresAt) {
    Instant now = Instant.now();
    if (expiresAt == null || !expiresAt.isAfter(now)) {
      return false;
    }
    return jdbc.update(
            """
            UPDATE conda_coordinate_lease SET expires_at = ?, updated_at = ?
            WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at >= ?
            """,
            nullableTimestamp(expiresAt),
            nullableTimestamp(now),
            leaseKey,
            owner,
            fencingToken,
            nullableTimestamp(now))
        > 0;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseLease(String leaseKey, String owner, long fencingToken) {
    Instant now = Instant.now();
    jdbc.update(
        """
        UPDATE conda_coordinate_lease SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ?
        """,
        nullableTimestamp(now.minusMillis(1)),
        nullableTimestamp(now),
        leaseKey,
        owner,
        fencingToken);
  }

  @Override
  @Transactional
  public int deleteExpiredLeases(Instant expiredBefore, int limit) {
    Instant cutoff = expiredBefore == null ? Instant.now() : expiredBefore;
    int bounded = Math.max(1, Math.min(10_000, limit));
    List<String> keys = jdbc.queryForList(
        """
        SELECT lease_key FROM conda_coordinate_lease
        WHERE expires_at < ?
        ORDER BY expires_at, lease_key
        LIMIT ?
        """,
        String.class,
        nullableTimestamp(cutoff),
        bounded);
    if (keys.isEmpty()) return 0;
    int[][] counts = jdbc.batchUpdate(
        "DELETE FROM conda_coordinate_lease WHERE lease_key = ? AND expires_at < ?",
        keys,
        Math.min(256, keys.size()),
        (statement, key) -> {
          statement.setString(1, key);
          statement.setTimestamp(2, nullableTimestamp(cutoff));
        });
    int deleted = 0;
    for (int[] batch : counts) {
      for (int count : batch) {
        if (count > 0) deleted += count;
      }
    }
    return deleted;
  }

  @Override
  @Transactional
  public void deleteRepositoryState(long repositoryId) {
    jdbc.update(
        "DELETE FROM conda_group_source_binding WHERE group_repository_id = ?", repositoryId);
    jdbc.update(
        "DELETE FROM conda_group_source_binding WHERE member_repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM conda_package_tombstone WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM conda_channel_state WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM conda_package_record WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM conda_coordinate_lease WHERE lease_key LIKE ?", leasePrefix(repositoryId));
    jdbc.update("DELETE FROM cache_version WHERE name = ?", revisionKey(repositoryId));
  }

  private void upsertChannelState(ChannelState state) {
    byte[] hash = channelHash(state.channel());
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
            UPDATE conda_channel_state
            SET channel_key = ?, metadata_sha256 = ?, package_base_url = ?, revision = ?,
                indexed_at = ?, updated_at = ?
            WHERE repository_id = ? AND channel_key_hash = ? AND channel_key = ? AND subdir = ?
            """,
        new Object[] {
          state.channel(), state.metadataSha256(), state.packageBaseUrl(), state.revision(),
          nullableTimestamp(state.indexedAt()), nullableTimestamp(state.updatedAt()),
          state.repositoryId(), hash, state.channel(), state.subdir()
        },
        """
            INSERT INTO conda_channel_state
              (repository_id, channel_key, channel_key_hash, subdir, metadata_sha256,
               package_base_url, revision, indexed_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        new Object[] {
          state.repositoryId(), state.channel(), hash, state.subdir(), state.metadataSha256(),
          state.packageBaseUrl(), state.revision(), nullableTimestamp(state.indexedAt()),
          nullableTimestamp(state.updatedAt())
        });
  }

  private Optional<Lease> findLease(String leaseKey) {
    return jdbc.query(
            "SELECT * FROM conda_coordinate_lease WHERE lease_key = ?",
            (rs, row) -> new Lease(
                rs.getString("lease_key"), rs.getString("owner"), rs.getLong("fencing_token"),
                nullableInstant(rs, "expires_at"), nullableInstant(rs, "updated_at")),
            leaseKey)
        .stream()
        .findFirst();
  }

  private PackageRecord mapPackage(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new PackageRecord(
        rs.getLong("id"), rs.getLong("repository_id"), rs.getString("channel_key"),
        rs.getString("subdir"), rs.getString("filename"), rs.getString("name"),
        rs.getString("version"), rs.getString("build_string"), rs.getLong("build_number"),
        rs.getString("archive_format"), json.read(rs.getString("record_json")),
        rs.getString("record_sha256"),
        rs.getString("md5"), rs.getString("sha256"), rs.getLong("size_bytes"),
        nullableLong(rs, "asset_id"), nullableLong(rs, "component_id"),
        rs.getString("source_kind"), rs.getLong("revision"), nullableInstant(rs, "indexed_at"),
        nullableInstant(rs, "updated_at"));
  }

  private ChannelState mapChannelState(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    return new ChannelState(
        rs.getLong("repository_id"), rs.getString("channel_key"), rs.getString("subdir"),
        rs.getString("metadata_sha256"), rs.getString("package_base_url"),
        rs.getLong("revision"),
        nullableInstant(rs, "indexed_at"), nullableInstant(rs, "updated_at"));
  }

  private GroupSourceBinding mapGroupBinding(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    return new GroupSourceBinding(
        rs.getLong("group_repository_id"), rs.getString("channel_key"), rs.getString("subdir"),
        rs.getString("filename"), rs.getLong("member_repository_id"),
        rs.getLong("member_revision"), rs.getString("sha256"),
        rs.getLong("group_config_revision"), nullableInstant(rs, "bound_at"),
        nullableInstant(rs, "updated_at"));
  }

  private PreparedStatement streamingStatement(java.sql.Connection connection, String sql)
      throws java.sql.SQLException {
    PreparedStatement statement = connection.prepareStatement(
        sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    statement.setFetchSize(conda.streamingFetchSize());
    return statement;
  }

  private static List<Long> distinctIds(Collection<Long> repositoryIds) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    if (repositoryIds != null) {
      repositoryIds.stream().filter(java.util.Objects::nonNull).forEach(ids::add);
    }
    return List.copyOf(ids);
  }

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
          EnumColumns.write(RepositoryFormat.CONDA),
          EnumColumns.write(RepositoryType.GROUP));
      for (Long groupId : containingGroups) {
        if (groupId == null || !visited.add(groupId)) {
          continue;
        }
        nextRepositoryRevision(groupId);
        pending.addLast(groupId);
      }
    }
  }

  private static void requireSource(PackageRecord record, String expected) {
    if (!expected.equals(record.sourceKind())) {
      throw new IllegalArgumentException("Unexpected Conda package source kind");
    }
  }

  private static void requireInventoryCoordinate(
      long repositoryId, String channel, String subdir, PackageRecord record) {
    if (record.repositoryId() != repositoryId
        || !channel.equals(record.channel())
        || !subdir.equals(record.subdir())) {
      throw new IllegalArgumentException("Proxy inventory records must target one channel subdir");
    }
  }

  private String fingerprint(PackageRecord record) {
    if (record.recordSha256() != null && !record.recordSha256().isBlank()) {
      return record.recordSha256();
    }
    return metadataFingerprint(record.metadata());
  }

  private String metadataFingerprint(Map<String, Object> metadata) {
    try {
      String encoded = json.write(castCanonicalMap(metadata == null ? Map.of() : metadata));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(encoded.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static Object canonicalJson(Object value) {
    if (value instanceof Map<?, ?> map) {
      java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
      map.forEach((key, child) -> sorted.put(String.valueOf(key), canonicalJson(child)));
      return sorted;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(JdbcCondaRegistryDao::canonicalJson).toList();
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castCanonicalMap(Map<String, Object> value) {
    return (Map<String, Object>) canonicalJson(value);
  }

  private static byte[] channelHash(String channel) {
    return PersistenceHashes.sha256(channel == null ? "" : channel);
  }

  private static String revisionKey(long repositoryId) {
    return REVISION_PREFIX + repositoryId;
  }

  private static String leasePrefix(long repositoryId) {
    return "conda:" + repositoryId + ":%";
  }

  private record ExistingProxyRecord(
      String filename, String recordSha256, boolean fingerprintMissing) { }

  private record ProxyDelta(Set<String> changed, Set<String> previouslyStored) { }
}
