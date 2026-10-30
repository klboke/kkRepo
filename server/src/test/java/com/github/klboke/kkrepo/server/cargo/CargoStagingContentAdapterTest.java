package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CargoStagingContentAdapterTest {
  private final CargoHostedService cargoHosted = mock(CargoHostedService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final CargoStagingContentAdapter adapter = new CargoStagingContentAdapter(
      mock(RawHostedService.class), cargoHosted, new ObjectMapper(), staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED, "cargo-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void recognizesThePublishEndpoint() {
    assertTrue(adapter.supportsPublication("api/v1/crates/new"));
    assertTrue(!adapter.supportsPublication("api/v1/crates/demo/1.0.0/download"));
    assertEquals("api/v1/crates/new", adapter.normalizePath("/api/v1/crates/new").orElseThrow());
  }

  @Test
  void mapsSparseIndexDownloadRequestsToTheStagedArchive() {
    assertEquals("crates/demo/1.0.0/demo-1.0.0.crate",
        adapter.normalizePath("crates/demo/1.0.0/download").orElseThrow());
    assertEquals("crates/demo/1.0.0/demo-1.0.0.crate",
        adapter.normalizePath("crates/demo/1.0.0/demo-1.0.0.crate").orElseThrow());
  }

  @Test
  void preflightRejectsDeletesAndExistingVersions() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED, "cargo-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    assertNotNull(adapter.validateItem(repository, item("DELETE"), null));
    assertNotNull(adapter.validateItem(repository, item("ADD"), mock(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord.class)));
  }

  @Test
  void mergesStagedCrateVersionsIntoTheSparseIndex() throws Exception {
    when(cargoHosted.indexEntries(any(), any())).thenReturn(List.of(indexEntry("1.0.0")));
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.CARGO, "crates/demo/1.1.0/demo-1.1.0.crate",
        new byte[32], "ADD", "STAGED", 1L, 1L, "application/octet-stream", 1L,
        "2".repeat(64), "3".repeat(32), "1".repeat(40), null, null, null,
        Map.of("cargoPublishPath", "api/v1/crates/new", "indexEntry", indexEntry("1.1.0")),
        Instant.now(), Instant.now());
    when(staging.listItems(7L)).thenReturn(List.of(staged));

    var response = adapter.generatedMetadata(runtime, 7L, "de/mo/demo", false).orElseThrow();
    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(body.contains("\"vers\":\"1.0.0\""));
    assertTrue(body.contains("\"vers\":\"1.1.0\""));
  }

  @Test
  void fallsBackToTheLiveIndexWhenNothingIsStaged() {
    when(cargoHosted.indexEntries(any(), any())).thenReturn(List.of(indexEntry("1.0.0")));
    when(staging.listItems(7L)).thenReturn(List.of());
    var response = adapter.generatedMetadata(runtime, 7L, "de/mo/demo", false).orElseThrow();
    assertTrue(response.hasBody());
  }

  private static Map<String, Object> indexEntry(String version) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("name", "demo");
    entry.put("vers", version);
    entry.put("deps", List.of());
    entry.put("cksum", "a".repeat(64));
    entry.put("features", Map.of());
    entry.put("yanked", false);
    entry.put("v", 2);
    return entry;
  }

  private StagingItemRecord item(String action) {
    String path = "crates/demo/1.0.0/demo-1.0.0.crate";
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.CARGO, path, new byte[32], action, "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null, Map.of("cargoPublishPath", "api/v1/crates/new"),
        Instant.now(), Instant.now());
  }
}
