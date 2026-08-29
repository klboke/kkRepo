package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmTokenService;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    RepositoryProtocolController controller = controller(repositories);
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
    RepositoryProtocolController controller = controller(repositories);
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

  @Test
  void npmLegacyLoginReturnsMaterializedTokenJson() throws Exception {
    assertLoginResponse("-/user/org.couchdb.user:alice");
  }

  @Test
  void npmLegacyLogoutReturnsMaterializedJson() throws Exception {
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    repositories.repository(repository("npm-example", RepositoryFormat.NPM, RepositoryType.HOSTED));
    byte[] logoutJson = "{\"ok\":\"true\"}".getBytes(StandardCharsets.UTF_8);
    NpmTokenService tokenService = mock(NpmTokenService.class);
    when(tokenService.logout(any()))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(logoutJson), logoutJson.length, "application/json", null, null));
    RepositoryProtocolController controller = controller(repositories, tokenService);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "DELETE", "/repository/npm-example/-/user/token/NpmToken.generated-token");

    ResponseEntity<?> response = controller.delete("npm-example", request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(logoutJson.length, response.getHeaders().getContentLength());
    assertEquals("{\"ok\":\"true\"}",
        new String((byte[]) response.getBody(), StandardCharsets.UTF_8));
  }

  private static void assertLoginResponse(String path) throws Exception {
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    repositories.repository(repository("npm-example", RepositoryFormat.NPM, RepositoryType.HOSTED));
    byte[] tokenJson = "{\"token\":\"NpmToken.generated-token\"}"
        .getBytes(StandardCharsets.UTF_8);
    NpmTokenService tokenService = mock(NpmTokenService.class);
    when(tokenService.login(any()))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(tokenJson), tokenJson.length, "application/json", null, null)
            .withStatus(201));
    RepositoryProtocolController controller = controller(repositories, tokenService);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "PUT", "/repository/npm-example/" + path);
    request.setContent("{\"name\":\"alice\",\"password\":\"secret\"}"
        .getBytes(StandardCharsets.UTF_8));

    ResponseEntity<?> response = controller.put("npm-example", request);

    assertEquals(201, response.getStatusCode().value());
    assertEquals(tokenJson.length, response.getHeaders().getContentLength());
    assertEquals("{\"token\":\"NpmToken.generated-token\"}",
        new String((byte[]) response.getBody(), StandardCharsets.UTF_8));
  }

  private static RepositoryProtocolController controller(FakeRepositoryDao repositories) {
    return controller(repositories, null);
  }

  private static RepositoryProtocolController controller(
      FakeRepositoryDao repositories, NpmTokenService tokenService) {
    return RepositoryProtocolControllerTestSupport.controller(
        new RepositoryRuntimeRegistry(repositories, 0),
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, tokenService,
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
