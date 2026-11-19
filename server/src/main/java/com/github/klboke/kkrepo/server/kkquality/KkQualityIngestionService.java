package com.github.klboke.kkrepo.server.kkquality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Analysis;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Branch;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Issue;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Measure;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Project;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * SARIF 2.1.0 ingestion for kkQuality.
 *
 * <p>Analyzers run in CI and submit one SARIF document per analysis. kkQuality normalizes rules,
 * results, locations and severities into {@code kkq_rule}/{@code kkq_issue}, keeps the immutable
 * document as an object-storage blob addressed by SHA-256, and exposes a status view.
 *
 * <p><strong>Idempotence.</strong> Resubmitting a document whose digest equals the latest analysis
 * of the same branch returns that analysis instead of creating a new one. Re-analysis of an
 * unchanged tree is therefore a no-op, and an issue is never duplicated: issues are keyed by a
 * fingerprint that excludes the analysis identifier, and each analysis adds one
 * {@code kkq_issue_occurrence} row.
 *
 * <p><strong>Multi-replica.</strong> The whole write happens in one transaction that first locks the
 * branch row ({@code SELECT ... FOR UPDATE}). Two replicas ingesting the same branch serialize;
 * different branches run in parallel. Rule metadata is global reference data, so a concurrent
 * first-sighting of the same rule from another branch can raise a duplicate-key error; the whole
 * transaction is retried once, which is safe because blob writes are content-addressed.
 */
@Service
public class KkQualityIngestionService {
  private static final HexFormat HEX = HexFormat.of();
  private static final int MAX_ENGINE = 64;
  private static final int MAX_RULE_KEY = 255;
  private static final int MAX_PATH = 1024;
  private static final String DEFAULT_BRANCH = "main";
  private static final List<String> SEVERITIES =
      List.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");
  private static final List<String> TYPES = List.of("BUG", "VULNERABILITY", "CODE_SMELL");

  private final KkQualityDao dao;
  private final BlobStoreDao blobStores;
  private final BlobStorageRegistry storages;
  private final ObjectMapper objectMapper;
  private final KkQualityProperties properties;
  private final TransactionTemplate transactions;

  public KkQualityIngestionService(
      KkQualityDao dao, BlobStoreDao blobStores, BlobStorageRegistry storages,
      ObjectMapper objectMapper, KkQualityProperties properties,
      TransactionTemplate transactions) {
    this.dao = dao;
    this.blobStores = blobStores;
    this.storages = storages;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.transactions = transactions;
  }

  public record AnalysisStatusView(
      String analysisId, String project, String branch, String revision, String status,
      int issueCount, int newIssueCount, Instant createdAt, Instant completedAt, String error) {}

  public record IngestRequest(
      String project, String projectName, String branch, String revision, String repository,
      JsonNode sarif) {}

  /**
   * Ingests one analysis on behalf of a caller the controller already authorized.
   *
   * <p>{@code repositoryId} is the resolved project tenant (an existing binding wins over the
   * repository named in the payload); it is re-validated inside the transaction so a concurrent
   * binding cannot be overwritten by a caller authorized for another repository.
   */
  public AnalysisStatusView ingest(IngestRequest request, Long repositoryId) {
    try {
      return transactions.execute(status -> doIngest(request, repositoryId));
    } catch (DuplicateKeyException conflict) {
      // A concurrent replica inserted the same rule (or project) first. Re-running is safe: the
      // SARIF blob is content-addressed and issue writes are keyed by fingerprint.
      return transactions.execute(status -> doIngest(request, repositoryId));
    }
  }

