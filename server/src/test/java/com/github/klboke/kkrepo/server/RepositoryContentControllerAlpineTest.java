package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.alpine.AlpineService;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerAlpineTest {

  @Test
  void routesAlpineGetHeadPutAndDelete() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    AlpineService alpine = mock(AlpineService.class);
    byte[] archive = "apk".getBytes(StandardCharsets.UTF_8);
    when(alpine.get(runtime, "v3.23/main/x86_64/demo-1-r0.apk", false)).thenReturn(
        MavenResponse.ok(new ByteArrayInputStream(archive), archive.length,
            "application/vnd.alpine.apk", "package", Instant.EPOCH));
    when(alpine.get(runtime, "v3.23/main/x86_64/APKINDEX.tar.gz", true)).thenReturn(
        MavenResponse.noBody(200, 42, "application/vnd.alpine.apk-index", "index", Instant.EPOCH));
    when(alpine.put(eq(runtime), eq("v3.23/main/x86_64/demo-1-r0.apk"), any(),
        eq("application/vnd.alpine.apk"), eq("anonymous"), eq("192.0.2.10")))
        .thenReturn(MavenResponse.noBody(200).withHeader(
            HttpHeaders.LOCATION, "v3.23/main/x86_64/demo-1-r0.apk"));
    when(alpine.delete(runtime, "v3.23/main/x86_64/demo-1-r0.apk",
        "repository-content-delete", true)).thenReturn(MavenResponse.noBody(204));
    RepositoryContentController controller = controller(runtimes(runtime), alpine);

    MockHttpServletRequest get = request(
        "GET", "/repository/alpine/v3.23/main/x86_64/demo-1-r0.apk");
    ResponseEntity<StreamingResponseBody> response = controller.get("alpine", get);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertArrayEquals(archive, output.toByteArray());

    MockHttpServletRequest head = request(
        "HEAD", "/repository/alpine/v3.23/main/x86_64/APKINDEX.tar.gz");
    assertEquals(200, controller.head("alpine", head).getStatusCode().value());
    assertEquals(42, controller.head("alpine", head).getHeaders().getContentLength());

    MockHttpServletRequest put = request(
        "PUT", "/repository/alpine/v3.23/main/x86_64/demo-1-r0.apk");
    put.setContent(archive);
    put.setRemoteAddr("192.0.2.10");
    ResponseEntity<?> putResponse = controller.put(
        "alpine", put, "application/vnd.alpine.apk");
    assertEquals(200, putResponse.getStatusCode().value());
    assertEquals("v3.23/main/x86_64/demo-1-r0.apk",
        putResponse.getHeaders().getFirst(HttpHeaders.LOCATION));

    MockHttpServletRequest delete = request(
        "DELETE", "/repository/alpine/v3.23/main/x86_64/demo-1-r0.apk");
    assertEquals(204, controller.delete("alpine", delete).getStatusCode().value());
  }

  @Test
  void acceptsNamedAndFallbackMultipartPartsAndFields() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    AlpineService alpine = mock(AlpineService.class);
    when(alpine.publish(eq(runtime), any(), any(), any(), any(), any(),
        eq("anonymous"), eq("127.0.0.1"))).thenReturn(published());
    RepositoryContentController controller = controller(runtimes(runtime), alpine);

    MockHttpServletRequest named = multipartRequest();
    named.addPart(new MockPart("empty", new byte[0]));
    named.addPart(new MockPart(
        "alpine.distribution", "v3.23".getBytes(StandardCharsets.UTF_8)));
    named.addPart(new MockPart("channel", "main".getBytes(StandardCharsets.UTF_8)));
    named.addPart(new MockPart(
        "alpine.repositoryArchitecture", "x86_64".getBytes(StandardCharsets.UTF_8)));
    named.addPart(new MockPart(
        "alpine.asset", "demo-1-r0.apk", "apk".getBytes(StandardCharsets.UTF_8)));
    ResponseEntity<?> response = controller.post("alpine", named);
    assertEquals(201, response.getStatusCode().value());
    assertEquals("v3.23/main/x86_64/demo-1-r0.apk",
        response.getHeaders().getFirst(HttpHeaders.LOCATION));
    assertEquals("demo", ((Map<?, ?>) response.getBody()).get("name"));
    assertEquals("identity", ((Map<?, ?>) response.getBody()).get("identity"));
    verify(alpine).publish(eq(runtime), eq("v3.23"), eq("main"), eq("x86_64"),
        eq("demo-1-r0.apk"), any(), eq("anonymous"), eq("127.0.0.1"));

    MockHttpServletRequest fallback = multipartRequest();
    fallback.addPart(new MockPart(
        "distribution", "edge".getBytes(StandardCharsets.UTF_8)));
    fallback.addPart(new MockPart("channel", "testing".getBytes(StandardCharsets.UTF_8)));
    fallback.addPart(new MockPart(
        "architecture", "aarch64".getBytes(StandardCharsets.UTF_8)));
    fallback.addPart(new MockPart(
        "package", "fallback.apk", "apk".getBytes(StandardCharsets.UTF_8)));
    assertEquals(201, controller.post("alpine", fallback).getStatusCode().value());
    verify(alpine).publish(eq(runtime), eq("edge"), eq("testing"), eq("aarch64"),
        eq("fallback.apk"), any(), eq("anonymous"), eq("127.0.0.1"));
  }

  @Test
  void rejectsInvalidPostRequestsAndUnavailableService() throws Exception {
    AlpineService alpine = mock(AlpineService.class);
    RepositoryContentController hosted = controller(
        runtimes(runtime(RepositoryType.HOSTED)), alpine);
    MockHttpServletRequest path = request("POST", "/repository/alpine/not-root");
    path.setContentType("multipart/form-data; boundary=x");
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> hosted.post("alpine", path));

    MockHttpServletRequest plain = request("POST", "/repository/alpine/");
    plain.setContentType("application/octet-stream");
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> hosted.post("alpine", plain));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> hosted.post("alpine", multipartRequest()));

    RepositoryContentController proxy = controller(
        runtimes(runtime(RepositoryType.PROXY)), alpine);
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> proxy.post("alpine", multipartRequest()));

    RepositoryContentController unavailable = controller(
        runtimes(runtime(RepositoryType.HOSTED)), null);
    assertThrows(IllegalStateException.class, () -> unavailable.head(
        "alpine", request("HEAD", "/repository/alpine/v3.23/main/x86_64/APKINDEX.tar.gz")));
    verify(alpine, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void mapsMultipartPartAndBodyFailuresToBadRequest() throws Exception {
    AlpineService alpine = mock(AlpineService.class);
    RepositoryContentController controller = controller(
        runtimes(runtime(RepositoryType.HOSTED)), alpine);
    MockHttpServletRequest invalidParts = new MockHttpServletRequest(
        "POST", "/repository/alpine/") {
      @Override
      public Collection<Part> getParts() throws ServletException {
        throw new ServletException("broken multipart");
      }
    };
    invalidParts.setContentType("multipart/form-data; boundary=x");
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> controller.post("alpine", invalidParts));

    Part broken = mock(Part.class);
    when(broken.getName()).thenReturn("alpine.asset");
    when(broken.getSubmittedFileName()).thenReturn("demo.apk");
    when(broken.getSize()).thenReturn(3L);
    when(broken.getInputStream()).thenThrow(new IOException("broken body"));
    MockHttpServletRequest brokenBody = multipartRequest();
    brokenBody.addPart(broken);
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> controller.post("alpine", brokenBody));
  }

  private static AlpineService.PublishedPackage published() {
    return new AlpineService.PublishedPackage(
        "v3.23/main/x86_64/demo-1-r0.apk", "demo", "1-r0", "x86_64",
        "identity", "a".repeat(64), 3L);
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }

  private static MockHttpServletRequest multipartRequest() {
    MockHttpServletRequest request = request("POST", "/repository/alpine/");
    request.setContentType("multipart/form-data; boundary=x");
    request.setRemoteAddr("127.0.0.1");
    return request;
  }

  private static RepositoryRuntimeRegistry runtimes(RepositoryRuntime runtime) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return runtimes;
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "alpine", RepositoryFormat.ALPINE, type,
        "alpine-" + type.name().toLowerCase(), true, 1L,
        type == RepositoryType.HOSTED ? "ALLOW" : null,
        null, null, true,
        type == RepositoryType.PROXY ? "https://dl-cdn.alpinelinux.org/alpine/" : null,
        60, 30, true, null, List.of());
  }

  private static RepositoryContentController controller(
      RepositoryRuntimeRegistry runtimes, AlpineService alpine) {
    RepositoryContentController controller = new RepositoryContentController(
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
    controller.setAlpineService(alpine);
    return controller;
  }
}
