package com.github.klboke.kkrepo.plugin.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryProtocolContractTest {
  private static final FormatId RAW = new FormatId("raw");

  @Test
  void requestNormalizesPathAndDefensivelyCopiesBody() {
    byte[] body = {'a', 'b'};
    RepositoryRequest request = new RepositoryRequest(
        descriptor(),
        "repo",
        "/snapshots//a",
        "post",
        Map.of("X-Test", List.of("1")),
        Map.of("q", "v"),
        body,
        null,
        Map.of());

    assertEquals("snapshots//a", request.path());
    assertEquals("POST", request.method());
    assertEquals("ab", request.bodyText());
    body[0] = 'z';
    assertArrayEquals(new byte[] {'a', 'b'}, request.body());
    assertEquals(List.of("1"), request.headers().get("X-Test"));
    assertEquals("v", request.queryParameters().get("q"));
  }

  @Test
  void routeMatchesExactPrefixAndAnyPath() {
    assertTrue(RepositoryProtocolRoute.exactPath(RAW, RepositoryKind.HOSTED, "GET", "asset", 1)
        .matches(RAW, RepositoryKind.HOSTED, "get", "/asset"));
    assertTrue(RepositoryProtocolRoute.exactPath(RAW, RepositoryKind.HOSTED, "GET", "api/**", 1)
        .matches(RAW, RepositoryKind.HOSTED, "GET", "api/v1/chart"));
    assertFalse(RepositoryProtocolRoute.exactPath(RAW, RepositoryKind.HOSTED, "GET", "api/**", 1)
        .matches(RAW, RepositoryKind.HOSTED, "GET", "other"));
    assertTrue(RepositoryProtocolRoute.anyPath(RAW, RepositoryKind.PROXY, "GET", 1)
        .matches(RAW, RepositoryKind.PROXY, "GET", "anything"));
  }

  @Test
  void routeRejectsUnsupportedWildcards() {
    assertThrows(IllegalArgumentException.class,
        () -> RepositoryProtocolRoute.exactPath(
            RAW, RepositoryKind.HOSTED, "GET", "a/*/b", 1));
  }

  @Test
  void responseDefensivelyCopiesHeadersAndBody() {
    byte[] body = {1, 2};
    RepositoryResponse response = new RepositoryResponse(
        201, Map.of("ETag", List.of("abc")), body);
    body[0] = 9;

    assertEquals(201, response.status());
    assertEquals(List.of("abc"), response.headers().get("ETag"));
    assertArrayEquals(new byte[] {1, 2}, response.body());
  }

  @Test
  void headersAreLookedUpCaseInsensitively() {
    RepositoryRequest request = new RepositoryRequest(descriptor(), "repo", "a", "GET",
        Map.of("x-kkrepo-lfs-upload", List.of("id", "other"), "Empty", List.of()), Map.of(), null, null,
        Map.of());

    assertEquals("id", request.header("X-KkRepo-Lfs-Upload"));
    assertNull(request.header("Empty"));
    assertNull(request.header("Missing"));
    assertNull(request.header(null));
  }

  @Test
  void streamedRequestBodiesAreExposedUnbuffered() throws Exception {
    InputStream stream = new ByteArrayInputStream(new byte[] {1, 2, 3});
    RepositoryRequest streamed = new RepositoryRequest(descriptor(), "repo", "a", "PUT", Map.of(),
        Map.of(), null, null, null, Map.of(), stream);
    assertTrue(streamed.isBodyStreamed());
    assertSame(stream, streamed.openBody());
    assertArrayEquals(new byte[0], streamed.body());

    RepositoryRequest buffered = new RepositoryRequest(descriptor(), "repo", "a", "PUT", Map.of(),
        Map.of(), new byte[] {4}, null, Map.of());
    assertFalse(buffered.isBodyStreamed());
    assertArrayEquals(new byte[] {4}, buffered.openBody().readAllBytes());
    assertThrows(IllegalArgumentException.class, () -> new RepositoryRequest(descriptor(), "repo", "a",
        "PUT", Map.of(), Map.of(), new byte[] {4}, null, null, Map.of(), stream));
  }

  @Test
  void streamingResponsesCarryNoInMemoryBody() throws Exception {
    RepositoryResponse response = RepositoryResponse.streaming(206,
        Map.of("Content-Range", List.of("bytes 0-1/2")), output -> output.write(new byte[] {7, 8}));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.stream().writeTo(output);

    assertTrue(response.isStreaming());
    assertArrayEquals(new byte[] {7, 8}, output.toByteArray());
    assertArrayEquals(new byte[0], response.body());
    assertFalse(RepositoryResponse.ok(new byte[] {1}).isStreaming());
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryResponse(200, Map.of(), new byte[] {1}, out -> { }));
    assertThrows(NullPointerException.class, () -> RepositoryResponse.streaming(200, Map.of(), null));
  }

  @Test
  void descriptorRequiresFormatAndKind() {
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryDescriptor(
            1, "repo", null, RepositoryKind.HOSTED, "", true, null,
            null, null, null, null, Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryDescriptor(
            1, "repo", RAW, null, "", true, null,
            null, null, null, null, Map.of()));
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1,
        "repo",
        RAW,
        RepositoryKind.HOSTED,
        "raw",
        true,
        1L,
        "ALLOW",
        "RELEASE",
        "STRICT",
        null,
        Map.of());
  }
}
