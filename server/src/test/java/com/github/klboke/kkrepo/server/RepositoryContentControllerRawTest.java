package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawGroupService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;

class RepositoryContentControllerRawTest {
  @ParameterizedTest
  @EnumSource(RepositoryType.class)
  void rootGetAndHeadReachRawServicesAndBareRootIsRejected(RepositoryType type) throws Exception {
    RepositoryRuntime runtime = new RepositoryRuntime(
        1L, "raw", RepositoryFormat.RAW, type, "raw-" + type.name().toLowerCase(), true, 1L,
        "ALLOW", null, null, false, "https://example.test/archive/", 60, 60, false, "INLINE", List.of());
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("raw")).thenReturn(Optional.of(runtime));
    RawHostedService hosted = mock(RawHostedService.class);
    RawProxyService proxy = mock(RawProxyService.class);
    RawGroupService group = mock(RawGroupService.class);
    byte[] body = "<html>Upstream listing</html>".getBytes(StandardCharsets.UTF_8);
    MavenResponse get = MavenResponse.ok(new ByteArrayInputStream(body), body.length, "text/html", null, null);
    MavenResponse head = MavenResponse.noBody(200, body.length, "text/html", null, null);
    switch (type) {
      case HOSTED -> {
        when(hosted.get(runtime, "", false)).thenReturn(get);
        when(hosted.get(runtime, "", true)).thenReturn(head);
      }
      case PROXY -> {
        when(proxy.get(runtime, "", false)).thenReturn(get);
        when(proxy.get(runtime, "", true)).thenReturn(head);
      }
      case GROUP -> {
        when(group.get(runtime, "", false)).thenReturn(get);
        when(group.get(runtime, "", true)).thenReturn(head);
      }
    }
    RepositoryProtocolController controller = RepositoryProtocolControllerTestSupport.controller(
        runtimes,
        null, null, null,
        null, null,
        null, null, null,
        null,
        null, null, null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        hosted, proxy, group,
        new ObjectMapper(), new ForwardedHeaderPolicy(""), null);

    assertEquals(400, controller.get("raw", request("GET", "/repository/raw")).getStatusCode().value());
    assertEquals(400, controller.head("raw", request("HEAD", "/repository/raw")).getStatusCode().value());
    verifyNoInteractions(hosted, proxy, group);
    var response = controller.get("raw", request("GET", "/repository/raw/"));
    assertEquals(200, response.getStatusCode().value());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertArrayEquals(body, output.toByteArray());
    var headers = controller.head("raw", request("HEAD", "/repository/raw/"));
    assertEquals(200, headers.getStatusCode().value());
    assertEquals(body.length, headers.getHeaders().getContentLength());
    assertNull(headers.getBody());
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }
}
