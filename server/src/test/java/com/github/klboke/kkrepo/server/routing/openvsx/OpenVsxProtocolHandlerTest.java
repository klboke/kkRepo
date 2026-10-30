package com.github.klboke.kkrepo.server.routing.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxGroupService;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxHostedService;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxProxyService;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class OpenVsxProtocolHandlerTest {
  private final OpenVsxHostedService hosted = mock(OpenVsxHostedService.class);
  private final OpenVsxProxyService proxy = mock(OpenVsxProxyService.class);
  private final OpenVsxGroupService group = mock(OpenVsxGroupService.class);
  private final com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService groupWriteTarget =
      mock(com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService.class);
  private final OpenVsxProtocolHandler handler = new OpenVsxProtocolHandler(
      hosted, proxy, group, new ForwardedHeaderPolicy(""), new ObjectMapper());

  {
    handler.setGroupWriteTargetService(groupWriteTarget);
  }

  @Test
  void delegatesMetadataReadsToTheMatchingFacet() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    byte[] body = "{\"name\":\"demo\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(hosted.get(eq(runtime), any(), any(), any(), eq("demo"), eq(false)))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(body), body.length,
            "application/json", "etag", null));

    ResponseEntity<?> response = handler.handle(request(runtime, "GET", "api/acme/demo"));

    assertEquals(200, response.getStatusCode().value());
    assertEquals("application/json", response.getHeaders().getContentType().toString());
    verify(hosted).get(eq(runtime), any(), any(), any(), eq("demo"), eq(false));
  }

  @Test
  void publishesOnlyThroughPost() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    byte[] body = "vsix".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    MavenResponse created = MavenResponse.created().withStatus(201);
    when(hosted.publish(eq(runtime), any(), eq("anonymous"), any(), any(), eq("demo")))
        .thenReturn(new OpenVsxHostedService.Published(created, "acme/demo/1.0.0/demo.vsix"));

    ResponseEntity<?> response = handler.handle(request(runtime, "POST", "api/-/publish", body));

    assertEquals(201, response.getStatusCode().value());
    verify(hosted).publish(eq(runtime), any(), eq("anonymous"), any(), any(), eq("demo"));
  }

  @Test
  void redirectsGroupPublishToTheConfiguredHostedMember() throws Exception {
    RepositoryRuntime groupRuntime = runtime(RepositoryType.GROUP);
    RepositoryRuntime hostedRuntime = runtime(RepositoryType.HOSTED);
    when(groupWriteTarget.requireWriteTarget(groupRuntime)).thenReturn(hostedRuntime);
    byte[] body = "vsix".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(hosted.publish(eq(hostedRuntime), any(), eq("anonymous"), any(), any(), eq("demo")))
        .thenReturn(new OpenVsxHostedService.Published(
            MavenResponse.created().withStatus(201), "acme/demo/1.0.0/demo.vsix"));

    ResponseEntity<?> response = handler.handle(
        request(groupRuntime, "POST", "api/-/publish", body));

    assertEquals(201, response.getStatusCode().value());
    verify(hosted).publish(eq(hostedRuntime), any(), eq("anonymous"), any(), any(), eq("demo"));
  }

  @Test
  void proxyAndGroupReadsUseTheirOwnFacet() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(RepositoryType.PROXY);
    RepositoryRuntime groupRuntime = runtime(RepositoryType.GROUP);
    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(proxy.get(eq(proxyRuntime), any(), any(), any(), eq("demo"), anyBoolean()))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(body), body.length,
            "application/json", "etag", null));
    when(group.get(eq(groupRuntime), any(), any(), any(), eq("demo"), anyBoolean()))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(body), body.length,
            "application/json", "etag", null));

    handler.handle(request(proxyRuntime, "GET", "api/acme/demo"));
    handler.handle(request(groupRuntime, "HEAD", "api/acme/demo"));

    verify(proxy).get(eq(proxyRuntime), any(), any(), any(), eq("demo"), eq(false));
    verify(group).get(eq(groupRuntime), any(), any(), any(), eq("demo"), eq(true));
  }

  @Test
  void malformedPathsBecomeProtocolBadRequests() {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    org.junit.jupiter.api.Assertions.assertThrows(
        com.github.klboke.kkrepo.server.maven.MavenExceptions.BadRequestException.class,
        () -> handler.handle(request(runtime, "GET", "api/acme%2Fdemo", null)));
  }

  @Test
  void galleryPostBodyFiltersAreAppliedToTheQuery() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    byte[] responseBody = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(hosted.get(eq(runtime), any(), any(), any(), eq("demo"), eq(false)))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(responseBody), responseBody.length,
            "application/json", "etag", null));
    byte[] body = """
        {"filters":[{"criteria":[
          {"filterType":10,"value":"python"},
          {"filterType":6,"value":"acme"}
        ],"pageNumber":3,"pageSize":7}]}
        """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    handler.handle(request(runtime, "POST", "vscode/gallery/extensionquery", body));

    org.mockito.ArgumentCaptor<com.github.klboke.kkrepo.server.openvsx.OpenVsxQuery> query =
        org.mockito.ArgumentCaptor.forClass(
            com.github.klboke.kkrepo.server.openvsx.OpenVsxQuery.class);
    verify(hosted).get(eq(runtime), any(), query.capture(), any(), eq("demo"), eq(false));
    assertEquals("python", query.getValue().query());
    assertEquals("acme", query.getValue().namespaceName());
    assertEquals(7, query.getValue().size());
    assertEquals(14, query.getValue().offset());
  }

  @Test
  void vscodeItemResolvesTheRequestedExtension() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    byte[] body = "{\"itemName\":\"acme.demo\"}".getBytes(
        java.nio.charset.StandardCharsets.UTF_8);
    when(hosted.getItem(eq(runtime), eq("acme"), eq("demo"), any(), any(), eq("demo"), eq(false)))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(body), body.length,
            "application/json", "etag", null));

    ResponseEntity<?> response = handler.handle(itemRequest(runtime,
        "vscode/item", "acme.demo"));

    assertEquals(200, response.getStatusCode().value());
    verify(hosted).getItem(eq(runtime), eq("acme"), eq("demo"), any(), any(), eq("demo"),
        eq(false));
  }

  private static RepositoryProtocolRequest itemRequest(
      RepositoryRuntime runtime, String path, String itemName) {
    MockHttpServletRequest servlet = new MockHttpServletRequest("GET",
        "/repository/demo/" + path);
    servlet.addParameter("itemName", itemName);
    return new RepositoryProtocolRequest(
        runtime, runtime.name(), path, HttpMethod.GET, servlet, null, null);
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "demo", RepositoryFormat.OPENVSX, type, "openvsx-" + type.name().toLowerCase(),
        true, 1L, type == RepositoryType.HOSTED ? "ALLOW" : null, null, null, true,
        type == RepositoryType.PROXY ? "https://open-vsx.org/" : null,
        60, 30, true, null, List.of());
  }

  private static RepositoryProtocolRequest request(
      RepositoryRuntime runtime, String method, String path) {
    return request(runtime, method, path, null);
  }

  private static RepositoryProtocolRequest request(
      RepositoryRuntime runtime, String method, String path, byte[] content) {
    MockHttpServletRequest servlet = new MockHttpServletRequest(method, "/repository/demo/" + path);
    if (content != null) servlet.setContent(content);
    return new RepositoryProtocolRequest(
        runtime, runtime.name(), path, HttpMethod.valueOf(method), servlet, null, null);
  }
}
