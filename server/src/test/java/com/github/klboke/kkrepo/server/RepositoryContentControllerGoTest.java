package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.goartifact.GoGroupService;
import com.github.klboke.kkrepo.server.goartifact.GoHostedService;
import com.github.klboke.kkrepo.server.goartifact.GoProxyService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerGoTest {
  private static final String MODULE_PATH = "example.com/acme/demo/@v/v1.2.3.mod";
  private static final String LIST_PATH = "example.com/acme/demo/@v/list";

  @Test
  void routesHostedGetHeadAndRootVersionUpload() throws Exception {
    RepositoryRuntime runtime = runtime();
    GoHostedService hosted = mock(GoHostedService.class);
    byte[] module = "module example.com/acme/demo\n".getBytes(StandardCharsets.UTF_8);
    when(hosted.get(runtime, MODULE_PATH, false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(module), module.length, "text/plain", null, Instant.EPOCH));
    when(hosted.get(runtime, MODULE_PATH, true)).thenReturn(
        MavenResponse.noBody(200, module.length, "text/plain", null, Instant.EPOCH));
    when(hosted.publish(eq(runtime), eq("v1.2.3.zip"), any(), eq("anonymous"), eq("192.0.2.10")))
        .thenReturn(new GoHostedService.Published(
            "example.com/acme/demo", "v1.2.3",
            "example.com/acme/demo/@v/v1.2.3.zip", Instant.EPOCH));
    RepositoryProtocolController controller = controller(runtimes(runtime), hosted);

    ResponseEntity<StreamingResponseBody> get = controller.get(
        "go-hosted", request("GET", "/repository/go-hosted/" + MODULE_PATH));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    get.getBody().writeTo(output);
    assertArrayEquals(module, output.toByteArray());

    ResponseEntity<Void> head = controller.head(
        "go-hosted", request("HEAD", "/repository/go-hosted/" + MODULE_PATH));
    assertEquals(module.length, head.getHeaders().getContentLength());

    MockHttpServletRequest put = request("PUT", "/repository/go-hosted/v1.2.3.zip");
    put.setContent("archive".getBytes(StandardCharsets.UTF_8));
    put.setRemoteAddr("192.0.2.10");
    assertEquals(201, controller.put("go-hosted", put, "application/zip")
        .getStatusCode().value());
    verify(hosted).publish(eq(runtime), eq("v1.2.3.zip"), any(),
        eq("anonymous"), eq("192.0.2.10"));
  }

  @Test
  void reportsUnavailableHostedServiceAtContentBoundary() {
    RepositoryProtocolController controller = controller(runtimes(runtime()), null);

    assertThrows(IllegalStateException.class, () -> controller.get(
        "go-hosted", request("GET", "/repository/go-hosted/" + MODULE_PATH)));
    MockHttpServletRequest put = request("PUT", "/repository/go-hosted/v1.2.3.zip");
    put.setContent(new byte[] {1});
    assertThrows(IllegalStateException.class,
        () -> controller.put("go-hosted", put, "application/zip"));
  }

  @Test
  void usesSmallTransferBufferForGeneratedVersionList() throws Exception {
    RepositoryRuntime runtime = runtime();
    GoHostedService hosted = mock(GoHostedService.class);
    byte[] versions = "v1.0.0\nv1.2.3\n".getBytes(StandardCharsets.UTF_8);
    BufferSizeRecordingInputStream body = new BufferSizeRecordingInputStream(versions);
    when(hosted.get(runtime, LIST_PATH, false)).thenReturn(MavenResponse.ok(
        body, versions.length, "text/plain", null, Instant.EPOCH));
    RepositoryProtocolController controller = controller(runtimes(runtime), hosted);

    ResponseEntity<StreamingResponseBody> response = controller.get(
        "go-hosted", request("GET", "/repository/go-hosted/" + LIST_PATH));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);

    assertArrayEquals(versions, output.toByteArray());
    assertEquals(versions.length, response.getHeaders().getContentLength());
    assertEquals(8 * 1024, body.largestRequestedRead);
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }

  private static RepositoryRuntimeRegistry runtimes(RepositoryRuntime runtime) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return runtimes;
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "go-hosted", RepositoryFormat.GO, RepositoryType.HOSTED, "go-hosted", true, 7L,
        "ALLOW_ONCE", null, null, true, null,
        60, 30, true, null, List.of());
  }

  private static RepositoryProtocolController controller(
      RepositoryRuntimeRegistry runtimes, GoHostedService hosted) {
    GoProxyService proxy = mock(GoProxyService.class);
    GoGroupService group = mock(GoGroupService.class);
    RepositoryProtocolControllerTestSupport controller =
        RepositoryProtocolControllerTestSupport.controller(
            runtimes,
            null, null, null,
            proxy, group,
            null, null,
            null,
            null, null, null, null, null,
            null, null, null,
            null, null, null,
            null, null, null,
            null, null, null,
            null, null, null,
            null, null, null,
            new ObjectMapper(),
            new ForwardedHeaderPolicy(""),
            null);
    if (hosted != null) controller.setGoHostedService(hosted);
    return controller;
  }

  private static final class BufferSizeRecordingInputStream extends ByteArrayInputStream {
    private int largestRequestedRead;

    private BufferSizeRecordingInputStream(byte[] payload) {
      super(payload);
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) {
      largestRequestedRead = Math.max(largestRequestedRead, length);
      return super.read(buffer, offset, length);
    }
  }
}
