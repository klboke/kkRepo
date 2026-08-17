package com.github.klboke.kkrepo.persistence.mysql;

import com.github.klboke.kkrepo.persistence.jdbc.spi.AlpinePersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.ComponentPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CondaPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.ConanPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import com.github.klboke.kkrepo.persistence.jdbc.spi.JsonPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.MigrationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SearchPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SecurityPersistenceDialect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

/** MySQL 8 implementation of the shared persistence dialect contracts. */
public final class MySqlDatabaseDialect implements DatabaseDialect {
  private final MySqlJsonPersistenceDialect json = new MySqlJsonPersistenceDialect();
  private final AlpinePersistenceDialect alpine = new MySqlAlpinePersistenceDialect();
  private final ComponentPersistenceDialect components = new MySqlComponentPersistenceDialect(json);
  private final CondaPersistenceDialect conda = new MySqlCondaPersistenceDialect();
  private final ConanPersistenceDialect conan = new MySqlConanPersistenceDialect();
  private final CoordinationPersistenceDialect coordination = new MySqlCoordinationPersistenceDialect();
  private final SearchPersistenceDialect search = new MySqlSearchPersistenceDialect();
  private final SecurityPersistenceDialect security = new MySqlSecurityPersistenceDialect(json);
  private final MigrationPersistenceDialect migrations =
      new MySqlMigrationPersistenceDialect(json);

  @Override
  public DatabaseType type() {
    return DatabaseType.MYSQL;
  }

  @Override
  public String tableReferenceWithPreferredIndex(String tableName, String indexName) {
    return tableName + " FORCE INDEX (" + indexName + ")";
  }

  @Override
  public ComponentPersistenceDialect components() {
    return components;
  }

  @Override
  public AlpinePersistenceDialect alpine() {
    return alpine;
  }

  @Override
  public CondaPersistenceDialect conda() {
    return conda;
  }

  @Override
  public ConanPersistenceDialect conan() {
    return conan;
  }

  @Override
  public CoordinationPersistenceDialect coordination() {
    return coordination;
  }

  @Override
  public JsonPersistenceDialect json() {
    return json;
  }

  @Override
  public SearchPersistenceDialect search() {
    return search;
  }

  @Override
  public SecurityPersistenceDialect security() {
    return security;
  }

  @Override
  public MigrationPersistenceDialect migrations() {
    return migrations;
  }

  private static final class MySqlAlpinePersistenceDialect
      implements AlpinePersistenceDialect {
    @Override
    public KeysetCursor packageNameIdCursor(String packageName, long packageId) {
      return new KeysetCursor(
          "(package_name > ? OR (package_name = ? AND id > ?))",
          List.of(packageName, packageName, packageId));
    }

    @Override
    public String pendingSuitesSql() {
      return """
          SELECT alpine_suite_state.*
          FROM alpine_suite_state FORCE INDEX (idx_alpine_suite_worker)
          STRAIGHT_JOIN repository r ON r.id = alpine_suite_state.repository_id
          WHERE alpine_suite_state.publish_pending = TRUE
            AND (alpine_suite_state.desired_at <= ?
              OR COALESCE(alpine_suite_state.pending_since, alpine_suite_state.desired_at) <= ?)
            AND (alpine_suite_state.last_error_at IS NULL
              OR alpine_suite_state.last_error_at <= ?)
            AND r.online = true AND r.format = 'alpine'
            AND r.type IN ('hosted', 'proxy', 'group')
          ORDER BY alpine_suite_state.desired_at, alpine_suite_state.repository_id,
            alpine_suite_state.distribution_name
          LIMIT ?
          """;
    }

    @Override
    public String snapshotCleanupCandidatesSql() {
      return """
          SELECT candidate.* FROM alpine_snapshot candidate
          JOIN alpine_suite_state suite
            ON suite.repository_id = candidate.repository_id
            AND suite.distribution_name = candidate.distribution_name
          WHERE candidate.published_at IS NOT NULL AND candidate.created_at < ?
            AND candidate.revision <> suite.published_revision
            AND EXISTS (
              SELECT 1 FROM alpine_snapshot newer
              WHERE newer.repository_id = candidate.repository_id
                AND newer.distribution_name = candidate.distribution_name
                AND newer.published_at IS NOT NULL
                AND newer.revision > candidate.revision
              ORDER BY newer.revision DESC
              LIMIT 1 OFFSET ?)
          ORDER BY candidate.created_at, candidate.repository_id,
            candidate.distribution_name, candidate.revision
          LIMIT ?
          """;
    }
  }

