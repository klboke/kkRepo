package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnsibleGalaxyServiceSupportTest {
  private static final String SHA256 = "a".repeat(64);
  private ObjectMapper mapper;
  private AnsibleGalaxyRegistryDao registry;
  private AnsibleGalaxyService service;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    registry = mock(AnsibleGalaxyRegistryDao.class);
    service = service(mapper, registry);
  }

  @Test
  void buildsSafeClientAndArtifactUrls() {
    assertEquals(
        "/repository/ansible/api/v3/collections/acme/tools/versions/?limit=10&offset=20",
        AnsibleGalaxyService.clientPaginationPath(
            "https://repo.example/repository/ansible/api/v3/collections/acme/tools/versions/",
            10, 20));
    assertThrows(IllegalArgumentException.class,
        () -> AnsibleGalaxyService.clientPaginationPath("versions", 1, 0));

    assertEquals("https://repo.example/base/api/v3/collections/a%20b/tools/",
        AnsibleGalaxyService.collectionUrl("https://repo.example/base", "a b", "tools"));
    assertEquals("https://repo.example/base/api/v3/collections/a/tools/versions/",
        AnsibleGalaxyService.versionsUrl("https://repo.example/base/", "a", "tools"));
    assertTrue(AnsibleGalaxyService.versionUrl(
        "https://repo.example/base/", "a", "tools", "1.2.3").endsWith("1.2.3/"));
    assertTrue(AnsibleGalaxyService.artifactUrl(
        "https://repo.example/base", "a tools.tar.gz").endsWith("a%20tools.tar.gz"));
    assertEquals("https://repo.example/base/", AnsibleGalaxyService.normalizedBase(
        "https://repo.example/base"));
    assertEquals("value%20with%20space", AnsibleGalaxyService.encode("value with space"));
  }

  @Test
  void validatesUpstreamIdentityAndResolvesAllLinkShapes() {
    Map<String, Object> valid = Map.of(
        "namespace", Map.of("name", "acme"),
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "artifact", Map.of("sha256", SHA256));
    AnsibleGalaxyService.validateUpstreamDetail("acme", "tools", "1.2.3", valid);
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.validateUpstreamDetail("other", "tools", "1.2.3", valid));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.validateUpstreamDetail(
            "acme", "tools", "1.2.3", Map.of("artifact", Map.of("sha256", "bad"))));

    RepositoryRuntime proxy = runtime(
        1L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.PROXY, true, 3L,
        "ALLOW_ONCE", "https://galaxy.example/root/", 60, List.of());
    assertNull(service.resolveUpstreamUrl(proxy, "https://galaxy.example/root/v3/", null));
    assertNull(service.resolveUpstreamUrl(proxy, "https://galaxy.example/root/v3/", " "));
    assertEquals("https://cdn.example/file.tar.gz", service.resolveUpstreamUrl(
        proxy, "https://galaxy.example/root/v3/", "https://cdn.example/file.tar.gz"));
    assertEquals("https://galaxy.example/api/file", service.resolveUpstreamUrl(
        proxy, "https://galaxy.example/root/v3/", "/api/file"));
    assertEquals("https://galaxy.example/root/v3/file", service.resolveUpstreamUrl(
        proxy, "https://galaxy.example/root/v3/", "file"));
    for (String invalid : List.of("http://[", "https://galaxy.example/%")) {
      assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
          () -> service.resolveUpstreamUrl(
              proxy, "https://galaxy.example/root/v3/", invalid));
    }
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.resolveUpstreamUrl(proxy, "http://[", "/api/file"));
  }

  @Test
  void projectsDiscoveryVersionPagesAndBoundedVersionDetails() {
    assertEquals(Map.of(), AnsibleGalaxyService.projectProxyDocument(
        "@v3-root", Map.of("available_versions", Map.of())));
    assertEquals(
        Map.of("available_versions", Map.of("v3", "api/v3/")),
        AnsibleGalaxyService.projectProxyDocument(
            "@v3-root", Map.of("available_versions", Map.of("v3", "api/v3/"))));

    Map<String, Object> page = AnsibleGalaxyService.projectProxyDocument(
        "@versions", Map.of(
            "results", List.of(Map.of("version", "2.0.0"), Map.of()),
            "next", "/next"));
    assertEquals(List.of(Map.of("version", "2.0.0"), Map.of("version", "")),
        page.get("data"));
    assertEquals(Map.of("next", "/next"), page.get("links"));

    List<Map<String, Object>> tooMany = new ArrayList<>();
    for (int i = 0; i < 1001; i++) tooMany.add(Map.of("version", "1.0.0"));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.projectProxyDocument("@versions-next", Map.of("data", tooMany)));

    Map<String, Object> detail = AnsibleGalaxyService.projectProxyDocument(
        "1.2.3", Map.of(
            "namespace", Map.of("name", "acme"),
            "collection", Map.of("name", "tools"),
            "version", "1.2.3",
            "href", "/href",
            "download_url", "/download",
            "requires_ansible", ">=2.16",
            "artifact", Map.of("filename", "acme-tools-1.2.3.tar.gz", "sha256", SHA256)));
    assertEquals("acme", map(detail.get("namespace")).get("name"));
    assertEquals(SHA256, map(detail.get("artifact")).get("sha256"));

    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.boundedUpstreamText(1, 3, "field"));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.boundedUpstreamText("long", 3, "field"));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.boundedUpstreamText("bad\n", 10, "field"));
    assertNull(AnsibleGalaxyService.boundedUpstreamText(null, 10, "field"));
    assertEquals("ok", AnsibleGalaxyService.boundedUpstreamText("ok", 10, "field"));
  }

  @Test
  void parsesResultArraysAndPaginationDefensively() {
    assertEquals("1.0.0", AnsibleGalaxyService.resultItems(
        Map.of("data", List.of(Map.of("version", "1.0.0")))).getFirst().get("version"));
    assertEquals("2.0.0", AnsibleGalaxyService.resultItems(
        Map.of("results", List.of(Map.of("version", "2.0.0")))).getFirst().get("version"));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.resultItems(Map.of()));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.resultItems(Map.of("data", List.of("bad"))));

    assertEquals("/next", AnsibleGalaxyService.nextLink(Map.of("links", Map.of("next", "/next"))));
    assertEquals("legacy", AnsibleGalaxyService.nextLink(Map.of("next", "legacy")));
    assertNull(AnsibleGalaxyService.nextLink(Map.of("next", "")));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.nextLink(Map.of("next", "x".repeat(4097))));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.nextLink(Map.of("next", "bad\n")));
    assertTrue(AnsibleGalaxyService.pageStateKey("https://example/next").startsWith("@versions-"));
    assertEquals("v3/", AnsibleGalaxyService.ensureSlash("v3"));
    assertEquals("v3/", AnsibleGalaxyService.ensureSlash("v3/"));
  }

  @Test
  void transformsMetadataSignaturesAndDiscoveryResponses() throws Exception {
    AnsibleGalaxyRegistryDao.CollectionVersion version = version(Map.of(
        "license", List.of("Apache-2.0", "MIT"),
        "authors", List.of("Alice", "Bob"),
        "description", "tools",
        "tags", List.of("cloud"),
        "repository", "https://git.example/repo",
        "documentation", "https://docs.example",
        "homepage", "https://example",
        "issues", "https://example/issues"));
    when(registry.listSignatures(version.id())).thenReturn(List.of(
        new AnsibleGalaxyRegistryDao.Signature(1L, version.id(), null, SHA256, null, "HOSTED", Instant.now()),
        new AnsibleGalaxyRegistryDao.Signature(2L, version.id(), null, "b".repeat(64), "ABCD", "HOSTED", Instant.now())));

    Map<String, Object> collection = service.collectionMetadata(version);
    assertEquals("Apache-2.0, MIT", collection.get("license"));
    assertEquals("Alice, Bob", collection.get("authors"));
    Map<String, Object> metadata = service.versionMetadata(version);
    assertEquals(version.dependencies(), metadata.get("dependencies"));
    assertEquals("https://docs.example", metadata.get("documentation"));
    List<?> signatures = service.signatures(version);
    assertEquals(2, signatures.size());
    assertEquals("", map(((List<?>) signatures).getFirst()).get("key_fingerprint"));

    Map<String, Object> target = new LinkedHashMap<>();
    AnsibleGalaxyService.copyMetadataValue(Map.of(), target, "description");
    assertTrue(target.isEmpty());
    AnsibleGalaxyService.copyMetadataValue(Map.of("description", "value"), target, "description");
    assertEquals("value", target.get("description"));

    assertEquals("kkrepo", service.discovery().get("server_version"));
    var head = service.jsonResponse(service.discovery(), 202, true, Instant.EPOCH);
    assertEquals(202, head.status());
    assertFalse(head.hasBody());
    var body = service.jsonResponse(service.discovery(), 200, false, null);
    try (InputStream input = body.body()) {
      Map<String, Object> decoded = mapper.readValue(input, new TypeReference<>() { });
      assertEquals("kkrepo", decoded.get("server_version"));
    }
  }

  @Test
  void boundsAndValidatesUpstreamJson() throws Exception {
    assertEquals("value", service.readJsonBounded(
        new ByteArrayInputStream("{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8))).get("key"));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.readJsonBounded(new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8))));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.readJsonBounded(new RepeatingInputStream(17 * 1024 * 1024)));

    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("broken") { });
    assertThrows(IllegalStateException.class,
        () -> service(broken, registry).jsonResponse(Map.of(), 200, false, null));
  }

  @Test
  void enforcesRuntimeWriteProxyAndCoordinationInputs() {
    RepositoryRuntime hosted = runtime(
        1L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED, true, 3L,
        "ALLOW_ONCE", null, null, List.of());
    AnsibleGalaxyService.requireRuntime(hosted);
    AnsibleGalaxyService.requireHostedWritable(hosted);
    AnsibleGalaxyService.requireExpectedSha(SHA256);
    assertTrue(AnsibleGalaxyService.validSha(SHA256.toUpperCase()));
    assertFalse(AnsibleGalaxyService.validSha(null));
    assertFalse(AnsibleGalaxyService.validSha("bad"));

    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> AnsibleGalaxyService.requireRuntime(
        runtime(2L, RepositoryFormat.MAVEN2, RepositoryType.HOSTED, true, 3L,
            "ALLOW_ONCE", null, 60, List.of())));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> AnsibleGalaxyService.requireRuntime(
        runtime(2L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED, false, 3L,
            "ALLOW_ONCE", null, 60, List.of())));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> AnsibleGalaxyService.requireHostedWritable(
        runtime(2L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP, true, 3L,
            "ALLOW_ONCE", null, 60, List.of())));
    assertThrows(AnsibleGalaxyExceptions.ServiceUnavailable.class,
        () -> AnsibleGalaxyService.requireHostedWritable(runtime(
            2L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED, true, null,
            "ALLOW_ONCE", null, 60, List.of())));
    assertThrows(AnsibleGalaxyExceptions.Forbidden.class,
        () -> AnsibleGalaxyService.requireHostedWritable(runtime(
            2L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED, true, 3L,
            "DENY", null, 60, List.of())));
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class,
        () -> AnsibleGalaxyService.requireExpectedSha("bad"));

    RepositoryRuntime proxy = runtime(
        3L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.PROXY, true, 3L,
        "ALLOW_ONCE", " https://galaxy.example/root ", 0, List.of());
    AnsibleGalaxyService.requireProxyRemote(proxy);
    assertEquals("https://galaxy.example/root/api/", AnsibleGalaxyService.remoteUrl(proxy, "api/"));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> AnsibleGalaxyService.requireProxyRemote(hosted));
    assertEquals(Duration.ofMinutes(60), AnsibleGalaxyService.metadataTtl(hosted));
    assertEquals(Duration.ofMinutes(1), AnsibleGalaxyService.metadataTtl(proxy));
    assertEquals(Duration.ofMinutes(5), AnsibleGalaxyService.negativeTtl(hosted));
    assertEquals(Duration.ofMinutes(1), AnsibleGalaxyService.negativeTtl(proxy));
    RepositoryRuntime longTtl = runtime(
        4L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.PROXY, true, 3L,
        "ALLOW_ONCE", "https://galaxy.example/", 100, List.of());
    assertEquals(Duration.ofMinutes(15), AnsibleGalaxyService.negativeTtl(longTtl));
    assertEquals("ansible:3:metadata:acme:tools:1.2.3",
        AnsibleGalaxyService.leaseKey(3L, "metadata", "acme", "tools", "1.2.3"));
  }

  @Test
  void handlesMembersTimesErrorsAndImmutableCopies() {
    RepositoryRuntime member = runtime(
        1L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED, true, 3L,
        "ALLOW_ONCE", null, 60, List.of());
    RepositoryRuntime nullMembers = runtime(
        2L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP, true, 3L,
        "ALLOW_ONCE", null, 60, null);
    RepositoryRuntime group = runtime(
        3L, RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP, true, 3L,
        "ALLOW_ONCE", null, 60, List.of(member));
    assertTrue(AnsibleGalaxyService.safeMembers(nullMembers).isEmpty());
    assertEquals(member, AnsibleGalaxyService.directMember(group, member.id()).orElseThrow());
    assertTrue(AnsibleGalaxyService.directMember(group, 99L).isEmpty());

    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    assertEquals(now.toString(), AnsibleGalaxyService.iso(now));
    assertNull(AnsibleGalaxyService.iso(null));
    assertEquals(now.toString(), AnsibleGalaxyService.instantText(now));
    assertNull(AnsibleGalaxyService.instantText(null));
    assertEquals(now, AnsibleGalaxyService.parseInstant(now.toString()));
    assertNull(AnsibleGalaxyService.parseInstant("bad"));
    assertNull(AnsibleGalaxyService.parseInstant(null));

    var failure = new AnsibleGalaxyExceptions.BadRequest("bad");
    assertEquals("invalid", AnsibleGalaxyService.errorCode(failure));
    assertEquals("import_failed", AnsibleGalaxyService.errorCode(new RuntimeException()));
    assertEquals("Collection import failed", AnsibleGalaxyService.safeDetail(new RuntimeException()));
    assertEquals("line one line two", AnsibleGalaxyService.safeDetail(
        new RuntimeException("line one\nline two")));
    assertEquals(2048, AnsibleGalaxyService.safeDetail(
        new RuntimeException("x".repeat(3000))).length());

    assertEquals("3", AnsibleGalaxyService.text(3));
    assertNull(AnsibleGalaxyService.text(null));
    assertTrue(AnsibleGalaxyService.map("not-map").isEmpty());
    assertEquals("value", AnsibleGalaxyService.map(Map.of("key", "value")).get("key"));
    Map<String, Object> source = new LinkedHashMap<>(Map.of("key", "value"));
    Map<String, Object> immutable = AnsibleGalaxyService.immutableMap(source);
    source.put("key", "changed");
    assertEquals("value", immutable.get("key"));
    assertThrows(UnsupportedOperationException.class, () -> immutable.put("other", "value"));
  }

  private static AnsibleGalaxyService service(
      ObjectMapper mapper, AnsibleGalaxyRegistryDao registry) {
    return new AnsibleGalaxyService(
        mapper,
        registry,
        mock(AnsibleGalaxyAssetSupport.class),
        mock(AnsibleCollectionArchiveInspector.class),
        mock(HttpRemoteFetcher.class),
        mock(RepositoryRuntimeRegistry.class),
        new AnsibleImportTaskLeaseManager(registry));
  }

  private static AnsibleGalaxyRegistryDao.CollectionVersion version(Map<String, Object> metadata) {
    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    return new AnsibleGalaxyRegistryDao.CollectionVersion(
        10L, 1L, 2L, 3L, "acme", "acme", "tools", "tools", "1.2.3", "1.2.3",
        "acme-tools-1.2.3.tar.gz", SHA256, 42L, metadata,
        Map.of("acme.base", ">=1.0.0"), ">=2.16", "HOSTED", 1L,
        AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
  }

  private static RepositoryRuntime runtime(
      long id,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String remote,
      Integer metadataTtl,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, "repo-" + id, format, type, "ansiblegalaxy-" + type.name().toLowerCase(),
        online, blobStoreId, writePolicy, null, null, true, remote, 60, metadataTtl,
        true, null, members);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static final class RepeatingInputStream extends InputStream {
    private long remaining;

    private RepeatingInputStream(long remaining) {
      this.remaining = remaining;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) {
      if (remaining == 0) return -1;
      int read = (int) Math.min(remaining, length);
      java.util.Arrays.fill(bytes, offset, offset + read, (byte) 'x');
      remaining -= read;
      return read;
    }

    @Override
    public int read() {
      if (remaining == 0) return -1;
      remaining--;
      return 'x';
    }
  }
}
