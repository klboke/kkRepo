package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.WingetDao;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL/PostgreSQL implementation of the WinGet manifest metadata store. */
@Repository
public class JdbcWingetDao implements WingetDao {
  private static final String COLUMNS = """
      id, repository_id, package_identifier, package_version, channel, default_locale,
      package_name, publisher, moniker, search_text, summary_json, manifest_json,
      manifest_sha256, updated_at
      """;
  private static final String SUMMARY_COLUMNS = """
      id, repository_id, package_identifier, package_version, channel, default_locale,
      package_name, publisher, moniker, search_text, summary_json,
      NULL AS manifest_json, manifest_sha256, updated_at
      """;

  private final JdbcTemplate jdbc;

  public JdbcWingetDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ManifestRecord> find(
      long repositoryId, String packageIdentifier, String packageVersion, String channel) {
    return jdbc.query("""
        SELECT %s FROM winget_manifest
        WHERE repository_id = ? AND package_identifier = ? AND package_version = ? AND channel = ?
        """.formatted(COLUMNS), this::map, repositoryId, packageIdentifier, packageVersion,
        channel == null ? "" : channel).stream().findFirst();
  }

  @Override
  public List<ManifestRecord> listByPackage(long repositoryId, String packageIdentifier) {
    return jdbc.query("""
        SELECT %s FROM winget_manifest
        WHERE repository_id = ? AND package_identifier = ?
        ORDER BY package_version, channel
        """.formatted(COLUMNS), this::map, repositoryId, packageIdentifier);
  }

  @Override
  public List<ManifestRecord> listSummaries(
      long repositoryId, List<String> packageIdentifiers) {
    if (packageIdentifiers == null || packageIdentifiers.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(packageIdentifiers.size(), "?"));
    List<Object> args = new ArrayList<>(packageIdentifiers.size() + 1);
    args.add(repositoryId);
    args.addAll(packageIdentifiers);
    return jdbc.query("""
        SELECT %s FROM winget_manifest
        WHERE repository_id = ? AND package_identifier IN (%s)
        ORDER BY package_identifier, package_version, channel
        """.formatted(SUMMARY_COLUMNS, placeholders), this::map, args.toArray());
  }

  @Override
  public List<String> searchPackageIdentifiers(
      long repositoryId, List<String> terms, int limit, int offset) {
    StringBuilder sql = new StringBuilder("""
        SELECT package_identifier FROM winget_manifest WHERE repository_id = ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    for (String term : terms == null ? List.<String>of() : terms) {
      if (term == null || term.isBlank()) continue;
      sql.append(" AND LOWER(search_text) LIKE ?");
      args.add("%" + escapeLike(term.toLowerCase(java.util.Locale.ROOT)) + "%");
    }
    sql.append(" GROUP BY package_identifier ORDER BY MAX(updated_at) DESC, package_identifier ASC"
        + " LIMIT ? OFFSET ?");
    args.add(Math.max(1, Math.min(limit, 1000)));
    args.add(Math.max(0, offset));
    return jdbc.queryForList(sql.toString(), String.class, args.toArray());
  }

  @Override
  public void insert(ManifestRecord record) {
    jdbc.update("""
        INSERT INTO winget_manifest(
          repository_id, package_identifier, package_version, channel, default_locale,
          package_name, publisher, moniker, search_text, summary_json, manifest_json,
          manifest_sha256)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, record.repositoryId(), record.packageIdentifier(), record.packageVersion(),
        record.channel() == null ? "" : record.channel(), record.defaultLocale(),
        record.packageName(), record.publisher(), record.moniker(), record.searchText(),
        record.summaryJson(), record.manifestJson(), record.manifestSha256());
  }

  @Override
  public int deleteVersion(
      long repositoryId, String packageIdentifier, String packageVersion, String channel) {
    if (channel == null || channel.isBlank()) {
      return jdbc.update("""
          DELETE FROM winget_manifest
          WHERE repository_id = ? AND package_identifier = ? AND package_version = ?
          """, repositoryId, packageIdentifier, packageVersion);
    }
    return jdbc.update("""
        DELETE FROM winget_manifest
        WHERE repository_id = ? AND package_identifier = ? AND package_version = ? AND channel = ?
        """, repositoryId, packageIdentifier, packageVersion, channel);
  }

  @Override
  public int deletePackage(long repositoryId, String packageIdentifier) {
    return jdbc.update("""
        DELETE FROM winget_manifest WHERE repository_id = ? AND package_identifier = ?
        """, repositoryId, packageIdentifier);
  }

  @Override
  public int deleteByRepository(long repositoryId) {
    return jdbc.update("DELETE FROM winget_manifest WHERE repository_id = ?", repositoryId);
  }

  @Override
  public long countVersions(long repositoryId) {
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM winget_manifest WHERE repository_id = ?", Long.class, repositoryId);
    return count == null ? 0 : count;
  }

  private ManifestRecord map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp updated = rs.getTimestamp("updated_at");
    Instant updatedAt = updated == null ? Instant.EPOCH : updated.toInstant();
    return new ManifestRecord(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        rs.getString("package_identifier"),
        rs.getString("package_version"),
        rs.getString("channel"),
        rs.getString("default_locale"),
        rs.getString("package_name"),
        rs.getString("publisher"),
        rs.getString("moniker"),
        rs.getString("search_text"),
        rs.getString("summary_json"),
        rs.getString("manifest_json"),
        rs.getString("manifest_sha256"),
        updatedAt);
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
