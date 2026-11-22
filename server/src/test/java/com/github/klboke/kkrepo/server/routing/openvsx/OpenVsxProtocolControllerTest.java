package com.github.klboke.kkrepo.server.routing.openvsx;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxGroupService;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxHostedService;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxProxyService;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP round-trip through {@link RepositoryProtocolController}: the POST entry point must write
 * the complete body, and the advertised URLs must not carry the URL-prefix separator twice.
 */
class OpenVsxProtocolControllerTest {
  private final OpenVsxHostedService hosted = mock(OpenVsxHostedService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    var handler = new OpenVsxProtocolHandler(hosted, mock(OpenVsxProxyService.class),
        mock(OpenVsxGroupService.class), new ForwardedHeaderPolicy(""), new ObjectMapper());
    var dispatcher = mock(RepositoryProtocolDispatcher.class);
    Answer<ResponseEntity<?>> dispatch = call -> {
      HttpServletRequest request = call.getArgument(1);
      return handler.handle(new RepositoryProtocolRequest(
          runtime(), "openvsx",
          request.getRequestURI().substring("/repository/demo/".length()),
          call.getArgument(2), request, null, null));
    };
    when(dispatcher.dispatchRead(eq("demo"), any(), any())).thenAnswer(dispatch);
    when(dispatcher.dispatch(eq("demo"), any(), any(), isNull())).thenAnswer(dispatch);
    mvc = MockMvcBuilders.standaloneSetup(new RepositoryProtocolController(dispatcher)).build();
  }

  @Test
  void galleryPostReturnsTheCompleteBodyThroughTheHttpPostEntry() throws Exception {
    byte[] body = ("{\"extensions\":[{\"publisher\":{\"publisherName\":\"acme\"},"
        + "\"extensionName\":\"demo\"}]}").getBytes(StandardCharsets.UTF_8);
    when(hosted.get(any(), any(), any(), any(), eq("demo"), eq(false)))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(body), body.length,
            "application/json", "etag", null));

    MockHttpServletResponse response = perform(
        post("/repository/demo/vscode/gallery/extensionquery")
            .content("{\"filters\":[{\"criteria\":[]}]}")
            .contentType("application/json"));

    org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatus());
    // Before the fix the POST entry point serialized the StreamingResponseBody lambda as `{}`.
    org.junit.jupiter.api.Assertions.assertEquals(body.length,
        response.getContentAsByteArray().length);
    org.junit.jupiter.api.Assertions.assertEquals(new String(body, StandardCharsets.UTF_8),
        response.getContentAsString());
  }

  @Test
  void advertisedUrlsDoNotRepeatTheRepositoryUrlPrefixSeparator() throws Exception {
    byte[] body = "{\"name\":\"demo\"}".getBytes(StandardCharsets.UTF_8);
    when(hosted.get(any(), any(), any(), any(), eq("demo"), eq(false)))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(body), body.length,
            "application/json", "etag", null));

    MockHttpServletResponse response = perform(get("/repository/demo/api/acme/demo"));

    org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatus());
    var baseUrl = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(hosted).get(any(), any(), any(), baseUrl.capture(), eq("demo"), eq(false));
    // `RepositoryUrlPrefix.path(request)` already ends in `/`; the handler strips it so a
    // builder's `baseUrl + "/" + repository` never generates `//repository//demo`.
    org.junit.jupiter.api.Assertions.assertEquals(
        "http://localhost/repository/demo/api/acme/demo/1.2.3/file/acme.demo-1.2.3.vsix",
        baseUrl.getValue() + "/demo/api/acme/demo/1.2.3/file/acme.demo-1.2.3.vsix");
    org.junit.jupiter.api.Assertions.assertFalse(baseUrl.getValue().endsWith("/"));
  }

  private MockHttpServletResponse perform(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    MvcResult result = mvc.perform(request).andReturn();
    if (result.getRequest().isAsyncStarted()) {
      result = mvc.perform(asyncDispatch(result)).andReturn();
    }
    return result.getResponse();
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "demo", RepositoryFormat.OPENVSX, RepositoryType.HOSTED, "openvsx-hosted",
        true, 1L, "ALLOW", null, null, true, null, 60, 30, true, null, java.util.List.of());
  }
}