  private AnalysisStatusView doIngest(IngestRequest request, Long repositoryId) {
    if (request == null) {
      throw badRequest("An analysis payload is required");
    }
    String projectKey = required(request.project(), "project");
    String branchName = optional(request.branch(), DEFAULT_BRANCH);
    String revision = optional(request.revision(), null);
    byte[] document = validateSarif(request.sarif());
    String sha256 = digest(document);
    // Normalize before touching the database: an invalid or oversized document is rejected without
    // creating a project, a branch or an analysis row.
    Parsed parsed = parse(document);

    Project project = ensureProject(projectKey, request.projectName(), repositoryId);
    Branch branch = ensureBranch(project.id(), branchName);
    if (!dao.lockBranch(branch.id())) {
      throw notFound("Branch " + branchName + " disappeared during ingestion");
    }

    Optional<Analysis> existing = dao.findLatestAnalysis(project.id(), branch.id())
        .filter(candidate -> sha256.equalsIgnoreCase(candidate.sarifSha256()))
        .filter(candidate -> "COMPLETE".equals(candidate.status()));
    if (existing.isPresent()) {
      return view(existing.get(), project, branchName);
    }

    storeDocument(document, sha256);
    Instant now = Instant.now();
    String analysisId = UUID.randomUUID().toString();
    long id = dao.insertAnalysis(new Analysis(
        0L, project.id(), branch.id(), analysisId, "RUNNING", revision, sha256, 0, 0,
        now, now, null, null));

    upsertRules(parsed.rules(), now);

    Map<String, Issue> existingIssues = new HashMap<>();
    for (Issue issue : dao.listBranchIssues(project.id(), branch.id())) {
      existingIssues.put(issue.issueKey(), issue);
    }
    int observed = 0;
    for (ParsedIssue issue : parsed.issues()) {
      Issue current = existingIssues.get(issue.issueKey());
      long issueId;
      if (current == null) {
        issueId = dao.insertIssue(
            project.id(), branch.id(), issue.issueKey(), id, issue.filePath(), issue.line(),
            issue.endLine(), issue.engine(), issue.ruleKey(), issue.severity(), issue.issueType(),
            issue.message(), now);
      } else {
        issueId = current.id();
        dao.observeIssue(
            issueId, id, issue.filePath(), issue.line(), issue.endLine(), issue.severity(),
            issue.issueType(), issue.message(), now);
      }
      dao.insertOccurrence(
          issueId, id, issue.line(), issue.endLine(), issue.messageHash(), now);
      observed++;
    }
    dao.closeUnseenIssues(project.id(), branch.id(), id, now);

    long newIssues = dao.countNewIssues(project.id(), branch.id(), id);
    replaceMeasures(id, parsed.issues(), newIssues);
    dao.completeAnalysis(id, observed, (int) Math.min(newIssues, Integer.MAX_VALUE), sha256, now);
    return view(dao.findAnalysis(id).orElseThrow(), project, branchName);
  }

  private Project ensureProject(String key, String name, Long repositoryId) {
    Optional<Project> existing = dao.findProjectByKey(key);
    if (existing.isPresent()) {
      Project project = existing.get();
      if (repositoryId != null && project.repositoryId() != null
          && !repositoryId.equals(project.repositoryId())) {
        // Defence in depth: the controller already resolved the binding, but the project may have
        // been bound by another replica between that check and this transaction.
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Project " + key + " is bound to another repository");
      }
      if (repositoryId != null && project.repositoryId() == null) {
        dao.bindRepository(project.id(), repositoryId, Instant.now());
        return dao.findProject(project.id()).orElse(project);
      }
      return project;
    }
    long id = dao.insertProject(
        UUID.randomUUID().toString(), key, optional(name, key), null, "private", repositoryId,
        Instant.now());
    return dao.findProject(id).orElseThrow();
  }

  private Branch ensureBranch(long projectId, String name) {
    return dao.findBranch(projectId, name).orElseGet(() -> {
      long id = dao.insertBranch(projectId, name, DEFAULT_BRANCH.equals(name), Instant.now());
      return dao.findBranch(projectId, name)
          .orElse(new Branch(id, projectId, name, DEFAULT_BRANCH.equals(name), Instant.now()));
    });
  }

  private void upsertRules(List<KkQualityDao.Rule> rules, Instant now) {
    for (KkQualityDao.Rule rule : rules) {
      dao.upsertRule(
          rule.engine(), rule.ruleKey(), rule.name(), rule.severity(), rule.language(),
          rule.description(), rule.remediation(), now);
    }
  }

  private void replaceMeasures(long analysisId, List<ParsedIssue> issues, long newIssues) {
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    Map<String, Long> byType = new LinkedHashMap<>();
    for (ParsedIssue issue : issues) {
      byType.merge(issue.issueType(), 1L, Long::sum);
    }
    values.put("bugs", BigDecimal.valueOf(byType.getOrDefault("BUG", 0L)));
    values.put("vulnerabilities", BigDecimal.valueOf(byType.getOrDefault("VULNERABILITY", 0L)));
    values.put("code_smells", BigDecimal.valueOf(byType.getOrDefault("CODE_SMELL", 0L)));
    // New-code metrics are only exact for issue types, so `new_*` mirrors the count of issues
    // whose first occurrence is this analysis.
    values.put("new_issues", BigDecimal.valueOf(newIssues));
    List<Measure> measures = new ArrayList<>();
    values.forEach((metric, value) -> measures.add(new Measure(analysisId, metric, value, null)));
    dao.replaceMeasures(analysisId, measures);
  }

