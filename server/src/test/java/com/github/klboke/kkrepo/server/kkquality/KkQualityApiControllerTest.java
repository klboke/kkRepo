package com.github.klboke.kkrepo.server.kkquality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Project;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenancy checks for the ingestion endpoint.
 *
 * <p>Rule: a caller who may edit repository B must not be able to write into a project bound to
 * repository A by simply naming B in the payload. The controller therefore authorizes against the
 * project's binding and only falls back to the named repository while the project is unbound.
 */
class KkQualityApiControllerTest {
  private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
  private static final String SARIF = "{\"version\":\"2.1.0\",\"runs\":[]}";

  private KkQualityIngestionService ingestion;
  private KkQualityDao dao;
  private KkQualityAuthorizer authorizer;
  private KkQualityApiController controller;
  private HttpServletRequest httpRequest;
  private final AuthenticatedSubject subject =
      new AuthenticatedSubject("local", "alice", "local", null, null);

  @BeforeEach
  void setUp() throws Exception {
    ingestion = mock(KkQualityIngestionService.class);
    dao = mock(KkQualityDao.class);
    authorizer = mock(KkQualityAuthorizer.class);
    KkQualityProperties properties = new KkQualityProperties();
    controller = new KkQualityApiController(ingestion, dao, authorizer, properties);
    httpRequest = mock(HttpServletRequest.class);
    when(authorizer.requireSubject(httpRequest)).thenReturn(subject);
    when(authorizer.findRepository("repo-a")).thenReturn(Optional.of(repository(3L, "repo-a")));
    when(authorizer.findRepository("repo-b")).thenReturn(Optional.of(repository(4L, "repo-b")));
  }

  @Test
  void refusesToWriteIntoAProjectBoundToAnotherRepository() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(project(3L)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.ingest(request("orders", "repo-b"), httpRequest));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    verify(authorizer, never()).requireIngest(any(), any());
    verify(ingestion, never()).ingest(any(), any());
  }

  @Test
  void authorizesAgainstTheBindingWhenThePayloadNamesTheSameRepository() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(project(3L)));

    var response = controller.ingest(request("orders", "repo-a"), httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(authorizer).requireIngest(subject, 3L);
    verify(ingestion).ingest(any(), eq(3L));
  }

  @Test
  void authorizesAgainstTheBindingWhenThePayloadOmitsTheRepository() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(project(3L)));

    controller.ingest(request("orders", null), httpRequest);

    verify(authorizer).requireIngest(subject, 3L);
    verify(ingestion).ingest(any(), eq(3L));
  }

  @Test
  void bindsANewProjectToTheNamedRepository() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.empty());

    controller.ingest(request("orders", "repo-a"), httpRequest);

    verify(authorizer).requireIngest(subject, 3L);
    verify(ingestion).ingest(any(), eq(3L));
  }

  @Test
  void fallsBackToTheGlobalUpdatePrivilegeWithoutBinding() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.empty());

    controller.ingest(request("orders", null), httpRequest);

    verify(authorizer).requireIngest(subject, null);
    verify(ingestion).ingest(any(), isNull());
  }

  @Test
  void rejectsAnUnknownRepositoryNameInsteadOfIgnoringIt() {
    when(dao.findProjectByKey("orders")).thenReturn(Optional.of(project(3L)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.ingest(request("orders", "nope"), httpRequest));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    verify(ingestion, never()).ingest(any(), any());
  }

  @Test
  void rejectsAMissingProjectKey() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.ingest(request(" ", "repo-a"), httpRequest));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
  }

  private KkQualityIngestionService.IngestRequest request(String project, String repository) {
    try {
      return new KkQualityIngestionService.IngestRequest(
          project, "Orders", "main", "rev-1", repository,
          new ObjectMapper().readTree(SARIF));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Project project(Long repositoryId) {
    return new Project(
        5L, "ext-5", "orders", "Orders", null, "private", repositoryId, CREATED, CREATED);
  }

  private static RepositoryRecord repository(Long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven2-hosted", true, 1L, null,
        null, "RELEASE", "STRICT", "ALLOW", false, Map.of());
  }
}
