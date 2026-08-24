package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.conda.CondaService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerCondaTest {

  @Test
  void routesCondaGetHeadPutAndDeleteWithoutFallingBackToRaw() throws Exception {
    RepositoryRuntime runtime = runtime();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    CondaService conda = mock(CondaService.class);
    when(runtimes.resolve("conda")).thenReturn(Optional.of(runtime));
    byte[] metadata = "{\"packages\":{}}".getBytes(StandardCharsets.UTF_8);
    when(conda.get(runtime, "main/linux-64/repodata.json", false)).thenReturn(
        MavenResponse.ok(
            new ByteArrayInputStream(metadata), metadata.length, "application/json",
            "metadata-etag", Instant.EPOCH));
    when(conda.get(runtime, "main/linux-64/repodata.json", true)).thenReturn(
        MavenResponse.noBody(
            200, metadata.length, "application/json", "metadata-etag", Instant.EPOCH));
    when(conda.put(
        eq(runtime), eq("main/linux-64/demo-1.0-0.conda"), any(InputStream.class),
        eq("application/vnd.conda.package.v2"), any(), eq("127.0.0.1")))
        .thenReturn(MavenResponse.created());
    when(conda.delete(runtime, "main/linux-64/demo-1.0-0.conda"))
        .thenReturn(MavenResponse.noBody(204));
    RepositoryProtocolController controller = controller(runtimes, conda);

    MockHttpServletRequest get = new MockHttpServletRequest(
        "GET", "/repository/conda/main/linux-64/repodata.json");
    ResponseEntity<StreamingResponseBody> getResponse = controller.get("conda", get);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    getResponse.getBody().writeTo(output);
    assertEquals(200, getResponse.getStatusCode().value());
    assertEquals("{\"packages\":{}}", output.toString(StandardCharsets.UTF_8));
    assertEquals("\"metadata-etag\"", getResponse.getHeaders().getETag());

    MockHttpServletRequest head = new MockHttpServletRequest(
        "HEAD", "/repository/conda/main/linux-64/repodata.json");
    head.addHeader(HttpHeaders.IF_NONE_MATCH, "\"metadata-etag\"");
    assertEquals(304, controller.head("conda", head).getStatusCode().value());

    MockHttpServletRequest put = new MockHttpServletRequest(
        "PUT", "/repository/conda/main/linux-64/demo-1.0-0.conda");
    put.setContent(new byte[] {1, 2, 3});
    put.setRemoteAddr("127.0.0.1");
    assertEquals(201, controller.put(
        "conda", put, "application/vnd.conda.package.v2").getStatusCode().value());

    MockHttpServletRequest delete = new MockHttpServletRequest(
        "DELETE", "/repository/conda/main/linux-64/demo-1.0-0.conda");
    assertEquals(204, controller.delete("conda", delete).getStatusCode().value());

    verify(conda).get(runtime, "main/linux-64/repodata.json", false);
    verify(conda).get(runtime, "main/linux-64/repodata.json", true);
    verify(conda).delete(runtime, "main/linux-64/demo-1.0-0.conda");
  }

  @Test
  void closesEagerCondaBodyWhenConditionalGetReturnsNotModified() throws Exception {
    RepositoryRuntime runtime = runtime();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    CondaService conda = mock(CondaService.class);
    InputStream body = mock(InputStream.class);
    when(runtimes.resolve("conda")).thenReturn(Optional.of(runtime));
    when(conda.get(runtime, "main/linux-64/repodata.json", false)).thenReturn(
        MavenResponse.ok(body, 42, "application/json", "metadata-etag", Instant.EPOCH));
    RepositoryProtocolController controller = controller(runtimes, conda);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/conda/main/linux-64/repodata.json");
    request.addHeader(HttpHeaders.IF_NONE_MATCH, "\"metadata-etag\"");

    ResponseEntity<StreamingResponseBody> response = controller.get("conda", request);

    assertEquals(304, response.getStatusCode().value());
    verify(body).close();
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "conda", RepositoryFormat.CONDA, RepositoryType.HOSTED, "conda-hosted",
        true, 1L, "ALLOW_ONCE", null, null, true, null, null, null, null, null, List.of());
  }

  private static RepositoryProtocolController controller(
      RepositoryRuntimeRegistry runtimes, CondaService conda) {
    RepositoryProtocolControllerTestSupport controller =
        RepositoryProtocolControllerTestSupport.controller(
        runtimes,
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
        null,
        null,
        null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""),
        null);
    controller.setCondaService(conda);
    return controller;
  }
}
