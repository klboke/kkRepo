package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.rubygems.RubygemsService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerRubygemsTest {
  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void trailingSlashRootGetServesRepositoryHtml(RepositoryType type) throws Exception {
    TestContext context = context(type);
    MockHttpServletRequest request = request("GET", context.repositoryName() + "/");

    ResponseEntity<StreamingResponseBody> response =
        context.controller().get(context.repositoryName(), request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    assertTrue(body(response).contains(
        "This rubygems " + type.name().toLowerCase() + " repository is not directly browseable"));
    assertEquals(0, context.rubygems().getCalls);
  }

  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void trailingSlashRootHeadServesRepositoryHtmlHeaders(RepositoryType type) {
    TestContext context = context(type);
    MockHttpServletRequest request = request("HEAD", context.repositoryName() + "/");

    ResponseEntity<Void> response = context.controller().head(context.repositoryName(), request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    assertTrue(response.getHeaders().getContentLength() > 0);
    assertNull(response.getBody());
    assertEquals(0, context.rubygems().getCalls);
  }

  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void bareRootGetReturnsNexusStyleBadRequestHtml(RepositoryType type) throws Exception {
    TestContext context = context(type);
    MockHttpServletRequest request = request("GET", context.repositoryName());

    ResponseEntity<StreamingResponseBody> response =
        context.controller().get(context.repositoryName(), request);

    assertEquals(400, response.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    assertTrue(body(response).contains("Repository path must have another '/' after initial '/'"));
    assertEquals(0, context.rubygems().getCalls);
  }

  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void bareRootHeadReturnsNexusStyleBadRequestHtmlHeaders(RepositoryType type) {
    TestContext context = context(type);
    MockHttpServletRequest request = request("HEAD", context.repositoryName());

    ResponseEntity<Void> response = context.controller().head(context.repositoryName(), request);

    assertEquals(400, response.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    assertTrue(response.getHeaders().getContentLength() > 0);
    assertNull(response.getBody());
    assertEquals(0, context.rubygems().getCalls);
  }

  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void explicitSpecsGetStillUsesRubygemsPullProtocol(RepositoryType type) throws Exception {
    TestContext context = context(type);
    MockHttpServletRequest request = request("GET", context.repositoryName() + "/specs.4.8.gz");

    ResponseEntity<StreamingResponseBody> response =
        context.controller().get(context.repositoryName(), request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    assertEquals("ruby specs", body(response));
    assertEquals(1, context.rubygems().getCalls);
    assertEquals("specs.4.8.gz", context.rubygems().rawPath);
    assertEquals(false, context.rubygems().headOnly);
  }

  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void explicitSpecsHeadStillUsesRubygemsPullProtocol(RepositoryType type) {
    TestContext context = context(type);
    MockHttpServletRequest request = request("HEAD", context.repositoryName() + "/specs.4.8.gz");

    ResponseEntity<Void> response = context.controller().head(context.repositoryName(), request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    assertNull(response.getBody());
    assertEquals(1, context.rubygems().getCalls);
    assertEquals("specs.4.8.gz", context.rubygems().rawPath);
    assertEquals(true, context.rubygems().headOnly);
  }

  private static TestContext context(RepositoryType type) {
    String name = "ruby-" + type.name().toLowerCase();
    FakeRepositoryDao repositories = new FakeRepositoryDao(repository(name, type));
    CapturingRubygemsService rubygems = new CapturingRubygemsService();
    return new TestContext(name, controller(repositories, rubygems), rubygems);
  }

  private static MockHttpServletRequest request(String method, String repositoryPath) {
    MockHttpServletRequest request = new MockHttpServletRequest(
        method, "/repository/" + repositoryPath);
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(28090);
    return request;
  }

  private static String body(ResponseEntity<StreamingResponseBody> response) throws Exception {
    assertNotNull(response.getBody());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    response.getBody().writeTo(out);
    return out.toString(StandardCharsets.UTF_8);
  }

  private static RepositoryContentController controller(
      FakeRepositoryDao repositories, RubygemsService rubygems) {
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
        null, rubygems, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""));
  }

  private static RepositoryRecord repository(String name, RepositoryType type) {
    return new RepositoryRecord(
        1L,
        name,
        RepositoryFormat.RUBYGEMS,
        type,
        "rubygems-" + type.name().toLowerCase(),
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

  private record TestContext(
      String repositoryName,
      RepositoryContentController controller,
      CapturingRubygemsService rubygems) {}

  private static final class FakeRepositoryDao extends RepositoryDaoAdapter {
    private final RepositoryRecord repository;

    FakeRepositoryDao(RepositoryRecord repository) {
      super(null, null);
      this.repository = repository;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return repository.name().equals(name) ? Optional.of(repository) : Optional.empty();
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return List.of();
    }
  }

  private static final class CapturingRubygemsService extends RubygemsService {
    private int getCalls;
    private String rawPath;
    private boolean headOnly;

    CapturingRubygemsService() {
      super(null, null, null, null, null);
    }

    @Override
    public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
      getCalls++;
      this.rawPath = rawPath;
      this.headOnly = headOnly;
      byte[] bytes = "ruby specs".getBytes(StandardCharsets.UTF_8);
      if (headOnly) {
        return MavenResponse.noBody(
            200, bytes.length, MediaType.APPLICATION_OCTET_STREAM_VALUE, null, null);
      }
      return MavenResponse.ok(
          new ByteArrayInputStream(bytes),
          bytes.length,
          MediaType.APPLICATION_OCTET_STREAM_VALUE,
          null,
          null);
    }
  }
}
