package com.github.klboke.kkrepo.server.rubygems;

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
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RubygemsStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final RubygemsService rubygemsHosted = mock(RubygemsService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final RubygemsStagingContentAdapter adapter =
      new RubygemsStagingContentAdapter(hosted, rubygemsHosted, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "gems-hosted", RepositoryFormat.RUBYGEMS, RepositoryType.HOSTED, "gems-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String gemPath = "gems/demo-1.0.0.gem";

  @Test
  void stageUsesTheGemPathDerivedFromTheUpload() throws Exception {
    when(rubygemsHosted.planPush(any())).thenReturn(new RubygemsService.UploadPlan(
        gemPath, "demo", "1.0.0", new RubygemsService.GemMetadata(
            "demo", "1.0.0", "ruby", List.of(), "a".repeat(64), "Demo", List.of("Alice"))));
    when(hosted.storeStagedBlobFile(
        eq(runtime), eq(gemPath), any(), any(), any(), any(), any()))
        .thenReturn(blob());
    RubygemsStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, "api/v1/gems", new ByteArrayInputStream(new byte[] {1}),
        "application/octet-stream", "alice", "127.0.0.1");
    assertEquals(gemPath, upload.logicalPath());
  }

  @Test
  void promotionReplaysTheNormalRubygemsPushWriter() {
    when(rubygemsHosted.push(any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.noBody(200));
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(rubygemsHosted).push(
        eq(runtime), any(), any(), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void preflightRejectsDeletesAndExistingGems() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "gems-hosted", RepositoryFormat.RUBYGEMS, RepositoryType.HOSTED, "gems-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    assertTrue(adapter.validateItem(repository, itemWithAction("DELETE"), null) != null);
    assertTrue(adapter.validateItem(repository, itemWithAction("ADD"), mock(AssetRecord.class)) != null);
    assertTrue(adapter.supportsPublication("api/v1/gems"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void mergesStagedGemsIntoTheCompactIndex() {
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("name", "demo");
    descriptor.put("version", "1.0.0");
    descriptor.put("platform", "ruby");
    descriptor.put("sha256", "a".repeat(64));
    descriptor.put("summary", "Demo");
    descriptor.put("authors", List.of("Alice"));
    descriptor.put("dependencies", List.of(
        Map.of("name", "rake", "requirements", List.of(">= 13"))));
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.RUBYGEMS, gemPath, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null,
        Map.of("gemMetadata", descriptor), Instant.now(), Instant.now());
    when(staging.listItems(8L)).thenReturn(List.of(staged));
    when(rubygemsHosted.stagedCompactVersions(any(), any(), any(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(runtime, 8L, "versions", false).isPresent());

    org.mockito.ArgumentCaptor<List<RubygemsService.GemMetadata>> gems =
        org.mockito.ArgumentCaptor.forClass(List.class);
    org.mockito.ArgumentCaptor<Set<String>> deleted =
        org.mockito.ArgumentCaptor.forClass(Set.class);
    verify(rubygemsHosted).stagedCompactVersions(
        eq(runtime), gems.capture(), deleted.capture(), eq(false));
    assertEquals("demo", gems.getValue().getFirst().name());
    assertEquals("rake", gems.getValue().getFirst().dependencies().getFirst().name());
    assertTrue(deleted.getValue().isEmpty());
  }

  @Test
  void leavesTheLiveIndexAloneWhenNothingIsStaged() {
    when(staging.listItems(8L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(runtime, 8L, "versions", false).isEmpty());
  }

  private StagingItemRecord item() {
    return itemWithAction("ADD");
  }

  private StagingItemRecord itemWithAction(String action) {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.RUBYGEMS, gemPath, new byte[32], action, "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/octet-stream", "alice",
        "127.0.0.1", now, now, Map.of());
  }
}
