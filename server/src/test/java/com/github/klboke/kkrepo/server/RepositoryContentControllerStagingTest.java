package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.pub.PubHostedService;
import com.github.klboke.kkrepo.server.pub.PubStagingService;
import com.github.klboke.kkrepo.server.raw.RawGroupService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.staging.StagingCredentialService;
import com.github.klboke.kkrepo.server.staging.StagingHttpSupport;
import com.github.klboke.kkrepo.server.staging.StagingSelector;
import com.github.klboke.kkrepo.server.staging.StagingService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
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

  @Test
  void querySelectorRoutesStagedWritesWithoutTouchingLiveContent() throws Exception {
    RepositoryRuntime runtime = runtime();
    RawHostedService rawHosted = mock(RawHostedService.class);
    StagingService staging = mock(StagingService.class);
    when(staging.putRequest(eq(runtime), eq("7"), eq(PATH), any(), any(), any()))
        .thenReturn(new StagingService.StagingWriteResult(7L, 3L, "ADD", "sha", 6L));
    RepositoryProtocolController controller = controller(runtime, rawHosted, staging);
    MockHttpServletRequest put = request("PUT");
    put.removeHeader(StagingService.HEADER);
    put.setParameter(StagingSelector.QUERY_PARAMETER, "7");
    put.setContent("staged".getBytes(StandardCharsets.UTF_8));

    ResponseEntity<?> response = controller.put("raw-hosted", put, "text/plain");

    assertEquals(201, response.getStatusCode().value());
    verifyNoInteractions(rawHosted);
  }

  @Test
  void credentialBindingRoutesStagedDeletesWithoutTouchingLiveContent() throws Exception {
    RepositoryRuntime runtime = runtime();
    RawHostedService rawHosted = mock(RawHostedService.class);
    StagingService staging = mock(StagingService.class);
    when(staging.deleteRaw(eq(runtime), eq("release-1"), eq(PATH), any(), any()))
        .thenReturn(1);
    RepositoryProtocolController controller = controller(runtime, rawHosted, staging);
    MockHttpServletRequest delete = request("DELETE");
    delete.removeHeader(StagingService.HEADER);
    delete.setAttribute(StagingCredentialService.SESSION_ATTRIBUTE, "release-1");

    ResponseEntity<?> response = controller.delete("raw-hosted", delete);

    assertEquals(204, response.getStatusCode().value());
    verifyNoInteractions(rawHosted);
  }

  @Test
  void contradictorySelectorsFailBeforeAnyPublication() {
    RepositoryRuntime runtime = runtime();
    RawHostedService rawHosted = mock(RawHostedService.class);
    StagingService staging = mock(StagingService.class);
    RepositoryProtocolController controller = controller(runtime, rawHosted, staging);
    MockHttpServletRequest put = request("PUT");
    put.setParameter(StagingSelector.QUERY_PARAMETER, "8");
    put.setContent("staged".getBytes(StandardCharsets.UTF_8));

    ResponseStatusException error = assertThrows(
        ResponseStatusException.class,
        () -> controller.put("raw-hosted", put, "text/plain"));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    verifyNoInteractions(rawHosted, staging);
  }

  @Test
  void pubFinalizeRoutesToTheStagedPathForQueryAndCredentialSelectors() throws Exception {
    RepositoryRuntime runtime = pubRuntime();
    PubHostedService pubHosted = mock(PubHostedService.class);
    PubStagingService pubStaging = mock(PubStagingService.class);
    when(pubStaging.finalizeStaged(
        eq(runtime), eq("session-1"), eq("7"), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(302, 0, "text/plain", null, null));
    RepositoryProtocolControllerTestSupport controller =
        pubController(runtime, pubHosted, pubStaging);

    MockHttpServletRequest query = new MockHttpServletRequest(
        "GET", "/repository/pub-hosted/api/packages/versions/finalize/session-1");
    query.setParameter(StagingSelector.QUERY_PARAMETER, "7");
    assertEquals(302, controller.get("pub-hosted", query).getStatusCode().value());

    MockHttpServletRequest bound = new MockHttpServletRequest(
        "GET", "/repository/pub-hosted/api/packages/versions/finalize/session-1");
    bound.setAttribute(StagingCredentialService.SESSION_ATTRIBUTE, "7");
    assertEquals(302, controller.get("pub-hosted", bound).getStatusCode().value());

    verify(pubStaging, org.mockito.Mockito.times(2)).finalizeStaged(
        eq(runtime), eq("session-1"), eq("7"), any(), any(), any(), any(), anyBoolean());
    verify(pubHosted, org.mockito.Mockito.never()).finalizeUpload(
        any(), any(), any(), any(), any(), anyBoolean());
  }

  private static RepositoryRuntime pubRuntime() {
    return new RepositoryRuntime(
        2L, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED, "pub-hosted", true, 7L,
        "ALLOW", null, null, true, null, 60, 30, true, null, List.of());
  }

  private static RepositoryProtocolControllerTestSupport pubController(
      RepositoryRuntime runtime, PubHostedService pubHosted, PubStagingService pubStaging) {
    RepositoryProtocolControllerTestSupport controller =
        RepositoryProtocolControllerTestSupport.controller(
            runtimes(runtime),
            // 1-18: unused protocol services.
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null,
            // 20-22: Pub services.
            pubHosted, null, null,
            // 23-28: Composer, NuGet, RubyGems and Yum services.
            null, null, null, null, null, null,
            // 29-31: Raw services.
            null, null, null,
            // 31-34: JSON, forwarded headers, Terraform.
            new ObjectMapper(),
            new ForwardedHeaderPolicy(""),
            null);
    controller.setPubStagingService(pubStaging);
    return controller;
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
