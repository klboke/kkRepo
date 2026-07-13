package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache.AuthorizationSnapshot;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecurityAuthorizationCacheTest {

  @Test
  void cachesAuthorizationSnapshotBySubjectAndRoleSet() {
    SecurityAuthorizationCache cache =
        new SecurityAuthorizationCache(new InMemorySharedCache(), new InMemoryVersionWatermark(), true, 10);
    AtomicInteger loads = new AtomicInteger();
    PermissionSubject subject = new PermissionSubject("Local", "alice", Set.of("role-b", "role-a"), null);
    PermissionSubject sameRolesDifferentOrder = new PermissionSubject(
        "Local",
        "alice",
        Set.of("role-a", "role-b"),
        null);

    AuthorizationSnapshot first = cache.getOrLoad(subject, () -> snapshot(loads.incrementAndGet()));
    AuthorizationSnapshot second = cache.getOrLoad(sameRolesDifferentOrder, () -> snapshot(loads.incrementAndGet()));

    assertEquals(1, loads.get());
    assertEquals(first, second);
  }

  @Test
  void invalidationBumpsVersionAndForcesReload() {
    SecurityAuthorizationCache cache =
        new SecurityAuthorizationCache(new InMemorySharedCache(), new InMemoryVersionWatermark(), true, 10);
    AtomicInteger loads = new AtomicInteger();
    PermissionSubject subject = new PermissionSubject("Local", "alice", Set.of("role-a"), null);

    cache.getOrLoad(subject, () -> snapshot(loads.incrementAndGet()));
    cache.invalidateAll();
    AuthorizationSnapshot reloaded = cache.getOrLoad(subject, () -> snapshot(loads.incrementAndGet()));

    assertEquals(2, loads.get());
    assertEquals(Set.of("role-2"), reloaded.roleIds());
  }

  @Test
  void localVersionCacheAvoidsVersionReadOnHotSnapshotHit() {
    CountingWatermark watermark = new CountingWatermark();
    SecurityAuthorizationCache cache = new SecurityAuthorizationCache(
        new InMemorySharedCache(),
        watermark,
        true,
        10,
        60);
    PermissionSubject subject = new PermissionSubject("Local", "alice", Set.of("role-a"), null);

    cache.getOrLoad(subject, () -> snapshot(1));
    watermark.versionReads.set(0);
    cache.getOrLoad(subject, () -> snapshot(2));

    assertEquals(0, watermark.versionReads.get(), "hot authorization hit should not reread global version");
  }

  private static AuthorizationSnapshot snapshot(int load) {
    return new AuthorizationSnapshot(
        Set.of("role-" + load),
        List.of(new SecurityPrivilegeRecord(
            "privilege-" + load,
            "privilege-" + load,
            null,
            "wildcard",
            false,
            Map.of("pattern", "nexus:*"))),
        Map.of());
  }

  private static final class CountingWatermark extends InMemoryVersionWatermark {
    private final AtomicInteger versionReads = new AtomicInteger();

    @Override
    public long current(String name) {
      if ("authz:global".equals(name)) {
        versionReads.incrementAndGet();
      }
      return super.current(name);
    }
  }
}
