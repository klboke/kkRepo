package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.staging.StagingService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PubStagingServiceTest {
  private final PubUploadSessionDao sessions = mock(PubUploadSessionDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final StagingService staging = mock(StagingService.class);
  private final PubHostedService pubHosted = mock(PubHostedService.class);
  private final PubStagingService service =
      new PubStagingService(sessions, assets, staging, pubHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED, "pub-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final PermissionSubject actor =
      new PermissionSubject("Local", "alice", Set.of(), null);

  @Test
  void finalizeStagesTheUploadedArchiveInsteadOfPublishingIt() {
    when(sessions.lock(1L, "session-1")).thenReturn(Optional.of(session()));
    when(assets.findBlobByObjectKeyHash(eq(1L), any())).thenReturn(Optional.of(blob()));
    when(pubHosted.publishSuccess(false)).thenReturn(MavenResponse.noBody(200));

    MavenResponse response = service.finalizeStaged(
        runtime, "session-1", "staging-1", "alice", null, "127.0.0.1", actor, false);

    assertEquals(200, response.status());
    verify(staging).stageExternalBlob(
        eq(runtime), eq("staging-1"), eq("packages/demo/versions/1.0.0.tar.gz"), eq(1L),
        eq("application/octet-stream"), any(), eq(actor), eq("127.0.0.1"));
    verify(sessions).markFinalized(eq(1L), any());
  }

  @Test
  void rejectsASessionOwnedByAnotherUser() {
    when(sessions.lock(1L, "session-1")).thenReturn(Optional.of(session()));
    org.junit.jupiter.api.Assertions.assertThrows(
        PubExceptions.BadRequestException.class,
        () -> service.finalizeStaged(
            runtime, "session-1", "staging-1", "bob", null, "127.0.0.1", actor, false));
  }

  private static PubUploadSessionRecord session() {
    Instant now = Instant.now();
    return new PubUploadSessionRecord(
        1L, 1L, "session-1", "token", "alice", null, PubUploadSessionDao.STATUS_UPLOADED,
        now.plusSeconds(3600), 1L, "blob-ref", "objects/pub", "md5", "sha1", "sha256",
        "sha512", 1L, "demo", "1.0.0", Map.of(), null, null, now, now);
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob-ref", new byte[32], "objects/pub", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/octet-stream", "alice",
        "127.0.0.1", now, now, Map.of());
  }
}
