package com.github.klboke.kkrepo.server;

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
import com.github.klboke.kkrepo.server.conan.ConanService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
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

class RepositoryContentControllerConanTest {

  @Test
  void forwardsGetHeadPutAndDeleteThroughTheConanProtocolService() throws Exception {
    RepositoryRuntime runtime = runtime();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    ConanService conan = mock(ConanService.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "LOCAL", "alice", "realm", null, null);
    when(conan.get(eq(runtime), any(), any(), eq(false), eq(subject))).thenReturn(
        MavenResponse.ok(
            new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)),
            7, "application/octet-stream", "etag", Instant.EPOCH));
    when(conan.get(eq(runtime), any(), any(), eq(true), eq(subject))).thenReturn(
        MavenResponse.noBody(200, 7, "application/octet-stream", "etag", Instant.EPOCH));
    when(conan.put(eq(runtime), any(), any(), eq(3L), eq("application/octet-stream"),
        eq("a".repeat(40)), eq(false), eq(subject), eq("192.0.2.1")))
        .thenReturn(MavenResponse.noBody(200));
    when(conan.delete(eq(runtime), any())).thenReturn(MavenResponse.noBody(200));
    RepositoryContentController controller = controller(runtimes, conan);

    MockHttpServletRequest get = request(
        "GET", "/repository/conan-hosted/v2/conans/search");
    get.setQueryString("q=demo*");
    get.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    ResponseEntity<StreamingResponseBody> getResponse = controller.get(runtime.name(), get);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    getResponse.getBody().writeTo(body);
    assertEquals("payload", body.toString(StandardCharsets.UTF_8));
    verify(conan).get(runtime, "v2/conans/search", "q=demo*", false, subject);

    MockHttpServletRequest head = request(
        "HEAD", "/repository/conan-hosted/v2/conans/demo/1.0/_/_/latest");
    head.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    assertEquals(200, controller.head(runtime.name(), head).getStatusCode().value());
    verify(conan).get(runtime, "v2/conans/demo/1.0/_/_/latest", null, true, subject);

    MockHttpServletRequest put = request(
        "PUT", "/repository/conan-hosted/v2/conans/demo/1.0/_/_/revisions/r/files/file");
    put.setContent(new byte[] {1, 2, 3});
    put.setContentType("application/octet-stream");
    put.addHeader("X-Checksum-Sha1", "a".repeat(40));
    put.setRemoteAddr("192.0.2.1");
    put.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    assertEquals(200, controller.put(runtime.name(), put, put.getContentType())
        .getStatusCode().value());

    MockHttpServletRequest delete = request(
        "DELETE", "/repository/conan-hosted/v2/conans/demo/1.0/_/_");
    assertEquals(200, controller.delete(runtime.name(), delete).getStatusCode().value());
    verify(conan).delete(runtime, "v2/conans/demo/1.0/_/_");
  }

  @Test
  void failsExplicitlyWhenOptionalConanServiceWasNotWired() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("conan-hosted")).thenReturn(Optional.of(runtime()));
    RepositoryContentController controller = controller(runtimes, null);

    assertThrows(IllegalStateException.class, () -> controller.get(
        "conan-hosted", request("GET", "/repository/conan-hosted/v1/ping")));
  }

  private static MockHttpServletRequest request(String method, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setScheme("https");
    request.setServerName("repo.example");
    request.setServerPort(443);
    return request;
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "conan-hosted", RepositoryFormat.CONAN, RepositoryType.HOSTED,
        "conan-hosted", true, 1L, "ALLOW", null, null, true,
        null, null, null, List.of());
  }

  private static RepositoryContentController controller(
      RepositoryRuntimeRegistry runtimes, ConanService conan) {
    RepositoryContentController controller = new RepositoryContentController(
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
    if (conan != null) controller.setConanService(conan);
    return controller;
  }
}
