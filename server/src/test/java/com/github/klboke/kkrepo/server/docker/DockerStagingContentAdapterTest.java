package com.github.klboke.kkrepo.server.docker;

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
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DockerStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final DockerHostedService dockerHosted = mock(DockerHostedService.class);
  private final DockerStagingContentAdapter adapter =
      new DockerStagingContentAdapter(hosted, dockerHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "docker-hosted", RepositoryFormat.DOCKER, RepositoryType.HOSTED, "docker-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "manifest/library/alpine/latest";

  @Test
  void normalizesOnlyManifestPaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("blobs/sha256:abc").isEmpty());
    assertTrue(adapter.normalizePath("blob/sha256:abc").isPresent());
    assertTrue(adapter.normalizePath("manifest/library").isEmpty());
  }

  @Test
  void stagesManifestTagsFromThePublishRequest() throws Exception {
    when(hosted.storeStagedBlob(
        eq(runtime), eq(path), any(), eq("application/vnd.oci.image.manifest.v1+json"),
        any(), any(), any()))
        .thenReturn(blob());
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/v2/docker-hosted/library/alpine/manifests/latest");
    request.setContentType("application/vnd.oci.image.manifest.v1+json");
    request.addParameter("tag", "latest");
    request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    DockerStagingContentAdapter.StagedUpload upload = adapter.stageRequest(
        runtime, path, request, "alice", "127.0.0.1").orElseThrow();

    assertEquals(path, upload.logicalPath());
    assertEquals(List.of("latest"), upload.attributes().get("dockerTags"));
  }

  @Test
  void promotionBindsTheManifestAndTags() throws Exception {
    when(dockerHosted.putManifest(any(), eq("library/alpine"), eq("latest"), any(), any(), any(),
        any(), any())).thenReturn(DockerResponse.noBody(201));
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.DOCKER, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/vnd.oci.image.manifest.v1+json", 2L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), path, null, null,
        Map.of("dockerTags", List.of("latest")), Instant.now(), Instant.now());

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(dockerHosted).putManifest(
        eq(runtime), eq("library/alpine"), eq("latest"), any(),
        eq("application/vnd.oci.image.manifest.v1+json"), eq(List.of("latest")),
        eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void blobRetentionItemsDoNotPublishLiveRows() {
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.DOCKER, "blob/sha256:abc", new byte[32], "ADD", "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null, Map.of("dockerBlob", true), Instant.now(), Instant.now());
    assertEquals("retained", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 2L, "application/vnd.oci.image.manifest.v1+json",
        "alice", "127.0.0.1", now, now, Map.of());
  }
}
