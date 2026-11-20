package com.github.klboke.kkrepo.server.gitlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.staging.StagingService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GitLfsStagingTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final StagingService staging = mock(StagingService.class);
  private final GitLfsStagingService service = new GitLfsStagingService(hosted, staging);
  private final GitLfsHostedService gitLfsHosted = mock(GitLfsHostedService.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final GitLfsStagingContentAdapter adapter =
      new GitLfsStagingContentAdapter(gitLfsHosted, assets);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "lfs-hosted", RepositoryFormat.GITLFS, RepositoryType.HOSTED, "lfs-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String oid = "a".repeat(64);

  @Test
  void stagesTheObjectOnlyAfterDigestVerification() throws Exception {
    when(hosted.storeStagedBlob(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(blob(oid, 3L));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(new byte[] {1, 2, 3});
    request.setContentType("application/octet-stream");
    service.put(runtime, oid, "staging-1", request, subject(), "127.0.0.1");
    verify(staging).stageExternalBlob(
        eq(runtime), eq("staging-1"), eq("lfs/" + oid), eq(1L),
        eq("application/octet-stream"), any(), any(), eq("127.0.0.1"));
  }

  @Test
  void rejectsADigestMismatch() {
    when(hosted.storeStagedBlob(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(blob("b".repeat(64), 3L));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(new byte[] {1, 2, 3});
    assertThrows(GitLfsException.class,
        () -> service.put(runtime, oid, "staging-1", request, subject(), "127.0.0.1"));
  }

  @Test
  void adapterPromotesObjectsAndRejectsExistingOnes() {
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.GITLFS, "lfs/" + oid, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/octet-stream", 3L, oid, "3".repeat(32), "1".repeat(40),
        null, null, null, Map.of("gitLfsOid", oid), Instant.now(), Instant.now());
    assertEquals("published", adapter.promote(
        runtime, item, () -> new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}),
        "alice", "127.0.0.1").orElseThrow());
    RepositoryRecord repository = new RepositoryRecord(
        1L, "lfs-hosted", RepositoryFormat.GITLFS, RepositoryType.HOSTED, "lfs-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    when(assets.findAssetByPath(1L, oid)).thenReturn(Optional.of(mock(AssetRecord.class)));
    assertTrue(adapter.validateItem(repository, item, null) != null);
  }

  private static AuthenticatedSubject subject() {
    return new AuthenticatedSubject(
        "Local", "alice", null, null,
        new com.github.klboke.kkrepo.auth.PermissionSubject("Local", "alice",
            java.util.Set.of(), null));
  }

  private static AssetBlobRecord blob(String sha256, long size) {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        sha256, "3".repeat(32), size, "application/octet-stream", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}
