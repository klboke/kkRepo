package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnsibleStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final AnsibleGalaxyService ansibleHosted = mock(AnsibleGalaxyService.class);
  private final AnsibleCollectionArchiveInspector inspector =
      mock(AnsibleCollectionArchiveInspector.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final AnsibleStagingContentAdapter adapter =
      new AnsibleStagingContentAdapter(hosted, ansibleHosted, inspector, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "galaxy-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED,
      "galaxy-hosted", true, 1L, "ALLOW", null, null, true, null, null, null, null, null,
      List.of());
  private final String path = "api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.2.3.tar.gz";

  @Test
  void acceptsOnlyArtifactPaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("api/v3/collections").isEmpty());
  }

  @Test
  void promotionReplaysTheGalaxyWriter() {
    when(ansibleHosted.putArtifact(any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(ansibleHosted).putArtifact(eq(runtime), eq(path), any(), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void stageCapturesCollectionMetadata() throws Exception {
    java.nio.file.Path temp = Files.createTempFile("ansible-staging-", ".tar.gz");
    var inspected = new AnsibleCollectionArchiveInspector.InspectedCollection(
        temp, 10L, "a".repeat(64), "acme-tools-1.2.3.tar.gz",
        "acme", "tools", "1.2.3",
        Map.of("description", "Tools"), Map.of("other.collection", ">=1.0.0"), ">=2.15");
    when(inspector.inspect(any())).thenReturn(inspected);
    when(hosted.storeStagedBlobFile(
        eq(runtime), eq(path), any(), any(), any(), any(), any()))
        .thenReturn(blob());

    var upload = adapter.stage(runtime, path,
        new ByteArrayInputStream(new byte[] {1}), "application/gzip", "alice", "127.0.0.1");

    assertEquals(path, upload.logicalPath());
    @SuppressWarnings("unchecked")
    Map<String, Object> descriptor =
        (Map<String, Object>) upload.attributes().get("ansibleCollection");
    assertEquals("acme", descriptor.get("namespace"));
    assertEquals("1.2.3", descriptor.get("version"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void servesTheVersionListFromStagedCollections() {
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("namespace", "acme");
    descriptor.put("name", "tools");
    descriptor.put("version", "1.2.3");
    descriptor.put("filename", "acme-tools-1.2.3.tar.gz");
    descriptor.put("sha256", "a".repeat(64));
    descriptor.put("size", 10);
    descriptor.put("metadata", Map.of("description", "Tools"));
    descriptor.put("dependencies", Map.of());
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.ANSIBLEGALAXY, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/gzip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of("ansibleCollection", descriptor),
        Instant.now(), Instant.now());
    when(staging.listItems(4L)).thenReturn(List.of(staged));
    when(ansibleHosted.stagedVersionList(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(runtime, 4L,
        "api/v3/collections/acme/tools/versions/", false,
        "https://repo.example/repository/galaxy-hosted").isPresent());

    org.mockito.ArgumentCaptor<List<String>> versions =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(ansibleHosted).stagedVersionList(
        eq(runtime), eq("acme"), eq("tools"),
        eq("https://repo.example/repository/galaxy-hosted"), versions.capture(),
        eq(100), eq(0), eq(false));
    assertEquals(List.of("1.2.3"), versions.getValue());
  }

  @Test
  void leavesLiveVersionListsAloneWhenNothingIsStaged() {
    when(staging.listItems(4L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(runtime, 4L,
        "api/v3/collections/acme/tools/versions/", false, "https://repo.example").isEmpty());
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.ANSIBLEGALAXY, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/gzip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/gzip", "alice",
        "127.0.0.1", now, now, Map.of());
  }
}
