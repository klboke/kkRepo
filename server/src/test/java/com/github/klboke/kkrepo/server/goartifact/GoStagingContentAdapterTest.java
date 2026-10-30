package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final GoHostedService goHosted = mock(GoHostedService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final GoStagingContentAdapter adapter =
      new GoStagingContentAdapter(hosted, goHosted, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "go-hosted", RepositoryFormat.GO, RepositoryType.HOSTED, "go-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "example.com/demo/@v/v1.0.0.zip";

  @Test
  void normalizesGoModulePaths() {
    assertEquals(path, adapter.normalizePath("/" + path).orElseThrow());
    assertTrue(adapter.normalizePath("../demo.go").isEmpty());
  }

  @Test
  void promotionReplaysTheGoWriter() {
    when(goHosted.publish(any(), any(), any(), any(), any()))
        .thenReturn(new GoHostedService.Published("example.com/demo", "v1.0.0", path, Instant.now()));
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(goHosted).publish(eq(runtime), eq(path), any(), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void mergesStagedVersionsIntoTheModuleList() throws Exception {
    when(goHosted.listVersions(any(), any())).thenReturn(List.of("v1.0.0"));
    when(staging.listItems(9L)).thenReturn(List.of(
        item("example.com/demo/@v/v1.1.0.zip", "ADD"),
        item("example.com/demo/@v/v1.0.0.zip", "DELETE"),
        item("example.com/other/@v/v9.9.9.zip", "ADD")));

    MavenResponse response = adapter.generatedMetadata(
        runtime, 9L, "example.com/demo/@v/list", false).orElseThrow();

    assertEquals("v1.1.0", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void keepsTheLiveListWhenNothingIsStagedForTheModule() throws Exception {
    when(goHosted.listVersions(any(), any())).thenReturn(List.of("v1.0.0", "v1.2.0"));
    when(staging.listItems(9L)).thenReturn(List.of());

    MavenResponse response = adapter.generatedMetadata(
        runtime, 9L, "example.com/demo/@v/list", true).orElseThrow();

    assertEquals("v1.0.0\nv1.2.0".length(), response.contentLength());
  }

  private StagingItemRecord item() {
    return item(path, "ADD");
  }

  private StagingItemRecord item(String logicalPath, String action) {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.GO, logicalPath, new byte[32], action, "STAGED",
        1L, 1L, "application/zip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        logicalPath, null, null, Map.of(), Instant.now(), Instant.now());
  }
}
