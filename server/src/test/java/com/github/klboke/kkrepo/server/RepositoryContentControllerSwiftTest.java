package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.swift.SwiftMediaTypes;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.swift.SwiftExceptions;
import com.github.klboke.kkrepo.server.swift.SwiftService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerSwiftTest {

  @Test
  void getForwardsProtocolTargetAndAppliesRangeOnlyToSourceArchives() throws Exception {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftService swift = mock(SwiftService.class);
    PermissionSubject permissionSubject =
        new PermissionSubject("Local", "reader", Set.of("swift-readers"), null);
    when(runtimes.resolve("swift")).thenReturn(Optional.of(runtime));
    byte[] archive = "abcdef".getBytes(StandardCharsets.UTF_8);
    when(swift.get(
            eq(runtime),
            eq("Acme/Demo/1.2.3.zip"),
            eq("download=1"),
            eq("https://repo.example/repository/swift"),
            eq(SwiftMediaTypes.VENDOR_ZIP),
            eq(false),
            eq(permissionSubject)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(archive),
            archive.length,
            SwiftMediaTypes.ARCHIVE,
            "archive-etag",
            Instant.EPOCH));
    RepositoryContentController controller = controller(runtimes, swift);
    MockHttpServletRequest request = request(
        "GET", "/repository/swift/Acme/Demo/1.2.3.zip");
    request.setQueryString("download=1");
    request.addHeader(HttpHeaders.ACCEPT, SwiftMediaTypes.VENDOR_ZIP);
    request.addHeader(HttpHeaders.RANGE, "bytes=1-3");
    request.setAttribute(
        AuthenticatedSubject.REQUEST_ATTRIBUTE,
        new AuthenticatedSubject("basic", "reader", "Local", null, permissionSubject));

    ResponseEntity<StreamingResponseBody> response = controller.get("swift", request);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    response.getBody().writeTo(body);

    assertEquals(206, response.getStatusCode().value());
    assertEquals("bcd", body.toString(StandardCharsets.UTF_8));
    assertEquals("bytes 1-3/6", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    assertEquals("\"archive-etag\"", response.getHeaders().getETag());
  }

  @Test
  void headForwardsAcceptQueryAndSuppressesBody() {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftService swift = mock(SwiftService.class);
    PermissionSubject permissionSubject =
        new PermissionSubject("Local", "reader", Set.of("swift-readers"), null);
    when(runtimes.resolve("swift")).thenReturn(Optional.of(runtime));
    when(swift.get(
            eq(runtime),
            eq("Acme/Demo/1.2.3/Package.swift"),
            eq("swift-version=5.9"),
            eq("https://repo.example/repository/swift"),
            eq(SwiftMediaTypes.VENDOR_SWIFT),
            eq(true),
            eq(permissionSubject)))
        .thenReturn(MavenResponse.noBody(
            200, 42, SwiftMediaTypes.MANIFEST, "manifest-etag", Instant.EPOCH));
    RepositoryContentController controller = controller(runtimes, swift);
    MockHttpServletRequest request = request(
        "HEAD", "/repository/swift/Acme/Demo/1.2.3/Package.swift");
    request.setQueryString("swift-version=5.9");
    request.addHeader(HttpHeaders.ACCEPT, SwiftMediaTypes.VENDOR_SWIFT);
    request.setAttribute(
        AuthenticatedSubject.REQUEST_ATTRIBUTE,
        new AuthenticatedSubject("basic", "reader", "Local", null, permissionSubject));

    ResponseEntity<Void> response = controller.head("swift", request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(42, response.getHeaders().getContentLength());
    assertEquals(SwiftMediaTypes.MANIFEST, response.getHeaders().getContentType().toString());
  }

  @Test
  void multipartPutForwardsOfficialFieldsSignatureAndRequestContext() throws Exception {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftService swift = mock(SwiftService.class);
    when(runtimes.resolve("swift")).thenReturn(Optional.of(runtime));
    when(swift.publish(
            eq(runtime),
            eq("Acme/Demo/1.2.3"),
            eq("source=swiftpm"),
            any(),
            eq("cms-1.0.0"),
            eq("https://repo.example/repository/swift"),
            eq(SwiftMediaTypes.VENDOR_JSON),
            eq("anonymous"),
            eq("192.0.2.10")))
        .thenReturn(MavenResponse.created()
            .withHeader("Content-Version", "1")
            .withHeader("Location", "https://repo.example/repository/swift/Acme/Demo/1.2.3"));
    RepositoryContentController controller = controller(runtimes, swift);
    MockHttpServletRequest request = request(
        "PUT", "/repository/swift/Acme/Demo/1.2.3");
    request.setQueryString("source=swiftpm");
    request.setRemoteAddr("192.0.2.10");
    request.setContentType("multipart/form-data; boundary=test");
    request.addHeader(HttpHeaders.ACCEPT, SwiftMediaTypes.VENDOR_JSON);
    request.addHeader("X-Swift-Package-Signature-Format", "cms-1.0.0");
    request.addPart(new MockPart(
        "source-archive", "source.zip", new byte[] {1, 2, 3}));

    ResponseEntity<?> response = controller.put("swift", request, request.getContentType());

    assertEquals(201, response.getStatusCode().value());
    assertEquals("1", response.getHeaders().getFirst("Content-Version"));
    assertEquals("https://repo.example/repository/swift/Acme/Demo/1.2.3",
        response.getHeaders().getFirst(HttpHeaders.LOCATION));
    verify(swift).publish(
        eq(runtime),
        eq("Acme/Demo/1.2.3"),
        eq("source=swiftpm"),
        any(),
        eq("cms-1.0.0"),
        eq("https://repo.example/repository/swift"),
        eq(SwiftMediaTypes.VENDOR_JSON),
        eq("anonymous"),
        eq("192.0.2.10"));
  }

  @Test
  void putRejectsNonMultipartRequestsBeforeCallingSwiftService() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftService swift = mock(SwiftService.class);
    when(runtimes.resolve("swift")).thenReturn(Optional.of(hosted()));
    RepositoryContentController controller = controller(runtimes, swift);
    MockHttpServletRequest request = request(
        "PUT", "/repository/swift/Acme/Demo/1.2.3");

    assertThrows(SwiftExceptions.UnsupportedMediaType.class,
        () -> controller.put("swift", request, "application/zip"));
    verify(swift, never()).publish(
        any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void putRunsReadOnlyPreflightBeforeParsingMultipartBody() {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftService swift = mock(SwiftService.class);
    when(runtimes.resolve("swift")).thenReturn(Optional.of(runtime));
    when(swift.validatePublishRequest(
        eq(runtime), eq("Acme/Demo/1.2.3"), eq(null), eq(null)))
        .thenThrow(new SwiftExceptions.MethodNotAllowed("read-only"));
    RepositoryContentController controller = controller(runtimes, swift);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "PUT", "/repository/swift/Acme/Demo/1.2.3") {
      @Override
      public Collection<jakarta.servlet.http.Part> getParts() {
        throw new AssertionError("multipart body must not be parsed before preflight rejection");
      }
    };
    request.setContentType("multipart/form-data; boundary=test");

    assertThrows(SwiftExceptions.MethodNotAllowed.class,
        () -> controller.put("swift", request, request.getContentType()));
    verify(swift, never()).publish(
        any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void putRejectsDuplicateCoordinateBeforeParsingMultipartBody() {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftService swift = mock(SwiftService.class);
    when(runtimes.resolve("swift")).thenReturn(Optional.of(runtime));
    when(swift.validatePublishRequest(
        eq(runtime), eq("Acme/Demo/1.2.3"), eq(null), eq(null)))
        .thenThrow(new SwiftExceptions.Conflict("Swift release already exists"));
    RepositoryContentController controller = controller(runtimes, swift);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "PUT", "/repository/swift/Acme/Demo/1.2.3") {
      @Override
      public Collection<jakarta.servlet.http.Part> getParts() {
        throw new AssertionError("duplicate publish must be rejected before multipart parsing");
      }
    };
    request.setContentType("multipart/form-data; boundary=test");

    assertThrows(SwiftExceptions.Conflict.class,
        () -> controller.put("swift", request, request.getContentType()));
    verify(swift, never()).publish(
        any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void postLoginUsesSwiftServiceAndRejectsOtherPostTargets() {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftService swift = mock(SwiftService.class);
    when(runtimes.resolve("swift")).thenReturn(Optional.of(runtime));
    when(swift.login(runtime)).thenReturn(MavenResponse.noBody(200));
    RepositoryContentController controller = controller(runtimes, swift);

    ResponseEntity<?> response = controller.post(
        "swift", request("POST", "/repository/swift/login"));

    assertEquals(200, response.getStatusCode().value());
    assertEquals("1", response.getHeaders().getFirst("Content-Version"));
    assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertThrows(SwiftExceptions.MethodNotAllowed.class, () -> controller.post(
        "swift", request("POST", "/repository/swift/Acme/Demo")));
  }

  private static MockHttpServletRequest request(String method, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setScheme("https");
    request.setServerName("repo.example");
    request.setServerPort(443);
    return request;
  }

  private static RepositoryRuntime hosted() {
    return new RepositoryRuntime(
        1L,
        "swift",
        RepositoryFormat.SWIFT,
        RepositoryType.HOSTED,
        "swift-hosted",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        List.of());
  }

  private static RepositoryContentController controller(
      RepositoryRuntimeRegistry runtimes, SwiftService swift) {
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
    controller.setSwiftService(swift);
    return controller;
  }
}
