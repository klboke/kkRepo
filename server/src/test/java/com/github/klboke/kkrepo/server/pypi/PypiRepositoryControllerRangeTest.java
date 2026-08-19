package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.RepositoryContentController;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class PypiRepositoryControllerRangeTest {
  @Test
  void packageDownloadHonorsSingleRangeRequest() throws Exception {
    RangeHostedService hosted = new RangeHostedService();
    RepositoryContentController controller = controller(hosted);
    MockHttpServletRequest request = request(
        "/repository/pypi/packages/demo/1.0.0/demo-1.0.0.whl");
    request.addHeader(HttpHeaders.RANGE, "bytes=2-4");

    ResponseEntity<StreamingResponseBody> response = controller.get("pypi", request);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    response.getBody().writeTo(out);

    assertEquals(206, response.getStatusCode().value());
    assertEquals("bytes 2-4/6", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    assertEquals(3, response.getHeaders().getContentLength());
    assertEquals("cde", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void packageDownloadDecodesPercentEncodedPlusWithoutChangingLiteralPlus() {
    RangeHostedService hosted = new RangeHostedService();
    RepositoryContentController controller = controller(hosted);

    controller.get("pypi", request(
        "/repository/pypi/packages/demo/0.0.0%2Bbuild/demo-0.0.0%2Bbuild.whl"));
    assertEquals(
        "packages/demo/0.0.0+build/demo-0.0.0+build.whl",
        hosted.requestedPath);

    controller.get("pypi", request(
        "/repository/pypi/packages/demo/0.0.0+build/demo-0.0.0+build.whl"));
    assertEquals(
        "packages/demo/0.0.0+build/demo-0.0.0+build.whl",
        hosted.requestedPath);
  }

  @Test
  void packageHeadDecodesPercentEncodedPlusOnce() {
    RangeHostedService hosted = new RangeHostedService();
    RepositoryContentController controller = controller(hosted);

    controller.head("pypi", request(
        "/repository/pypi/packages/demo/0.0.0%2Bbuild/demo-0.0.0%2Bbuild.whl"));

    assertEquals(
        "packages/demo/0.0.0+build/demo-0.0.0+build.whl",
        hosted.requestedPath);
  }

  @Test
  void proxyReceivesFilterCanonicalPathWithoutDecodingItAgain() {
    RangeProxyService proxy = new RangeProxyService();
    RepositoryContentController controller = controller(proxy);
    MockHttpServletRequest request = request(
        "/repository/pypi/packages/demo/0.0.0%252Bbuild/demo-0.0.0%252Bbuild.whl");
    String canonicalPath =
        "packages/demo/0.0.0%2Bbuild/demo-0.0.0%2Bbuild.whl";
    request.setAttribute(
        RepositorySecurityFilter.NORMALIZED_REPOSITORY_PATH_ATTRIBUTE,
        canonicalPath);

    controller.get("pypi", request);

    assertEquals(canonicalPath, proxy.requestedPath);
  }

  @Test
  void packageDownloadRejectsEncodedPathSeparators() {
    RepositoryContentController controller = controller(new RangeHostedService());

    assertThrows(PypiExceptions.BadRequestException.class, () -> controller.get(
        "pypi",
        request("/repository/pypi/packages/demo/1.0.0/demo%2Fevil.whl")));
  }

  private static RepositoryContentController controller(PypiHostedService hosted) {
    return controller(RepositoryType.HOSTED, hosted, null);
  }

  private static RepositoryContentController controller(PypiProxyService proxy) {
    return controller(RepositoryType.PROXY, null, proxy);
  }

  private static RepositoryContentController controller(
      RepositoryType type,
      PypiHostedService hosted,
      PypiProxyService proxy) {
    return new RepositoryContentController(
        new RepositoryRuntimeRegistry(new SingleRepositoryDao(type), 0),
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, null,
        hosted, proxy, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""));
  }

  private static MockHttpServletRequest request(String uri) {
    return new MockHttpServletRequest("GET", uri);
  }

  private static final class RangeHostedService extends PypiHostedService {
    private String requestedPath;

    private RangeHostedService() {
      super(null, null, null, null, null, null, null, 0);
    }

    @Override
    public PypiResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
      requestedPath = path;
      byte[] bytes = "abcdef".getBytes(StandardCharsets.UTF_8);
      return PypiResponse.ok(
          new ByteArrayInputStream(bytes),
          bytes.length,
          "application/octet-stream",
          "sha1",
          Instant.parse("2026-05-28T00:00:00Z"));
    }
  }

  private static final class RangeProxyService extends PypiProxyService {
    private String requestedPath;

    private RangeProxyService() {
      super(null, null, null, null, null, null, null, null, null);
    }

    @Override
    public PypiResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
      requestedPath = path;
      return PypiResponse.noBody(200);
    }
  }

  private static final class SingleRepositoryDao extends RepositoryDaoAdapter {
    private final RepositoryType type;

    private SingleRepositoryDao(RepositoryType type) {
      super(null, null);
      this.type = type;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      if (!"pypi".equals(name)) {
        return Optional.empty();
      }
      return Optional.of(new RepositoryRecord(
          1L,
          "pypi",
          RepositoryFormat.PYPI,
          type,
          "pypi-" + type.name().toLowerCase(),
          true,
          1L,
          null,
          null,
          null,
          null,
          "ALLOW",
          true,
          Map.of()));
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return List.of();
    }
  }
}