  private void storeDocument(byte[] document, String sha256) {
    BlobStoreRecord store = blobStores.list().stream().findFirst().orElseThrow(
        () -> new IllegalStateException("No blob store is configured for kkQuality analyses"));
    BlobStorage storage = storages.forBlobStoreId(store.id());
    BlobReference reference = storage.put(
        properties.getBlobRepository(),
        properties.getSarifPathPrefix() + sha256 + ".json",
        new ByteArrayInputStream(document),
        document.length,
        sha256);
    if (!sha256.equalsIgnoreCase(reference.sha256())) {
      throw new IllegalStateException("Object storage did not accept the SARIF digest");
    }
  }

  private byte[] validateSarif(JsonNode sarif) {
    if (sarif == null || sarif.isNull()) {
      throw badRequest("A SARIF document is required");
    }
    JsonNode root = sarif;
    if (sarif.isTextual()) {
      try {
        root = objectMapper.readTree(sarif.asText());
      } catch (IOException e) {
        throw badRequest("The SARIF document is not valid JSON");
      }
    }
    if (!root.isObject() || !root.path("runs").isArray()) {
      throw badRequest("The SARIF document must contain a runs array");
    }
    String version = root.path("version").asText("");
    if (!version.isBlank() && !version.startsWith("2.")) {
      throw badRequest("Only SARIF 2.x documents are supported");
    }
    byte[] document;
    try {
      document = objectMapper.writeValueAsBytes(root);
    } catch (IOException e) {
      throw badRequest("The SARIF document could not be serialized");
    }
    if (document.length > properties.getMaxSarifBytes()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "SARIF exceeds the configured size limit");
    }
    return document;
  }

  private Parsed parse(byte[] document) {
    JsonNode root;
    try {
      root = objectMapper.readTree(document);
    } catch (IOException e) {
      throw badRequest("The SARIF document is not valid JSON");
    }
    List<KkQualityDao.Rule> rules = new ArrayList<>();
    List<ParsedIssue> issues = new ArrayList<>();
    Instant now = Instant.now();
    for (JsonNode run : root.path("runs")) {
      JsonNode driver = run.path("tool").path("driver");
      String engine = truncate(driver.path("name").asText("analyzer"), MAX_ENGINE);
      for (JsonNode rule : driver.path("rules")) {
        String ruleKey = truncate(rule.path("id").asText(""), MAX_RULE_KEY);
        if (ruleKey.isBlank()) {
          continue;
        }
        rules.add(new KkQualityDao.Rule(
            0L, engine, ruleKey, truncate(rule.path("name").asText(ruleKey), 512),
            severityFor(rule, null), languageFor(rule),
            text(rule.path("fullDescription")), text(rule.path("help")), now, now));
      }
      for (JsonNode result : run.path("results")) {
        String ruleKey = truncate(result.path("ruleId").asText(""), MAX_RULE_KEY);
        JsonNode ruleNode = ruleByKey(driver.path("rules"), ruleKey);
        String message = text(result.path("message"));
        JsonNode location = firstLocation(result);
        String filePath = truncate(location.path("uri").asText(""), MAX_PATH);
        if (filePath.isBlank()) {
          continue;
        }
        Integer line = location.path("line").isNumber() ? location.path("line").asInt() : null;
        Integer endLine = location.path("endLine").isNumber()
            ? Integer.valueOf(location.path("endLine").asInt()) : line;
        String severity = severityFor(ruleNode, result.path("level").asText(null));
        String messageHash = digestText(message == null ? "" : message);
        issues.add(new ParsedIssue(
            fingerprint(engine, ruleKey, filePath, message), filePath, line, endLine, engine,
            ruleKey.isBlank() ? null : ruleKey, severity, issueType(ruleNode), message,
            messageHash));
        if (issues.size() > properties.getMaxIssues()) {
          throw new ResponseStatusException(
              HttpStatus.PAYLOAD_TOO_LARGE,
              "SARIF contains more than " + properties.getMaxIssues() + " issues");
        }
      }
    }
    return new Parsed(rules, issues);
  }

  private static JsonNode ruleByKey(JsonNode rules, String key) {
    for (JsonNode rule : rules) {
      if (key.equals(rule.path("id").asText(""))) {
        return rule;
      }
    }
    return null;
  }

  private static JsonNode firstLocation(JsonNode result) {
    JsonNode locations = result.path("locations");
    if (!locations.isArray() || locations.isEmpty()) {
      return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }
    JsonNode physical = locations.get(0).path("physicalLocation");
    JsonNode region = physical.path("region");
    com.fasterxml.jackson.databind.node.ObjectNode location =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    location.put("uri", physical.path("artifactLocation").path("uri").asText(""));
    if (region.path("startLine").isNumber()) {
      location.put("line", region.path("startLine").asInt());
    }
    if (region.path("endLine").isNumber()) {
      location.put("endLine", region.path("endLine").asInt());
    }
    return location;
  }

  private static String severityFor(JsonNode rule, String level) {
    JsonNode security = rule == null ? null : rule.path("properties").path("security-severity");
    if (security != null && security.isValueNode() && !security.asText().isBlank()) {
      try {
        BigDecimal score = new BigDecimal(security.asText());
        if (score.compareTo(new BigDecimal("9.0")) >= 0) return "BLOCKER";
        if (score.compareTo(new BigDecimal("7.0")) >= 0) return "CRITICAL";
        if (score.compareTo(new BigDecimal("4.0")) >= 0) return "MAJOR";
        if (score.compareTo(new BigDecimal("1.0")) >= 0) return "MINOR";
        return "INFO";
      } catch (NumberFormatException ignored) {
        // Fall back to the SARIF level.
      }
    }
    if (level == null) {
      return "MAJOR";
    }
    return switch (level.toLowerCase(Locale.ROOT)) {
      case "error" -> "MAJOR";
      case "warning" -> "MINOR";
      case "note", "none" -> "INFO";
      default -> "MAJOR";
    };
  }

  private static String issueType(JsonNode rule) {
    JsonNode tags = rule == null ? null : rule.path("properties").path("tags");
    StringBuilder joined = new StringBuilder();
    if (tags != null && tags.isArray()) {
      for (JsonNode tag : tags) {
        joined.append(tag.asText("")).append(' ');
      }
    }
    String value = joined.toString().toLowerCase(Locale.ROOT);
    if (value.contains("security") || value.contains("vulnerab")) {
      return "VULNERABILITY";
    }
    if (value.contains("bug") || value.contains("correctness")) {
      return "BUG";
    }
    return "CODE_SMELL";
  }

  private static String languageFor(JsonNode rule) {
    JsonNode language = rule.path("properties").path("language");
    return language.isValueNode() && !language.asText().isBlank()
        ? truncate(language.asText(), 64) : null;
  }

  /**
   * Stable issue identity: analyzer, rule and file plus the normalized message. The line number is
   * deliberately excluded so moving code does not create a new issue, and the analysis identifier is
   * excluded so the same finding is one row for the life of the branch.
   */
  static String fingerprint(String engine, String ruleKey, String filePath, String message) {
    String seed = String.join("|", engine, ruleKey, filePath,
        message == null ? "" : message.trim());
    return digestText(seed);
  }

  private static String digestText(String value) {
    return digest(value.getBytes(StandardCharsets.UTF_8));
  }

  private AnalysisStatusView view(Analysis analysis, Project project, String branch) {
    return new AnalysisStatusView(
        analysis.analysisId(), project.key(), branch, analysis.revision(), analysis.status(),
        analysis.issueCount(), analysis.newIssueCount(), analysis.createdAt(),
        analysis.completedAt(), analysis.error());
  }

  private static String text(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isTextual()) {
      String value = node.asText().trim();
      return value.isEmpty() ? null : value;
    }
    JsonNode text = node.path("text");
    if (text.isTextual()) {
      String value = text.asText().trim();
      return value.isEmpty() ? null : value;
    }
    return null;
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw badRequest(field + " is required");
    }
    return value.trim();
  }

  private static String optional(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static String digest(byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private record Parsed(List<KkQualityDao.Rule> rules, List<ParsedIssue> issues) {}

  private record ParsedIssue(
      String issueKey, String filePath, Integer line, Integer endLine, String engine,
      String ruleKey, String severity, String issueType, String message, String messageHash) {}

  static List<String> severities() {
    return SEVERITIES;
  }

  static List<String> types() {
    return TYPES;
  }

  /** Exposed for tests and the read API: normalized severity order. */
  public static int severityRank(String severity) {
    int index = SEVERITIES.indexOf(severity == null ? "" : severity);
    return index < 0 ? SEVERITIES.size() : index;
  }
}
