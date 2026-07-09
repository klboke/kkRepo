package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.RepositoryContentController;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
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
    RepositoryContentController controller = new RepositoryContentController(
        new RepositoryRuntimeRegistry(new SingleRepositoryDao(), 0),
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, null,
        new RangeHostedService(), null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""));
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET",
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

  private static final class RangeHostedService extends PypiHostedService {
    private RangeHostedService() {
      super(null, null, null, null, null, null, null, 0);
    }

    @Override
    public PypiResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
      byte[] bytes = "abcdef".getBytes(StandardCharsets.UTF_8);
      return PypiResponse.ok(
          new ByteArrayInputStream(bytes),
          bytes.length,
          "application/octet-stream",
          "sha1",
          Instant.parse("2026-05-28T00:00:00Z"));
    }
  }

  private static final class SingleRepositoryDao extends RepositoryDao {
    private SingleRepositoryDao() {
      super(null, null);
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
          RepositoryType.HOSTED,
          "pypi-hosted",
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
