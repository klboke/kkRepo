package com.github.klboke.kkrepo.server.kkquality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract checks for the Sonar-compatible read surface: payload keys, pagination and the fact that
 * authorization happens before pagination instead of after it.
 */
class KkQualitySonarControllerTest {
  private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
  private static final Project PROJECT = new Project(
      5L, "ext", "orders", "Orders", null, "private", null, CREATED, CREATED);
  private static final Branch BRANCH = new Branch(9L, 5L, "main", true, CREATED);
  private static final Analysis ANALYSIS = new Analysis(
      11L, 5L, 9L, "analysis-11", "COMPLETE", "rev-1", "a".repeat(64), 2, 1, CREATED, CREATED,
      CREATED, null);

  private KkQualityDao dao;
  private KkQualityAuthorizer authorizer;
  private KkQualityGateService gates;
  private KkQualityProperties properties;
  private KkQualitySonarController controller;
  private HttpServletRequest request;

  @BeforeEach
  void setUp() {
    dao = mock(KkQualityDao.class);
    authorizer = mock(KkQualityAuthorizer.class);
    properties = new KkQualityProperties();
    gates = new KkQualityGateService(dao);
    controller = new KkQualitySonarController(dao, authorizer, gates, properties);
    request = mock(HttpServletRequest.class);
    when(authorizer.requireSubject(request))
        .thenReturn(new AuthenticatedSubject("local", "alice", "local", null, null));
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(PROJECT));
    when(dao.findBranch(5L, "main")).thenReturn(Optional.of(BRANCH));
  }

  @Test
  void projectSearchUsesTheSonarEnvelopeAndRepositoryScopedPaging() {
    when(authorizer.hasGlobalRead(any())).thenReturn(false);
    when(authorizer.readableRepositoryIds(any())).thenReturn(List.of(3L));
    when(dao.listProjectsInRepositories(eq(List.of(3L)), eq(null), eq("ord"), eq(10), eq(0)))
        .thenReturn(List.of(PROJECT));
    when(dao.countProjectsInRepositories(List.of(3L), null, "ord")).thenReturn(1L);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.of(ANALYSIS));

    Map<String, Object> body = controller.searchProjects(
        "ord", null, null, 10, null, request);

    assertEquals(1L, body.get("total"));
    assertEquals(1, body.get("p"));
    assertEquals(10, body.get("ps"));
    List<Map<String, Object>> components =
        (List<Map<String, Object>>) body.get("components");
    assertEquals("orders", components.get(0).get("key"));
    assertEquals("TRK", components.get(0).get("qualifier"));
    assertEquals("2026-01-02T03:04:05+0000", components.get(0).get("lastAnalysisDate"));
    verify(dao).listProjectsInRepositories(List.of(3L), null, "ord", 10, 0);
  }

  @Test
  void issueSearchTranslatesSonarFiltersAndExposesComponentKeys() {
    when(authorizer.hasGlobalRead(any())).thenReturn(true);
    when(dao.countIssues(eq(5L), eq(9L), any(IssueFilter.class))).thenReturn(2L);
    when(dao.listIssues(eq(5L), eq(9L), any(IssueFilter.class), eq(100), eq(0)))
        .thenReturn(List.of(issue()));

    Map<String, Object> body = controller.searchIssues(
        "orders:src/App.java", null, null, "OPEN,CONFIRMED", null, "MAJOR", "VULNERABILITY",
        "semgrep:rule-1", null, null, null, null, 100, null, request);

    assertEquals(2L, body.get("total"));
    List<Map<String, Object>> issues = (List<Map<String, Object>>) body.get("issues");
    Map<String, Object> issue = issues.get(0);
    assertEquals("orders:fp", issue.get("key"));
    assertEquals("orders:src/App.java", issue.get("component"));
    assertEquals("orders", issue.get("project"));
    assertEquals("semgrep:rule-1", issue.get("rule"));
    assertEquals("OPEN", issue.get("status"));
    assertEquals("OPEN", issue.get("issueStatus"));
    Map<String, Object> textRange = (Map<String, Object>) issue.get("textRange");
    assertEquals(42, textRange.get("startLine"));
    assertEquals(45, textRange.get("endLine"));
    List<Map<String, Object>> components =
        (List<Map<String, Object>>) body.get("components");
    assertEquals("orders:src/App.java", components.get(0).get("key"));
    assertEquals("FIL", components.get(0).get("qualifier"));
    assertFalse(((List<?>) body.get("facets")).iterator().hasNext());
  }

  @Test
  void issueSearchMapsResolvedFalseToTheOpenStatusSet() {
    when(authorizer.hasGlobalRead(any())).thenReturn(true);
    when(dao.countIssues(eq(5L), eq(9L), any(IssueFilter.class))).thenReturn(0L);
    when(dao.listIssues(eq(5L), eq(9L), any(IssueFilter.class), anyInt(), anyInt()))
        .thenReturn(List.of());

    controller.searchIssues(
        null, "orders", null, null, false, null, null, null, null, null, null, null, null, null,
        request);

    var captor = org.mockito.ArgumentCaptor.forClass(IssueFilter.class);
    verify(dao).countIssues(eq(5L), eq(9L), captor.capture());
    assertEquals(KkQualityDao.OPEN_STATUSES, captor.getValue().statuses());
  }

  @Test
  void measuresComponentReturnsTheLatestAnalysisValues() {
    when(authorizer.hasGlobalRead(any())).thenReturn(true);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.of(ANALYSIS));
    when(dao.listMeasures(11L)).thenReturn(List.of(
        new Measure(11L, "bugs", new BigDecimal("3"), null),
        new Measure(11L, "vulnerabilities", new BigDecimal("0"), null)));

    Map<String, Object> body = controller.measures("orders", null, "bugs", request);

    Map<String, Object> component = (Map<String, Object>) body.get("component");
    assertEquals("orders", component.get("key"));
    assertEquals("TRK", component.get("qualifier"));
    List<Map<String, Object>> measures = (List<Map<String, Object>>) component.get("measures");
    assertEquals(1, measures.size());
    assertEquals("bugs", measures.get(0).get("metric"));
    assertEquals("3", measures.get(0).get("value"));
  }

  @Test
  void projectStatusReportsAFailedGateCondition() {
    when(authorizer.hasGlobalRead(any())).thenReturn(true);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.of(ANALYSIS));
    when(dao.listMeasures(11L)).thenReturn(List.of(
        new Measure(11L, "bugs", new BigDecimal("3"), null)));
    when(dao.findQualityGateForProject(5L)).thenReturn(Optional.of(
        new KkQualityDao.QualityGate(
            7L, null, "gate", "Gate",
            List.of(Map.of("metric", "bugs", "operator", "GREATER_THAN", "value", "0")),
            true, CREATED, CREATED)));

    Map<String, Object> body = controller.projectStatus("orders", null, null, request);

    Map<String, Object> status = (Map<String, Object>) body.get("projectStatus");
    assertEquals("ERROR", status.get("status"));
    List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
    assertEquals("ERROR", conditions.get(0).get("status"));
    assertEquals("GT", conditions.get(0).get("comparator"));
    assertEquals("0", conditions.get(0).get("errorThreshold"));
    assertEquals("3", conditions.get(0).get("actualValue"));
  }

  @Test
  void projectStatusIsNoneWithoutAGate() {
    when(authorizer.hasGlobalRead(any())).thenReturn(true);
    when(dao.findLatestAnalysis(5L, 9L)).thenReturn(Optional.of(ANALYSIS));
    when(dao.findQualityGateForProject(5L)).thenReturn(Optional.empty());
    when(dao.listMeasures(11L)).thenReturn(List.of());

    Map<String, Object> body = controller.projectStatus("orders", null, null, request);

    assertEquals("NONE", ((Map<String, Object>) body.get("projectStatus")).get("status"));
  }

  @Test
  void rulesSearchKeysRulesByEngine() {
    when(dao.listRules(eq(null), eq("xss"), eq(List.of()), eq(100), eq(0))).thenReturn(List.of(
        new Rule(1L, "semgrep", "rule-1", "XSS", "MAJOR", "java", "desc", null, CREATED,
            CREATED)));
    when(dao.countRules(null, "xss", List.of())).thenReturn(1L);

    Map<String, Object> body = controller.searchRules("xss", null, null, null, 100, null, request);

    List<Map<String, Object>> rules = (List<Map<String, Object>>) body.get("rules");
    assertEquals("semgrep:rule-1", rules.get(0).get("key"));
    assertEquals("semgrep", rules.get(0).get("repo"));
    assertEquals(1L, body.get("total"));
  }

  @Test
  void rulesSearchPushesTheLanguageFilterIntoTheQuery() {
    when(dao.listRules(eq(null), eq(null), eq(List.of("java", "kotlin")), eq(50), eq(0)))
        .thenReturn(List.of(new Rule(
            1L, "semgrep", "rule-1", "XSS", "MAJOR", "java", "desc", null, CREATED, CREATED)));
    when(dao.countRules(null, null, List.of("java", "kotlin"))).thenReturn(1L);

    Map<String, Object> body = controller.searchRules(
        null, "java,kotlin", null, null, 50, null, request);

    assertEquals(1L, body.get("total"));
    assertEquals(1, ((List<?>) body.get("rules")).size());
    verify(dao).countRules(null, null, List.of("java", "kotlin"));
  }

  @Test
  void unreadableProjectIsForbidden() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(PROJECT));
    org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
        .when(authorizer).requireProjectRead(any(), any());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.measures("orders", null, null, request));
    assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
  }

  @Test
  void disabledServiceAnswersNotFound() {
    properties.setEnabled(false);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.searchProjects(null, null, null, null, null, request));
    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    assertNotNull(error.getReason());
    assertTrue(error.getReason().contains("disabled"));
  }

  @Test
  void unknownBranchIsNotFound() {
    when(authorizer.hasGlobalRead(any())).thenReturn(true);
    when(dao.findBranch(5L, "feature")).thenReturn(Optional.empty());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.measures("orders", "feature", null, request));
    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
  }

  private static Issue issue() {
    return new Issue(
        1L, 5L, 9L, "fp", 11L, "src/App.java", 42, 45, "semgrep", "rule-1", "MAJOR",
        "VULNERABILITY", "Reflected XSS", "OPEN", null, null, CREATED, CREATED, null);
  }
}
