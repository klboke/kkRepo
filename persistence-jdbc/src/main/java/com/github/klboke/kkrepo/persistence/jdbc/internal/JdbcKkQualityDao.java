package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKkQualityDao implements KkQualityDao {
  private static final TypeReference<List<Map<String, Object>>> CONDITIONS =
      new TypeReference<>() {};
  private static final String OPEN_STATUSES = "'OPEN', 'CONFIRMED', 'REOPENED'";

  private final JdbcTemplate jdbc;
  private final JsonColumns json;

  public JdbcKkQualityDao(JdbcTemplate jdbc, JsonColumns json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<Project> projects = (rs, n) -> new Project(
      rs.getLong("id"), rs.getString("external_id"), rs.getString("project_key"),
      rs.getString("name"), rs.getString("organization"), rs.getString("visibility"),
      nullableLong(rs, "repository_id"), instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Branch> branches = (rs, n) -> new Branch(
      rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
      rs.getBoolean("is_main"), instant(rs, "created_at"));

  private final RowMapper<Analysis> analyses = (rs, n) -> new Analysis(
      rs.getLong("id"), rs.getLong("project_id"), nullableLong(rs, "branch_id"),
      rs.getString("analysis_id"), rs.getString("status"), rs.getString("revision"),
      rs.getString("sarif_sha256"), rs.getInt("issue_count"), rs.getInt("new_issue_count"),
      instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at"),
      rs.getString("error"));

  private final RowMapper<Rule> rules = (rs, n) -> new Rule(
      rs.getLong("id"), rs.getString("engine"), rs.getString("rule_key"),
      rs.getString("name"), rs.getString("severity"), rs.getString("language"),
      rs.getString("description"), rs.getString("remediation"),
      instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Issue> issues = (rs, n) -> new Issue(
      rs.getLong("id"), rs.getLong("project_id"), rs.getLong("branch_id"),
      rs.getString("issue_key"), nullableLong(rs, "analysis_id"), rs.getString("file_path"),
      nullableInt(rs, "line"), nullableInt(rs, "end_line"), rs.getString("engine"),
      rs.getString("rule_key"), rs.getString("severity"), rs.getString("issue_type"),
      rs.getString("message"), rs.getString("status"), rs.getString("resolution"),
      rs.getString("assignee"), instant(rs, "creation_date"), instant(rs, "update_date"),
      instant(rs, "closed_at"));

  private final RowMapper<QualityGate> gates = (rs, n) -> new QualityGate(
      rs.getLong("id"), nullableLong(rs, "project_id"), rs.getString("external_id"),
      rs.getString("name"), readConditions(rs.getString("conditions_json")),
      rs.getBoolean("is_default"), instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Measure> measures = (rs, n) -> new Measure(
      rs.getLong("analysis_id"), rs.getString("metric_key"),
      rs.getBigDecimal("value_num"), rs.getString("value_text"));

  private final RowMapper<CountByKey> counts = (rs, n) ->
      new CountByKey(rs.getString("facet"), rs.getLong("total"));

  @Override
  public List<Project> listProjects(String organization, String query, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = projectFilter(organization, query, args);
    args.add(boundLimit(limit));
    args.add(boundOffset(offset));
    return jdbc.query("SELECT * FROM kkq_project " + where
        + " ORDER BY name, id LIMIT ? OFFSET ?", projects, args.toArray());
  }

  @Override
  public long countProjects(String organization, String query) {
    List<Object> args = new ArrayList<>();
    return count(
        "SELECT COUNT(*) FROM kkq_project " + projectFilter(organization, query, args), args);
  }

  @Override
  public List<Project> listProjectsInRepositories(
      Collection<Long> repositoryIds, String organization, String query, int limit, int offset) {
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      return List.of();
    }
    List<Object> args = new ArrayList<>();
    String where = repositoryFilter(repositoryIds, organization, query, args);
    args.add(boundLimit(limit));
    args.add(boundOffset(offset));
    return jdbc.query("SELECT * FROM kkq_project " + where
        + " ORDER BY name, id LIMIT ? OFFSET ?", projects, args.toArray());
  }

  @Override
  public long countProjectsInRepositories(
      Collection<Long> repositoryIds, String organization, String query) {
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      return 0;
    }
    List<Object> args = new ArrayList<>();
    return count(
        "SELECT COUNT(*) FROM kkq_project "
            + repositoryFilter(repositoryIds, organization, query, args), args);
  }

  @Override
  public Optional<Project> findProject(long id) {
    return jdbc.query("SELECT * FROM kkq_project WHERE id = ?", projects, id).stream().findFirst();
  }

  @Override
  public Optional<Project> findProjectByKey(String key) {
    return jdbc.query("SELECT * FROM kkq_project WHERE project_key = ?", projects, key)
        .stream().findFirst();
  }

  @Override
  public Optional<Project> findProjectByExternalId(String externalId) {
    return jdbc.query("SELECT * FROM kkq_project WHERE external_id = ?", projects, externalId)
        .stream().findFirst();
  }

  @Override
  public Optional<Project> findProjectByRepositoryId(long repositoryId) {
    return jdbc.query("SELECT * FROM kkq_project WHERE repository_id = ?", projects, repositoryId)
        .stream().findFirst();
  }

  @Override
  public long insertProject(
      String externalId, String key, String name, String organization, String visibility,
      Long repositoryId, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_project
          (external_id, project_key, name, organization, visibility, repository_id,
           created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, externalId);
      ps.setString(2, key);
      ps.setString(3, name);
      ps.setString(4, organization);
      ps.setString(5, visibility);
      bindLong(ps, 6, repositoryId);
      ps.setTimestamp(7, Timestamp.from(now));
      ps.setTimestamp(8, Timestamp.from(now));
    });
  }

  @Override
  public boolean updateProject(
      long id, String key, String name, String organization, String visibility, Instant now) {
    return jdbc.update("""
        UPDATE kkq_project SET project_key = ?, name = ?, organization = ?, visibility = ?,
          updated_at = ? WHERE id = ?
        """, key, name, organization, visibility, Timestamp.from(now), id) > 0;
  }

  @Override
  public boolean bindRepository(long projectId, Long repositoryId, Instant now) {
    return jdbc.update(
        "UPDATE kkq_project SET repository_id = ?, updated_at = ? WHERE id = ?",
        repositoryId, Timestamp.from(now), projectId) > 0;
  }

  @Override
  public boolean deleteProject(long id) {
    return jdbc.update("DELETE FROM kkq_project WHERE id = ?", id) > 0;
  }

  @Override
  public Optional<Branch> findBranch(long projectId, String name) {
    return jdbc.query(
        "SELECT * FROM kkq_branch WHERE project_id = ? AND name = ?", branches, projectId, name)
        .stream().findFirst();
  }

  @Override
  public List<Branch> listBranches(long projectId) {
    return jdbc.query(
        "SELECT * FROM kkq_branch WHERE project_id = ? ORDER BY is_main DESC, name",
        branches, projectId);
  }

  @Override
  public long insertBranch(long projectId, String name, boolean main, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_branch (project_id, name, is_main, created_at) VALUES (?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, projectId);
      ps.setString(2, name);
      ps.setBoolean(3, main);
      ps.setTimestamp(4, Timestamp.from(now));
    });
  }

  @Override
  public boolean lockBranch(long branchId) {
    return !jdbc.queryForList(
        "SELECT id FROM kkq_branch WHERE id = ? FOR UPDATE", Long.class, branchId).isEmpty();
  }

  @Override
  public long insertAnalysis(Analysis analysis) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_analysis
          (project_id, branch_id, analysis_id, status, revision, sarif_sha256, issue_count,
           new_issue_count, created_at, started_at, completed_at, error)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, analysis.projectId());
      bindLong(ps, 2, analysis.branchId());
      ps.setString(3, analysis.analysisId());
      ps.setString(4, analysis.status());
      ps.setString(5, analysis.revision());
      ps.setString(6, analysis.sarifSha256());
      ps.setInt(7, analysis.issueCount());
      ps.setInt(8, analysis.newIssueCount());
      ps.setTimestamp(9, Timestamp.from(
          analysis.createdAt() == null ? Instant.now() : analysis.createdAt()));
      bindInstant(ps, 10, analysis.startedAt());
      bindInstant(ps, 11, analysis.completedAt());
      ps.setString(12, analysis.error());
    });
  }

  @Override
  public Optional<Analysis> findAnalysis(long id) {
    return jdbc.query("SELECT * FROM kkq_analysis WHERE id = ?", analyses, id)
        .stream().findFirst();
  }

  @Override
  public Optional<Analysis> findAnalysisByExternalId(String analysisId) {
    return jdbc.query("SELECT * FROM kkq_analysis WHERE analysis_id = ?", analyses, analysisId)
        .stream().findFirst();
  }

  @Override
  public Optional<Analysis> findLatestAnalysis(long projectId, Long branchId) {
    if (branchId == null) {
      return jdbc.query("""
          SELECT * FROM kkq_analysis WHERE project_id = ? ORDER BY created_at DESC, id DESC
          """, analyses, projectId).stream().findFirst();
    }
    return jdbc.query("""
        SELECT * FROM kkq_analysis
        WHERE project_id = ? AND branch_id = ?
        ORDER BY created_at DESC, id DESC
        """, analyses, projectId, branchId).stream().findFirst();
  }

  @Override
  public boolean completeAnalysis(
      long id, int issueCount, int newIssueCount, String sarifSha256, Instant completedAt) {
    return jdbc.update("""
        UPDATE kkq_analysis
        SET status = 'COMPLETE', issue_count = ?, new_issue_count = ?,
          sarif_sha256 = COALESCE(?, sarif_sha256), error = NULL, completed_at = ?
        WHERE id = ?
        """, issueCount, newIssueCount, sarifSha256, Timestamp.from(completedAt), id) > 0;
  }

  @Override
  public boolean failAnalysis(long id, String error, Instant completedAt) {
    return jdbc.update("""
        UPDATE kkq_analysis SET status = 'FAILED', error = ?, completed_at = ? WHERE id = ?
        """, error, Timestamp.from(completedAt), id) > 0;
  }

  @Override
  public List<Analysis> listAnalyses(long projectId, int limit, int offset) {
    return jdbc.query("""
        SELECT * FROM kkq_analysis WHERE project_id = ?
        ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
        """, analyses, projectId, boundLimit(limit), boundOffset(offset));
  }

  @Override
  public long countAnalyses(long projectId) {
    Long value = jdbc.queryForObject(
        "SELECT COUNT(*) FROM kkq_analysis WHERE project_id = ?", Long.class, projectId);
    return value == null ? 0 : value;
  }

  @Override
  public List<Issue> listIssues(
      long projectId, Long branchId, IssueFilter filter, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = issueFilter(projectId, branchId, filter, args);
    args.add(boundLimit(limit));
    args.add(boundOffset(offset));
    return jdbc.query("SELECT * FROM kkq_issue " + where
        + " ORDER BY update_date DESC, id DESC LIMIT ? OFFSET ?", issues, args.toArray());
  }

  @Override
  public long countIssues(long projectId, Long branchId, IssueFilter filter) {
    List<Object> args = new ArrayList<>();
    return count("SELECT COUNT(*) FROM kkq_issue " + issueFilter(projectId, branchId, filter, args),
        args);
  }

  @Override
  public List<Issue> listBranchIssues(long projectId, long branchId) {
    return jdbc.query(
        "SELECT * FROM kkq_issue WHERE project_id = ? AND branch_id = ? ORDER BY id",
        issues, projectId, branchId);
  }

  @Override
  public Optional<Issue> findIssue(long projectId, long branchId, String issueKey) {
    return jdbc.query("""
        SELECT * FROM kkq_issue WHERE project_id = ? AND branch_id = ? AND issue_key = ?
        """, issues, projectId, branchId, issueKey).stream().findFirst();
  }

  @Override
  public long insertIssue(
      long projectId, long branchId, String issueKey, long analysisId, String filePath,
      Integer line, Integer endLine, String engine, String ruleKey, String severity,
      String issueType, String message, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_issue
          (project_id, branch_id, issue_key, analysis_id, file_path, line, end_line, engine,
           rule_key, severity, issue_type, message, status, creation_date, update_date)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
        """, ps -> {
      ps.setLong(1, projectId);
      ps.setLong(2, branchId);
      ps.setString(3, issueKey);
      ps.setLong(4, analysisId);
      ps.setString(5, filePath);
      bindInt(ps, 6, line);
      bindInt(ps, 7, endLine);
      ps.setString(8, engine);
      ps.setString(9, ruleKey);
      ps.setString(10, severity);
      ps.setString(11, issueType);
      ps.setString(12, message);
      ps.setTimestamp(13, Timestamp.from(now));
      ps.setTimestamp(14, Timestamp.from(now));
    });
  }

  @Override
  public boolean observeIssue(
      long issueId, long analysisId, String filePath, Integer line, Integer endLine,
      String severity, String issueType, String message, Instant now) {
    return jdbc.update("""
        UPDATE kkq_issue
        SET analysis_id = ?, file_path = ?, line = ?, end_line = ?, severity = ?,
          issue_type = ?, message = ?, update_date = ?,
          status = CASE WHEN status = 'CLOSED' THEN 'REOPENED' ELSE status END,
          closed_at = CASE WHEN status = 'CLOSED' THEN NULL ELSE closed_at END
        WHERE id = ?
        """, analysisId, filePath, line, endLine, severity, issueType, message,
        Timestamp.from(now), issueId) > 0;
  }

  @Override
  public long closeUnseenIssues(long projectId, long branchId, long analysisId, Instant now) {
    return jdbc.update("""
        UPDATE kkq_issue
        SET status = 'CLOSED', resolution = 'REMOVED', closed_at = ?, update_date = ?
        WHERE project_id = ? AND branch_id = ?
          AND status IN ('OPEN', 'CONFIRMED', 'REOPENED')
          AND (analysis_id IS NULL OR analysis_id <> ?)
        """, Timestamp.from(now), Timestamp.from(now), projectId, branchId, analysisId);
  }

  @Override
  public long countNewIssues(long projectId, long branchId, long analysisId) {
    Long value = jdbc.queryForObject("""
        SELECT COUNT(*) FROM kkq_issue_occurrence o
        JOIN kkq_issue i ON i.id = o.issue_id
        WHERE o.analysis_id = ? AND i.project_id = ? AND i.branch_id = ?
          AND o.analysis_id = (
            SELECT MIN(o2.analysis_id) FROM kkq_issue_occurrence o2 WHERE o2.issue_id = o.issue_id)
        """, Long.class, analysisId, projectId, branchId);
    return value == null ? 0 : value;
  }

  @Override
  public boolean updateIssueState(
      long projectId, long branchId, String issueKey, String status, String resolution,
      String assignee, Instant now) {
    StringBuilder sql = new StringBuilder(
        "UPDATE kkq_issue SET update_date = ?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(now));
    if (status != null) {
      sql.append(", status = ?");
      args.add(status);
      boolean closed = "CLOSED".equals(status) || "RESOLVED".equals(status)
          || "FALSE_POSITIVE".equals(status) || "ACCEPTED".equals(status);
      sql.append(", closed_at = ").append(closed ? "?" : "NULL");
      if (closed) {
        args.add(Timestamp.from(now));
      }
    }
    if (resolution != null) {
      sql.append(", resolution = ?");
      args.add(resolution);
    }
    if (assignee != null) {
      sql.append(", assignee = ?");
      args.add(assignee);
    }
    sql.append(" WHERE project_id = ? AND branch_id = ? AND issue_key = ?");
    args.add(projectId);
    args.add(branchId);
    args.add(issueKey);
    return jdbc.update(sql.toString(), args.toArray()) > 0;
  }

  @Override
  public List<CountByKey> countOpenIssuesBySeverity(long projectId, long branchId) {
    return jdbc.query("""
        SELECT COALESCE(severity, 'INFO') AS facet, COUNT(*) AS total FROM kkq_issue
        WHERE project_id = ? AND branch_id = ? AND status IN (%s)
        GROUP BY COALESCE(severity, 'INFO') ORDER BY total DESC
        """.formatted(OPEN_STATUSES), counts, projectId, branchId);
  }

  @Override
  public List<CountByKey> countOpenIssuesByType(long projectId, long branchId) {
    return jdbc.query("""
        SELECT COALESCE(issue_type, 'CODE_SMELL') AS facet, COUNT(*) AS total FROM kkq_issue
        WHERE project_id = ? AND branch_id = ? AND status IN (%s)
        GROUP BY COALESCE(issue_type, 'CODE_SMELL') ORDER BY total DESC
        """.formatted(OPEN_STATUSES), counts, projectId, branchId);
  }

  @Override
  public List<CountByKey> countOpenIssuesByRule(long projectId, long branchId, int limit) {
    return jdbc.query("""
        SELECT COALESCE(rule_key, '') AS facet, COUNT(*) AS total FROM kkq_issue
        WHERE project_id = ? AND branch_id = ? AND status IN (%s)
        GROUP BY COALESCE(rule_key, '') ORDER BY total DESC, facet LIMIT ?
        """.formatted(OPEN_STATUSES), counts, projectId, branchId, boundLimit(limit));
  }

  @Override
  public void insertOccurrence(
      long issueId, long analysisId, Integer line, Integer endLine, String messageHash,
      Instant now) {
    jdbc.update("""
        INSERT INTO kkq_issue_occurrence
          (issue_id, analysis_id, line, end_line, message_hash, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """, issueId, analysisId, line, endLine, messageHash, Timestamp.from(now));
  }

  @Override
  public void upsertRule(
      String engine, String ruleKey, String name, String severity, String language,
      String description, String remediation, Instant now) {
    int inserted = jdbc.update("""
        INSERT INTO kkq_rule
          (engine, rule_key, name, severity, language, description, remediation,
           created_at, updated_at)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
        WHERE NOT EXISTS (SELECT 1 FROM kkq_rule WHERE engine = ? AND rule_key = ?)
        """, engine, ruleKey, name, severity, language, description, remediation,
        Timestamp.from(now), Timestamp.from(now), engine, ruleKey);
    if (inserted == 0) {
      jdbc.update("""
          UPDATE kkq_rule SET name = ?, severity = ?, language = ?, description = ?,
            remediation = ?, updated_at = ? WHERE engine = ? AND rule_key = ?
          """, name, severity, language, description, remediation, Timestamp.from(now),
          engine, ruleKey);
    }
  }

  @Override
  public Optional<Rule> findRule(String engine, String ruleKey) {
    return jdbc.query("SELECT * FROM kkq_rule WHERE engine = ? AND rule_key = ?",
        rules, engine, ruleKey).stream().findFirst();
  }

  @Override
  public List<Rule> listRules(
      String engine, String query, List<String> languages, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = ruleFilter(engine, query, languages, args);
    args.add(boundLimit(limit));
    args.add(boundOffset(offset));
    return jdbc.query("SELECT * FROM kkq_rule " + where
        + " ORDER BY name, id LIMIT ? OFFSET ?", rules, args.toArray());
  }

  @Override
  public long countRules(String engine, String query, List<String> languages) {
    List<Object> args = new ArrayList<>();
    return count(
        "SELECT COUNT(*) FROM kkq_rule " + ruleFilter(engine, query, languages, args), args);
  }

  @Override
  public long insertQualityGate(
      Long projectId, String externalId, String name, List<Map<String, Object>> conditions,
      boolean isDefault, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_quality_gate
          (project_id, external_id, name, conditions_json, is_default, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      bindLong(ps, 1, projectId);
      ps.setString(2, externalId);
      ps.setString(3, name);
      json.bindSerialized(ps, 4, json.writeValue(conditions));
      ps.setBoolean(5, isDefault);
      ps.setTimestamp(6, Timestamp.from(now));
      ps.setTimestamp(7, Timestamp.from(now));
    });
  }

  @Override
  public List<QualityGate> listQualityGates(Long projectId) {
    if (projectId == null) {
      return jdbc.query(
          "SELECT * FROM kkq_quality_gate ORDER BY is_default DESC, name, id", gates);
    }
    return jdbc.query("""
        SELECT * FROM kkq_quality_gate WHERE project_id IS NULL OR project_id = ?
        ORDER BY is_default DESC, name, id
        """, gates, projectId);
  }

  @Override
  public Optional<QualityGate> findDefaultQualityGate() {
    return jdbc.query(
        "SELECT * FROM kkq_quality_gate WHERE is_default = TRUE AND project_id IS NULL ORDER BY id",
        gates).stream().findFirst();
  }

  @Override
  public Optional<QualityGate> findQualityGateForProject(long projectId) {
    return jdbc.query("""
        SELECT * FROM kkq_quality_gate WHERE project_id = ? ORDER BY is_default DESC, id
        """, gates, projectId).stream().findFirst()
        .or(this::findDefaultQualityGate);
  }

  @Override
  public boolean updateQualityGate(
      long id, String name, List<Map<String, Object>> conditions, boolean isDefault,
      Instant now) {
    return jdbc.update("""
        UPDATE kkq_quality_gate SET name = ?, conditions_json = ?, is_default = ?, updated_at = ?
        WHERE id = ?
        """, name, json.serializedParameter(json.writeValue(conditions)), isDefault,
        Timestamp.from(now), id) > 0;
  }

  @Override
  public boolean deleteQualityGate(long id) {
    return jdbc.update("DELETE FROM kkq_quality_gate WHERE id = ?", id) > 0;
  }

  @Override
  public int clearDefaultQualityGates() {
    return jdbc.update(
        "UPDATE kkq_quality_gate SET is_default = FALSE WHERE is_default = TRUE");
  }

  @Override
  public void replaceMeasures(long analysisId, List<Measure> values) {
    jdbc.update("DELETE FROM kkq_measure WHERE analysis_id = ?", analysisId);
    for (Measure measure : values) {
      jdbc.update("""
          INSERT INTO kkq_measure (analysis_id, metric_key, value_num, value_text)
          VALUES (?, ?, ?, ?)
          """, analysisId, measure.metricKey(), measure.valueNum(), measure.valueText());
    }
  }

  @Override
  public List<Measure> listMeasures(long analysisId) {
    return jdbc.query(
        "SELECT * FROM kkq_measure WHERE analysis_id = ? ORDER BY metric_key",
        measures, analysisId);
  }

  private static String projectFilter(String organization, String query, List<Object> args) {
    StringBuilder where = new StringBuilder("WHERE 1 = 1");
    if (organization != null && !organization.isBlank()) {
      where.append(" AND organization = ?");
      args.add(organization);
    }
    if (query != null && !query.isBlank()) {
      where.append(" AND (LOWER(project_key) LIKE ? OR LOWER(name) LIKE ?)");
      String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
      args.add(pattern);
      args.add(pattern);
    }
    return where.toString();
  }

  private static String repositoryFilter(
      Collection<Long> repositoryIds, String organization, String query, List<Object> args) {
    StringBuilder where = new StringBuilder("WHERE repository_id IN (")
        .append(placeholders(repositoryIds.size())).append(')');
    args.addAll(repositoryIds);
    if (organization != null && !organization.isBlank()) {
      where.append(" AND organization = ?");
      args.add(organization);
    }
    if (query != null && !query.isBlank()) {
      where.append(" AND (LOWER(project_key) LIKE ? OR LOWER(name) LIKE ?)");
      String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
      args.add(pattern);
      args.add(pattern);
    }
    return where.toString();
  }

  private static String placeholders(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(i -> "?").collect(Collectors.joining(", "));
  }

  private static String issueFilter(
      long projectId, Long branchId, IssueFilter filter, List<Object> args) {
    StringBuilder where = new StringBuilder("WHERE project_id = ?");
    args.add(projectId);
    if (branchId != null) {
      where.append(" AND branch_id = ?");
      args.add(branchId);
    }
    if (filter == null) {
      return where.toString();
    }
    appendIn(where, "status", filter.statuses(), args);
    appendIn(where, "issue_type", filter.types(), args);
    appendIn(where, "severity", filter.severities(), args);
    appendIn(where, "rule_key", filter.ruleKeys(), args);
    if (notBlank(filter.resolution())) {
      where.append(" AND resolution = ?");
      args.add(filter.resolution());
    }
    if (notBlank(filter.engine())) {
      where.append(" AND engine = ?");
      args.add(filter.engine());
    }
    if (notBlank(filter.assignee())) {
      where.append(" AND assignee = ?");
      args.add(filter.assignee());
    }
    if (notBlank(filter.filePath())) {
      where.append(" AND file_path = ?");
      args.add(filter.filePath());
    }
    if (notBlank(filter.textQuery())) {
      where.append(
          " AND (LOWER(message) LIKE ? OR LOWER(file_path) LIKE ? OR LOWER(rule_key) LIKE ?)");
      String pattern = "%" + filter.textQuery().toLowerCase(Locale.ROOT) + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    return where.toString();
  }

  private static void appendIn(
      StringBuilder where, String column, Collection<String> values, List<Object> args) {
    if (values == null || values.isEmpty()) {
      return;
    }
    where.append(" AND ").append(column).append(" IN (")
        .append(placeholders(values.size())).append(')');
    args.addAll(values);
  }

  private static String ruleFilter(
      String engine, String query, List<String> languages, List<Object> args) {
    StringBuilder where = new StringBuilder("WHERE 1 = 1");
    if (notBlank(engine)) {
      where.append(" AND engine = ?");
      args.add(engine);
    }
    if (notBlank(query)) {
      where.append(" AND (LOWER(name) LIKE ? OR LOWER(rule_key) LIKE ?)");
      String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
      args.add(pattern);
      args.add(pattern);
    }
    // The language filter belongs in SQL so pagination and `total` describe the filtered set
    // instead of the unfiltered one.
    appendIn(where, "language", languages, args);
    return where.toString();
  }

  private long count(String sql, List<Object> args) {
    Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
    return value == null ? 0 : value;
  }

  private List<Map<String, Object>> readConditions(String value) {
    return value == null || value.isBlank() ? List.of() : json.readValue(value, CONDITIONS);
  }

  private static int boundLimit(int limit) {
    return Math.max(1, Math.min(limit, 500));
  }

  private static int boundOffset(int offset) {
    return Math.max(0, offset);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static void bindLong(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.BIGINT);
    } else {
      ps.setLong(index, value);
    }
  }

  private static void bindInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.INTEGER);
    } else {
      ps.setInt(index, value);
    }
  }

  private static void bindInstant(PreparedStatement ps, int index, Instant value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.TIMESTAMP);
    } else {
      ps.setTimestamp(index, Timestamp.from(value));
    }
  }

  /** Kept for callers that bind a decimal measure value explicitly. */
  static void bindDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.DECIMAL);
    } else {
      ps.setBigDecimal(index, value);
    }
  }
}
