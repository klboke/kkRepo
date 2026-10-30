package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PubStagingContentAdapterTest {
  private final PubHostedService pubHosted = mock(PubHostedService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PubStagingContentAdapter adapter =
      new PubStagingContentAdapter(pubHosted, objectMapper, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED, "pub-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "packages/demo-1.0.0.tar.gz";

  @Test
  void normalizesArchivePaths() {
    assertEquals(path, adapter.normalizePath("/" + path).orElseThrow());
    assertTrue(adapter.normalizePath("../demo").isEmpty());
  }

  @Test
  void promotionReplaysThePubArchiveWriter() {
    when(pubHosted.uploadArchive(any(), any(), any(), any(), any())).thenReturn(path);
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(pubHosted).uploadArchive(
        eq(runtime), any(), eq("alice"), eq("127.0.0.1"), eq("staging"));
  }

  @Test
  void mergesStagedReleasesIntoThePackageMetadata() throws Exception {
    when(pubHosted.packageEntries(any(), any(), any())).thenReturn(List.of());
    when(pubHosted.packageMetadataBodyFromEntries(any(), any())).thenAnswer(invocation -> {
      List<Map<String, Object>> entries = invocation.getArgument(1);
      return Map.of(
          "name", invocation.getArgument(0),
          "latest", entries.isEmpty() ? Map.of() : entries.get(entries.size() - 1),
          "versions", entries);
    });
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.PUB, "packages/demo/versions/1.0.0.tar.gz",
        new byte[32], "ADD", "STAGED", 1L, 1L, "application/octet-stream", 1L,
        "2".repeat(64), "3".repeat(32), "1".repeat(40), null, null, null,
        Map.of("pubPackage", "demo", "pubVersion", "1.0.0",
            "archiveSha256", "4".repeat(64),
            "pubspec", Map.of("name", "demo", "version", "1.0.0"),
            "publishedAt", "2026-10-27T00:00:00Z"),
        Instant.now(), Instant.now());
    when(staging.listItems(3L)).thenReturn(List.of(staged));

    var response = adapter.generatedMetadata(
        runtime, 3L, "api/packages/demo", false, "https://repo.example/repository/pub-hosted")
        .orElseThrow();
    JsonNode body = objectMapper.readTree(response.body().readAllBytes());

    assertEquals("demo", body.path("name").asText());
    assertEquals("1.0.0", body.path("versions").get(0).path("version").asText());
    assertEquals("https://repo.example/repository/pub-hosted/api/archives/demo-1.0.0.tar.gz",
        body.path("versions").get(0).path("archive_url").asText());
  }

  @Test
  void mapsArchiveApiRequestsToTheStagedArchivePath() {
    assertEquals("packages/demo/versions/1.0.0.tar.gz",
        adapter.normalizePath("api/archives/demo-1.0.0.tar.gz").orElseThrow());
  }

  @Test
  void preflightRejectsDeletesAndExistingArchives() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED, "pub-hosted", true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
    assertTrue(adapter.validateItem(repository, item("DELETE"), null) != null);
    assertTrue(adapter.validateItem(repository, item("ADD"), mock(AssetRecord.class)) != null);
  }

  private StagingItemRecord item() {
    return item("ADD");
  }

  private StagingItemRecord item(String action) {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.PUB, path, new byte[32], action, "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null, Map.of(), Instant.now(), Instant.now());
  }
}
