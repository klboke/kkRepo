package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable state for the kkQuality code-quality service.
 *
 * <p>Issues are stored as <em>current state</em>: one row per {@code (project, branch, issue_key)}
 * fingerprint, independent of how many analyses observed it. {@code kkq_issue_occurrence} records
 * which analyses saw the issue, so repeated analyses of an unchanged tree are idempotent and "new
 * issues" can be derived without a history scan.
 *
 * <p>Every list method takes an explicit bound and every authorization filter is expressed in SQL
 * (see {@link #listProjectsInRepositories}) so a caller can never widen the result set after the
 * fact. Multi-replica ingestion relies on {@link #lockBranch(long)}: analyses of the same branch
 * serialize on the branch row, analyses of different branches proceed in parallel.
 */
public interface KkQualityDao {
  /** Issue statuses that count as unresolved. Must stay in sync with the Sonar read API. */
  List<String> OPEN_STATUSES = List.of("OPEN", "CONFIRMED", "REOPENED");

  record Project(
      long id, String externalId, String key, String name, String organization, String visibility,
      Long repositoryId, Instant createdAt, Instant updatedAt) {}

  record Branch(long id, long projectId, String name, boolean main, Instant createdAt) {}

  record Analysis(
      long id, long projectId, Long branchId, String analysisId, String status, String revision,
      String sarifSha256, int issueCount, int newIssueCount, Instant createdAt, Instant startedAt,
      Instant completedAt, String error) {}

  record Rule(
      long id, String engine, String ruleKey, String name, String severity, String language,
      String description, String remediation, Instant createdAt, Instant updatedAt) {}

  record Issue(
      long id, long projectId, long branchId, String issueKey, Long analysisId, String filePath,
      Integer line, Integer endLine, String engine, String ruleKey, String severity,
      String issueType, String message, String status, String resolution, String assignee,
      Instant creationDate, Instant updateDate, Instant closedAt) {}

  /** Server-side filters for issue search. A null/empty field means "no constraint". */
  record IssueFilter(
      List<String> statuses, String resolution, List<String> types, List<String> severities,
      List<String> ruleKeys, String engine, String assignee, String filePath, String textQuery) {
    public static IssueFilter empty() {
      return new IssueFilter(null, null, null, null, null, null, null, null, null);
    }
  }

  record QualityGate(
      long id, Long projectId, String externalId, String name,
      List<Map<String, Object>> conditions, boolean isDefault,
      Instant createdAt, Instant updatedAt) {}

  record Measure(long analysisId, String metricKey, BigDecimal valueNum, String valueText) {}

  /** Aggregated issue count for a facet (severity, type or rule). */
  record CountByKey(String key, long count) {}

  List<Project> listProjects(String organization, String query, int limit, int offset);

  long countProjects(String organization, String query);

  /** Projects whose repository binding is in {@code repositoryIds}; empty ids yield no rows. */
  List<Project> listProjectsInRepositories(
      Collection<Long> repositoryIds, String organization, String query, int limit, int offset);

  long countProjectsInRepositories(
      Collection<Long> repositoryIds, String organization, String query);

  Optional<Project> findProject(long id);

  Optional<Project> findProjectByKey(String key);

  Optional<Project> findProjectByExternalId(String externalId);

  Optional<Project> findProjectByRepositoryId(long repositoryId);

  long insertProject(
      String externalId, String key, String name, String organization, String visibility,
      Long repositoryId, Instant now);

  boolean updateProject(
      long id, String key, String name, String organization, String visibility, Instant now);

  boolean bindRepository(long projectId, Long repositoryId, Instant now);

  boolean deleteProject(long id);

  Optional<Branch> findBranch(long projectId, String name);

  List<Branch> listBranches(long projectId);

  long insertBranch(long projectId, String name, boolean main, Instant now);

  /** Takes the branch row lock for the current transaction; false when the branch is gone. */
  boolean lockBranch(long branchId);

  long insertAnalysis(Analysis analysis);

  Optional<Analysis> findAnalysis(long id);

  Optional<Analysis> findAnalysisByExternalId(String analysisId);

  Optional<Analysis> findLatestAnalysis(long projectId, Long branchId);

  boolean completeAnalysis(
      long id, int issueCount, int newIssueCount, String sarifSha256, Instant completedAt);

  boolean failAnalysis(long id, String error, Instant completedAt);

  List<Analysis> listAnalyses(long projectId, int limit, int offset);

  long countAnalyses(long projectId);

  List<Issue> listIssues(long projectId, Long branchId, IssueFilter filter, int limit, int offset);

  long countIssues(long projectId, Long branchId, IssueFilter filter);

  /** Every issue of a branch, used by ingestion to diff the previous state. */
  List<Issue> listBranchIssues(long projectId, long branchId);

  Optional<Issue> findIssue(long projectId, long branchId, String issueKey);

  long insertIssue(
      long projectId, long branchId, String issueKey, long analysisId, String filePath,
      Integer line, Integer endLine, String engine, String ruleKey, String severity,
      String issueType, String message, Instant now);

  boolean observeIssue(
      long issueId, long analysisId, String filePath, Integer line, Integer endLine,
      String severity, String issueType, String message, Instant now);

  /** Closes issues of the branch that the given analysis did not observe. */
  long closeUnseenIssues(long projectId, long branchId, long analysisId, Instant now);

  /** Issues whose first recorded occurrence is {@code analysisId}. */
  long countNewIssues(long projectId, long branchId, long analysisId);

  boolean updateIssueState(
      long projectId, long branchId, String issueKey, String status, String resolution,
      String assignee, Instant now);

  List<CountByKey> countOpenIssuesBySeverity(long projectId, long branchId);

  List<CountByKey> countOpenIssuesByType(long projectId, long branchId);

  List<CountByKey> countOpenIssuesByRule(long projectId, long branchId, int limit);

  void insertOccurrence(
      long issueId, long analysisId, Integer line, Integer endLine, String messageHash,
      Instant now);

  void upsertRule(
      String engine, String ruleKey, String name, String severity, String language,
      String description, String remediation, Instant now);

  Optional<Rule> findRule(String engine, String ruleKey);

  /** Rules filtered by engine, free-text query and language set; the language filter is in SQL. */
  List<Rule> listRules(String engine, String query, List<String> languages, int limit, int offset);

  long countRules(String engine, String query, List<String> languages);

  long insertQualityGate(
      Long projectId, String externalId, String name, List<Map<String, Object>> conditions,
      boolean isDefault, Instant now);

  List<QualityGate> listQualityGates(Long projectId);

  Optional<QualityGate> findDefaultQualityGate();

  Optional<QualityGate> findQualityGateForProject(long projectId);

  boolean updateQualityGate(
      long id, String name, List<Map<String, Object>> conditions, boolean isDefault, Instant now);

  boolean deleteQualityGate(long id);

  /** Clears {@code is_default} so a caller can install exactly one global default. */
  int clearDefaultQualityGates();

  void replaceMeasures(long analysisId, List<Measure> values);

  List<Measure> listMeasures(long analysisId);
}
