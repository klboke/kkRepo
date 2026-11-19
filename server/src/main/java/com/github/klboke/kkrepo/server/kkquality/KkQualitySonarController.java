package com.github.klboke.kkrepo.server.kkquality;

import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Analysis;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Branch;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Issue;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.IssueFilter;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Measure;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Project;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Rule;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only SonarQube Web API subset backed by kkQuality.
 *
 * <p>Shapes follow the SonarQube 8/9 Web API so existing dashboards and CI quality checks can point
 * at kkRepo unchanged: {@code /api/projects/search}, {@code /api/issues/search},
 * {@code /api/measures/component}, {@code /api/qualitygates/project_status} and
 * {@code /api/rules/search}. Ingestion is a kkRepo extension and lives under
 * {@code /api/kkquality/analyses}.
 *
 * <p>Authorization is applied before any row is read: a subject either holds
 * {@code nx-kkquality-read} or only sees projects bound to repositories it can read. The filter is
 * pushed into the SQL query, never applied after pagination.
 */
@RestController
public class KkQualitySonarController {
  private static final DateTimeFormatter SONAR_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 500;

  private final KkQualityDao dao;
  private final KkQualityAuthorizer authorizer;
  private final KkQualityGateService gates;
  private final KkQualityProperties properties;

  public KkQualitySonarController(
      KkQualityDao dao, KkQualityAuthorizer authorizer, KkQualityGateService gates,
      KkQualityProperties properties) {
    this.dao = dao;
    this.authorizer = authorizer;
    this.gates = gates;
    this.properties = properties;
  }

