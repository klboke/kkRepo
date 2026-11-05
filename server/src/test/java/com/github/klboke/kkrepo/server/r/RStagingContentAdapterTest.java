package com.github.klboke.kkrepo.server.r;

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
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
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

class RStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final RService rHosted = mock(RService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final RStagingContentAdapter adapter = new RStagingContentAdapter(
      hosted, rHosted, mock(RSourcePackageInspector.class), staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "r-hosted", RepositoryFormat.R, RepositoryType.HOSTED, "r-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "src/contrib/demo_1.0.0.tar.gz";

  @Test
  void acceptsSourcePackagesAndTheIndex() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertEquals("src/contrib/PACKAGES.gz",
        adapter.normalizePath("src/contrib/PACKAGES.gz").orElseThrow());
    assertTrue(adapter.normalizePath("other/file.txt").isEmpty());
  }

  @Test
  void promotionAndDeleteReplayTheRWriter() {
    when(rHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.noBody(200));
    when(rHosted.delete(any(), any(), any(), eq(false)))
        .thenReturn(MavenResponse.noBody(204));
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    assertTrue(adapter.delete(runtime, path));
    verify(rHosted).put(eq(runtime), eq(path), any(), any(), eq("alice"), eq("127.0.0.1"));
    verify(rHosted).delete(runtime, path, "staging promotion", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void rendersTheIndexFromStagedPackageAttributes() {
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("name", "demo");
    descriptor.put("version", "1.0.0");
    descriptor.put("filename", "demo_1.0.0.tar.gz");
    descriptor.put("md5", "a".repeat(32));
    descriptor.put("sha256", "b".repeat(64));
    descriptor.put("size", 42);
    descriptor.put("controlFields", Map.of("Package", "demo", "Version", "1.0.0"));
    Map<String, Object> attributes = Map.of("rPackage", descriptor);
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.R, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/gzip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, attributes, Instant.now(), Instant.now());
    when(staging.listItems(4L)).thenReturn(List.of(staged));
    when(rHosted.stagedPackagesIndex(any(), any(), any(), any(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(
        runtime, 4L, "src/contrib/PACKAGES.gz", false).isPresent());

    org.mockito.ArgumentCaptor<List<RRegistryDao.PackageRecord>> records =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(rHosted).stagedPackagesIndex(
        eq(runtime), any(), records.capture(), eq(Set.<String>of()), eq(false));
    assertEquals("demo", records.getValue().getFirst().packageName());
    assertEquals(path, records.getValue().getFirst().path());
  }

  @Test
  void leavesLiveIndexAloneWhenNothingIsStaged() {
    when(staging.listItems(4L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(
        runtime, 4L, "src/contrib/PACKAGES.gz", false).isEmpty());
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.R, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/gzip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of(), Instant.now(), Instant.now());
  }
}
