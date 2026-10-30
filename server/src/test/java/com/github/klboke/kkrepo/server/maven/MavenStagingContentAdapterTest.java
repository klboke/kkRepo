package com.github.klboke.kkrepo.server.maven;

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
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MavenStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final MavenHostedService mavenHosted = mock(MavenHostedService.class);
  private final MavenStagingContentAdapter adapter =
      new MavenStagingContentAdapter(hosted, mavenHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "maven-hosted", RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven-hosted",
      true, 1L, "ALLOW", "RELEASE", "STRICT", true, null, null, null, null, null, List.of());
  private final String path = "com/acme/demo/1.0.0/demo-1.0.0.jar";

  @Test
  void stagesTheUploadedBytesAtTheCanonicalMavenPath() {
    AssetBlobRecord blob = blob();
    when(hosted.storeStagedBlob(
        eq(runtime), eq(path), any(), eq("application/java-archive"), any(), any(), any()))
        .thenReturn(blob);

    MavenStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, path, new ByteArrayInputStream(new byte[] {1}), "application/java-archive",
        "alice", "127.0.0.1");

    assertEquals(path, upload.logicalPath());
    assertEquals(blob, upload.blob());
    assertTrue(adapter.normalizePath(path).isPresent());
  }

  @Test
  void promotionReplaysTheNormalMavenWriter() {
    when(mavenHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(mavenHosted).put(
        eq(runtime), any(), any(), eq("application/java-archive"), eq("alice"), eq("127.0.0.1"));
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.MAVEN2, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/java-archive", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), path, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/java-archive", "alice",
        "127.0.0.1", now, now, Map.of());
  }
}