  @GetMapping("/api/projects/search")
  public Map<String, Object> searchProjects(
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "p", required = false) Integer page,
      @RequestParam(name = "page", required = false) Integer pageAlias,
      @RequestParam(name = "ps", required = false) Integer pageSize,
      @RequestParam(name = "pageSize", required = false) Integer pageSizeAlias,
      HttpServletRequest request) {
    ensureEnabled();
    AuthenticatedSubject subject = authorizer.requireSubject(request);
    int size = pageSize(pageSize, pageSizeAlias);
    int index = page(page, pageAlias);
    int offset = (index - 1) * size;

    List<Project> projects;
    long total;
    if (authorizer.hasGlobalRead(subject)) {
      projects = dao.listProjects(null, query, size, offset);
      total = dao.countProjects(null, query);
    } else {
      List<Long> repositoryIds = authorizer.readableRepositoryIds(subject);
      projects = dao.listProjectsInRepositories(repositoryIds, null, query, size, offset);
      total = dao.countProjectsInRepositories(repositoryIds, null, query);
    }

    List<Map<String, Object>> components = new ArrayList<>();
    for (Project project : projects) {
      Map<String, Object> component = new LinkedHashMap<>();
      component.put("key", project.key());
      component.put("name", project.name());
      component.put("qualifier", "TRK");
      component.put("visibility", project.visibility());
      Optional<Analysis> latest = dao.findLatestAnalysis(project.id(), mainBranchId(project.id()));
      latest.ifPresent(analysis -> {
        component.put("lastAnalysisDate", date(analysis.completedAt()));
        if (analysis.revision() != null) {
          component.put("revision", analysis.revision());
        }
      });
      components.add(component);
    }

    return envelope(
        Map.of("paging", paging(index, size, total), "components", components), index, size, total);
  }

  @GetMapping("/api/issues/search")
  public Map<String, Object> searchIssues(
      @RequestParam(name = "componentKeys", required = false) String componentKeys,
      @RequestParam(name = "projectKeys", required = false) String projectKeys,
      @RequestParam(name = "branch", required = false) String branch,
      @RequestParam(name = "statuses", required = false) String statuses,
      @RequestParam(name = "resolved", required = false) Boolean resolved,
      @RequestParam(name = "severities", required = false) String severities,
      @RequestParam(name = "types", required = false) String types,
      @RequestParam(name = "rules", required = false) String rules,
      @RequestParam(name = "assignees", required = false) String assignees,
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "p", required = false) Integer page,
      @RequestParam(name = "page", required = false) Integer pageAlias,
      @RequestParam(name = "ps", required = false) Integer pageSize,
      @RequestParam(name = "pageSize", required = false) Integer pageSizeAlias,
      HttpServletRequest request) {
    ensureEnabled();
    AuthenticatedSubject subject = authorizer.requireSubject(request);
    Project project = requireProject(subject, first(projectKeys, componentKeys));
    long branchId = requireBranch(project.id(), branch);
    int size = pageSize(pageSize, pageSizeAlias);
    int index = page(page, pageAlias);

    List<String> statusList = split(statuses);
    if (statusList.isEmpty() && resolved != null) {
      statusList = resolved ? List.of("RESOLVED", "CLOSED", "FALSE_POSITIVE", "ACCEPTED")
          : KkQualityDao.OPEN_STATUSES;
    }
    List<String> ruleList = new ArrayList<>();
    for (String rule : split(rules)) {
      ruleList.add(rule.contains(":") ? rule.substring(rule.indexOf(':') + 1) : rule);
    }
    IssueFilter filter = new IssueFilter(
        statusList, null, upper(split(types)), upper(split(severities)), ruleList, null,
        first(assignees, null), null, query);

    long total = dao.countIssues(project.id(), branchId, filter);
    List<Issue> issues = dao.listIssues(project.id(), branchId, filter, size, (index - 1) * size);

    List<Map<String, Object>> result = new ArrayList<>();
    for (Issue issue : issues) {
      result.add(issueJson(project.key(), issue));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("paging", paging(index, size, total));
    body.put("issues", result);
    body.put("components", componentsOf(project.key(), issues));
    body.put("rules", rulesOf(issues));
    body.put("facets", List.of());
    return envelope(body, index, size, total);
  }

  @GetMapping("/api/measures/component")
  public Map<String, Object> measures(
      @RequestParam(name = "component", required = false) String component,
      @RequestParam(name = "branch", required = false) String branch,
      @RequestParam(name = "metricKeys", required = false) String metricKeys,
      HttpServletRequest request) {
    ensureEnabled();
    AuthenticatedSubject subject = authorizer.requireSubject(request);
    Project project = requireProject(subject, component);
    long branchId = requireBranch(project.id(), branch);

    Optional<Analysis> latest = dao.findLatestAnalysis(project.id(), branchId);
    Map<String, BigDecimal> values = gates.measuresOf(latest);
    List<String> requested = split(metricKeys);

    List<Map<String, Object>> measures = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
      if (!requested.isEmpty() && !requested.contains(entry.getKey())) {
        continue;
      }
      Map<String, Object> measure = new LinkedHashMap<>();
      measure.put("metric", entry.getKey());
      measure.put("value", entry.getValue().stripTrailingZeros().toPlainString());
      measure.put("bestValue", false);
      measures.add(measure);
    }

    Map<String, Object> node = new LinkedHashMap<>();
    node.put("key", project.key());
    node.put("name", project.name());
    node.put("qualifier", "TRK");
    node.put("measures", measures);
    return Map.of("component", node);
  }

  @GetMapping("/api/qualitygates/project_status")
  public Map<String, Object> projectStatus(
      @RequestParam(name = "projectKey", required = false) String projectKey,
      @RequestParam(name = "project", required = false) String projectAlias,
      @RequestParam(name = "branch", required = false) String branch,
      HttpServletRequest request) {
    ensureEnabled();
    AuthenticatedSubject subject = authorizer.requireSubject(request);
    Project project = requireProject(subject, first(projectKey, projectAlias));
    long branchId = requireBranch(project.id(), branch);
    KkQualityGateService.ProjectStatus status = gates.evaluate(project.id(), branchId);

    List<Map<String, Object>> conditions = new ArrayList<>();
    for (KkQualityGateService.Condition condition : status.conditions()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("status", condition.status());
      node.put("metricKey", condition.metricKey());
      node.put("comparator", condition.comparator());
      node.put("errorThreshold", condition.errorThreshold());
      node.put("actualValue", condition.actualValue());
      conditions.add(node);
    }
    Map<String, Object> projectStatus = new LinkedHashMap<>();
    projectStatus.put("status", status.status());
    projectStatus.put("conditions", conditions);
    if (status.analysisId() != null) {
      projectStatus.put("analysisId", status.analysisId());
    }
    return Map.of("projectStatus", projectStatus);
  }

  @GetMapping("/api/rules/search")
  public Map<String, Object> searchRules(
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "languages", required = false) String languages,
      @RequestParam(name = "p", required = false) Integer page,
      @RequestParam(name = "page", required = false) Integer pageAlias,
      @RequestParam(name = "ps", required = false) Integer pageSize,
      @RequestParam(name = "pageSize", required = false) Integer pageSizeAlias,
      HttpServletRequest request) {
    ensureEnabled();
    authorizer.requireSubject(request);
    int size = pageSize(pageSize, pageSizeAlias);
    int index = page(page, pageAlias);
    List<String> wanted = split(languages);
    List<Rule> rules = dao.listRules(null, query, wanted, size, (index - 1) * size);
    long total = dao.countRules(null, query, wanted);

    List<Map<String, Object>> result = new ArrayList<>();
    for (Rule rule : rules) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("key", rule.engine() + ":" + rule.ruleKey());
      node.put("repo", rule.engine());
      node.put("name", rule.name());
      node.put("severity", rule.severity());
      node.put("lang", rule.language());
      node.put("status", "READY");
      node.put("type", "CODE_SMELL");
      if (rule.description() != null) {
        node.put("htmlDesc", rule.description());
      }
      result.add(node);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("paging", paging(index, size, total));
    body.put("rules", result);
    return envelope(body, index, size, total);
  }

  private Map<String, Object> issueJson(String projectKey, Issue issue) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("key", projectKey + ":" + issue.issueKey());
    node.put("rule", (issue.engine() == null ? "unknown" : issue.engine())
        + ":" + (issue.ruleKey() == null ? "" : issue.ruleKey()));
    node.put("severity", issue.severity());
    node.put("component", projectKey + ":" + issue.filePath());
    node.put("project", projectKey);
    if (issue.line() != null) {
      node.put("line", issue.line());
      Map<String, Object> textRange = new LinkedHashMap<>();
      textRange.put("startLine", issue.line());
      textRange.put("endLine", issue.endLine() == null ? issue.line() : issue.endLine());
      node.put("textRange", textRange);
    }
    node.put("status", issue.status());
    node.put("issueStatus", issue.status());
    if (issue.resolution() != null) {
      node.put("resolution", issue.resolution());
    }
    node.put("message", issue.message());
    node.put("type", issue.issueType());
    node.put("creationDate", date(issue.creationDate()));
    node.put("updateDate", date(issue.updateDate()));
    if (issue.assignee() != null) {
      node.put("assignee", issue.assignee());
    }
    node.put("flows", List.of());
    return node;
  }

  private List<Map<String, Object>> componentsOf(String projectKey, List<Issue> issues) {
    Map<String, Map<String, Object>> components = new LinkedHashMap<>();
    for (Issue issue : issues) {
      components.computeIfAbsent(issue.filePath(), path -> {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", projectKey + ":" + path);
        node.put("longName", path);
        node.put("qualifier", "FIL");
        node.put("path", path);
        return node;
      });
    }
    return new ArrayList<>(components.values());
  }

  private List<Map<String, Object>> rulesOf(List<Issue> issues) {
    Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
    for (Issue issue : issues) {
      if (issue.engine() == null || issue.ruleKey() == null) {
        continue;
      }
      String key = issue.engine() + ":" + issue.ruleKey();
      rules.computeIfAbsent(key, ignored -> {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", key);
        node.put("name", issue.ruleKey());
        node.put("status", "READY");
        return node;
      });
    }
    return new ArrayList<>(rules.values());
  }

  private Project requireProject(AuthenticatedSubject subject, String raw) {
    String key = componentKey(raw);
    if (key == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A project key is required");
    }
    Project project = dao.findProjectByKey(key)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Project " + key + " was not found"));
    authorizer.requireProjectRead(subject, project);
    return project;
  }

  private long requireBranch(long projectId, String branch) {
    String name = branch == null || branch.isBlank() ? "main" : branch.trim();
    return dao.findBranch(projectId, name)
        .map(Branch::id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Branch " + name + " has no analysis"));
  }

  private Long mainBranchId(long projectId) {
    return dao.findBranch(projectId, "main").map(Branch::id).orElse(null);
  }

  private void ensureEnabled() {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "kkQuality is disabled");
    }
  }

  private static String componentKey(String raw) {
    String value = first(raw, null);
    if (value == null) {
      return null;
    }
    int separator = value.indexOf(':');
    return separator > 0 ? value.substring(0, separator) : value;
  }

  private static String first(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.split(",")[0].trim();
  }

  private static List<String> split(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(part -> !part.isEmpty())
        .toList();
  }

  private static List<String> upper(List<String> values) {
    return values.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
  }

  private static int page(Integer page, Integer alias) {
    Integer value = page != null ? page : alias;
    return value == null || value < 1 ? 1 : value;
  }

  private static int pageSize(Integer pageSize, Integer alias) {
    Integer value = pageSize != null ? pageSize : alias;
    if (value == null || value < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(value, MAX_PAGE_SIZE);
  }

  private static Map<String, Object> paging(int page, int pageSize, long total) {
    Map<String, Object> paging = new LinkedHashMap<>();
    paging.put("pageIndex", page);
    paging.put("pageSize", pageSize);
    paging.put("total", total);
    return paging;
  }

  private static Map<String, Object> envelope(
      Map<String, Object> body, int page, int pageSize, long total) {
    Map<String, Object> result = new LinkedHashMap<>(body);
    result.put("total", total);
    result.put("p", page);
    result.put("ps", pageSize);
    result.putIfAbsent("paging", paging(page, pageSize, total));
    return result;
  }

  private static String date(Instant instant) {
    return instant == null ? null : SONAR_DATE.format(instant);
  }

  /** Exposed for tests: the Sonar date format used in every payload. */
  static DateTimeFormatter sonarDateFormatter() {
    return SONAR_DATE;
  }
}
