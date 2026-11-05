package com.github.klboke.kkrepo.server.routing.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.arch.ArchService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ArchProtocolHandlerTest {
  private final ArchService service = mock(ArchService.class);
  private final com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService groupWriteTarget =
      mock(com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService.class);
  private final ArchProtocolHandler handler = new ArchProtocolHandler(service);

  {
    handler.setGroupWriteTargetService(groupWriteTarget);
  }

  @Test
  void publishesPackagesWithPut() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    byte[] body = "package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(service.publish(eq(runtime), eq("demo-1.0.0-1-x86_64.pkg.tar.zst"),
        any(), eq("anonymous"), any())).thenReturn(new ArchService.Published(
            "demo-1.0.0-1-x86_64.pkg.tar.zst"));

    ResponseEntity<?> response = handler.handle(request(runtime, "PUT",
        "demo-1.0.0-1-x86_64.pkg.tar.zst", body));

    assertEquals(201, response.getStatusCode().value());
    assertEquals("demo-1.0.0-1-x86_64.pkg.tar.zst",
        response.getHeaders().getFirst("Location"));
  }

  @Test
  void redirectsGroupWritesToTheConfiguredHostedMember() throws Exception {
    RepositoryRuntime group = runtime(RepositoryType.GROUP);
    RepositoryRuntime hosted = runtime(RepositoryType.HOSTED);
    when(groupWriteTarget.requireWriteTarget(group)).thenReturn(hosted);
    byte[] body = "package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(service.publish(eq(hosted), eq("demo-1.0.0-1-x86_64.pkg.tar.zst"),
        any(), eq("anonymous"), any()))
        .thenReturn(new ArchService.Published("demo-1.0.0-1-x86_64.pkg.tar.zst"));

    ResponseEntity<?> response = handler.handle(request(group, "PUT",
        "demo-1.0.0-1-x86_64.pkg.tar.zst", body));

    assertEquals(201, response.getStatusCode().value());
    verify(service).publish(eq(hosted), eq("demo-1.0.0-1-x86_64.pkg.tar.zst"),
        any(), eq("anonymous"), any());
  }

  @Test
  void uploadsPackageSignaturesAndServesThePublicKey() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    byte[] signature = "signature".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(service.publishSignature(eq(runtime),
        eq("demo-1.0.0-1-x86_64.pkg.tar.zst.sig"), any(), eq("anonymous"), any()))
        .thenReturn(new ArchService.Published("demo-1.0.0-1-x86_64.pkg.tar.zst.sig"));
    when(service.get(eq(runtime), any(), eq(false))).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(signature), signature.length, "application/pgp-keys", "key",
        null));

    ResponseEntity<?> upload = handler.handle(request(runtime, "PUT",
        "demo-1.0.0-1-x86_64.pkg.tar.zst.sig", signature));
    assertEquals(201, upload.getStatusCode().value());

    ResponseEntity<?> key = handler.handle(request(runtime, "GET", "public-key", null));
    assertEquals(200, key.getStatusCode().value());
    assertEquals("application/pgp-keys", key.getHeaders().getContentType().toString());
  }

  @Test
  void servesGeneratedDatabasesAndPackages() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    byte[] body = "db".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(service.get(eq(runtime), any(), eq(false))).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(body), body.length, "application/gzip", "etag", null));

    ResponseEntity<?> response = handler.handle(request(runtime, "GET", "core.db", null));

    assertEquals(200, response.getStatusCode().value());
    verify(service).get(eq(runtime), any(), eq(false));
  }

  @Test
  void rejectsPostAndDatabaseWrites() {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    org.junit.jupiter.api.Assertions.assertThrows(
        com.github.klboke.kkrepo.server.maven.MavenExceptions.BadRequestException.class,
        () -> handler.handle(request(runtime, "PUT", "core.db", new byte[0])));
  }

  @Test
  void malformedPathsBecomeProtocolBadRequests() {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    org.junit.jupiter.api.Assertions.assertThrows(
        com.github.klboke.kkrepo.server.maven.MavenExceptions.BadRequestException.class,
        () -> handler.handle(request(runtime, "GET", "bad%2Fpath", null)));
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "demo", RepositoryFormat.ARCH, type, "arch-" + type.name().toLowerCase(),
        true, 1L, type == RepositoryType.HOSTED ? "ALLOW" : null, null, null, true,
        type == RepositoryType.PROXY ? "https://geo.mirror.pkgbuild.com/" : null,
        60, 30, true, null, List.of());
  }

  private static RepositoryProtocolRequest request(
      RepositoryRuntime runtime, String method, String path, byte[] content) {
    MockHttpServletRequest servlet = new MockHttpServletRequest(method, "/repository/demo/" + path);
    if (content != null) servlet.setContent(content);
    return new RepositoryProtocolRequest(
        runtime, runtime.name(), path, HttpMethod.valueOf(method), servlet, null, null);
  }
}
