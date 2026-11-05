package com.github.klboke.kkrepo.server.conan;

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
import org.springframework.mock.web.MockHttpServletRequest;

class ConanStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final ConanService conanHosted = mock(ConanService.class);
  private final ConanStagingContentAdapter adapter =
      new ConanStagingContentAdapter(hosted, conanHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "conan-hosted", RepositoryFormat.CONAN, RepositoryType.HOSTED, "conan-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path =
      "v2/conans/demo/1.0/acme/stable/revisions/rrev/files/conanfile.py";

  @Test
  void normalizesOnlyRevisionFiles() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("v2/conans/demo/1.0/acme/stable").isEmpty());
  }

  @Test
  void stagingRequiresTheConanChecksumHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/repository/conan/" + path);
    request.setContent(new byte[] {1});
    org.junit.jupiter.api.Assertions.assertThrows(
        ConanExceptions.BadRequest.class,
        () -> adapter.stageRequest(runtime, path, request, "alice", "127.0.0.1"));
  }

  @Test
  void stagesTheFileWithItsChecksumAndReplaysTheConanWriter() throws Exception {
    String checksum = "a".repeat(40);
    when(hosted.storeStagedBlob(eq(runtime), eq(path), any(), any(), any(), any(), any()))
        .thenReturn(blob());
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/repository/conan/" + path);
    request.setContent(new byte[] {1});
    request.setContentType("text/x-python");
    request.addHeader("X-Checksum-Sha1", checksum);

    ConanStagingContentAdapter.StagedUpload upload = adapter.stageRequest(
        runtime, path, request, "alice", "127.0.0.1").orElseThrow();
    assertEquals(checksum, upload.attributes().get("conanChecksumSha1"));

    when(conanHosted.put(any(), any(), any(), any(Long.class), any(), any(), any(Boolean.class),
        any(), any())).thenReturn(MavenResponse.noBody(200));
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.CONAN, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "text/x-python", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of("conanChecksumSha1", checksum), Instant.now(), Instant.now());
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(conanHosted).put(
        eq(runtime), eq(path), any(), eq(1L), eq("text/x-python"), eq(checksum), eq(false),
        any(), eq("127.0.0.1"));
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "text/x-python", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}
