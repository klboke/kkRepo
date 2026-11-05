package com.github.klboke.kkrepo.server.conda;

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
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
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

class CondaStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final CondaService condaHosted = mock(CondaService.class);
  private final CondaArchiveInspector inspector = mock(CondaArchiveInspector.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final CondaStagingContentAdapter adapter =
      new CondaStagingContentAdapter(hosted, condaHosted, inspector, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "conda-hosted", RepositoryFormat.CONDA, RepositoryType.HOSTED, "conda-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "noarch/demo-1.0-py_0.conda";

  @Test
  void acceptsPackageAndRepodataPaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("main/noarch/repodata.json").isPresent());
    assertTrue(adapter.normalizePath("other/file.txt").isEmpty());
  }

  @Test
  void promotionAndDeleteReplayTheCondaWriter() {
    when(condaHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    when(condaHosted.delete(any(), any())).thenReturn(MavenResponse.noBody(204));
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    assertTrue(adapter.delete(runtime, path));
    verify(condaHosted).put(eq(runtime), eq(path), any(), any(), eq("alice"), eq("127.0.0.1"));
    verify(condaHosted).delete(runtime, path);
  }

  @Test
  @SuppressWarnings("unchecked")
  void servesRepodataFromStagedPackages() {
    String packagePath = "main/noarch/demo-1.0-py_0.conda";
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", "demo");
    metadata.put("version", "1.0");
    metadata.put("build", "py_0");
    metadata.put("depends", List.of("python >=3.9"));
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("filename", "demo-1.0-py_0.conda");
    descriptor.put("archiveFormat", "conda");
    descriptor.put("name", "demo");
    descriptor.put("version", "1.0");
    descriptor.put("build", "py_0");
    descriptor.put("buildNumber", 0);
    descriptor.put("metadata", metadata);
    descriptor.put("md5", "a".repeat(32));
    descriptor.put("sha256", "b".repeat(64));
    descriptor.put("size", 10);
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.CONDA, packagePath, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), packagePath, null, null,
        Map.of("condaPackage", descriptor), Instant.now(), Instant.now());
    when(staging.listItems(6L)).thenReturn(List.of(staged));
    when(condaHosted.stagedRepodata(any(), any(), any(), any(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(
        runtime, 6L, "main/noarch/repodata.json", false).isPresent());

    org.mockito.ArgumentCaptor<List<CondaRegistryDao.PackageRecord>> records =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(condaHosted).stagedRepodata(
        eq(runtime), any(), records.capture(), eq(Set.of()), eq(false));
    assertEquals("demo", records.getValue().getFirst().name());
    assertEquals("noarch", records.getValue().getFirst().subdir());
  }

  @Test
  void leavesLiveRepodataAloneWhenNothingIsStaged() {
    when(staging.listItems(6L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(
        runtime, 6L, "main/noarch/repodata.json", false).isEmpty());
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.CONDA, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), path, null, null, Map.of(), Instant.now(), Instant.now());
  }
}
