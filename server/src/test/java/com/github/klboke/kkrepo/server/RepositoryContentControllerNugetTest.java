package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.nuget.NugetPaths;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.nuget.NugetService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerNugetTest {
  @Test
  void rootRepositoryUrlServesNugetServiceIndex() throws Exception {
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    repositories.repository(repository("nuget-hosted", RepositoryFormat.NUGET, RepositoryType.HOSTED));
    ObjectMapper objectMapper = new ObjectMapper();
    NugetService nuget = new NugetService(null, null, null, objectMapper);
    RepositoryContentController controller = controller(repositories, nuget);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/nuget-hosted/");
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(28090);

    ResponseEntity<StreamingResponseBody> response = controller.get("nuget-hosted", request);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    response.getBody().writeTo(body);
    String json = body.toString(StandardCharsets.UTF_8);

    assertEquals(200, response.getStatusCode().value());
    assertTrue(json.contains("\"version\":\"3.0.0\""));
    assertTrue(json.contains("http://localhost:28090/repository/nuget-hosted/v3-flatcontainer/"));
    assertTrue(json.contains("http://localhost:28090/repository/nuget-hosted/api/v2/package"));
  }

  @Test
  void rootMultipartPutPublishesNugetPackage() throws Exception {
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    repositories.repository(repository("nuget-hosted", RepositoryFormat.NUGET, RepositoryType.HOSTED));
    CapturingNugetService nuget = new CapturingNugetService();
    RepositoryContentController controller = controller(repositories, nuget);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "PUT", "/repository/nuget-hosted/");
    request.setContentType("multipart/form-data; boundary=test");
    request.addPart(new MockPart("package", "fixture.nupkg",
        "fake nupkg".getBytes(StandardCharsets.UTF_8)));

    ResponseEntity<?> response = controller.put("nuget-hosted", request, request.getContentType());

    assertEquals(201, response.getStatusCode().value());
    assertEquals(NugetPaths.PACKAGE_PUBLISH, nuget.rawPath);
    assertEquals("fake nupkg", nuget.body);
  }

  private static RepositoryContentController controller(FakeRepositoryDao repositories, NugetService nuget) {
    return new RepositoryContentController(
        new com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry(repositories, 0),
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, null,
        null, null, null,
        nuget, null, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""));
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

  private static final class FakeRepositoryDao extends RepositoryDao {
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

  private static final class CapturingNugetService extends NugetService {
    private String rawPath;
    private String body;

    CapturingNugetService() {
      super(null, null, null, new ObjectMapper());
    }

    @Override
    public MavenResponse putPackage(RepositoryRuntime runtime, String rawPath, InputStream body,
        String contentType, String createdBy, String createdByIp) {
      this.rawPath = rawPath;
      try {
        this.body = new String(body.readAllBytes(), StandardCharsets.UTF_8);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      return MavenResponse.created();
    }
  }
}
