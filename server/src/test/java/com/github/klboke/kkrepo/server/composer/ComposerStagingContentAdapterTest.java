package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComposerStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final ComposerHostedService composerHosted = mock(ComposerHostedService.class);
  private final ComponentDao components = mock(ComponentDao.class);
  private final ComposerArchiveInspector inspector = mock(ComposerArchiveInspector.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ComposerStagingContentAdapter adapter = new ComposerStagingContentAdapter(
      hosted, composerHosted, components, inspector, objectMapper, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "composer-hosted", RepositoryFormat.COMPOSER, RepositoryType.HOSTED,
      "composer-hosted", true, 1L, "ALLOW", null, null, true, null, null, null, null, null,
      List.of());
  private final String path = "dist/acme/demo/1.0.0.zip";

  @Test
  void normalizesComposerArchivePaths() {
    assertEquals(path, adapter.normalizePath("/" + path).orElseThrow());
    assertTrue(adapter.normalizePath("acme/demo").isEmpty());
  }

  @Test
  void stageRetainsTheArchiveWithItsCoordinate() throws Exception {
    when(inspector.inspect(any(), any(), any(), any())).thenReturn(
        new ComposerArchiveInspector.Inspected(
            "acme/demo", "1.0.0", Map.of("name", "acme/demo", "version", "1.0.0")));
    when(hosted.storeStagedBlobFile(eq(runtime), eq(path), any(), any(), any(), any(), any()))
        .thenReturn(blob());
    ComposerStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, path, new ByteArrayInputStream(new byte[] {1}), "application/zip",
        "alice", "127.0.0.1");
    assertEquals("acme/demo", upload.attributes().get("composerName"));
    assertEquals("1.0.0", upload.attributes().get("composerVersion"));
    assertTrue(upload.attributes().get("composerMetadata") instanceof Map);
  }

  @Test
  @SuppressWarnings("unchecked")
  void mergesStagedArchivesIntoTheP2Document() throws Exception {
    when(composerHosted.versionEntries(any(), any(), anyBoolean(), any())).thenReturn(List.of(
        Map.of("name", "acme/demo", "version", "0.9.0", "type", "library")));
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", "acme/demo");
    metadata.put("version", "1.0.0");
    metadata.put("type", "library");
    metadata.put("dist", Map.of("type", "zip", "shasum", "a".repeat(40)));
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.COMPOSER, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/zip", 1L, "2".repeat(64), "3".repeat(32), "4".repeat(40),
        null, null, null,
        Map.of("composerName", "acme/demo", "composerVersion", "1.0.0",
            "composerMetadata", metadata), Instant.now(), Instant.now());
    when(staging.listItems(6L)).thenReturn(List.of(staged));

    var response = adapter.generatedMetadata(
        runtime, 6L, "p2/acme/demo.json", false,
        "https://repo.example/repository/composer-hosted").orElseThrow();
    var body = objectMapper.readTree(response.body().readAllBytes());

    var versions = body.path("packages").path("acme/demo");
    assertEquals(2, versions.size());
    assertEquals("1.0.0", versions.get(0).path("version").asText());
    assertEquals("https://repo.example/repository/composer-hosted/" + path,
        versions.get(0).path("dist").path("url").asText());
  }

  @Test
  void leavesTheLiveP2DocumentAloneWhenNothingIsStaged() {
    when(composerHosted.versionEntries(any(), any(), anyBoolean(), any())).thenReturn(List.of());
    when(staging.listItems(6L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(
        runtime, 6L, "p2/acme/demo.json", false, "https://repo.example").isEmpty());
  }

  @Test
  void promotionReplaysTheComposerArchiveWriter() throws Exception {
    when(composerHosted.uploadArchive(
        any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("dist/acme/demo/1.0.0.zip");
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(composerHosted).uploadArchive(
        eq(runtime), any(), eq("1.0.0.zip"), any(), eq("acme/demo"), eq("1.0.0"),
        eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void preflightRejectsExistingComposerVersions() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "composer-hosted", RepositoryFormat.COMPOSER, RepositoryType.HOSTED,
        "composer-hosted", true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    when(components.findByNameAndVersion(1L, "acme/demo", "1.0.0"))
        .thenReturn(Optional.of(mock(ComponentRecord.class)));
    assertTrue(adapter.validateItem(repository, item(), null) != null);
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.COMPOSER, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/zip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null,
        Map.of("composerName", "acme/demo", "composerVersion", "1.0.0",
            "composerFileName", "1.0.0.zip"),
        Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/zip", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}
