package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BasicAuthCacheTest {

  @Test
  void repeatedCredentialUsesTypedLocalTierWithoutSecondLookupOrJsonRead() {
    SharedCache shared = spy(new InMemorySharedCache());
    BasicAuthCache cache = new BasicAuthCache(shared, true, 60, 5);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "local", "alice", "local", null,
        new PermissionSubject("local", "alice", Set.of("developers"), null));
    AtomicInteger loads = new AtomicInteger();

    assertEquals(subject, cache.find("alice", "secret", () -> {
      loads.incrementAndGet();
      return Optional.of(subject);
    }).orElseThrow());
    assertEquals(subject, cache.find("alice", "secret", () -> {
      loads.incrementAndGet();
      return Optional.of(subject);
    }).orElseThrow());

    assertEquals(1, loads.get());
    verify(shared, times(1)).getString(eq("basic-auth"), anyString());
    verify(shared, never()).getJson(
        eq("basic-auth"), anyString(), eq(AuthenticatedSubject.class));
  }

  @Test
  void negativeCredentialUsesTypedLocalTierAndEvictionClearsIt() {
    SharedCache shared = spy(new InMemorySharedCache());
    BasicAuthCache cache = new BasicAuthCache(shared, true, 60, 5);
    AtomicInteger loads = new AtomicInteger();

    assertTrue(cache.find("alice", "wrong", () -> {
      loads.incrementAndGet();
      return Optional.empty();
    }).isEmpty());
    assertTrue(cache.find("alice", "wrong", () -> {
      loads.incrementAndGet();
      return Optional.empty();
    }).isEmpty());
    assertEquals(1, loads.get());

    cache.evictAll();
    cache.find("alice", "wrong", () -> {
      loads.incrementAndGet();
      return Optional.empty();
    });
    assertEquals(2, loads.get());
  }

  @Test
  void positiveCredentialRoundTripsThroughSharedTierAcrossReplicas() {
    SharedCache shared = new InMemorySharedCache();
    BasicAuthCache writer = new BasicAuthCache(shared, true, 60, 5);
    BasicAuthCache reader = new BasicAuthCache(shared, true, 60, 5);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "local", "alice", "local", null,
        new PermissionSubject("local", "alice", Set.of("developers"), null));
    writer.find("alice", "secret", () -> Optional.of(subject));
    AtomicInteger readerLoads = new AtomicInteger();

    Optional<AuthenticatedSubject> cached = reader.find("alice", "secret", () -> {
      readerLoads.incrementAndGet();
      return Optional.empty();
    });

    assertEquals(subject, cached.orElseThrow());
    assertEquals(0, readerLoads.get());
  }

  @Test
  void negativeCredentialRoundTripsThroughSharedTierAcrossReplicas() {
    SharedCache shared = new InMemorySharedCache();
    BasicAuthCache writer = new BasicAuthCache(shared, true, 60, 5);
    BasicAuthCache reader = new BasicAuthCache(shared, true, 60, 5);
    writer.find("alice", "wrong", Optional::empty);
    AtomicInteger readerLoads = new AtomicInteger();

    Optional<AuthenticatedSubject> cached = reader.find("alice", "wrong", () -> {
      readerLoads.incrementAndGet();
      return Optional.empty();
    });

    assertTrue(cached.isEmpty());
    assertEquals(0, readerLoads.get());
  }

  @Test
  void malformedSharedEntryIsEvictedBeforeCredentialReload() {
    SharedCache shared = mock(SharedCache.class);
    when(shared.getString(eq("basic-auth"), anyString())).thenReturn(Optional.of("{malformed"));
    BasicAuthCache cache = new BasicAuthCache(shared, true, 60, 5);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "local", "alice", "local", null,
        new PermissionSubject("local", "alice", Set.of(), null));

    assertEquals(subject, cache.find("alice", "secret", () -> Optional.of(subject)).orElseThrow());

    verify(shared).evict(eq("basic-auth"), anyString());
  }
}
