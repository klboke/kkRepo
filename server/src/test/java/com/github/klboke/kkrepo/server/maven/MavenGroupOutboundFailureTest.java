package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.SecurityErrorAdvice;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import com.github.klboke.kkrepo.server.securityscan.ArtifactPolicyException;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MavenGroupOutboundFailureTest {
  private static final String ARTIFACT = "com/acme/demo/1.0/demo-1.0.pom";
  private static final String METADATA = "com/acme/demo/maven-metadata.xml";
  private static final byte[] CONTENT = "member-content".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METADATA_BYTES = ("<metadata><groupId>com.acme</groupId><artifactId>demo</artifactId>"
      + "<versioning><versions><version>1.0</version></versions></versioning></metadata>")
      .getBytes(StandardCharsets.UTF_8);
  private final AtomicInteger upstreamCalls = new AtomicInteger();
  private final AtomicInteger targetCalls = new AtomicInteger();
  private final AtomicInteger hostedCalls = new AtomicInteger();
  private HttpServer upstream;
  private HttpServer target;
  private ProxiedHttpClientFactory transport;
  private HttpRemoteFetcher fetcher;
  private MavenHostedService hosted;
  private MavenProxyService proxy;
  private MavenGroupService groupService;
  private RepositoryRuntime proxyRepo;
  private RepositoryRuntime hostedRepo;

  @BeforeEach
  void setUp() throws Exception {
    target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    target.createContext("/", exchange -> {
      targetCalls.incrementAndGet();
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    target.start();
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", exchange -> {
      upstreamCalls.incrementAndGet();
      exchange.getResponseHeaders().set("Location", "http://localhost:" + target.getAddress().getPort() + "/missing");
      exchange.sendResponseHeaders(303, -1);
      exchange.close();
    });
    upstream.start();
    transport = new ProxiedHttpClientFactory(10000, 1000);
    configure(OutboundRequestPolicy.allowPrivateForTests());
  }

  private void configure(OutboundRequestPolicy policy) throws Exception {
    fetcher = new HttpRemoteFetcher(policy, null, transport, "HTTP_1_1", 5, 5, 5, 5, 0);
    AssetDao dao = mock(AssetDao.class);
    AssetMetadataCache cache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    proxy = new MavenProxyService(dao, null, null, mock(ProxyStateDao.class), fetcher, null, cache);
    hosted = mock(MavenHostedService.class);
    when(hosted.get(any(), any(), anyBoolean())).thenAnswer(invocation -> {
      hostedCalls.incrementAndGet();
      MavenPath path = invocation.getArgument(1);
      boolean headOnly = invocation.getArgument(2);
      byte[] content = path.path().equals(METADATA) ? METADATA_BYTES : CONTENT;
      return headOnly ? MavenResponse.noBody(200, content.length, "application/xml", "etag", Instant.EPOCH)
          : MavenResponse.ok(new ByteArrayInputStream(content), content.length, "application/xml", "etag", Instant.EPOCH);
    });
    AtomicReference<byte[]> merged = new AtomicReference<>();
    MavenAssetWriter writer = mock(MavenAssetWriter.class);
    when(writer.writeBytes(any(), any(), anyLong(), any(), any(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          byte[] content = invocation.getArgument(4);
          merged.set(content);
          var stored = mock(MavenAssetWriter.Stored.class);
          var asset = mock(AssetRecord.class);
          when(asset.contentType()).thenReturn("application/xml");
          when(asset.lastUpdatedAt()).thenReturn(Instant.EPOCH);
          var blob = mock(AssetBlobRecord.class);
          when(blob.size()).thenReturn((long) content.length);
          when(blob.blobRef()).thenReturn("s3://bucket/metadata");
          when(blob.objectKey()).thenReturn("metadata");
          when(stored.asset()).thenReturn(asset);
          when(stored.blob()).thenReturn(blob);
          return stored;
        });
    BlobStorage storage = mock(BlobStorage.class);
    when(storage.get(any())).thenAnswer(invocation -> Optional.of(new ByteArrayInputStream(merged.get())));
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    when(registry.forBlobStoreId(anyLong())).thenReturn(storage);
    groupService = new MavenGroupService(hosted, proxy, dao, registry, writer, cache);
    proxyRepo = runtime(1, RepositoryType.PROXY, "http://127.0.0.1:" + upstream.getAddress().getPort(), List.of());
    hostedRepo = runtime(2, RepositoryType.HOSTED, null, List.of());
  }

  @AfterEach
  void tearDown() {
    if (upstream != null) upstream.stop(0);
    if (target != null) target.stop(0);
    if (transport != null) transport.close();
  }

  @ParameterizedTest
  @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false",
      "false,false,true", "true,false,true", "false,true,true", "true,true,true"})
  void rejectedRedirectFallsThroughForArtifactsAndMetadata(boolean head, boolean metadata, boolean nested)
      throws Exception {
    assertFallback(head, metadata, nested);
    assertEquals(1, upstreamCalls.get());
    assertEquals(0, targetCalls.get(), "rejected redirect must never be followed");
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void rejectedInitialAddressFallsThroughWithoutConnecting(boolean head, boolean metadata) throws Exception {
    configure(new OutboundRequestPolicy(false, ""));
    assertFallback(head, metadata, false);
    assertEquals(0, upstreamCalls.get());
    assertEquals(0, targetCalls.get());
  }

  private void assertFallback(boolean head, boolean metadata, boolean nested) throws Exception {
    RepositoryRuntime first = nested
        ? runtime(4, RepositoryType.GROUP, null, List.of(proxyRepo)) : proxyRepo;
    MavenResponse response = groupService.get(runtime(3, RepositoryType.GROUP, null, List.of(first, hostedRepo)),
        path(metadata), head);
    assertEquals(200, response.status());
    assertEquals(1, hostedCalls.get());
    if (!head) {
      try (var body = response.body()) {
        if (metadata) assertEquals(List.of("1.0"), MavenMetadataXml.read(body.readAllBytes()).versions);
        else assertArrayEquals(CONTENT, body.readAllBytes());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void allRejectedMembersReturnNotFound(boolean head, boolean metadata) {
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> groupService.get(runtime(3, RepositoryType.GROUP, null, List.of(proxyRepo)), path(metadata), head));
    assertEquals(0, hostedCalls.get());
    assertEquals(0, targetCalls.get());
  }

  @Test
  void directProxyKeepsSecurityDiagnostic() {
    var error = assertThrows(HttpRemoteFetcher.RemoteRequestRejectedException.class,
        () -> proxy.get(proxyRepo, path(false), false));
    assertEquals("remote redirect URL host is not allowed: localhost", error.getMessage());
    assertEquals(400, new SecurityErrorAdvice().validation(error).getStatusCode().value());
    assertEquals(0, targetCalls.get());
  }

  @Test
  void responseHandlerSecurityFailureIsNotTaggedAsAnOutboundFailure() throws Exception {
    upstream.createContext("/ok", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    var denied = new SecurityValidationException("download policy denied");
    assertSame(denied, assertThrows(SecurityValidationException.class,
        () -> fetcher.fetchWithBodyRetry(HttpRemoteFetcher.Request.get(proxyRepo.proxyRemoteUrl() + "/ok")
            .withRepository(proxyRepo), ARTIFACT, response -> { throw denied; })));
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void unrelatedSecurityFailuresAreNotSkipped(boolean metadata, boolean scanPolicy) {
    RuntimeException denied = scanPolicy ? mock(ArtifactPolicyException.class)
        : new SecurityValidationException("member read denied");
    doThrow(denied).when(hosted).get(any(), any(), anyBoolean());
    assertSame(denied, assertThrows(RuntimeException.class,
        () -> groupService.get(runtime(3, RepositoryType.GROUP, null, List.of(hostedRepo, proxyRepo)),
            path(metadata), false)));
    assertEquals(0, upstreamCalls.get());
  }

  private static MavenPath path(boolean metadata) {
    return new MavenPathParser().parsePath(metadata ? METADATA : ARTIFACT);
  }

  private static RepositoryRuntime runtime(long id, RepositoryType type, String remote,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(id, "repo-" + id, RepositoryFormat.MAVEN2, type,
        "maven2-" + type.name().toLowerCase(java.util.Locale.ROOT), true, 1L, null, "MIXED", "PERMISSIVE",
        true, remote, 1440, 1440, true, null, members);
  }
}
