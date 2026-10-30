package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelmStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final HelmHostedService helmHosted = mock(HelmHostedService.class);
  private final HelmStagingContentAdapter adapter =
      new HelmStagingContentAdapter(hosted, helmHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "helm-hosted", RepositoryFormat.HELM, RepositoryType.HOSTED, "helm-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "demo-1.0.0.tgz";

  @Test
  void acceptsOnlyChartArchives() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("index.yaml").isEmpty());
    assertTrue(adapter.normalizePath("../demo-1.0.0.tgz").isEmpty());
  }

  @Test
  void promotionReplaysTheNormalHelmWriter() {
    when(helmHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.noBody(200));
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(helmHosted).put(
        eq(runtime), eq(path), any(), eq("application/gzip"), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void stageRetainsTheChartBytesAndMetadata() throws Exception {
    when(hosted.storeStagedBlobFile(
        eq(runtime), eq(path), any(), eq("application/gzip"), any(), any(), any()))
        .thenReturn(blob());
    HelmStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, path, new ByteArrayInputStream(chart()), "application/gzip",
        "alice", "127.0.0.1");
    assertEquals(path, upload.logicalPath());
    assertEquals("demo", upload.attributes().get("helmName"));
    assertEquals("1.0.0", upload.attributes().get("helmVersion"));
  }

  private static byte[] chart() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      byte[] chartYaml = "apiVersion: v2\nname: demo\nversion: 1.0.0\n"
          .getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry("demo/Chart.yaml");
      entry.setSize(chartYaml.length);
      tar.putArchiveEntry(entry);
      tar.write(chartYaml);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.HELM, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/gzip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/gzip", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}
