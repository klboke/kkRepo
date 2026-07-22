package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnsibleVersionListCacheTest {

  @Test
  void returnsOnlyCurrentRevisionSnapshotsAndCopiesTheVersionList() {
    InMemorySharedCache shared = new InMemorySharedCache();
    AnsibleVersionListCache cache = new AnsibleVersionListCache(shared, true);
    List<String> versions = new ArrayList<>(List.of("2.0.0", "1.0.0"));

    cache.put("repo:acme:tools", "revision-2", versions);
    versions.clear();

    assertEquals(
        List.of("2.0.0", "1.0.0"),
        cache.find("repo:acme:tools", "revision-2").orElseThrow());
    assertTrue(cache.find("repo:acme:tools", "revision-1").isEmpty());
  }

  @Test
  void rejectsExpiredSnapshotsAndNormalizesNullLists() throws Exception {
    InMemorySharedCache shared = new InMemorySharedCache();
    AnsibleVersionListCache cache = new AnsibleVersionListCache(shared, true);
    String identity = "repo:acme:tools";
    String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(identity.getBytes(StandardCharsets.UTF_8)));
    shared.putJson(
        "ansible-version-list",
        key,
        new AnsibleVersionListCache.Snapshot(
            null, "revision-2", Instant.now().minus(Duration.ofMinutes(11))),
        Duration.ofMinutes(1));

    assertTrue(cache.find(identity, "revision-2").isEmpty());
    assertEquals(List.of(), new AnsibleVersionListCache.Snapshot(
        null, "revision", Instant.now()).versions());
  }

  @Test
  void disabledOrUnavailableCacheIsAnOptionalOptimization() {
    SharedCache shared = mock(SharedCache.class);
    AnsibleVersionListCache disabled = new AnsibleVersionListCache(shared, false);

    disabled.put("identity", "revision", List.of("1.0.0"));

    assertTrue(disabled.find("identity", "revision").isEmpty());
    verifyNoInteractions(shared);
    assertTrue(new AnsibleVersionListCache(null, true)
        .find("identity", "revision").isEmpty());
  }

  @Test
  void cacheReadAndWriteFailuresDoNotAffectRepositoryCorrectness() {
    SharedCache shared = mock(SharedCache.class);
    when(shared.getJson(anyString(), anyString(), any(Class.class)))
        .thenThrow(new IllegalStateException("read failed"));
    doThrow(new IllegalStateException("write failed"))
        .when(shared).putJson(anyString(), anyString(), any(), any(Duration.class));
    AnsibleVersionListCache cache = new AnsibleVersionListCache(shared, true);

    assertEquals(Optional.empty(), cache.find("identity", "revision"));
    cache.put("identity", "revision", List.of("1.0.0"));
  }
}
