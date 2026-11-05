package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NpmStagingMetadataTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
  private final NpmHostedService npmHosted = mock(NpmHostedService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final NpmStagingContentAdapter adapter = new NpmStagingContentAdapter(
      mock(RawHostedService.class), npmHosted, staging, assets, storages, mapper);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "npm-hosted", RepositoryFormat.NPM, RepositoryType.HOSTED, "npm-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void buildsAPackumentWithRewrittenTarballUrls() throws Exception {
    String publish = """
        {"name":"demo","dist-tags":{"latest":"1.0.0"},
         "versions":{"1.0.0":{"name":"demo","version":"1.0.0",
           "dist":{"tarball":"demo-1.0.0.tgz"}}}}
        """;
    when(staging.findItem(eq(7L), eq(1L), any())).thenReturn(Optional.of(item()));
    when(assets.findBlobById(5L)).thenReturn(Optional.of(blob()));
    BlobStorage storage = mock(BlobStorage.class);
    when(storages.forBlobStoreId(1L)).thenReturn(storage);
    when(storage.get(any())).thenReturn(Optional.of(
        new ByteArrayInputStream(publish.getBytes(StandardCharsets.UTF_8))));
    when(npmHosted.get(any(), any(), any(), eq(false)))
        .thenThrow(new NpmExceptions.NpmNotFoundException("demo"));

    String body = new String(adapter.generatedMetadata(
        runtime, 7L, "demo", false, "https://repo.example/repository/npm-hosted")
        .orElseThrow().body().readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(body.contains("\"name\":\"demo\""));
    assertTrue(body.contains("https://repo.example/repository/npm-hosted/demo/-/"));
    assertTrue(body.contains("demo-1.0.0.tgz"));
  }

  private static StagingItemRecord item() {
    return new StagingItemRecord(
        2L, 7L, 1L, RepositoryFormat.NPM, "demo", new byte[32], "ADD", "STAGED",
        1L, 5L, "application/json", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        null, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        5L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/json", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}
