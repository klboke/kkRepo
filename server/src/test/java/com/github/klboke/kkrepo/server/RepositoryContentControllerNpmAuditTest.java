package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class RepositoryContentControllerNpmAuditTest {

  @Test
  void npmAdvisoriesBulkReturnsEmptyCompatibilityResponse() {
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    repositories.repository(repository("npm-example", RepositoryFormat.NPM, RepositoryType.GROUP));
    RepositoryContentController controller = controller(repositories);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/repository/npm-example/-/npm/v1/security/advisories/bulk");

    ResponseEntity<?> response = controller.post("npm-example", request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(Map.of(), response.getBody());
  }

  @Test
  @SuppressWarnings("unchecked")
  void npmAuditQuickReturnsEmptyAuditReport() {
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    repositories.repository(repository("npm-example", RepositoryFormat.NPM, RepositoryType.GROUP));
    RepositoryContentController controller = controller(repositories);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/repository/npm-example/-/npm/v1/security/audits/quick");

    ResponseEntity<?> response = controller.post("npm-example", request);

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals(List.of(), body.get("actions"));
    assertEquals(Map.of(), body.get("advisories"));
    assertEquals(List.of(), body.get("muted"));
    assertEquals(0, ((Map<String, Object>) body.get("metadata")).get("totalDependencies"));
  }

  private static RepositoryContentController controller(FakeRepositoryDao repositories) {
    return new RepositoryContentController(
        new RepositoryRuntimeRegistry(repositories, 0),
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        new ObjectMapper(),
        null);
  }

  private static RepositoryRecord repository(String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L,
        name,
        format,
        type,
        format.name().toLowerCase() + "-" + type.name().toLowerCase(),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static class FakeRepositoryDao extends RepositoryDaoAdapter {
    private RepositoryRecord repository;

    FakeRepositoryDao() {
      super(null, null);
    }

    void repository(RepositoryRecord repository) {
      this.repository = repository;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return repository != null && repository.name().equals(name) ? Optional.of(repository) : Optional.empty();
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return List.of();
    }
  }
}
