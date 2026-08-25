package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoGroupServiceTest {
  @Test
  void returnsFirstSuccessfulHostedOrProxyMemberInConfiguredOrder() {
    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime miss = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime success = runtime(3L, "proxy", RepositoryType.PROXY, List.of());
    String path = "example.com/demo/@v/v1.0.0.zip";
    MavenResponse expected = MavenResponse.noBody(200);
    when(hosted.get(miss, path, true))
        .thenThrow(new MavenExceptions.MavenNotFoundException(path));
    when(proxy.get(success, path, true)).thenReturn(expected);

    MavenResponse response = service(hosted, proxy).get(
        runtime(1L, "group", RepositoryType.GROUP, List.of(miss, success)), path, true);

    assertSame(expected, response);
  }

  @Test
  void mergesAndSemanticallySortsMemberVersionLists() throws Exception {
    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime hostedMember = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime proxyMember = runtime(3L, "proxy", RepositoryType.PROXY, List.of());
    String path = "example.com/demo/@v/list";
    when(hosted.get(hostedMember, path, false)).thenReturn(text(
        "v1.10.0\nv1.0.0\nv0.0.0-20250824120000-abcdef1\n"));
    when(proxy.get(proxyMember, path, false)).thenReturn(text("v1.2.0\nv1.10.0\n"));

    MavenResponse response = service(hosted, proxy).get(
        runtime(1L, "group", RepositoryType.GROUP, List.of(hostedMember, proxyMember)),
        path,
        false);

    assertEquals("v1.0.0\nv1.2.0\nv1.10.0",
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals("text/plain", response.contentType());
    assertNull(response.lastModified());
  }

  @Test
  void selectsGroupLatestUsingGoReleasePrecedence() throws Exception {
    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime hostedMember = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime proxyMember = runtime(3L, "proxy", RepositoryType.PROXY, List.of());
    String path = "example.com/demo/@latest";
    when(hosted.get(hostedMember, path, false)).thenReturn(json(
        "{\"Version\":\"v1.9.0\",\"Time\":\"2026-08-24T00:00:00Z\"}"));
    when(proxy.get(proxyMember, path, false)).thenReturn(json(
        "{\"Version\":\"v2.0.0-rc.1\",\"Time\":\"2026-08-25T00:00:00Z\"}"));

    MavenResponse response = service(hosted, proxy).get(
        runtime(1L, "group", RepositoryType.GROUP, List.of(hostedMember, proxyMember)),
        path,
        false);

    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
    var parsed = new ObjectMapper().readTree(body);
    assertEquals("v1.9.0", parsed.get("Version").textValue());
    assertTrue(parsed.get("Time").isTextual());
    assertEquals("text/plain", response.contentType());
  }

  @Test
  void preservesMemberPolicyFailuresInsteadOfFallingThroughForLatest() {
    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime blocked = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime fallback = runtime(3L, "proxy", RepositoryType.PROXY, List.of());
    String path = "example.com/demo/@latest";
    RuntimeException policyFailure = new RuntimeException("artifact download policy denied");
    when(hosted.get(blocked, path, false)).thenThrow(policyFailure);
    when(proxy.get(fallback, path, false)).thenReturn(json(
        "{\"Version\":\"v1.0.0\",\"Time\":\"2026-08-25T00:00:00Z\"}"));

    RuntimeException thrown = assertThrows(RuntimeException.class, () -> service(hosted, proxy).get(
        runtime(1L, "group", RepositoryType.GROUP, List.of(blocked, fallback)), path, false));

    assertSame(policyFailure, thrown);
  }

  @Test
  void preservesUpstreamFailureWhenNoMemberCanServeAndRejectsNonGroups() {
    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime failed = runtime(3L, "proxy", RepositoryType.PROXY, List.of());
    String path = "example.com/demo/@v/v1.0.0.mod";
    when(proxy.get(failed, path, false))
        .thenThrow(new MavenExceptions.BadUpstreamException("offline"));
    GoGroupService service = service(hosted, proxy);

    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(failed)), path, false));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.get(
            runtime(1L, "proxy", RepositoryType.PROXY, List.of()), path, false));
    RepositoryRuntime nested = runtime(4L, "nested", RepositoryType.GROUP, List.of());
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(nested)), path, false));
  }

  @Test
  void reportsEmptyInvalidAndExhaustedGroupRequestsAsNotFound() {
    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    GoGroupService service = service(hosted, proxy);
    RepositoryRuntime empty = runtime(1L, "group", RepositoryType.GROUP, List.of());

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(empty, "example.com/demo/@v/v1.0.0.mod", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(empty, "not-a-go-module/@v/list", false));

    RepositoryRuntime miss = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    String path = "example.com/demo/@v/v1.0.0.zip";
    when(hosted.get(miss, path, false))
        .thenThrow(new MavenExceptions.MavenNotFoundException(path));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(miss)), path, false));

    RepositoryRuntime offline = offlineRuntime(3L, "offline");
    RepositoryRuntime group = runtime(1L, "group", RepositoryType.GROUP, List.of(offline));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group, "example.com/demo/@v/list", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group, "example.com/demo/@latest", false));
  }

  @Test
  void listPreservesMissingInvalidUpstreamAndIoFailures() {
    String path = "example.com/demo/@v/list";

    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime miss = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime failed = runtime(3L, "proxy", RepositoryType.PROXY, List.of());
    when(hosted.get(miss, path, false))
        .thenThrow(new MavenExceptions.MavenNotFoundException(path));
    when(proxy.get(failed, path, false))
        .thenThrow(new MavenExceptions.BadUpstreamException("offline"));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service(hosted, proxy).get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(miss, failed)), path, false));

    GoHostedService invalidHosted = mock(GoHostedService.class);
    RepositoryRuntime invalid = runtime(4L, "invalid", RepositoryType.HOSTED, List.of());
    when(invalidHosted.get(invalid, path, false)).thenReturn(text("v1.0.0\nnot-a-version\n"));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service(invalidHosted, mock(GoProxyService.class)).get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(invalid)), path, false));

    GoHostedService unreadableHosted = mock(GoHostedService.class);
    RepositoryRuntime unreadable = runtime(5L, "unreadable", RepositoryType.HOSTED, List.of());
    when(unreadableHosted.get(unreadable, path, false)).thenReturn(unreadable());
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service(unreadableHosted, mock(GoProxyService.class)).get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(unreadable)), path, false));
  }

  @Test
  void latestSkipsMemberMissesAndRejectsMalformedMetadata() {
    String path = "example.com/demo/@latest";
    GoHostedService hosted = mock(GoHostedService.class);
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime miss = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime failed = runtime(3L, "proxy", RepositoryType.PROXY, List.of());
    when(hosted.get(miss, path, false))
        .thenThrow(new MavenExceptions.MavenNotFoundException(path));
    when(proxy.get(failed, path, false))
        .thenThrow(new MavenExceptions.BadUpstreamException("offline"));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service(hosted, proxy).get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(miss, failed)), path, false));

    GoHostedService malformedHosted = mock(GoHostedService.class);
    RepositoryRuntime malformed = runtime(4L, "malformed", RepositoryType.HOSTED, List.of());
    when(malformedHosted.get(malformed, path, false))
        .thenReturn(json("{\"Time\":\"not-an-instant\"}"));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service(malformedHosted, mock(GoProxyService.class)).get(
            runtime(1L, "group", RepositoryType.GROUP, List.of(malformed)), path, false));
  }

  @Test
  void rendersHeadResponsesForGeneratedMetadata() {
    GoHostedService hosted = mock(GoHostedService.class);
    RepositoryRuntime member = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    String listPath = "example.com/demo/@v/list";
    String latestPath = "example.com/demo/@latest";
    when(hosted.get(member, listPath, false)).thenReturn(text("v1.0.0\n"));
    when(hosted.get(member, latestPath, false)).thenReturn(json(
        "{\"Version\":\"v1.0.0\",\"Time\":\"2026-08-25T00:00:00Z\"}"));
    RepositoryRuntime group = runtime(1L, "group", RepositoryType.GROUP, List.of(member));

    assertNull(service(hosted, mock(GoProxyService.class)).get(group, listPath, true).body());
    assertNull(service(hosted, mock(GoProxyService.class)).get(group, latestPath, true).body());
  }

  private static GoGroupService service(GoHostedService hosted, GoProxyService proxy) {
    return new GoGroupService(hosted, proxy, new ObjectMapper().findAndRegisterModules());
  }

  private static MavenResponse text(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "text/plain", null, Instant.EPOCH);
  }

  private static MavenResponse json(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "application/json", null, Instant.EPOCH);
  }

  private static MavenResponse unreadable() {
    InputStream body = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken stream");
      }
    };
    return MavenResponse.ok(body, 1L, "text/plain", null, Instant.EPOCH);
  }

  private static RepositoryRuntime offlineRuntime(long id, String name) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.GO, RepositoryType.HOSTED, name, false, 7L,
        "ALLOW_ONCE", null, null, true, null,
        60, 60, true, null, List.of());
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.GO, type, name, true, 7L,
        type == RepositoryType.HOSTED ? "ALLOW_ONCE" : null,
        null, null, true, "https://proxy.golang.org/",
        60, 60, true, null, members);
  }
}
