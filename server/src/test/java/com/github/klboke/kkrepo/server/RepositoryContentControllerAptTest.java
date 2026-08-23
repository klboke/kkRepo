package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.pypi.PypiExceptions;
import com.github.klboke.kkrepo.server.pypi.PypiHostedService;
import com.github.klboke.kkrepo.server.pypi.PypiResponse;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerAptTest {

  @Test
  void routesAptGetHeadPutAndDelete() throws Exception {
    RepositoryRuntime runtime = aptRuntime(RepositoryType.HOSTED);
    RepositoryRuntimeRegistry runtimes = runtimes(runtime);
    AptService apt = mock(AptService.class);
    byte[] archive = "deb".getBytes(StandardCharsets.UTF_8);
    when(apt.get(runtime, "pool/d/demo/demo_1_amd64.deb", false)).thenReturn(
        MavenResponse.ok(new ByteArrayInputStream(archive), archive.length,
            "application/vnd.debian.binary-package", "package", Instant.EPOCH));
    when(apt.get(runtime, "dists/stable/Release", true)).thenReturn(
        MavenResponse.noBody(200, 42, "text/plain", "release", Instant.EPOCH));
    when(apt.put(eq(runtime), eq("pool/d/demo/demo_1_amd64.deb"), any(),
        eq("application/vnd.debian.binary-package"), eq("anonymous"), eq("192.0.2.10")))
        .thenReturn(MavenResponse.created().withHeader(
            HttpHeaders.LOCATION, "pool/d/demo/demo_1_amd64.deb"));
    when(apt.delete(runtime, "pool/d/demo/demo_1_amd64.deb",
        "repository-content-delete", true)).thenReturn(MavenResponse.noBody(204));
    RepositoryProtocolController controller = controller(runtimes, apt, null);

    MockHttpServletRequest get = request(
        "GET", "/repository/apt/pool/d/demo/demo_1_amd64.deb");
    ResponseEntity<StreamingResponseBody> response = controller.get("apt", get);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertArrayEquals(archive, output.toByteArray());

    MockHttpServletRequest head = request("HEAD", "/repository/apt/dists/stable/Release");
    assertEquals(200, controller.head("apt", head).getStatusCode().value());
    assertEquals(42, controller.head("apt", head).getHeaders().getContentLength());

    MockHttpServletRequest put = request(
        "PUT", "/repository/apt/pool/d/demo/demo_1_amd64.deb");
    put.setContent(archive);
    put.setRemoteAddr("192.0.2.10");
    ResponseEntity<?> putResponse = controller.put(
        "apt", put, "application/vnd.debian.binary-package");
    assertEquals(201, putResponse.getStatusCode().value());
    assertEquals("pool/d/demo/demo_1_amd64.deb",
        putResponse.getHeaders().getFirst(HttpHeaders.LOCATION));

    MockHttpServletRequest delete = request(
        "DELETE", "/repository/apt/pool/d/demo/demo_1_amd64.deb");
    assertEquals(204, controller.delete("apt", delete).getStatusCode().value());
  }

  @Test
  void acceptsNexusStyleRawMultipartBodyWithoutBoundary() {
    RepositoryRuntime runtime = aptRuntime(RepositoryType.HOSTED);
    AptService apt = mock(AptService.class);
    when(apt.publish(eq(runtime), eq(null), any(), eq(null), eq(null),
        eq("anonymous"), eq("192.0.2.20"))).thenReturn(published());
    RepositoryProtocolController controller = controller(runtimes(runtime), apt, null);
    MockHttpServletRequest request = request("POST", "/repository/apt/");
    request.setRemoteAddr("192.0.2.20");
    request.setContentType("multipart/form-data");
    request.setContent("deb".getBytes(StandardCharsets.UTF_8));

    ResponseEntity<?> response = controller.post("apt", request);

    assertEquals(201, response.getStatusCode().value());
    assertEquals("pool/d/demo/demo_1_amd64.deb",
        response.getHeaders().getFirst(HttpHeaders.LOCATION));
    assertEquals("demo", ((Map<?, ?>) response.getBody()).get("name"));
  }

  @Test
  void acceptsNamedAndFallbackAptMultipartPartsAndFields() throws Exception {
    RepositoryRuntime runtime = aptRuntime(RepositoryType.HOSTED);
    AptService apt = mock(AptService.class);
    when(apt.publish(eq(runtime), anyString(), any(), any(), any(),
        eq("anonymous"), eq("127.0.0.1"))).thenReturn(published());
    RepositoryProtocolController controller = controller(runtimes(runtime), apt, null);

    MockHttpServletRequest named = multipartRequest();
    named.addPart(new MockPart("empty", new byte[0]));
    named.addPart(new MockPart("apt.distribution", "bookworm".getBytes(StandardCharsets.UTF_8)));
    named.addPart(new MockPart("component", "main".getBytes(StandardCharsets.UTF_8)));
    named.addPart(new MockPart(
        "apt.asset", "transport.deb", "deb".getBytes(StandardCharsets.UTF_8)));
    assertEquals(201, controller.post("apt", named).getStatusCode().value());
    verify(apt).publish(eq(runtime), eq("transport.deb"), any(), eq("bookworm"), eq("main"),
        eq("anonymous"), eq("127.0.0.1"));

    MockHttpServletRequest fallback = multipartRequest();
    fallback.addPart(new MockPart("distribution", "stable".getBytes(StandardCharsets.UTF_8)));
    fallback.addPart(new MockPart(
        "package", "fallback.deb", "deb".getBytes(StandardCharsets.UTF_8)));
    assertEquals(201, controller.post("apt", fallback).getStatusCode().value());
    verify(apt).publish(eq(runtime), eq("fallback.deb"), any(), eq("stable"), eq(null),
        eq("anonymous"), eq("127.0.0.1"));
  }

  @Test
  void rejectsInvalidAptPostRequestsAndUnavailableService() throws Exception {
    AptService apt = mock(AptService.class);
    RepositoryProtocolController hosted = controller(
        runtimes(aptRuntime(RepositoryType.HOSTED)), apt, null);
    MockHttpServletRequest path = request("POST", "/repository/apt/not-root");
    path.setContentType("multipart/form-data; boundary=x");
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> hosted.post("apt", path));

    MockHttpServletRequest plain = request("POST", "/repository/apt/");
    plain.setContentType("application/octet-stream");
    assertThrows(MavenExceptions.BadRequestException.class, () -> hosted.post("apt", plain));

    MockHttpServletRequest missing = multipartRequest();
    assertThrows(MavenExceptions.BadRequestException.class, () -> hosted.post("apt", missing));

    RepositoryProtocolController proxy = controller(
        runtimes(aptRuntime(RepositoryType.PROXY)), apt, null);
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> proxy.post("apt", multipartRequest()));

    RepositoryProtocolController unavailable = controller(
        runtimes(aptRuntime(RepositoryType.HOSTED)), null, null);
    assertThrows(IllegalStateException.class, () -> unavailable.head(
        "apt", request("HEAD", "/repository/apt/dists/stable/Release")));
    verify(apt, never()).publish(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void mapsMultipartPartAndBodyFailuresToBadRequest() throws Exception {
    RepositoryRuntime runtime = aptRuntime(RepositoryType.HOSTED);
    AptService apt = mock(AptService.class);
    RepositoryProtocolController controller = controller(runtimes(runtime), apt, null);
    MockHttpServletRequest invalidParts = new MockHttpServletRequest(
        "POST", "/repository/apt/") {
      @Override
      public Collection<Part> getParts() throws jakarta.servlet.ServletException {
        throw new jakarta.servlet.ServletException("broken multipart");
      }
    };
    invalidParts.setContentType("multipart/form-data; boundary=x");
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> controller.post("apt", invalidParts));

    Part broken = mock(Part.class);
    when(broken.getName()).thenReturn("apt.asset");
    when(broken.getSubmittedFileName()).thenReturn("demo.deb");
    when(broken.getSize()).thenReturn(3L);
    when(broken.getInputStream()).thenThrow(new IOException("broken body"));
    MockHttpServletRequest brokenBody = multipartRequest();
    brokenBody.addPart(broken);
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> controller.post("apt", brokenBody));
  }

  @Test
  void routesPypiMultipartThroughTheSharedRootPostHandler(@TempDir Path temp) throws Exception {
    RepositoryRuntime runtime = pypiRuntime(RepositoryType.HOSTED);
    PypiHostedService pypi = mock(PypiHostedService.class);
    when(pypi.upload(eq(runtime), any(), any(), any(), eq("anonymous"), eq("192.0.2.30")))
        .thenAnswer(invocation -> {
          Map<String, String> fields = invocation.getArgument(1);
          MultipartFile content = invocation.getArgument(2);
          MultipartFile signature = invocation.getArgument(3);
          assertEquals("file_upload", fields.get(":action"));
          assertEquals("content", content.getName());
          assertEquals("demo.whl", content.getOriginalFilename());
          assertEquals("application/zip", content.getContentType());
          assertFalse(content.isEmpty());
          assertEquals(3, content.getSize());
          assertArrayEquals("zip".getBytes(StandardCharsets.UTF_8), content.getBytes());
          assertEquals("zip", new String(content.getInputStream().readAllBytes(),
              StandardCharsets.UTF_8));
          File destination = temp.resolve("copy.whl").toFile();
          content.transferTo(destination);
          assertEquals("zip", Files.readString(destination.toPath()));
          assertTrue(signature.isEmpty());
          return PypiResponse.noBody(200);
        });
    RepositoryProtocolController controller = controller(runtimes(runtime), null, pypi);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/repository/pypi/");
    request.setRemoteAddr("192.0.2.30");
    request.setContentType("multipart/form-data; boundary=x");
    request.addParameter(":action", "file_upload");
    request.addParameter("name", new String[] {"demo", "duplicate"});
    MockPart content = new MockPart(
        "content", "demo.whl", "zip".getBytes(StandardCharsets.UTF_8));
    content.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
    content.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/zip");
    request.addPart(content);
    request.addPart(new MockPart("gpg_signature", "demo.asc", new byte[0]));

    assertEquals(200, controller.post("pypi", request).getStatusCode().value());
  }

  @Test
  void validatesPypiRootMultipartBeforeUpload() throws Exception {
    PypiHostedService pypi = mock(PypiHostedService.class);
    RepositoryProtocolController hosted = controller(
        runtimes(pypiRuntime(RepositoryType.HOSTED)), null, pypi);
    MockHttpServletRequest path = new MockHttpServletRequest(
        "POST", "/repository/pypi/project");
    path.setContentType("multipart/form-data; boundary=x");
    assertThrows(PypiExceptions.MethodNotAllowed.class, () -> hosted.post("pypi", path));

    MockHttpServletRequest noBoundary = new MockHttpServletRequest(
        "POST", "/repository/pypi/");
    noBoundary.setContentType("multipart/form-data");
    assertThrows(PypiExceptions.BadRequestException.class,
        () -> hosted.post("pypi", noBoundary));

    MockHttpServletRequest missing = new MockHttpServletRequest(
        "POST", "/repository/pypi/");
    missing.setContentType("multipart/form-data; boundary=x");
    assertThrows(PypiExceptions.BadRequestException.class, () -> hosted.post("pypi", missing));

    RepositoryProtocolController proxy = controller(
        runtimes(pypiRuntime(RepositoryType.PROXY)), null, pypi);
    assertThrows(PypiExceptions.MethodNotAllowed.class,
        () -> proxy.post("pypi", missing));

    MockHttpServletRequest broken = new MockHttpServletRequest(
        "POST", "/repository/pypi/") {
      @Override
      public Collection<Part> getParts() throws jakarta.servlet.ServletException {
        throw new jakarta.servlet.ServletException("broken");
      }
    };
    broken.setContentType("multipart/form-data; boundary=x");
    assertThrows(PypiExceptions.BadRequestException.class,
        () -> hosted.post("pypi", broken));
  }

  private static AptService.PublishedPackage published() {
    return new AptService.PublishedPackage(
        "pool/d/demo/demo_1_amd64.deb", "demo", "1", "amd64", "a".repeat(64), 3);
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }

  private static MockHttpServletRequest multipartRequest() {
    MockHttpServletRequest request = request("POST", "/repository/apt/");
    request.setContentType("multipart/form-data; boundary=x");
    request.setRemoteAddr("127.0.0.1");
    return request;
  }

  private static RepositoryRuntimeRegistry runtimes(RepositoryRuntime runtime) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return runtimes;
  }

  private static RepositoryRuntime aptRuntime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "apt", RepositoryFormat.APT, type, "apt-" + type.name().toLowerCase(),
        true, 1L, type == RepositoryType.HOSTED ? "ALLOW" : null,
        null, null, true,
        type == RepositoryType.PROXY ? "https://deb.debian.org/debian/" : null,
        60, 30, true, null, List.of());
  }

  private static RepositoryRuntime pypiRuntime(RepositoryType type) {
    return new RepositoryRuntime(
        2L, "pypi", RepositoryFormat.PYPI, type, "pypi-" + type.name().toLowerCase(),
        true, 1L, type == RepositoryType.HOSTED ? "ALLOW" : null,
        null, null, true,
        type == RepositoryType.PROXY ? "https://pypi.org/" : null,
        60, 30, true, null, List.of());
  }

  private static RepositoryProtocolController controller(
      RepositoryRuntimeRegistry runtimes, AptService apt, PypiHostedService pypi) {
    RepositoryProtocolControllerTestSupport controller =
        RepositoryProtocolControllerTestSupport.controller(
        runtimes,
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null, null, null,
        pypi, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""),
        null);
    controller.setAptService(apt);
    return controller;
  }
}
