package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.ansible.AnsibleGalaxyExceptions;
import com.github.klboke.kkrepo.server.ansible.AnsibleGalaxyMultipartReader;
import com.github.klboke.kkrepo.server.ansible.AnsibleGalaxyService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RepositoryContentControllerAnsibleTest {
  private static final String SHA256 = "a".repeat(64);
  private static final String FILENAME = "acme-tools-1.2.3.tar.gz";

  @Test
  void routesHeadAndGetWithRepositoryRelativeProtocolContext() throws Exception {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = runtimes(runtime);
    AnsibleGalaxyService ansible = mock(AnsibleGalaxyService.class);
    when(ansible.get(
        eq(runtime), eq("api/v3/collections/acme/tools/"), eq("view=full"),
        eq("https://repo.example/repository/ansible"), eq(true), eq("anonymous")))
        .thenReturn(MavenResponse.noBody(
            200, 42L, "application/json", "metadata", Instant.EPOCH));
    byte[] archive = "archive".getBytes(StandardCharsets.UTF_8);
    when(ansible.get(
        eq(runtime), eq("api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME),
        eq(null), eq("https://repo.example/repository/ansible"), eq(false), eq("anonymous")))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(archive), archive.length,
            "application/octet-stream", SHA256, Instant.EPOCH));
    RepositoryContentController controller = controller(
        runtimes, ansible, new AnsibleGalaxyMultipartReader(1024, 1024));

    MockHttpServletRequest head = request(
        "HEAD", "/repository/ansible/api/v3/collections/acme/tools/");
    head.setQueryString("view=full");
    ResponseEntity<Void> headResponse = controller.head("ansible", head);
    assertEquals(200, headResponse.getStatusCode().value());
    assertEquals(42L, headResponse.getHeaders().getContentLength());

    MockHttpServletRequest get = request(
        "GET", "/repository/ansible/api/v3/plugin/ansible/content/published/collections/artifacts/"
            + FILENAME);
    ResponseEntity<StreamingResponseBody> getResponse = controller.get("ansible", get);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    getResponse.getBody().writeTo(body);
    assertEquals("archive", body.toString(StandardCharsets.UTF_8));
    assertEquals("application/octet-stream",
        getResponse.getHeaders().getContentType().toString());
    assertEquals('"' + SHA256 + '"', getResponse.getHeaders().getETag());
  }

  @Test
  void routesRawArtifactPutAndRejectsQueryOrDelete() throws Exception {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = runtimes(runtime);
    AnsibleGalaxyService ansible = mock(AnsibleGalaxyService.class);
    String path = "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME;
    when(ansible.putArtifact(eq(runtime), eq(path), any(), eq("anonymous"), eq("192.0.2.10")))
        .thenReturn(MavenResponse.created());
    RepositoryContentController controller = controller(
        runtimes, ansible, new AnsibleGalaxyMultipartReader(1024, 1024));
    MockHttpServletRequest put = request("PUT", "/repository/ansible/" + path);
    put.setRemoteAddr("192.0.2.10");
    put.setContent("archive".getBytes(StandardCharsets.UTF_8));

    assertEquals(201, controller.put("ansible", put, "application/gzip")
        .getStatusCode().value());
    verify(ansible).putArtifact(eq(runtime), eq(path), any(), eq("anonymous"), eq("192.0.2.10"));

    MockHttpServletRequest queried = request("PUT", "/repository/ansible/" + path);
    queried.setQueryString("overwrite=true");
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
        () -> controller.put("ansible", queried, "application/gzip"));
    assertThrows(AnsibleGalaxyExceptions.MethodNotAllowed.class,
        () -> controller.delete("ansible", request("DELETE", "/repository/ansible/" + path)));
  }

  @Test
  void routesCurrentAndLegacyMultipartPublication() {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = runtimes(runtime);
    AnsibleGalaxyService ansible = mock(AnsibleGalaxyService.class);
    byte[] responseBody = "{\"task\":\"task-1\"}".getBytes(StandardCharsets.UTF_8);
    when(ansible.publish(
        eq(runtime), eq("api/v3/artifacts/collections/"), eq(null),
        eq("https://repo.example/repository/ansible"), any(),
        eq(FILENAME), eq(SHA256), eq("anonymous"), eq("192.0.2.20"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(responseBody), responseBody.length,
            "application/json", null, null).withStatus(202));
    RepositoryContentController controller = controller(
        runtimes, ansible, new AnsibleGalaxyMultipartReader(1024, 1024));
    MockHttpServletRequest request = request(
        "POST", "/repository/ansible/api/v3/artifacts/collections/");
    request.setRemoteAddr("192.0.2.20");
    request.setContentType("multipart/form-data; boundary=galaxy-boundary");
    request.setContent(multipartBody());

    ResponseEntity<?> response = controller.post("ansible", request);

    assertEquals(202, response.getStatusCode().value());
    assertEquals("{\"task\":\"task-1\"}",
        new String((byte[]) response.getBody(), StandardCharsets.UTF_8));
    verify(ansible).publish(
        eq(runtime), eq("api/v3/artifacts/collections/"), eq(null),
        eq("https://repo.example/repository/ansible"), any(),
        eq(FILENAME), eq(SHA256), eq("anonymous"), eq("192.0.2.20"), eq(false));
    verify(ansible).validatePublishRequest(
        runtime, "api/v3/artifacts/collections/", null);
  }

  @Test
  void rejectsNonHostedPublicationBeforeReadingTheMultipartBody() throws Exception {
    for (RepositoryType type : List.of(RepositoryType.PROXY, RepositoryType.GROUP)) {
      for (String contentType : List.of(
          "multipart/form-data; boundary=x", "application/gzip")) {
        RepositoryRuntime runtime = nonHosted(type);
        AnsibleGalaxyService ansible = mock(AnsibleGalaxyService.class);
        AnsibleGalaxyMultipartReader reader = mock(AnsibleGalaxyMultipartReader.class);
        RepositoryContentController controller = controller(runtimes(runtime), ansible, reader);
        MockHttpServletRequest request = request(
            "POST", "/repository/ansible/api/v3/artifacts/collections/");
        request.setContentType(contentType);

        assertThrows(AnsibleGalaxyExceptions.NotFound.class,
            () -> controller.post("ansible", request));

        verify(reader, never()).read(any());
        verify(ansible, never()).validatePublishRequest(any(), any(), any());
        verify(ansible, never()).publish(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
      }
    }
  }

  @Test
  void validatesTheHostedPublishPathBeforeReadingTheMultipartBody() throws Exception {
    RepositoryRuntime runtime = hosted();
    AnsibleGalaxyService ansible = mock(AnsibleGalaxyService.class);
    AnsibleGalaxyMultipartReader reader = mock(AnsibleGalaxyMultipartReader.class);
    doThrow(new AnsibleGalaxyExceptions.MethodNotAllowed("unsupported"))
        .when(ansible).validatePublishRequest(runtime, "api/v3/collections/acme/tools/", null);
    RepositoryContentController controller = controller(runtimes(runtime), ansible, reader);
    MockHttpServletRequest request = request(
        "POST", "/repository/ansible/api/v3/collections/acme/tools/");
    request.setContentType("multipart/form-data; boundary=x");

    assertThrows(AnsibleGalaxyExceptions.MethodNotAllowed.class,
        () -> controller.post("ansible", request));

    verify(reader, never()).read(any());
    verify(ansible, never()).publish(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
  }

  @Test
  void rejectsInvalidMultipartAndUnavailableOptionalServices() throws Exception {
    RepositoryRuntime runtime = hosted();
    RepositoryRuntimeRegistry runtimes = runtimes(runtime);
    AnsibleGalaxyService ansible = mock(AnsibleGalaxyService.class);
    RepositoryContentController controller = controller(runtimes, ansible, null);
    MockHttpServletRequest plain = request(
        "POST", "/repository/ansible/api/v3/artifacts/collections/");
    plain.setContentType("application/gzip");
    assertThrows(AnsibleGalaxyExceptions.UnsupportedMediaType.class,
        () -> controller.post("ansible", plain));
    verify(ansible, never()).publish(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false));

    MockHttpServletRequest multipart = request(
        "POST", "/repository/ansible/api/v3/artifacts/collections/");
    multipart.setContentType("multipart/form-data; boundary=x");
    assertThrows(IllegalStateException.class, () -> controller.post("ansible", multipart));

    RepositoryContentController unavailable = controller(runtimes, null, null);
    assertThrows(IllegalStateException.class, () -> unavailable.head(
        "ansible", request("HEAD", "/repository/ansible/api/")));
  }

  @Test
  void mapsMultipartSpoolIoFailuresToBadRequest() throws Exception {
    RepositoryRuntime runtime = hosted();
    AnsibleGalaxyService ansible = mock(AnsibleGalaxyService.class);
    AnsibleGalaxyMultipartReader reader = mock(AnsibleGalaxyMultipartReader.class);
    AnsibleGalaxyMultipartReader.Upload upload = mock(AnsibleGalaxyMultipartReader.Upload.class);
    when(reader.read(any())).thenReturn(upload);
    when(upload.openStream()).thenThrow(new IOException("spool closed"));
    RepositoryContentController controller = controller(runtimes(runtime), ansible, reader);
    MockHttpServletRequest request = request(
        "POST", "/repository/ansible/api/v3/artifacts/collections/");
    request.setContentType("multipart/form-data; boundary=x");

    AnsibleGalaxyExceptions.BadRequest failure = assertThrows(
        AnsibleGalaxyExceptions.BadRequest.class, () -> controller.post("ansible", request));
    assertTrue(failure.getMessage().contains("Unable to read"));
  }

  private static RepositoryContentController controller(
      RepositoryRuntimeRegistry runtimes,
      AnsibleGalaxyService ansible,
      AnsibleGalaxyMultipartReader multipart) {
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
    if (ansible != null) controller.setAnsibleGalaxyService(ansible);
    if (multipart != null) controller.setAnsibleGalaxyMultipartReader(multipart);
    return controller;
  }

  private static RepositoryRuntimeRegistry runtimes(RepositoryRuntime runtime) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return runtimes;
  }

  private static RepositoryRuntime hosted() {
    return new RepositoryRuntime(
        1L, "ansible", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED,
        "ansiblegalaxy-hosted", true, 1L, "ALLOW_ONCE", null, null, true,
        null, 60, 60, List.of());
  }

  private static RepositoryRuntime nonHosted(RepositoryType type) {
    return new RepositoryRuntime(
        2L, "ansible", RepositoryFormat.ANSIBLEGALAXY, type,
        "ansiblegalaxy-" + type.name().toLowerCase(), true, 1L, "ALLOW_ONCE",
        null, null, true,
        type == RepositoryType.PROXY ? "https://galaxy.example/" : null,
        60, 60, List.of());
  }

  private static MockHttpServletRequest request(String method, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setScheme("https");
    request.setServerName("repo.example");
    request.setServerPort(443);
    return request;
  }

  private static byte[] multipartBody() {
    String boundary = "galaxy-boundary";
    String body = "--" + boundary + "\r\n"
        + "Content-Disposition: file; name=\"file\"; filename=\"" + FILENAME + "\"\r\n"
        + "Content-Type: application/gzip\r\n\r\n"
        + "archive\r\n--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
        + SHA256 + "\r\n--" + boundary + "--\r\n";
    return body.getBytes(StandardCharsets.ISO_8859_1);
  }
}
