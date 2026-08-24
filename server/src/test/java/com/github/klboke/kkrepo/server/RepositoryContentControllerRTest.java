package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.r.RService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerRTest {

  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void repositoryRootUsesNexusStyleHtmlAndBareRootIsBadRequest(RepositoryType type)
      throws Exception {
    RepositoryRuntime runtime = runtime(type);
    RService r = mock(RService.class);
    RepositoryProtocolController controller = controller(runtimes(runtime), r);

    ResponseEntity<StreamingResponseBody> get = controller.get(
        "cran", request("GET", "/repository/cran/"));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    get.getBody().writeTo(output);
    String html = output.toString(StandardCharsets.UTF_8);
    assertEquals(200, get.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, get.getHeaders().getContentType());
    assertTrue(html.contains("<span class=\"description\">cran</span>"));
    assertTrue(html.contains("This r " + type.name().toLowerCase()
        + " repository is not directly browseable at this URL."));

    ResponseEntity<Void> head = controller.head(
        "cran", request("HEAD", "/repository/cran/"));
    assertEquals(200, head.getStatusCode().value());
    assertTrue(head.getHeaders().getContentLength() > 0);
    assertNull(head.getBody());

    ResponseEntity<StreamingResponseBody> bare = controller.get(
        "cran", request("GET", "/repository/cran"));
    ByteArrayOutputStream badRequest = new ByteArrayOutputStream();
    bare.getBody().writeTo(badRequest);
    assertEquals(400, bare.getStatusCode().value());
    assertTrue(badRequest.toString(StandardCharsets.UTF_8).contains(
        "Repository path must have another '/' after initial '/'"));
    verifyNoInteractions(r);
  }

  @Test
  void routesRGetHeadPutAndDelete() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    RService r = mock(RService.class);
    byte[] archive = "r-package".getBytes(StandardCharsets.UTF_8);
    String path = "src/contrib/demo_1.0.0.tar.gz";
    when(r.get(runtime, path, false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(archive), archive.length, "application/x-gzip",
        "package", Instant.EPOCH));
    when(r.get(runtime, "src/contrib/PACKAGES.gz", true)).thenReturn(
        MavenResponse.noBody(200, 42, "application/x-gzip", "index", Instant.EPOCH));
    when(r.put(eq(runtime), eq(path), any(), eq("application/x-gzip"),
        eq("anonymous"), eq("192.0.2.10"))).thenReturn(
            MavenResponse.noBody(200).withHeader(HttpHeaders.LOCATION, path));
    when(r.delete(runtime, path, "repository-content-delete", true))
        .thenReturn(MavenResponse.noBody(204));
    RepositoryProtocolController controller = controller(runtimes(runtime), r);

    ResponseEntity<StreamingResponseBody> get = controller.get(
        "cran", request("GET", "/repository/cran/" + path));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    get.getBody().writeTo(output);
    assertArrayEquals(archive, output.toByteArray());

    ResponseEntity<Void> head = controller.head(
        "cran", request("HEAD", "/repository/cran/src/contrib/PACKAGES.gz"));
    assertEquals(42, head.getHeaders().getContentLength());

    MockHttpServletRequest put = request("PUT", "/repository/cran/" + path);
    put.setContent(archive);
    put.setRemoteAddr("192.0.2.10");
    ResponseEntity<?> putResponse = controller.put("cran", put, "application/x-gzip");
    assertEquals(200, putResponse.getStatusCode().value());
    assertEquals(path, putResponse.getHeaders().getFirst(HttpHeaders.LOCATION));

    ResponseEntity<?> delete = controller.delete(
        "cran", request("DELETE", "/repository/cran/" + path));
    assertEquals(204, delete.getStatusCode().value());
  }

  @Test
  void reportsUnavailableRServiceAtContentBoundary() {
    RepositoryProtocolController controller = controller(
        runtimes(runtime(RepositoryType.HOSTED)), null);
    assertThrows(IllegalStateException.class, () -> controller.get(
        "cran", request("GET", "/repository/cran/src/contrib/PACKAGES.gz")));
    assertThrows(IllegalStateException.class, () -> controller.head(
        "cran", request("HEAD", "/repository/cran/src/contrib/PACKAGES.gz")));
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }

  private static RepositoryRuntimeRegistry runtimes(RepositoryRuntime runtime) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return runtimes;
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "cran", RepositoryFormat.R, type, "r-" + type.name().toLowerCase(), true, 1L,
        type == RepositoryType.HOSTED ? "ALLOW" : null, null, null, true,
        type == RepositoryType.PROXY ? "https://cloud.r-project.org/" : null,
        60, 30, true, null, List.of());
  }

  private static RepositoryProtocolController controller(
      RepositoryRuntimeRegistry runtimes, RService r) {
    RepositoryProtocolControllerTestSupport controller =
        RepositoryProtocolControllerTestSupport.controller(
        runtimes,
        null, null, null,
        null, null,
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
    controller.setRService(r);
    return controller;
  }
}
