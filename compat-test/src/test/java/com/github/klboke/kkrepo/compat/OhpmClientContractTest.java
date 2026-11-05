package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPath;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPathParser;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPublishMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmUnpublishRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfSystemProperty(named = "kkrepo.ohpm.client", matches = "true")
class OhpmClientContractTest {
  private static final String PREFIX = "/repository/ohpm/";
  private static final String TOKEN = "ohpm-contract-fixture-token";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final OhpmPathParser PARSER = new OhpmPathParser();

  @TempDir Path temporary;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger invocations = new AtomicInteger();
  private HttpServer server;
  private String registry;
  private int streamStatus = 200;
  private boolean retryNextPublish;
  private volatile ObjectNode metadata;
  private volatile OhpmFixture fixture;

  @BeforeEach
  void startRecordingRegistry() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registry = "http://127.0.0.1:" + server.getAddress().getPort() + PREFIX;
    server.createContext(PREFIX, this::record);
    server.start();
    Files.writeString(temporary.resolve(".ohpmrc"), """
        registry=%s
        publish_registry=%s
        //127.0.0.1:%d%s:_auth=%s
        cache=%s
        log_level=error
        fetch_timeout=5000
        retry_times=0
        http_proxy=
        https_proxy=
        no_proxy=127.0.0.1,localhost
        use_stream_threshold_size=5
        """.formatted(registry, registry, server.getAddress().getPort(), PREFIX, TOKEN,
            temporary.resolve("cache")));
    String version = run("--version").trim();
    assertEquals(System.getProperty("kkrepo.ohpm.expectedVersion", "6.0.1"), version,
        "Pin the actual tested client with -Dkkrepo.ohpm.expectedVersion");
  }

  @AfterEach
  void stopRecordingRegistry() {
    if (server != null) server.stop(0);
  }

  @Test
  void pingTagMutationsAndUnpublishUseTheDocumentedRoutesAndBodies() throws Exception {
    run("ping", "--registry", registry);
    assertEquals(OhpmPath.Kind.PING, parsed(requests.getLast()).kind());
    run("dist-tags", "add", "@kkrepo/fixture@1.2.3", "beta", "--publish_registry", registry);
    Request add = requests.getLast();
    assertEquals("POST", add.method());
    assertEquals(OhpmPath.Kind.DIST_TAG, parsed(add).kind());
    assertEquals("beta", parsed(add).tag());
    assertEquals(MAPPER.readTree("{\"version\":\"1.2.3\"}"), MAPPER.readTree(add.body()));
    assertEquals(TOKEN, add.authorization());
    run("dist-tags", "update", "@kkrepo/fixture@2.0.0", "beta", "--publish_registry", registry);
    assertEquals("PUT", requests.getLast().method());
    assertEquals("2.0.0", MAPPER.readTree(requests.getLast().body()).path("version").textValue());
    run("dist-tags", "remove", "@kkrepo/fixture", "beta", "--publish_registry", registry);
    assertEquals("DELETE", requests.getLast().method());
    run("unpublish", "@kkrepo/fixture@1.2.3", "--publish_registry", registry);
    assertEquals(OhpmPath.Kind.UNPUBLISH, parsed(requests.getLast()).kind());
    assertEquals("1.2.3", OhpmUnpublishRequest.parse(parsed(requests.getLast()),
        MAPPER.readTree(requests.getLast().body())).version());
    run("unpublish", "@kkrepo/fixture", "--force", "--publish_registry", registry);
    assertEquals(OhpmPath.Kind.UNPUBLISH, parsed(requests.getLast()).kind());
    assertNull(OhpmUnpublishRequest.parse(parsed(requests.getLast()),
        MAPPER.readTree(requests.getLast().body())).version());
  }

  @ParameterizedTest
  @CsvSource({"false, false, 1.2.3", "true, false, 1.2.3", "false, true, 1.2.3", "true, true, 1.2.3",
      "false, false, v1.2.3", "false, false, 1.2.3-beta.1+build.4"})
  void publicationMatchesTheProtocolModelsAndAttachmentChecksums(boolean hsp, boolean stream, String version)
      throws Exception {
    fixture = OhpmFixture.create(temporary, "@kkrepo/fixture", version, hsp, stream);
    run("publish", fixture.archive().toString(), "--publish_registry", registry, "--tag", "beta");
    Request publication = requests.stream().filter(request ->
        parsed(request).kind() == (stream ? OhpmPath.Kind.STREAM_PUBLISH : OhpmPath.Kind.PUBLISH))
        .findFirst().orElseThrow();
    assertEquals(TOKEN, publication.authorization());
    ObjectNode body = (ObjectNode) MAPPER.readTree(stream ? part(publication, "metadata") : publication.body());
    OhpmPublishMetadata parsed = OhpmPublishMetadata.parse(OhpmPackageId.parse(fixture.name()), body);
    assertEquals(fixture.version(), parsed.version());
    assertEquals(hsp, parsed.isHsp());
    assertEquals(version, body.path("dist-tags").path("beta").textValue());
    assertTrue(parsed.harIntegrity().matches(new ByteArrayInputStream(fixture.har())));
    if (hsp) assertTrue(parsed.hspIntegrity().matches(new ByteArrayInputStream(fixture.hsp())));
    if (stream) {
      assertFalse(body.has("_attachments"));
      assertArrayEquals(Files.readAllBytes(fixture.archive()), part(publication, "pkg_stream"));
    } else {
      assertAttachment(body, ".har", fixture.har());
      if (hsp) assertAttachment(body, ".hsp", fixture.hsp());
      assertEquals(hsp ? 2 : 1, body.path("_attachments").size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingStreamEndpointFallsBackToNormalPublish(boolean hsp) throws Exception {
    streamStatus = 404;
    fixture = OhpmFixture.create(temporary, "@kkrepo/fixture", "1.2.3", hsp, true);
    run("publish", fixture.archive().toString(), "--publish_registry", registry);
    List<OhpmPath.Kind> publicationKinds = requests.stream().map(OhpmClientContractTest::parsed)
        .map(OhpmPath::kind).filter(kind ->
            kind == OhpmPath.Kind.STREAM_PUBLISH || kind == OhpmPath.Kind.PUBLISH).toList();
    assertEquals(List.of(OhpmPath.Kind.STREAM_PUBLISH, OhpmPath.Kind.PUBLISH), publicationKinds);
    assertAttachment(MAPPER.readTree(requests.getLast().body()), ".har", fixture.har());
    if (hsp) assertAttachment(MAPPER.readTree(requests.getLast().body()), ".hsp", fixture.hsp());
  }

  @Test
  void retryableResponseIsReportedAndAnExplicitRetryResendsTheSameVersion() throws Exception {
    retryNextPublish = true;
    fixture = OhpmFixture.create(temporary, "@kkrepo/fixture", "1.2.3", false, false);
    String failure = runExpecting(1, "publish", fixture.archive().toString(), "--publish_registry", registry);
    assertTrue(failure.contains("598"), failure);
    assertEquals(1, requests.stream().filter(request -> parsed(request).kind() == OhpmPath.Kind.PUBLISH).count());
    run("publish", fixture.archive().toString(), "--publish_registry", registry);
    List<Request> publications = requests.stream()
        .filter(request -> parsed(request).kind() == OhpmPath.Kind.PUBLISH).toList();
    assertEquals(2, publications.size());
    assertEquals(MAPPER.readTree(publications.getFirst().body()), MAPPER.readTree(publications.getLast().body()));
  }

  @Test
  void installReadsMetadataAndVerifiesThePublishedHar() throws Exception {
    fixture = OhpmFixture.create(temporary, "@kkrepo/fixture", "1.2.3", false, false);
    run("publish", fixture.archive().toString(), "--publish_registry", registry);
    Files.writeString(temporary.resolve("oh-package.json5"), """
        {"name":"fixture-consumer","version":"1.0.0","dependencies":{"@kkrepo/fixture":"1.2.3"}}
        """);
    run("install", "--registry", registry);
    assertTrue(requests.stream().anyMatch(request -> parsed(request).kind() == OhpmPath.Kind.METADATA));
    Request download = requests.stream().filter(request -> parsed(request).kind() == OhpmPath.Kind.DOWNLOAD)
        .findFirst().orElseThrow();
    assertEquals(TOKEN, download.authorization());
    assertTrue(Files.exists(temporary.resolve("oh_modules/@kkrepo/fixture/Index.ets")));
  }

  private void assertAttachment(JsonNode body, String extension, byte[] expected) {
    JsonNode attachment = body.path("_attachments").path(fixture.name() + "-" + fixture.version() + extension);
    assertEquals(expected.length, attachment.path("length").longValue());
    assertArrayEquals(expected, Base64.getDecoder().decode(attachment.path("data").textValue()));
  }

  private void record(HttpExchange exchange) throws IOException {
    try (exchange) {
      Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
          exchange.getRequestHeaders().getFirst("Authorization"),
          exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes());
      requests.add(request);
      OhpmPath path = parsed(request);
      int status = path.kind() == OhpmPath.Kind.STREAM_PUBLISH ? streamStatus : 200;
      byte[] response = "{\"code\":200,\"message\":\"success\"}".getBytes(StandardCharsets.UTF_8);
      if (path.kind() == OhpmPath.Kind.UNKNOWN) status = 404;
      if (path.kind() == OhpmPath.Kind.PUBLISH && retryNextPublish) {
        retryNextPublish = false;
        status = 598;
        response = "{\"code\":598,\"error\":\"Retry fixture\"}".getBytes(StandardCharsets.UTF_8);
      }
      if (path.kind() == OhpmPath.Kind.PUBLISH && status == 200) {
        metadata = (ObjectNode) MAPPER.readTree(request.body());
        metadata.remove("_attachments");
        metadata.put("_rev", 1);
        metadata.putObject("time").put("created", "2026-01-01T00:00:00Z")
            .put("modified", "2026-01-01T00:00:00Z").put(fixture.version(), "2026-01-01T00:00:00Z");
        ((ObjectNode) metadata.path("versions").path(fixture.version()).path("dist"))
            .put("tarball", registry + fixture.name() + "/-/" + fixture.name() + "-" + fixture.version() + ".har");
      }
      if (path.kind() == OhpmPath.Kind.METADATA) {
        if (metadata == null) {
          status = 404;
          response = "{\"error\":\"Package not found\"}".getBytes(StandardCharsets.UTF_8);
        } else {
          response = MAPPER.writeValueAsBytes(metadata);
        }
      }
      if (path.kind() == OhpmPath.Kind.DOWNLOAD) response = fixture.har();
      exchange.getResponseHeaders().set("Content-Type",
          path.kind() == OhpmPath.Kind.DOWNLOAD ? "application/octet-stream" : "application/json");
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
    }
  }

  private String run(String... arguments) throws Exception {
    return runExpecting(0, arguments);
  }

  private String runExpecting(int exitCode, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("kkrepo.ohpm.command", "ohpm"));
    command.addAll(List.of(arguments));
    Path output = temporary.resolve("client-" + invocations.incrementAndGet() + ".log");
    Process process = new ProcessBuilder(command).directory(temporary.toFile())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    boolean completed = process.waitFor(45, TimeUnit.SECONDS);
    if (!completed) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    String text = Files.readString(output);
    assertTrue(completed, "OHPM command timed out: " + String.join(" ", arguments));
    assertEquals(exitCode, process.exitValue(), text);
    return text;
  }

  private static OhpmPath parsed(Request request) {
    return PARSER.parse(request.method(), request.path().substring(PREFIX.length()));
  }

  private static byte[] part(Request request, String name) {
    assertNotNull(request.contentType());
    assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
    String boundary = request.contentType().substring(request.contentType().indexOf("boundary=") + 9);
    String body = new String(request.body(), StandardCharsets.ISO_8859_1);
    int disposition = body.indexOf("name=\"" + name + "\"");
    assertTrue(disposition >= 0, "Missing multipart field " + name);
    int start = body.indexOf("\r\n\r\n", disposition) + 4;
    int end = body.indexOf("\r\n--" + boundary, start);
    assertTrue(start >= 4 && end >= start, "Malformed multipart field " + name);
    return body.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
  }

  private record Request(String method, String path, String authorization, String contentType, byte[] body) {}
}
