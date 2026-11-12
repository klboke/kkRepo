package com.github.klboke.kkrepo.server.npm;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NpmStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final NpmHostedService npmHosted = mock(NpmHostedService.class);
  private final NpmStagingContentAdapter adapter =
      new NpmStagingContentAdapter(hosted, npmHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "npm-hosted", RepositoryFormat.NPM, RepositoryType.HOSTED, "npm-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String packagePath = "acme-demo";

  @Test
  void normalizesOnlyPackagePublishPaths() {
    assertTrue(adapter.normalizePath("acme-demo").isPresent());
    assertTrue(adapter.normalizePath("acme-demo/-/acme-demo-1.0.0.tgz").isEmpty());
  }

  @Test
  void promotionReplaysTheNormalNpmPublishWriter() {
    when(npmHosted.putPackage(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(npmHosted).putPackage(
        eq(runtime), any(), any(), any(), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void stageRetainsThePublishJson() {
    when(hosted.storeStagedBlob(
        eq(runtime), eq(packagePath), any(), eq("application/json"), any(), any(), any()))
        .thenReturn(blob());
    NpmStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, packagePath, new ByteArrayInputStream(new byte[] {1}), "application/json",
        "alice", "127.0.0.1");
    assertEquals(packagePath, upload.logicalPath());
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.NPM, packagePath, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/json", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        packagePath, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/json", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}
