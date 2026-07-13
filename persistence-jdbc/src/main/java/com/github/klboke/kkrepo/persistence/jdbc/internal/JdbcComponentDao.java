package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcComponentDao implements com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao {
  private static final String INSERT_SQL = """
      INSERT INTO component
        (repository_id, format, namespace, name, version, kind, coordinate_hash,
         attributes_json, last_updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;
  private static final String UPSERT_RETURNING_ID_SQL = """
      INSERT INTO component
        (repository_id, format, namespace, name, version, kind, coordinate_hash,
         attributes_json, last_updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        id = LAST_INSERT_ID(id),
        last_updated_at = VALUES(last_updated_at)
      """;
  private static final String SEARCH_SELECT = """
      SELECT c.id, c.repository_id, r.name AS repository_name, c.format, c.namespace,
             c.name, c.version, c.kind, c.last_updated_at,
             CASE WHEN c.format = 'composer'
                  THEN COALESCE(
                      NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.attributes_json, '$.distPath')), 'null'),
                      c.name)
                  ELSE NULL
             END AS storage_path
      FROM component c
      JOIN repository r ON r.id = c.repository_id
      """;
  private static final String SEARCH_VISIBLE_PREDICATE = """
      (c.format <> 'composer'
       OR (c.name <> '_composer' AND LEFT(c.name, 10) <> '_composer/'))
      """;

  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<ComponentRecord> rowMapper;

  @Autowired
  public JdbcComponentDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.rowMapper = (rs, rowNum) -> new ComponentRecord(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        rs.getString("namespace"),
        rs.getString("name"),
        rs.getString("version"),
        rs.getString("kind"),
        rs.getBytes("coordinate_hash"),
        jsonColumns.read(rs.getString("attributes_json")),
        nullableInstant(rs, "last_updated_at"));
  }

  public long insert(ComponentRecord record) {
    long id = JdbcInserts.insert(jdbcTemplate, INSERT_SQL, ps -> setInsertParameters(ps, record));
    upsertSearchIndex(id, record);
    return id;
  }

  public Optional<ComponentRecord> findByCoordinateHash(long repositoryId, byte[] coordinateHash) {
    return jdbcTemplate.query("""
        SELECT * FROM component
        WHERE repository_id = ? AND coordinate_hash = ?
        """, rowMapper, repositoryId, coordinateHash).stream().findFirst();
  }

  public Optional<ComponentRecord> findById(long componentId) {
    return jdbcTemplate.query("""
        SELECT * FROM component
        WHERE id = ?
        """, rowMapper, componentId).stream().findFirst();
  }

  public long upsertReturningId(ComponentRecord record) {
    long id = upsertReturningIdFromDatabase(record);
    return id;
  }

  public Optional<ComponentRecord> findByGav(long repositoryId, String groupId, String artifactId, String version) {
    return findByCoordinateHash(
        repositoryId,
        HashColumns.componentCoordinateHash(groupId, artifactId, version));
  }

  public Optional<ComponentRecord> findByNameAndVersion(long repositoryId, String name, String version) {
    return findByCoordinateHash(
        repositoryId,
        HashColumns.componentCoordinateHash(null, name, version));
  }

  public List<ComponentRecord> listByRepositoryId(long repositoryId) {
    return jdbcTemplate.query("""
        SELECT * FROM component
        WHERE repository_id = ?
        ORDER BY name, version
        """, rowMapper, repositoryId);
  }

  public List<String> listDistinctNamesByRepositoryId(long repositoryId) {
    return jdbcTemplate.queryForList("""
        SELECT DISTINCT name
        FROM component
        WHERE repository_id = ? AND name IS NOT NULL AND name <> ''
        ORDER BY name
        """, String.class, repositoryId);
  }

  public List<ComponentRecord> listByName(long repositoryId, String name) {
    return jdbcTemplate.query("""
        SELECT * FROM component
        WHERE repository_id = ? AND name = ?
        ORDER BY version
        """, rowMapper, repositoryId, name);
  }

  public List<ComponentRecord> listByGa(long repositoryId, String groupId, String artifactId) {
    return jdbcTemplate.query("""
        SELECT * FROM component
        WHERE repository_id = ? AND namespace = ? AND name = ?
        ORDER BY version
        """, rowMapper, repositoryId, groupId, artifactId);
  }

  public List<ComponentSearchRow> search(String keyword, RepositoryFormat format, int limit) {
    String normalized = keyword == null ? "" : keyword.trim();
    int safeLimit = Math.max(1, Math.min(limit, 300));
    if (normalized.isEmpty() && format == null) {
      return jdbcTemplate.query(SEARCH_SELECT + "WHERE " + SEARCH_VISIBLE_PREDICATE + """
          ORDER BY c.last_updated_at DESC, c.id DESC
          LIMIT ?
          """, searchRowMapper, safeLimit);
    }
    if (normalized.isEmpty()) {
      return jdbcTemplate.query(SEARCH_SELECT + """
          WHERE c.format = ?
            AND
          """ + SEARCH_VISIBLE_PREDICATE + """
          ORDER BY c.last_updated_at DESC, c.id DESC
          LIMIT ?
          """, searchRowMapper, EnumColumns.write(format), safeLimit);
    }
    String booleanQuery = fulltextBooleanQuery(normalized);
    if (booleanQuery.isBlank()) {
      return List.of();
    }
    if (format == null) {
      return jdbcTemplate.query("""
          SELECT c.id, c.repository_id, r.name AS repository_name, c.format, c.namespace,
                 c.name, c.version, c.kind, c.last_updated_at,
                 CASE WHEN c.format = 'composer'
                      THEN COALESCE(
                          NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.attributes_json, '$.distPath')), 'null'),
                          c.name)
                      ELSE NULL
                 END AS storage_path
          FROM component_search cs
          JOIN component c ON c.id = cs.component_id
          JOIN repository r ON r.id = c.repository_id
          WHERE MATCH(cs.namespace, cs.name, cs.version, cs.keywords)
                AGAINST (? IN BOOLEAN MODE)
            AND
          """ + SEARCH_VISIBLE_PREDICATE + """
          ORDER BY c.last_updated_at DESC, r.name, c.namespace, c.name, c.version
          LIMIT ?
          """, searchRowMapper, booleanQuery, safeLimit);
    }
    return jdbcTemplate.query("""
        SELECT c.id, c.repository_id, r.name AS repository_name, c.format, c.namespace,
               c.name, c.version, c.kind, c.last_updated_at,
               CASE WHEN c.format = 'composer'
                    THEN COALESCE(
                        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.attributes_json, '$.distPath')), 'null'),
                        c.name)
                    ELSE NULL
               END AS storage_path
        FROM component_search cs
        JOIN component c ON c.id = cs.component_id
        JOIN repository r ON r.id = c.repository_id
        WHERE c.format = ?
          AND MATCH(cs.namespace, cs.name, cs.version, cs.keywords)
              AGAINST (? IN BOOLEAN MODE)
          AND
        """ + SEARCH_VISIBLE_PREDICATE + """
        ORDER BY c.last_updated_at DESC, r.name, c.namespace, c.name, c.version
        LIMIT ?
        """, searchRowMapper, EnumColumns.write(format), booleanQuery, safeLimit);
  }

  public List<ComponentSearchRow> searchByRepositoryIds(List<Long> repositoryIds, String keyword, int limit) {
    return searchByRepositoryIds(repositoryIds, RepositoryFormat.NPM, keyword, limit);
  }

  public List<ComponentSearchRow> searchByRepositoryIds(
      List<Long> repositoryIds,
      RepositoryFormat format,
      String keyword,
      int limit) {
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      return List.of();
    }
    String normalized = keyword == null ? "" : keyword.trim();
    int safeLimit = Math.max(1, Math.min(limit, 300));
    String placeholders = String.join(",", Collections.nCopies(repositoryIds.size(), "?"));
    List<Object> args = new ArrayList<>(repositoryIds);
    args.add(EnumColumns.write(format));
    StringBuilder sql = new StringBuilder("""
        SELECT c.id, c.repository_id, r.name AS repository_name, c.format, c.namespace,
               c.name, c.version, c.kind, c.last_updated_at,
               CASE WHEN c.format = 'composer'
                    THEN COALESCE(
                        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.attributes_json, '$.distPath')), 'null'),
                        c.name)
                    ELSE NULL
               END AS storage_path
        FROM component c
        JOIN repository r ON r.id = c.repository_id
        WHERE c.repository_id IN (
        """);
    sql.append(placeholders).append(") AND c.format = ?\n");
    if (!normalized.isEmpty()) {
      String booleanQuery = fulltextBooleanQuery(normalized);
      if (booleanQuery.isBlank()) return List.of();
      int fromIndex = sql.indexOf("FROM component c");
      sql.replace(fromIndex, fromIndex + "FROM component c".length(), """
          FROM component_search cs
          JOIN component c ON c.id = cs.component_id""");
      sql.append("""
          AND MATCH(cs.namespace, cs.name, cs.version, cs.keywords)
              AGAINST (? IN BOOLEAN MODE)
          """);
      args.add(booleanQuery);
    }
    sql.append("""
        ORDER BY c.last_updated_at DESC, r.name, c.namespace, c.name, c.version
        LIMIT ?
        """);
    args.add(safeLimit);
    return jdbcTemplate.query(sql.toString(), searchRowMapper, args.toArray());
  }

  public List<ComponentRecord> searchComponentsByRepositoryIds(
      List<Long> repositoryIds,
      RepositoryFormat format,
      String keyword,
      int limit) {
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      return List.of();
    }
    String normalized = keyword == null ? "" : keyword.trim();
    int safeLimit = Math.max(1, Math.min(limit, 300));
    String placeholders = String.join(",", Collections.nCopies(repositoryIds.size(), "?"));
    List<Object> args = new ArrayList<>(repositoryIds);
    args.add(EnumColumns.write(format));
    StringBuilder sql = new StringBuilder("""
        SELECT c.*
        FROM component c
        WHERE c.repository_id IN (
        """);
    sql.append(placeholders).append(") AND c.format = ?");
    if (!normalized.isEmpty()) {
      String booleanQuery = fulltextBooleanQuery(normalized);
      if (booleanQuery.isBlank()) return List.of();
      int fromIndex = sql.indexOf("FROM component c");
      sql.replace(fromIndex, fromIndex + "FROM component c".length(), """
          FROM component_search cs
          JOIN component c ON c.id = cs.component_id""");
      sql.append("""
          AND MATCH(cs.namespace, cs.name, cs.version, cs.keywords)
              AGAINST (? IN BOOLEAN MODE)
          """);
      args.add(booleanQuery);
    }
    sql.append("""
        ORDER BY c.last_updated_at DESC, c.namespace, c.name, c.version
        LIMIT ?
        """);
    args.add(safeLimit);
    return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
  }

  public int deleteIfNoAssets(long componentId) {
    return jdbcTemplate.update("""
        DELETE FROM component
        WHERE id = ? AND NOT EXISTS (SELECT 1 FROM asset WHERE component_id = ?)
        """, componentId, componentId);
  }

  public int touchLastUpdated(long componentId, java.time.Instant when) {
    int updated = jdbcTemplate.update("""
        UPDATE component SET last_updated_at = ? WHERE id = ?
        """, nullableTimestamp(when), componentId);
    jdbcTemplate.update("""
        UPDATE component_search SET refreshed_at = NOW(3) WHERE component_id = ?
        """, componentId);
    return updated;
  }

  public int updateAttributes(long componentId, Map<String, Object> attributes, java.time.Instant when) {
    int updated = jdbcTemplate.update("""
        UPDATE component
        SET attributes_json = ?, last_updated_at = ?
        WHERE id = ?
        """, jsonColumns.write(attributes), nullableTimestamp(when), componentId);
    findById(componentId).ifPresent(record -> upsertSearchIndex(componentId, record));
    return updated;
  }

  private void upsertSearchIndex(long componentId, ComponentRecord record) {
    jdbcTemplate.update("""
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
        """,
        componentId,
        record.repositoryId(),
        EnumColumns.write(record.format()),
        record.namespace(),
        record.name(),
        record.version(),
        searchKeywords(record));
  }

  static String upsertReturningIdSql() {
    return UPSERT_RETURNING_ID_SQL;
  }

  private long upsertReturningIdFromDatabase(ComponentRecord record) {
    Long key = jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
      try (PreparedStatement statement = connection.prepareStatement(UPSERT_RETURNING_ID_SQL)) {
        setInsertParameters(statement, record);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement("SELECT LAST_INSERT_ID()");
          var rs = statement.executeQuery()) {
        return rs.next() ? rs.getLong(1) : null;
      }
    });
    if (key == null || key <= 0) {
      throw new IllegalStateException("Component upsert did not return an id");
    }
    long componentId = key;
    upsertSearchIndex(componentId, record);
    return componentId;
  }

  private void setInsertParameters(PreparedStatement ps, ComponentRecord record) throws SQLException {
    ps.setLong(1, record.repositoryId());
    ps.setString(2, EnumColumns.write(record.format()));
    ps.setString(3, record.namespace());
    ps.setString(4, record.name());
    ps.setString(5, record.version());
    ps.setString(6, record.kind());
    ps.setBytes(7, record.coordinateHash());
    ps.setString(8, jsonColumns.write(record.attributes()));
    ps.setTimestamp(9, nullableTimestamp(record.lastUpdatedAt()));
  }

  private String searchKeywords(ComponentRecord record) {
    List<String> tokens = new ArrayList<>();
    addToken(tokens, record.namespace());
    addToken(tokens, record.name());
    addToken(tokens, record.version());
    addToken(tokens, record.kind());
    addToken(tokens, record.format() == null ? null : record.format().name().toLowerCase(Locale.ROOT));
    if (record.attributes() != null) {
      record.attributes().forEach((key, value) -> {
        addToken(tokens, key);
        if (value instanceof String s) addToken(tokens, s);
      });
    }
    return String.join(" ", tokens);
  }

  private static void addToken(List<String> tokens, String value) {
    if (value == null || value.isBlank()) return;
    tokens.add(value);
  }

  static String fulltextBooleanQuery(String keyword) {
    if (keyword == null || keyword.isBlank()) return "";
    List<String> terms = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    for (int i = 0; i < keyword.length(); i++) {
      char c = keyword.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        token.append(Character.toLowerCase(c));
      } else {
        addFulltextTerm(terms, token);
      }
    }
    addFulltextTerm(terms, token);
    return String.join(" ", terms);
  }

  private static void addFulltextTerm(List<String> terms, StringBuilder token) {
    if (token.isEmpty()) {
      return;
    }
    terms.add("+" + token + "*");
    token.setLength(0);
  }

  public long countByRepositoryId(long repositoryId) {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM component WHERE repository_id = ?",
        Long.class,
        repositoryId);
    return count == null ? 0 : count;
  }

  private final RowMapper<ComponentSearchRow> searchRowMapper = (rs, rowNum) -> new ComponentSearchRow(
      rs.getLong("id"),
      rs.getLong("repository_id"),
      rs.getString("repository_name"),
      EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
      rs.getString("namespace"),
      rs.getString("name"),
      rs.getString("version"),
      rs.getString("kind"),
      nullableInstant(rs, "last_updated_at"),
      rs.getString("storage_path"));

}