  private static final class MySqlComponentPersistenceDialect
      implements ComponentPersistenceDialect {
    private static final String UPSERT_RETURNING_ID_SQL = """
        INSERT INTO component
          (repository_id, format, namespace, name, version, kind, coordinate_hash,
           attributes_json, last_updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          id = LAST_INSERT_ID(id),
          last_updated_at = VALUES(last_updated_at)
        """;
    private static final String UPSERT_SEARCH_DOCUMENT_SQL = """
        INSERT INTO component_search
          (component_id, repository_id, format, namespace, name, version, keywords, refreshed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))
        ON DUPLICATE KEY UPDATE
          repository_id = VALUES(repository_id),
          format = VALUES(format),
          namespace = VALUES(namespace),
          name = VALUES(name),
          version = VALUES(version),
          keywords = VALUES(keywords),
          refreshed_at = NOW(3)
        """;

    private final JsonPersistenceDialect json;

    private MySqlComponentPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public long upsertAndReturnId(JdbcOperations jdbc, ComponentUpsert command) {
      Long key = jdbc.execute((ConnectionCallback<Long>) connection -> {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_RETURNING_ID_SQL)) {
          statement.setLong(1, command.repositoryId());
          statement.setString(2, command.format());
          statement.setString(3, command.namespace());
          statement.setString(4, command.name());
          statement.setString(5, command.version());
          statement.setString(6, command.kind());
          statement.setBytes(7, command.coordinateHash());
          json.bind(statement, 8, command.attributesJson());
          statement.setTimestamp(9, command.lastUpdatedAt());
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT LAST_INSERT_ID()");
            var resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getLong(1) : null;
        }
      });
      if (key == null || key <= 0) {
        throw new IllegalStateException("Component upsert did not return an id");
      }
      return key;
    }

    @Override
    public String caseSensitiveText(String expression) {
      return "CAST(" + expression + " AS BINARY)";
    }

    @Override
    public List<String> cleanupFamilyOrderExpressions() {
      return List.of(
          "cleanup_namespace_prefix",
          "cleanup_namespace_hash",
          "cleanup_name_prefix",
          "cleanup_name_hash",
          "cleanup_kind_key");
    }

    @Override
    public List<Object> cleanupFamilyCursorValues(
        String namespace, String name, String kind) {
      String normalizedNamespace = namespace == null ? "" : namespace;
      String normalizedName = name == null ? "" : name;
      String normalizedKind = kind == null ? "" : kind;
      return List.of(
          binaryPrefix(normalizedNamespace, 384),
          rawSha256(normalizedNamespace),
          binaryPrefix(normalizedName, 384),
          rawSha256(normalizedName),
          binaryPrefix(normalizedKind, 200));
    }

    @Override
    public CleanupFamilyCursorClause cleanupFamilyCursorClause(
        String namespace, String name, String kind) {
      List<String> expressions = cleanupFamilyOrderExpressions();
      List<Object> values = cleanupFamilyCursorValues(namespace, name, kind);
      List<String> alternatives = new ArrayList<>(expressions.size());
      List<Object> arguments = new ArrayList<>(expressions.size() * (expressions.size() + 1) / 2);
      for (int rangeColumn = 0; rangeColumn < expressions.size(); rangeColumn++) {
        List<String> terms = new ArrayList<>(rangeColumn + 1);
        for (int equalityColumn = 0; equalityColumn < rangeColumn; equalityColumn++) {
          terms.add(expressions.get(equalityColumn) + " = ?");
          arguments.add(values.get(equalityColumn));
        }
        terms.add(expressions.get(rangeColumn) + " > ?");
        arguments.add(values.get(rangeColumn));
        alternatives.add("(" + String.join(" AND ", terms) + ")");
      }
      return new CleanupFamilyCursorClause(
          "(" + String.join(" OR ", alternatives) + ")", arguments);
    }

    private static byte[] rawSha256(String value) {
      try {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
      } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException("SHA-256 is not available", exception);
      }
    }

    private static byte[] binaryPrefix(String value, int maxBytes) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      return bytes.length <= maxBytes
          ? bytes
          : java.util.Arrays.copyOf(bytes, maxBytes);
    }

    @Override
    public void upsertSearchDocument(JdbcOperations jdbc, ComponentSearchDocument document) {
      jdbc.update(
          UPSERT_SEARCH_DOCUMENT_SQL,
          document.componentId(),
          document.repositoryId(),
          document.format(),
          document.namespace(),
          document.name(),
          document.version(),
          document.keywords());
    }
  }

  private static final class MySqlCoordinationPersistenceDialect
      implements CoordinationPersistenceDialect {
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z0-9_.]+");

    @Override
    public long bumpCacheVersion(JdbcOperations jdbc, String name) {
      Long version = jdbc.execute((ConnectionCallback<Long>) connection -> {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cache_version (name, version, updated_at)
            VALUES (?, LAST_INSERT_ID(1), NOW(3))
            ON DUPLICATE KEY UPDATE
              version = LAST_INSERT_ID(version + 1),
              updated_at = NOW(3)
            """)) {
          statement.setString(1, name);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT LAST_INSERT_ID()");
            var resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getLong(1) : null;
        }
      });
      return version == null ? 0 : version;
    }

    @Override
    public String oldestBacklogAgeSecondsExpression(String timestampColumn) {
      if (timestampColumn == null || !COLUMN.matcher(timestampColumn).matches()) {
        throw new IllegalArgumentException("Unsafe timestamp column: " + timestampColumn);
      }
      return "COALESCE(TIMESTAMPDIFF(SECOND, MIN(" + timestampColumn + "), NOW(3)), 0)";
    }
  }

  private static final class MySqlConanPersistenceDialect
      implements ConanPersistenceDialect {
    private static final String RECIPE_NAME_RANGE_SQL = """
        SELECT * FROM conan_recipe
        WHERE repository_id = ?
          AND name_key >= CAST(? AS CHAR CHARACTER SET ascii) COLLATE ascii_bin
          AND name_key < CAST(? AS CHAR CHARACTER SET ascii) COLLATE ascii_bin
          AND id > ?
        ORDER BY id
        LIMIT ?
        """;

    @Override
    public String recipeNameRangeSql() {
      return RECIPE_NAME_RANGE_SQL;
    }
  }

  private static final class MySqlCondaPersistenceDialect
      implements CondaPersistenceDialect {
    private static final String INSERT_CHANNEL_STATE_IF_ABSENT_SQL = """
        INSERT IGNORE INTO conda_channel_state
          (repository_id, channel_key, channel_key_hash, subdir, metadata_sha256,
           package_base_url, revision, indexed_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    private static final String INSERT_COORDINATE_LEASE_IF_ABSENT_SQL = """
        INSERT IGNORE INTO conda_coordinate_lease
          (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
        VALUES (?, ?, 1, 1, ?, ?)
        """;

    @Override
    public String insertChannelStateIfAbsentSql() {
      return INSERT_CHANNEL_STATE_IF_ABSENT_SQL;
    }

    @Override
    public String insertCoordinateLeaseIfAbsentSql() {
      return INSERT_COORDINATE_LEASE_IF_ABSENT_SQL;
    }

    @Override
    public int streamingFetchSize() {
      return Integer.MIN_VALUE;
    }
  }

  private static final class MySqlJsonPersistenceDialect implements JsonPersistenceDialect {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.]+");
    private static final Pattern PATH_PART = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public Object jdbcValue(String json) {
      return json;
    }

    @Override
    public void bind(PreparedStatement statement, int index, String json) throws SQLException {
      statement.setString(index, json);
    }

    @Override
    public String extractText(String column, String... path) {
      return "JSON_UNQUOTE(JSON_EXTRACT(" + column(column) + ", '" + path(path) + "'))";
    }

    @Override
    public String setBoolean(String column, boolean value, String... path) {
      return "JSON_SET(" + column(column) + ", '" + path(path) + "', " + value + ")";
    }

    @Override
    public String selectLongsFromArray(String columnAlias) {
      String alias = column(columnAlias);
      return """
          SELECT %s
          FROM JSON_TABLE(
            ?, '$[*]' COLUMNS (%s BIGINT PATH '$')
          ) AS scope
          """.formatted(alias, alias);
    }

    private static String column(String value) {
      if (value == null || !IDENTIFIER.matcher(value).matches()) {
        throw new IllegalArgumentException("Unsafe JSON column: " + value);
      }
      return value;
    }

    private static String path(String... parts) {
      if (parts == null || parts.length == 0) {
        throw new IllegalArgumentException("JSON path is required");
      }
      StringBuilder path = new StringBuilder("$");
      for (String part : parts) {
        if (part == null || !PATH_PART.matcher(part).matches()) {
          throw new IllegalArgumentException("Unsafe JSON path part: " + part);
        }
        path.append('.').append(part);
      }
      return path.toString();
    }
  }

  private static final class MySqlSearchPersistenceDialect implements SearchPersistenceDialect {
    private static final int DEFAULT_INNODB_FT_MIN_TOKEN_SIZE = 3;
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public String componentSearchPredicate(String searchAlias) {
      if (searchAlias == null || !ALIAS.matcher(searchAlias).matches()) {
        throw new IllegalArgumentException("Unsafe component search alias: " + searchAlias);
      }
      return "MATCH(" + searchAlias + ".namespace, " + searchAlias + ".name, "
          + searchAlias + ".version, " + searchAlias + ".keywords) "
          + "AGAINST (? IN BOOLEAN MODE)";
    }

    @Override
    public String prepareComponentQuery(String keyword) {
      if (keyword == null || keyword.isBlank()) {
        return "";
      }
      List<String> terms = new ArrayList<>();
      StringBuilder token = new StringBuilder();
      for (int index = 0; index < keyword.length(); index++) {
        char value = keyword.charAt(index);
        // MySQL's built-in FULLTEXT parser keeps underscores inside a word. Preserve them in the
        // boolean query as well; splitting them would require short fragments that InnoDB does not
        // index by default and would make exact package names such as "name_h2_1" unsearchable.
        if (Character.isLetterOrDigit(value) || value == '_') {
          token.append(Character.toLowerCase(value));
        } else {
          addTerm(terms, token);
        }
      }
      addTerm(terms, token);
      return String.join(" ", terms);
    }

    private static void addTerm(List<String> terms, StringBuilder token) {
      if (!token.isEmpty()) {
        // Requiring a term omitted by InnoDB's default FULLTEXT parser makes the whole boolean
        // query unmatchable. Keep short terms optional while retaining AND semantics for indexed
        // terms.
        boolean indexedByDefault = token.length() >= DEFAULT_INNODB_FT_MIN_TOKEN_SIZE;
        terms.add((indexedByDefault ? "+" : "") + token + "*");
        token.setLength(0);
      }
    }
  }

  private static final class MySqlSecurityPersistenceDialect
      implements SecurityPersistenceDialect {
    private final JsonPersistenceDialect json;

    private MySqlSecurityPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public void insertPrivilegeIfAbsent(JdbcOperations jdbc, PrivilegeInsert privilege) {
      jdbc.update("""
          INSERT IGNORE INTO security_privilege
            (privilege_id, name, description, type, read_only, properties_json)
          VALUES (?, ?, ?, ?, ?, ?)
          """,
          privilege.privilegeId(),
          privilege.name(),
          privilege.description(),
          privilege.type(),
          privilege.readOnly(),
          json.jdbcValue(privilege.propertiesJson()));
    }

    @Override
    public void assignRoleIfAbsent(JdbcOperations jdbc, long userId, String roleId) {
      jdbc.update("""
          INSERT IGNORE INTO security_user_role (user_id, role_id)
          VALUES (?, ?)
          """, userId, roleId);
    }

    @Override
    public void grantPrivilegeIfAbsent(JdbcOperations jdbc, String roleId, String privilegeId) {
      jdbc.update("""
          INSERT IGNORE INTO security_role_privilege (role_id, privilege_id)
          VALUES (?, ?)
          """, roleId, privilegeId);
    }

    @Override
    public void inheritRoleIfAbsent(JdbcOperations jdbc, String roleId, String childRoleId) {
      jdbc.update("""
          INSERT IGNORE INTO security_role_inheritance (role_id, child_role_id)
          VALUES (?, ?)
          """, roleId, childRoleId);
    }
  }

  private static final class MySqlMigrationPersistenceDialect
      implements MigrationPersistenceDialect {
    private static final String UPSERT_DISCOVERED_ASSET_SQL = """
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
        """;

    private final JsonPersistenceDialect json;

    private MySqlMigrationPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public void upsertDiscoveredAssets(JdbcOperations jdbc, List<DiscoveredAsset> assets) {
      jdbc.batchUpdate(UPSERT_DISCOVERED_ASSET_SQL, assets, Math.min(assets.size(), 500),
          (statement, asset) -> bind(statement, asset));
    }

    private void bind(PreparedStatement statement, DiscoveredAsset asset) throws SQLException {
      statement.setLong(1, asset.repositoryJobId());
      statement.setString(2, asset.sourceAssetId());
      statement.setString(3, asset.sourceComponentId());
      statement.setString(4, asset.sourcePath());
      statement.setBytes(5, asset.sourcePathHash());
      statement.setString(6, asset.format());
      statement.setString(7, asset.namespace());
      statement.setString(8, asset.name());
      statement.setString(9, asset.version());
      statement.setString(10, asset.assetKind());
      statement.setString(11, asset.contentType());
      statement.setObject(12, asset.size(), Types.BIGINT);
      statement.setString(13, asset.sourceBlobRef());
      statement.setTimestamp(14, asset.sourceLastUpdatedAt());
      statement.setTimestamp(15, asset.sourceLastDownloadedAt());
      statement.setTimestamp(16, asset.sourceBlobCreatedAt());
      statement.setTimestamp(17, asset.sourceBlobUpdatedAt());
      statement.setString(18, asset.sourceCreatedBy());
      statement.setString(19, asset.sourceCreatedByIp());
      statement.setString(20, asset.status());
      statement.setTimestamp(21, asset.migratedAt());
      statement.setObject(22, asset.targetComponentId(), Types.BIGINT);
      statement.setObject(23, asset.targetAssetId(), Types.BIGINT);
      statement.setObject(24, asset.targetAssetBlobId(), Types.BIGINT);
      json.bind(statement, 25, asset.metadataJson());
    }
  }
}
