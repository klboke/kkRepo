package com.github.klboke.kkrepo.plugin.gitlfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsBatchResult;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsBatchResult.ObjectResult;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsByteRange;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsHostOperations;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsStoredObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitLfsProtocolPluginTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OID = "a".repeat(64);
  private static final String OTHER = "b".repeat(64);
  private static final Instant UPDATED = Instant.parse("2026-01-01T00:00:00Z");
  private final GitLfsProtocolPlugin plugin = new GitLfsProtocolPlugin();
  private final FakeHost host = new FakeHost();

  @Test
  void registersTheGitLfsFormatAndEveryHostedRouteAtTheNormalOrder() {
    RecordingContext context = new RecordingContext();
    plugin.contribute(context);

    assertSame(plugin, context.handler);
    assertSame(plugin, context.format);
    assertEquals("gitlfs", plugin.formatDescriptor().id().value());
    assertEquals(List.of("GET", "HEAD", "POST", "PUT", "DELETE"),
        plugin.routes().stream().map(RepositoryProtocolRoute::method).toList());
    for (RepositoryProtocolRoute route : plugin.routes()) {
      assertEquals(new FormatId("gitlfs"), route.format());
      assertEquals(RepositoryKind.HOSTED, route.type());
      assertEquals(RepositoryProtocolRoute.ANY_PATH, route.pathPattern());
      assertEquals(0, route.order());
    }
    assertTrue(plugin.streamsRequestBody());
    assertEquals(GitLfsProtocolPlugin.class.getName(), plugin.descriptor().mainClass());
  }

  @Test
  void batchParsesTheBodyAndRendersEveryHostOutcome() throws Exception {
    host.batchResult = new GitLfsBatchResult("https://repo.example/repository/lfs/", List.of(
        ObjectResult.upload(OID, 3, "11111111-1111-1111-1111-111111111111", 900),
        ObjectResult.present(OTHER, 4),
        ObjectResult.download(OID, 3),
        ObjectResult.error("", 0, 422, "Invalid SHA-256 OID")));
    RepositoryResponse response = handle(request("POST", GitLfsProtocol.BATCH_PATH,
        "{\"operation\":\"upload\",\"objects\":[{\"oid\":\"" + OID + "\",\"size\":3}]}",
        "Content-Type", GitLfsProtocol.MEDIA_TYPE));

    assertEquals(200, response.status());
    assertEquals(List.of(GitLfsProtocol.MEDIA_TYPE), response.headers().get("Content-Type"));
    assertEquals(List.of("no-store"), response.headers().get("Cache-Control"));
    assertEquals(new GitLfsProtocol.Batch("upload",
        List.of(new GitLfsProtocol.ObjectRequest(OID, 3, null))), host.batch);
    JsonNode body = JSON.readTree(response.body());
    assertEquals("basic", body.path("transfer").asText());
    JsonNode upload = body.path("objects").get(0);
    assertEquals("https://repo.example/repository/lfs/" + OID,
        upload.path("actions").path("upload").path("href").asText());
    assertEquals("11111111-1111-1111-1111-111111111111",
        upload.path("actions").path("upload").path("header").path("X-KkRepo-Lfs-Upload").asText());
    assertEquals(900, upload.path("actions").path("upload").path("expires_in").asInt());
    assertEquals("https://repo.example/repository/lfs/" + OID + "/verify",
        upload.path("actions").path("verify").path("href").asText());
    assertFalse(upload.path("authenticated").asBoolean(true));
    assertEquals(JSON.readTree("{\"oid\":\"" + OTHER + "\",\"size\":4}"), body.path("objects").get(1));
    assertEquals("https://repo.example/repository/lfs/" + OID,
        body.path("objects").get(2).path("actions").path("download").path("href").asText());
    assertEquals(JSON.readTree("{\"oid\":\"\",\"size\":0,\"error\":{\"code\":422,"
        + "\"message\":\"Invalid SHA-256 OID\"}}"), body.path("objects").get(3));
  }

  @Test
  void batchErrorsUseTheGitLfsEnvelopeAndAuthenticationChallenge() throws Exception {
    host.batchFailure = new GitLfsException(401, "Authentication required");
    RepositoryResponse response = handle(request("POST", GitLfsProtocol.BATCH_PATH,
        "{\"operation\":\"download\",\"objects\":[]}"));

    assertEquals(401, response.status());
    assertEquals(List.of("Basic realm=\"Git LFS\""), response.headers().get("LFS-Authenticate"));
    assertEquals(List.of("Basic realm=\"Git LFS\""), response.headers().get("WWW-Authenticate"));
    assertEquals(List.of(GitLfsProtocol.MEDIA_TYPE), response.headers().get("Content-Type"));
    assertEquals("Authentication required", JSON.readTree(response.body()).path("message").asText());

    RepositoryResponse invalid = handle(request("POST", GitLfsProtocol.BATCH_PATH,
        "{\"operation\":\"delete\",\"objects\":[]}"));
    assertEquals(422, invalid.status());
  }

  @Test
  void offlineRepositoriesAndLocksAnswerBeforeTheHostIsCalled() throws Exception {
    RepositoryResponse offline = plugin.handle(request(descriptor(false), "GET", OID, null));
    assertEquals(503, offline.status());
    assertEquals(List.of("5"), offline.headers().get("Retry-After"));
    assertEquals("Repository is offline", JSON.readTree(offline.body()).path("message").asText());

    RepositoryResponse locks = handle(request("POST", "info/lfs/locks/verify", "{}"));
    assertEquals(501, locks.status());
    assertEquals("Git LFS file locking is not supported",
        JSON.readTree(locks.body()).path("message").asText());
    assertEquals(404, handle(request("GET", "not-an-oid", null)).status());
    assertTrue(host.calls.isEmpty());
  }

  @Test
  void verifyChecksMediaTypeBodyAndUrlBeforeAskingTheHost() throws Exception {
    String body = "{\"oid\":\"" + OID + "\",\"size\":3}";
    assertEquals(415, handle(request("POST", OID + "/verify", body, "Content-Type", "text/plain"))
        .status());
    assertEquals(422, handle(request("POST", OID + "/verify",
        "{\"oid\":\"" + OTHER + "\",\"size\":3}", "content-type", GitLfsProtocol.MEDIA_TYPE)).status());
    assertTrue(host.calls.isEmpty());

    RepositoryResponse verified = handle(request("POST", OID + "/verify", body,
        "content-type", GitLfsProtocol.MEDIA_TYPE + "; charset=utf-8"));
    assertEquals(200, verified.status());
    assertEquals(JSON.readTree(body), JSON.readTree(verified.body()));
    assertEquals(List.of("verify " + OID + " 3"), host.calls);

    host.verifyFailure = new GitLfsException(404, "Object does not exist on the server");
    assertEquals(404, handle(request("POST", OID + "/verify", body,
        "Content-Type", GitLfsProtocol.MEDIA_TYPE)).status());
  }

  @ParameterizedTest
  @CsvSource({"GET, /verify, POST", "HEAD, /verify, POST", "PUT, /verify, POST", "DELETE, /verify, POST",
      "POST, '', 'GET,HEAD,PUT'", "DELETE, '', 'GET,HEAD,PUT'"})
  void unsupportedMethodsAdvertiseTheMethodsOfTheTargetRoute(String method, String suffix, String allow)
      throws Exception {
    RepositoryResponse response = handle(request(method, OID + suffix, null));

    assertEquals(405, response.status());
    assertEquals(List.of(allow), response.headers().get("Allow"));
    if (method.equals("HEAD")) {
      assertEquals(0, response.body().length);
    } else {
      String message = JSON.readTree(response.body()).path("message").asText();
      assertEquals(suffix.isEmpty() ? "Use the administration API to delete LFS assets"
          : "Method not allowed", message);
    }
    assertTrue(host.calls.isEmpty());
  }

  @Test
  void uploadStreamsTheUnbufferedBodyWithTheActionHeadersMatchedCaseInsensitively() throws Exception {
    InputStream body = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));
    RepositoryRequest request = streamed("PUT", OID, body, Map.of(
        "x-kkrepo-lfs-upload", List.of("11111111-1111-1111-1111-111111111111"),
        "content-length", List.of("3")));

    RepositoryResponse response = plugin.handle(request);

    assertEquals(200, response.status());
    assertEquals(List.of("no-store"), response.headers().get("Cache-Control"));
    assertEquals(0, response.body().length);
    assertSame(body, host.uploadBody);
    assertEquals(List.of("upload " + OID + " 11111111-1111-1111-1111-111111111111 3"), host.calls);

    host.calls.clear();
    plugin.handle(streamed("PUT", OID, new ByteArrayInputStream(new byte[0]), Map.of()));
    assertEquals(List.of("upload " + OID + " null -1"), host.calls);

    host.uploadFailure = new GitLfsException(429, "Upload capacity exhausted; retry later");
    RepositoryResponse busy = plugin.handle(streamed("PUT", OID, new ByteArrayInputStream(new byte[0]),
        Map.of()));
    assertEquals(429, busy.status());
    assertEquals(List.of("5"), busy.headers().get("Retry-After"));
  }

  @Test
  void getStreamsTheWholeObjectWithValidators() throws Exception {
    RepositoryResponse response = handle(request("GET", OID, null));

    assertEquals(200, response.status());
    assertTrue(response.isStreaming());
    assertEquals(List.of("application/octet-stream"), response.headers().get("Content-Type"));
    assertEquals(List.of("private, no-cache"), response.headers().get("Cache-Control"));
    assertEquals(List.of("bytes"), response.headers().get("Accept-Ranges"));
    assertEquals(List.of("\"etag-sha1\""), response.headers().get("ETag"));
    assertEquals(List.of("Thu, 01 Jan 2026 00:00:00 GMT"), response.headers().get("Last-Modified"));
    assertEquals(List.of("6"), response.headers().get("Content-Length"));
    assertEquals("abcdef", written(response));
    assertEquals(List.of("find " + OID, "open " + OID + " null"), host.calls);
  }

  @Test
  void singleRangesAreServedPartiallyAndOnlyTheRequestedBytesAreOpened() throws Exception {
    RepositoryResponse partial = handle(request("GET", OID, null, "range", "bytes=1-3"));
    assertEquals(206, partial.status());
    assertEquals(List.of("bytes 1-3/6"), partial.headers().get("Content-Range"));
    assertEquals(List.of("3"), partial.headers().get("Content-Length"));
    assertEquals("bcd", written(partial));
    assertEquals("open " + OID + " 1+3", host.calls.getLast());

    assertEquals("cdef", written(handle(request("GET", OID, null, "Range", "bytes=-4"))));
    assertEquals("ef", written(handle(request("GET", OID, null, "Range", "bytes=4-"))));
    assertEquals("ef", written(handle(request("GET", OID, null, "Range", "bytes=4-999"))));
    RepositoryResponse matching = handle(request("GET", OID, null, "Range", "bytes=1-3",
        "If-Range", "\"etag-sha1\""));
    assertEquals(206, matching.status());
    RepositoryResponse byDate = handle(request("GET", OID, null, "Range", "bytes=1-3",
        "If-Range", "Thu, 01 Jan 2026 00:00:00 GMT"));
    assertEquals(206, byDate.status());
  }

  @Test
  void unsatisfiableRangesAre416WithoutOpeningTheObject() throws Exception {
    for (String range : List.of("bytes=10-12", "bytes=3-1", "items=1-3", "bytes=", "bytes=x-1")) {
      host.calls.clear();
      RepositoryResponse response = handle(request("GET", OID, null, "Range", range));
      assertEquals(416, response.status(), range);
      assertEquals(Map.of("Content-Range", List.of("bytes */6")), response.headers(), range);
      assertEquals(List.of("find " + OID), host.calls, range);
    }
  }

  @Test
  void ignoredRangesFallBackToTheWholeObject() throws Exception {
    RepositoryResponse stale = handle(request("GET", OID, null, "Range", "bytes=1-3", "If-Range", "\"old\""));
    assertEquals(200, stale.status());
    assertEquals("abcdef", written(stale));
    RepositoryResponse multiple = handle(request("GET", OID, null, "Range", "bytes=0-1,3-4"));
    assertEquals(200, multiple.status());
    assertNull(multiple.headers().get("Content-Range"));
  }

  @Test
  void headAndConditionalRequestsDoNotOpenTheObject() throws Exception {
    RepositoryResponse head = handle(request("HEAD", OID, null));
    assertEquals(200, head.status());
    assertEquals(List.of("6"), head.headers().get("Content-Length"));
    assertFalse(head.isStreaming());

    RepositoryResponse byEtag = handle(request("GET", OID, null, "if-none-match", "W/\"etag-sha1\""));
    assertEquals(304, byEtag.status());
    assertNull(byEtag.headers().get("Content-Length"));
    assertEquals(List.of("\"etag-sha1\""), byEtag.headers().get("ETag"));
    RepositoryResponse byDate = handle(request("GET", OID, null,
        "If-Modified-Since", "Thu, 01 Jan 2026 00:00:00 GMT"));
    assertEquals(304, byDate.status());
    RepositoryResponse changed = handle(request("GET", OID, null, "If-None-Match", "\"other\"",
        "If-Modified-Since", "Thu, 01 Jan 2026 00:00:00 GMT"));
    assertEquals(200, changed.status());

    assertEquals(4, host.calls.stream().filter(call -> call.startsWith("find")).count());
    assertEquals(1, host.calls.stream().filter(call -> call.startsWith("open")).count());
  }

  @Test
  void missingObjectsAndUnavailableContentUseTheErrorEnvelope() throws Exception {
    host.findFailure = new GitLfsException(404, "Object does not exist on the server");
    RepositoryResponse missing = handle(request("GET", OID, null));
    assertEquals(404, missing.status());
    assertFalse(missing.isStreaming());
    assertEquals(List.of(GitLfsProtocol.MEDIA_TYPE), missing.headers().get("Content-Type"));
    assertEquals("Object does not exist on the server",
        JSON.readTree(missing.body()).path("message").asText());
    RepositoryResponse missingHead = handle(request("HEAD", OID, null));
    assertEquals(404, missingHead.status());
    assertEquals(0, missingHead.body().length);

    host.findFailure = null;
    host.openFailure = new GitLfsException(404, "Object content is unavailable");
    assertEquals(404, handle(request("GET", OID, null)).status());
  }

  @Test
  void hostFailuresThatAreNotProtocolErrorsPropagate() {
    host.findFailure = new IllegalStateException("database down");
    assertThrows(IllegalStateException.class, () -> handle(request("GET", OID, null)));
    RepositoryRequest withoutHost = new RepositoryRequest(descriptor(true), "lfs", OID, "GET",
        Map.of(), Map.of(), null, null, RepositoryContext.empty(descriptor(true)), Map.of());
    assertThrows(IllegalStateException.class, () -> plugin.handle(withoutHost));
  }

  private RepositoryResponse handle(RepositoryRequest request) throws IOException {
    return plugin.handle(request);
  }

  private RepositoryRequest request(String method, String path, String body, String... headers) {
    return request(descriptor(true), method, path, body, headers);
  }

  private RepositoryRequest request(RepositoryDescriptor descriptor, String method, String path,
      String body, String... headers) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    for (int index = 0; index < headers.length; index += 2) {
      values.put(headers[index], List.of(headers[index + 1]));
    }
    InputStream stream = new ByteArrayInputStream(
        body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    return new RepositoryRequest(descriptor, "lfs", path, method, values, Map.of(), null, null,
        new HostContext(descriptor, host), Map.of(), stream);
  }

  private RepositoryRequest streamed(String method, String path, InputStream body,
      Map<String, List<String>> headers) {
    RepositoryDescriptor descriptor = descriptor(true);
    return new RepositoryRequest(descriptor, "lfs", path, method, headers, Map.of(), null, null,
        new HostContext(descriptor, host), Map.of(), body);
  }

  private static String written(RepositoryResponse response) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.stream().writeTo(output);
    return output.toString(StandardCharsets.UTF_8);
  }

  private static RepositoryDescriptor descriptor(boolean online) {
    return new RepositoryDescriptor(1L, "lfs", new FormatId("gitlfs"), RepositoryKind.HOSTED,
        "gitlfs-hosted", online, 1L, "ALLOW", null, null, null, Map.of());
  }

  /** In-memory host: one stored object {@code abcdef}, every call recorded. */
  private static final class FakeHost implements GitLfsHostOperations {
    private final List<String> calls = new ArrayList<>();
    private GitLfsBatchResult batchResult = new GitLfsBatchResult("http://h/", List.of());
    private GitLfsProtocol.Batch batch;
    private InputStream uploadBody;
    private RuntimeException batchFailure;
    private RuntimeException verifyFailure;
    private RuntimeException uploadFailure;
    private RuntimeException findFailure;
    private RuntimeException openFailure;

    @Override
    public long maxObjectBytes() {
      return 1024;
    }

    @Override
    public GitLfsBatchResult batch(RepositoryContext repository, GitLfsProtocol.Batch batch) {
      calls.add("batch");
      this.batch = batch;
      if (batchFailure != null) throw batchFailure;
      return batchResult;
    }

    @Override
    public void verify(RepositoryContext repository, String oid, long size) {
      calls.add("verify " + oid + " " + size);
      if (verifyFailure != null) throw verifyFailure;
    }

    @Override
    public void upload(RepositoryContext repository, String oid, String uploadId, long contentLength,
        InputStream body) {
      calls.add("upload " + oid + " " + uploadId + " " + contentLength);
      uploadBody = body;
      if (uploadFailure != null) throw uploadFailure;
    }

    @Override
    public GitLfsStoredObject find(RepositoryContext repository, String oid) {
      calls.add("find " + oid);
      if (findFailure != null) throw findFailure;
      return new GitLfsStoredObject(oid, 6, "etag-sha1", UPDATED);
    }

    @Override
    public InputStream open(RepositoryContext repository, GitLfsStoredObject object,
        GitLfsByteRange range) {
      calls.add("open " + object.oid() + " "
          + (range == null ? "null" : range.start() + "+" + range.length()));
      if (openFailure != null) throw openFailure;
      byte[] content = "abcdef".getBytes(StandardCharsets.UTF_8);
      return range == null
          ? new ByteArrayInputStream(content)
          : new ByteArrayInputStream(content, (int) range.start(), (int) range.length());
    }
  }

  private record HostContext(RepositoryDescriptor repository, GitLfsHostOperations host)
      implements RepositoryContext {
    @Override
    public Optional<BlobStorage> blobs() {
      return Optional.empty();
    }

    @Override
    public <T> Optional<T> service(Class<T> type) {
      return type.isInstance(host) ? Optional.of(type.cast(host)) : Optional.empty();
    }
  }

  private static final class RecordingContext implements PluginContext {
    private RepositoryProtocolHandler handler;
    private RepositoryFormatProvider format;

    @Override
    public void registerRemoteTransport(RemoteTransportProvider provider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerRepositoryFormat(RepositoryFormatProvider provider) {
      this.format = provider;
    }

    @Override
    public void registerRepositoryProtocolHandler(RepositoryProtocolHandler handler) {
      this.handler = handler;
    }

    @Override
    public void registerBlobStoreProvider(BlobStoreProvider provider) {
      throw new UnsupportedOperationException();
    }
  }
}
