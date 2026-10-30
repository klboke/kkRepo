package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawGroupService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.staging.StagingHttpSupport;
import com.github.klboke.kkrepo.server.staging.StagingService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerStagingTest {
  private static final String PATH = "docs/a.txt";
  private static final PermissionSubject ALICE =
      new PermissionSubject("Local", "alice", Set.of(), null);

  @Test
  void routesStagedReadsToTheSessionViewWithoutTouchingLiveRawContent() throws Exception {
    RepositoryRuntime runtime = runtime();
    RawHostedService rawHosted = mock(RawHostedService.class);
    StagingService staging = mock(StagingService.class);
    byte[] staged = "staged".getBytes(StandardCharsets.UTF_8);
    when(staging.getStagedContent(
        eq(runtime), eq("release-1"), eq(PATH), eq(false), eq(ALICE), any()))
        .thenReturn(Optional.of(MavenResponse.ok(new ByteArrayInputStream(staged), staged.length,
            "text/plain", "etag", Instant.EPOCH)));
    when(staging.getStagedContent(
        eq(runtime), eq("release-1"), eq(PATH), eq(true), eq(ALICE), any()))
        .thenReturn(Optional.of(MavenResponse.noBody(
            200, staged.length, "text/plain", "etag", Instant.EPOCH)));
    RepositoryProtocolController controller = controller(runtime, rawHosted, staging);

    MockHttpServletRequest get = request("GET");
    ResponseEntity<StreamingResponseBody> response = controller.get("raw-hosted", get);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertArrayEquals(staged, output.toByteArray());

    ResponseEntity<Void> head = controller.head("raw-hosted", request("HEAD"));
    assertEquals(staged.length, head.getHeaders().getContentLength());
  }

  @Test
  void routesStagedWritesAndDeletesWithTheSessionHeader() throws Exception {
    RepositoryRuntime runtime = runtime();
    RawHostedService rawHosted = mock(RawHostedService.class);
    StagingService staging = mock(StagingService.class);
    when(staging.putRequest(eq(runtime), any(), any(), any(), any(), any()))
        .thenReturn(new StagingService.StagingWriteResult(7L, 3L, "ADD", "sha", 6L));
    when(staging.deleteRaw(eq(runtime), any(), any(), any(), any()))
        .thenReturn(1);
    RepositoryProtocolController controller = controller(runtime, rawHosted, staging);

    MockHttpServletRequest put = request("PUT");
    put.setContent("staged".getBytes(StandardCharsets.UTF_8));
    ResponseEntity<?> putResponse = controller.put("raw-hosted", put, "text/plain");
    assertEquals(201, putResponse.getStatusCode().value());
    assertEquals("7", putResponse.getHeaders().getFirst(StagingService.HEADER));
    assertEquals("ADD", putResponse.getHeaders().getFirst("X-KkRepo-Staging-Action"));

    ResponseEntity<?> deleteResponse = controller.delete("raw-hosted", request("DELETE"));
    assertEquals(204, deleteResponse.getStatusCode().value());
    verify(rawHosted, org.mockito.Mockito.never()).get(runtime, PATH, false);
  }

  private static MockHttpServletRequest request(String method) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/repository/raw-hosted/" + PATH);
    request.addHeader(StagingService.HEADER, "release-1");
    request.setRemoteAddr("192.0.2.10");
    request.setAttribute(
        AuthenticatedSubject.REQUEST_ATTRIBUTE,
        new AuthenticatedSubject("Local", "alice", "local", null, ALICE));
    return request;
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "raw-hosted", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true, 7L,
        "ALLOW", null, null, true, null, 60, 30, true, null, List.of());
  }

  private static RepositoryRuntimeRegistry runtimes(RepositoryRuntime runtime) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return runtimes;
  }

  private static RepositoryProtocolController controller(
      RepositoryRuntime runtime, RawHostedService rawHosted, StagingService staging) {
    RepositoryProtocolControllerTestSupport controller =
        RepositoryProtocolControllerTestSupport.controller(
            runtimes(runtime),
            // 1-28: unused protocol services.
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            // 29-31: Raw services.
            rawHosted, mock(RawProxyService.class), mock(RawGroupService.class),
            // 32-34: JSON, forwarded headers, Terraform.
            new ObjectMapper(),
            new ForwardedHeaderPolicy(""),
            null);
    controller.setStagingHttpSupport(new StagingHttpSupport(staging));
    return controller;
  }
}
