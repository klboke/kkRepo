package com.github.klboke.kkrepo.server.alpine;

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

class AlpineStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final AlpineService alpineHosted = mock(AlpineService.class);
  private final AlpinePackageInspector inspector = mock(AlpinePackageInspector.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final AlpineStagingContentAdapter adapter =
      new AlpineStagingContentAdapter(hosted, alpineHosted, inspector, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "alpine-hosted", RepositoryFormat.ALPINE, RepositoryType.HOSTED, "alpine-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "v3.20/main/x86_64/demo-1.0.0-r0.apk";

  @Test
  void acceptsPackageAndIndexPaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("v3.20/main/x86_64/APKINDEX.tar.gz").isPresent());
    assertTrue(adapter.normalizePath("other/file.txt").isEmpty());
  }

  @Test
  void promotionAndDeleteReplayTheAlpineWriter() {
    when(alpineHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.noBody(200));
    when(alpineHosted.delete(any(), any(), any(), eq(false)))
        .thenReturn(MavenResponse.noBody(204));
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    assertTrue(adapter.delete(runtime, path));
    verify(alpineHosted).put(eq(runtime), eq(path), any(), any(), eq("alice"), eq("127.0.0.1"));
    verify(alpineHosted).delete(runtime, path, "staging promotion", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void servesTheIndexFromStagedPackages() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("P", "demo");
    fields.put("V", "1.0.0-r0");
    fields.put("A", "x86_64");
    fields.put("S", "10");
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("name", "demo");
    descriptor.put("version", "1.0.0-r0");
    descriptor.put("architecture", "x86_64");
    descriptor.put("filename", "demo-1.0.0-r0.apk");
    descriptor.put("identity", "Q1" + "a".repeat(30));
    descriptor.put("dataSha256", "b".repeat(64));
    descriptor.put("sha256", "c".repeat(64));
    descriptor.put("size", 10);
    descriptor.put("fields", fields);
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.ALPINE, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/vnd.alpine.apk", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), path, null, null,
        Map.of("alpinePackage", descriptor), Instant.now(), Instant.now());
    when(staging.listItems(5L)).thenReturn(List.of(staged));
    when(alpineHosted.stagedIndex(any(), any(), any(), any(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(
        runtime, 5L, "v3.20/main/x86_64/APKINDEX.tar.gz", false).isPresent());

    org.mockito.ArgumentCaptor<List<com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao.PackageRecord>>
        rows = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(alpineHosted).stagedIndex(
        eq(runtime), any(), rows.capture(), eq(Set.of()), eq(false));
    assertEquals("demo", rows.getValue().getFirst().packageName());
    assertEquals("v3.20", rows.getValue().getFirst().distribution());
  }

  @Test
  void leavesLiveIndexesAloneWhenNothingIsStaged() {
    when(staging.listItems(5L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(
        runtime, 5L, "v3.20/main/x86_64/APKINDEX.tar.gz", false).isEmpty());
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.ALPINE, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/vnd.alpine.apk", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), path, null, null, Map.of(), Instant.now(), Instant.now());
  }
}
