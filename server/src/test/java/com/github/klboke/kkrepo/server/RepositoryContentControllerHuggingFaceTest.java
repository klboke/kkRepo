package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.huggingface.HuggingFaceService;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerHuggingFaceTest {

  @Test
  void trailingSlashRootGetAndHeadServeNexusStyleRepositoryHtml() throws Exception {
    RepositoryRuntime runtime = proxy();
    HuggingFaceService huggingFace = mock(HuggingFaceService.class);
    RepositoryProtocolController controller = controller(runtimes(runtime), huggingFace);

    ResponseEntity<StreamingResponseBody> get = controller.get(
        "hf", request("GET", "/repository/hf/"));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    get.getBody().writeTo(output);
    String html = output.toString(StandardCharsets.UTF_8);

    assertEquals(200, get.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, get.getHeaders().getContentType());
    assertTrue(html.contains("<span class=\"description\">hf</span>"));
    assertTrue(html.contains(
        "This huggingface proxy repository is not directly browseable at this URL."));
    assertTrue(html.contains("href=\"/browse/#browse/browse:hf\""));
    assertTrue(html.contains("/service/rest/repository/browse/hf/"));

    ResponseEntity<Void> head = controller.head(
        "hf", request("HEAD", "/repository/hf/"));
    assertEquals(200, head.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, head.getHeaders().getContentType());
    assertTrue(head.getHeaders().getContentLength() > 0);
    assertNull(head.getBody());
    verifyNoInteractions(huggingFace);
  }

  @Test
  void bareRootGetAndHeadReturnNexusStyleBadRequest() throws Exception {
    RepositoryRuntime runtime = proxy();
    HuggingFaceService huggingFace = mock(HuggingFaceService.class);
    RepositoryProtocolController controller = controller(runtimes(runtime), huggingFace);

    ResponseEntity<StreamingResponseBody> get = controller.get(
        "hf", request("GET", "/repository/hf"));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    get.getBody().writeTo(output);

    assertEquals(400, get.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, get.getHeaders().getContentType());
    assertTrue(output.toString(StandardCharsets.UTF_8).contains(
        "Repository path must have another '/' after initial '/'"));

    ResponseEntity<Void> head = controller.head(
        "hf", request("HEAD", "/repository/hf"));
    assertEquals(400, head.getStatusCode().value());
    assertEquals(MediaType.TEXT_HTML, head.getHeaders().getContentType());
    assertNull(head.getBody());
    verifyNoInteractions(huggingFace);
  }

  @Test
  void pathsInfoPostMaterializesBoundedResponseBody() {
    RepositoryRuntime runtime = proxy();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    HuggingFaceService huggingFace = mock(HuggingFaceService.class);
    when(runtimes.resolve("hf")).thenReturn(Optional.of(runtime));
    byte[] requestBody = "{\"paths\":[\"model.safetensors\"]}"
        .getBytes(StandardCharsets.UTF_8);
    byte[] responseBody = ("[{\"path\":\"model.safetensors\","
        + "\"size\":4194304,\"type\":\"file\"}]").getBytes(StandardCharsets.UTF_8);
    when(huggingFace.post(
            eq(runtime),
            eq("api/models/org/model/paths-info/0123456789abcdef0123456789abcdef01234567"),
            eq(null),
            eq("https://repo.example/repository/hf"),
            eq(requestBody),
            eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(responseBody), responseBody.length,
            MediaType.APPLICATION_JSON_VALUE, "paths-etag", null));
    RepositoryProtocolController controller = controller(runtimes, huggingFace);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/repository/hf/api/models/org/model/paths-info/"
            + "0123456789abcdef0123456789abcdef01234567");
    request.setScheme("https");
    request.setServerName("repo.example");
    request.setServerPort(443);
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    request.setContent(requestBody);

    ResponseEntity<?> response = controller.post("hf", request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(responseBody.length, response.getHeaders().getContentLength());
    assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    assertArrayEquals(responseBody, (byte[]) response.getBody());
  }

  @Test
  void headDelegatesToHuggingFaceServiceWithoutMaterializingTheBody() {
    RepositoryRuntime runtime = proxy();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    HuggingFaceService huggingFace = mock(HuggingFaceService.class);
    when(runtimes.resolve("hf")).thenReturn(Optional.of(runtime));
    when(huggingFace.get(
            eq(runtime),
            eq("org/model/resolve/main/config.json"),
            eq("download=true"),
            eq("https://repo.example/repository/hf"),
            eq(true)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(new byte[0]), 123L,
            MediaType.APPLICATION_OCTET_STREAM_VALUE, "config-etag", null));
    RepositoryProtocolController controller = controller(runtimes, huggingFace);
    MockHttpServletRequest request = request(
        "HEAD", "/repository/hf/org/model/resolve/main/config.json");
    request.setQueryString("download=true");

    ResponseEntity<Void> response = controller.head("hf", request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(123L, response.getHeaders().getContentLength());
    assertNull(response.getBody());
  }

  @Test
  void getDelegatesMetadataAndFileRoutesToHuggingFaceService() {
    RepositoryRuntime runtime = proxy();
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    HuggingFaceService huggingFace = mock(HuggingFaceService.class);
    when(runtimes.resolve("hf")).thenReturn(Optional.of(runtime));
    byte[] metadata = "{\"id\":\"org/model\"}".getBytes(StandardCharsets.UTF_8);
    when(huggingFace.get(
            eq(runtime), eq("api/models/org/model"), eq(null),
            eq("https://repo.example/repository/hf"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(metadata), metadata.length,
            MediaType.APPLICATION_JSON_VALUE, "model-etag", null));
    byte[] artifact = "weights".getBytes(StandardCharsets.UTF_8);
    when(huggingFace.get(
            eq(runtime), eq("org/model/resolve/main/model.safetensors"), eq(null),
            eq("https://repo.example/repository/hf"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(artifact), artifact.length,
            MediaType.APPLICATION_OCTET_STREAM_VALUE, "file-etag", null));
    RepositoryProtocolController controller = controller(runtimes, huggingFace);

    ResponseEntity<?> metadataResponse = controller.get(
        "hf", request("GET", "/repository/hf/api/models/org/model"));
    ResponseEntity<?> fileResponse = controller.get(
        "hf", request("GET", "/repository/hf/org/model/resolve/main/model.safetensors"));

    assertEquals(200, metadataResponse.getStatusCode().value());
    assertEquals(MediaType.APPLICATION_JSON, metadataResponse.getHeaders().getContentType());
    assertEquals(200, fileResponse.getStatusCode().value());
    assertEquals(MediaType.APPLICATION_OCTET_STREAM, fileResponse.getHeaders().getContentType());
    verify(huggingFace).get(
        runtime, "api/models/org/model", null, "https://repo.example/repository/hf", false);
    verify(huggingFace).get(
        runtime, "org/model/resolve/main/model.safetensors", null,
        "https://repo.example/repository/hf", false);
  }

  @Test
  void rejectsUnreadablePathsInfoBody() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("hf")).thenReturn(Optional.of(proxy()));
    RepositoryProtocolController controller = controller(runtimes, mock(HuggingFaceService.class));
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI())
        .thenReturn("/repository/hf/api/models/org/model/paths-info/main");
    when(request.getContextPath()).thenReturn("");
    try {
      when(request.getInputStream()).thenThrow(new IOException("disconnected"));
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }

    assertThrows(
        MavenExceptions.BadRequestException.class,
        () -> controller.post("hf", request));
  }

  @Test
  void failsClosedWhenHuggingFaceServiceIsUnavailable() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("hf")).thenReturn(Optional.of(proxy()));
    RepositoryProtocolController controller = controller(runtimes, null);

    assertThrows(
        IllegalStateException.class,
        () -> controller.head(
            "hf", request("HEAD", "/repository/hf/org/model/resolve/main/config.json")));
  }

  private static RepositoryRuntime proxy() {
    return new RepositoryRuntime(
        1L,
        "hf",
        RepositoryFormat.HUGGINGFACE,
        RepositoryType.PROXY,
        "huggingface-proxy",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        "http://hub.example",
        60,
        30,
        true,
        null,
        List.of());
  }

  private static RepositoryRuntimeRegistry runtimes(RepositoryRuntime runtime) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return runtimes;
  }

  private static MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setScheme("https");
    request.setServerName("repo.example");
    request.setServerPort(443);
    return request;
  }

  private static RepositoryProtocolController controller(
      RepositoryRuntimeRegistry runtimes, HuggingFaceService huggingFace) {
    RepositoryProtocolControllerTestSupport controller =
        RepositoryProtocolControllerTestSupport.controller(
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
    controller.setHuggingFaceService(huggingFace);
    return controller;
  }
}
