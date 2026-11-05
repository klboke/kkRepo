package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwiftStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final SwiftService swiftHosted = mock(SwiftService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SwiftStagingContentAdapter adapter = new SwiftStagingContentAdapter(
      hosted, swiftHosted, mock(ForwardedHeaderPolicy.class),
      mock(SwiftArchiveInspector.class), objectMapper, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED, "swift-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void normalizesPublishPaths() {
    assertEquals("acme/demo/1.0.0", adapter.normalizePath("/acme/demo/1.0.0").orElseThrow());
    assertEquals("swift/acme/demo/1.0.0.zip",
        adapter.normalizePath("acme/demo/1.0.0.zip").orElseThrow());
    assertTrue(adapter.normalizePath("../demo").isEmpty());
  }

  @Test
  void plainStreamStagingIsRejectedBecauseSwiftRequiresMultipart() {
    assertThrows(com.github.klboke.kkrepo.server.maven.MavenExceptions.BadRequestException.class,
        () -> adapter.stage(runtime, "acme/demo/1.0.0",
            new ByteArrayInputStream(new byte[] {1}), "application/zip", "alice", "ip"));
  }

  @Test
  void servesTheReleaseListFromStagedReleases() {
    when(staging.listItems(3L)).thenReturn(List.of(stagedItem(Map.of())));
    when(swiftHosted.stagedReleaseList(any(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(com.github.klboke.kkrepo.server.maven.MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(runtime, 3L, "acme/demo", false,
        "https://repo.example/repository/swift-hosted").isPresent());

    verify(swiftHosted).stagedReleaseList(
        eq(runtime), eq("acme"), eq("demo"),
        eq("https://repo.example/repository/swift-hosted"),
        eq(List.of("1.0.0")), eq(false));
  }

  @Test
  void servesStagedReleaseMetadataAndDefaultManifest() throws Exception {
    String manifest = "// swift-tools-version:5.9\nimport PackageDescription\n";
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("swiftMetadata", "{\"description\":\"Demo\"}");
    attributes.put("swiftArchiveSha256", "a".repeat(64));
    attributes.put("swiftManifests", List.of(Map.of(
        "filename", "Package.swift",
        "declaredToolsVersion", "5.9",
        "sha256", "b".repeat(64),
        "content", Base64.getEncoder().encodeToString(
            manifest.getBytes(StandardCharsets.UTF_8)))));
    StagingItemRecord item = stagedItem(attributes);
    when(staging.listItems(3L)).thenReturn(List.of(item));
    when(swiftHosted.stagedReleaseMetadata(any(), anyBoolean()))
        .thenReturn(com.github.klboke.kkrepo.server.maven.MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(runtime, 3L, "acme/demo/1.0.0", false,
        "https://repo.example/repository/swift-hosted").isPresent());
    org.mockito.ArgumentCaptor<com.github.klboke.kkrepo.protocol.swift.SwiftReleaseMetadata>
        metadata = org.mockito.ArgumentCaptor.forClass(
            com.github.klboke.kkrepo.protocol.swift.SwiftReleaseMetadata.class);
    verify(swiftHosted).stagedReleaseMetadata(metadata.capture(), eq(false));
    assertEquals("acme.demo", metadata.getValue().id());
    assertEquals("a".repeat(64),
        metadata.getValue().resources().getFirst().checksum());

    var manifestResponse = adapter.generatedMetadata(
        runtime, 3L, "acme/demo/1.0.0/Package.swift", false, null).orElseThrow();
    assertEquals(manifest, new String(manifestResponse.body().readAllBytes(),
        StandardCharsets.UTF_8));
  }

  private StagingItemRecord stagedItem(Map<String, Object> extra) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("swiftScope", "acme");
    attributes.put("swiftName", "demo");
    attributes.put("swiftVersion", "1.0.0");
    attributes.putAll(extra);
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.SWIFT, "swift/acme/demo/1.0.0.zip", new byte[32],
        "ADD", "STAGED", 1L, 1L, "application/zip", 1L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), null, null, null, attributes,
        Instant.now(), Instant.now());
  }

  @Test
  void preflightRejectsDeletes() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED, "swift-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.SWIFT, "swift/acme/demo/1.0.0.zip", new byte[32],
        "DELETE", "STAGED", 1L, 1L, "application/zip", 1L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), null, null, null, Map.of(), Instant.now(), Instant.now());
    assertNotNull(adapter.validateItem(repository, item, null));
  }
}
