package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.huggingface.HuggingFaceService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class RepositoryContentControllerHuggingFaceTest {

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
    RepositoryContentController controller = controller(runtimes, huggingFace);
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

  private static RepositoryContentController controller(
      RepositoryRuntimeRegistry runtimes, HuggingFaceService huggingFace) {
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
    controller.setHuggingFaceService(huggingFace);
    return controller;
  }
}
