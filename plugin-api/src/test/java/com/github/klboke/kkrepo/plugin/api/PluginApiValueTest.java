package com.github.klboke.kkrepo.plugin.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginApiValueTest {

  @Test
  void remoteRequestNormalizesDefaultsAndCopiesMutableInputs() {
    byte[] body = {1, 2};
    Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
    headers.put("X-Test", List.of("one"));
    headers.put("Skipped", null);
    RemoteRequest request = new RemoteRequest(URI.create("https://example.test/a"), " post ",
        headers, body, null);
    body[0] = 9;

    assertEquals("POST", request.method());
    assertEquals(Duration.ofSeconds(30), request.timeout());
    assertArrayEquals(new byte[] {1, 2}, request.body());
    assertEquals(List.of("one"), request.headers().get("X-Test"));
    assertFalse(request.headers().containsKey("Skipped"));

    RemoteRequest defaults = new RemoteRequest(URI.create("https://example.test/b"), null, null, null, null);
    assertEquals("GET", defaults.method());
    assertArrayEquals(new byte[0], defaults.body());
    assertTrue(defaults.headers().isEmpty());
    assertThrows(NullPointerException.class,
        () -> new RemoteRequest(null, "GET", Map.of(), null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new RemoteRequest(URI.create("https://example.test"), "GET", Map.of(), null,
            Duration.ZERO));
    assertThrows(IllegalArgumentException.class,
        () -> new RemoteRequest(URI.create("https://example.test"), "GET", Map.of(), null,
            Duration.ofSeconds(-1)));
  }

  @Test
  void remoteResponseValidatesStatusAndCopiesMutableInputs() {
    byte[] body = {3};
    Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
    headers.put("X-Test", List.of("one"));
    headers.put("Skipped", null);
    RemoteResponse response = new RemoteResponse(204, headers, body);
    body[0] = 9;

    assertTrue(response.successful());
    assertArrayEquals(new byte[] {3}, response.body());
    assertEquals(List.of("one"), response.headers().get("X-Test"));
    assertFalse(response.headers().containsKey("Skipped"));
    assertFalse(new RemoteResponse(404, null, null).successful());
    assertTrue(new RemoteResponse(299, Map.of(), null).successful());
    assertThrows(IllegalArgumentException.class, () -> new RemoteResponse(99, Map.of(), null));
    assertThrows(IllegalArgumentException.class, () -> new RemoteResponse(600, Map.of(), null));
  }

  @Test
  void pluginDescriptorValidatesRequiredFieldsAndDefaultsCapabilities() {
    PluginDescriptor descriptor = new PluginDescriptor(
        " plugin ", " 1.0 ", " 1.0 ", " Example ", null);

    assertEquals("plugin", descriptor.id());
    assertEquals("1.0", descriptor.version());
    assertEquals("1.0", descriptor.apiVersion());
    assertEquals("Example", descriptor.mainClass());
    assertEquals(List.of(), descriptor.capabilities());
    assertEquals(List.of("repository.format"), new PluginDescriptor(
        "p", "1", "1", "E", List.of("repository.format")).capabilities());

    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(null, "1", "1", "E", null));
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(" ", "1", "1", "E", null));
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor("p", null, "1", "E", null));
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor("p", "1", " ", "E", null));
    assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor("p", "1", "1", null, null));
  }

  @Test
  void repositoryFormatDescriptorValidatesAndDefaults() {
    RepositoryFormatDescriptor descriptor = new RepositoryFormatDescriptor(
        new FormatId("demo"), " Demo ", null, null);
    assertEquals("Demo", descriptor.name());
    assertEquals("", descriptor.description());
    assertEquals(Set.of(), descriptor.capabilities());

    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryFormatDescriptor(null, "Demo", "", Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new RepositoryFormatDescriptor(new FormatId("demo"), " ", "", Set.of()));
  }

  @Test
  void formatIdAndKindNormalizeAndRejectInvalidValues() {
    assertEquals("raw", new FormatId(" RAW ").value());
    assertEquals("raw", new FormatId("raw").toString());
    assertThrows(IllegalArgumentException.class, () -> new FormatId(null));
    assertThrows(IllegalArgumentException.class, () -> new FormatId(" "));
    assertThrows(IllegalArgumentException.class, () -> new FormatId("bad/id"));
    assertThrows(IllegalArgumentException.class, () -> new FormatId("-bad"));

    assertEquals(RepositoryKind.HOSTED, RepositoryKind.from(" hosted "));
    assertEquals(RepositoryKind.PROXY, RepositoryKind.from("PROXY"));
    assertThrows(IllegalArgumentException.class, () -> RepositoryKind.from(null));
    assertThrows(IllegalArgumentException.class, () -> RepositoryKind.from(" "));
    assertThrows(IllegalArgumentException.class, () -> RepositoryKind.from("OTHER"));
  }

  @Test
  void repositoryPolicyTrimsEveryField() {
    RepositoryPolicy policy = new RepositoryPolicy(" ALLOW ", null, " STRICT ");
    assertEquals("ALLOW", policy.writePolicy());
    assertEquals("", policy.versionPolicy());
    assertEquals("STRICT", policy.layoutPolicy());
  }

  @Test
  void multipartPartCopiesContentAndDefaultsNull() {
    byte[] content = {1};
    MultipartPart part = new MultipartPart("file", "a.bin", "application/octet-stream", content);
    content[0] = 9;
    assertArrayEquals(new byte[] {1}, part.content());
    assertEquals("file", part.name());
    assertEquals("a.bin", part.filename());
    assertEquals("application/octet-stream", part.contentType());
    assertArrayEquals(new byte[0], new MultipartPart("file", "", "", null).content());
  }

  @Test
  void remoteTransportConfigCopiesNullSettings() {
    assertTrue(new RemoteTransportConfig(null).settings().isEmpty());
    assertEquals("one", new RemoteTransportConfig(Map.of("k", "one")).settings().get("k"));
  }

  @Test
  void repositoryDescriptorDefaultsAndValidatesRequiredFields() {
    RepositoryDescriptor descriptor = new RepositoryDescriptor(
        1L, " repo ", new FormatId("raw"), RepositoryKind.HOSTED, null, true, 1L,
        "ALLOW", "RELEASE", "STRICT", null, null);
    assertEquals("repo", descriptor.name());
    assertEquals("", descriptor.recipeName());
    assertTrue(descriptor.attributes().isEmpty());

    assertThrows(IllegalArgumentException.class, () -> new RepositoryDescriptor(
        1L, " ", new FormatId("raw"), RepositoryKind.HOSTED, "", true, null,
        null, null, null, null, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new RepositoryDescriptor(
        1L, "repo", null, RepositoryKind.HOSTED, "", true, null,
        null, null, null, null, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new RepositoryDescriptor(
        1L, "repo", new FormatId("raw"), null, "", true, null,
        null, null, null, null, Map.of()));
  }

  @Test
  void pluginExceptionAndApiVersionAreStable() {
    assertEquals("1.0", PluginApiVersion.CURRENT);
    assertEquals("message", new PluginException("message").getMessage());
    Throwable cause = new IllegalStateException("cause");
    assertEquals(cause, new PluginException("message", cause).getCause());
  }
}
