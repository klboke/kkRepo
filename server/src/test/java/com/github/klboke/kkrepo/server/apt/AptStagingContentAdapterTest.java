package com.github.klboke.kkrepo.server.apt;

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
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AptStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final AptService aptHosted = mock(AptService.class);
  private final AptDebPackageInspector inspector = mock(AptDebPackageInspector.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final AptStagingContentAdapter adapter =
      new AptStagingContentAdapter(hosted, aptHosted, inspector, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "apt-hosted", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "pool/main/d/demo/demo_1.0-1_amd64.deb";

  @Test
  void acceptsPackageAndMetadataPaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("dists/stable/Release").isPresent());
    assertTrue(adapter.normalizePath("other/file.txt").isEmpty());
  }

  @Test
  void promotionAndDeleteReplayTheAptWriter() {
    when(aptHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    when(aptHosted.delete(any(), any(), any(), eq(false)))
        .thenReturn(MavenResponse.noBody(204));
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    assertTrue(adapter.delete(runtime, path));
    verify(aptHosted).put(eq(runtime), eq(path), any(), any(), eq("alice"), eq("127.0.0.1"));
    verify(aptHosted).delete(runtime, path, "staging promotion", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void servesPackagesFromStagedDebs() {
    StagingItemRecord staged = stagedPackage();
    when(staging.listItems(7L)).thenReturn(List.of(staged));
    byte[] packages = "Package: demo\nVersion: 1.0-1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(aptHosted.stagedMetadataFiles(any(), any(), any(), any()))
        .thenReturn(new AptService.StagedMetadataFiles(
            "stable", "main",
            Map.of("dists/stable/main/binary-amd64/Packages.gz", packages)));

    MavenResponse response = adapter.generatedMetadata(
        runtime, 7L, "dists/stable/main/binary-amd64/Packages.gz", false).orElseThrow();

    assertEquals(packages.length, response.contentLength());
    org.mockito.ArgumentCaptor<List<AptRegistryDao.PackageRecord>> records =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(aptHosted).stagedMetadataFiles(
        eq(runtime), any(), records.capture(), eq(Set.of()));
    assertEquals("demo", records.getValue().getFirst().packageName());
    assertEquals("stable", records.getValue().getFirst().distribution());
    assertEquals("main", records.getValue().getFirst().component());
  }

  @Test
  void servesMetadataFromOneSnapshotForAllFilesAndHeadRequests() {
    StagingItemRecord staged = stagedPackage();
    when(staging.listItems(7L)).thenReturn(List.of(staged));
    byte[] packages = "Package: demo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] release = "Origin: demo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(aptHosted.stagedMetadataFiles(any(), any(), any(), any()))
        .thenReturn(new AptService.StagedMetadataFiles(
            "stable", "main",
            Map.of(
                "dists/stable/main/binary-amd64/Packages.gz", packages,
                "dists/stable/Release", release)));
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    com.github.klboke.kkrepo.server.cache.AssetMetadataCache assetMetadata = mock(
        com.github.klboke.kkrepo.server.cache.AssetMetadataCache.class);
    AptStagingContentAdapter cached = new AptStagingContentAdapter(
        hosted, aptHosted, inspector, staging,
        new com.github.klboke.kkrepo.server.staging.StagingMetadataSnapshotCache(
            watermark, assetMetadata, true, 60, 100));

    cached.generatedMetadata(runtime, 7L, "dists/stable/Release", false).orElseThrow();
    cached.generatedMetadata(runtime, 7L, "dists/stable/Release", true).orElseThrow();

    // One render per session revision: the second, HEAD request does not even list staged items.
    verify(aptHosted, org.mockito.Mockito.times(1))
        .stagedMetadataFiles(any(), any(), any(), any());
    verify(staging, org.mockito.Mockito.times(1)).listItems(7L);
  }

  @Test
  void leavesLiveMetadataAloneWhenNothingIsStaged() {
    when(staging.listItems(7L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(
        runtime, 7L, "dists/stable/Release", false).isEmpty());
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.APT, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/vnd.debian.binary-package", 1L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), path, null, null, Map.of(),
        Instant.now(), Instant.now());
  }

  private StagingItemRecord stagedPackage() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("Package", "demo");
    fields.put("Version", "1.0-1");
    fields.put("Architecture", "amd64");
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("filename", "demo_1.0-1_amd64.deb");
    descriptor.put("packageName", "demo");
    descriptor.put("version", "1.0-1");
    descriptor.put("architecture", "amd64");
    descriptor.put("sourcePackage", "demo");
    descriptor.put("fields", fields);
    descriptor.put("md5", "a".repeat(32));
    descriptor.put("sha1", "b".repeat(40));
    descriptor.put("sha256", "c".repeat(64));
    descriptor.put("size", 10);
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.APT, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/vnd.debian.binary-package", 1L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), path, null, null,
        Map.of("aptPackage", descriptor), Instant.now(), Instant.now());
  }
}
