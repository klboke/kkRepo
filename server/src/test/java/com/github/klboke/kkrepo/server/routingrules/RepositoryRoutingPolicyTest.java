package com.github.klboke.kkrepo.server.routingrules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Rule;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Binding;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RepositoryRoutingPolicyTest {
  private final RoutingRuleDao rules = mock(RoutingRuleDao.class);
  private final RepositoryRoutingPolicy policy = new RepositoryRoutingPolicy(rules);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(repositories, 300);

  @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

  @Test void requestsOnlyReadSnapshotUntilExplicitRefresh() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    when(rules.bindings()).thenReturn(List.of(binding("ALLOW", "/private/.*")));
    for (int i = 0; i < 1000; i++) {
      assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "private/a", request()));
      assertSame(proxy, policy.prepare(proxy, "public/a", request()));
    }
    verify(rules).bindings();
    policy.syncDatabaseToMemory();
    assertSame(proxy, policy.prepare(proxy, "private/a", request()));
    verify(rules, times(2)).bindings();
  }

  @Test void replicasConvergeOnRefreshWhileInflightRequestKeepsItsSnapshot() {
    var first = new RepositoryRoutingPolicy(rules);
    var second = new RepositoryRoutingPolicy(rules);
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    RepositoryRuntime group = runtime(3, RepositoryType.GROUP).withMembers(List.of(proxy));
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/never")));
    first.initialize(); second.initialize();
    var inflight = request();
    assertEquals(List.of(proxy), first.prepare(group, "private/a", inflight).members());
    String oldToken = RepositoryRoutingPolicy.cacheToken(group.id(), "1");
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    first.syncDatabaseToMemory();
    assertTrue(first.prepare(group, "private/a", request()).members().isEmpty());
    assertNotEquals(oldToken, RepositoryRoutingPolicy.cacheToken(group.id(), "1"));
    assertEquals(List.of(proxy), second.prepare(group, "private/a", request()).members());
    second.syncDatabaseToMemory();
    assertTrue(second.prepare(group, "private/a", request()).members().isEmpty());
    assertEquals(List.of(proxy), first.prepare(group, "private/a", inflight).members());
    when(rules.bindings()).thenReturn(List.of());
    first.syncDatabaseToMemory(); second.syncDatabaseToMemory();
    assertEquals(List.of(proxy), first.prepare(group, "private/a", request()).members());
    assertEquals("1", RepositoryRoutingPolicy.cacheToken(group.id(), "1"));
    assertEquals(List.of(proxy), second.prepare(group, "private/a", request()).members());
  }

  @Test void emptySnapshotAndMissingBindingsNeverTriggerDatabaseLoads() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of());
    policy.initialize();
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    for (int i = 0; i < 1000; i++) assertSame(proxy, policy.prepare(proxy, "private/a", request()));
    verify(rules).bindings();
    policy.syncDatabaseToMemory();
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "private/a", request()));
  }

  @Test void hostedReadsAndWritesDoNotLoadRules() {
    RepositoryRuntime hosted = runtime(2, RepositoryType.HOSTED);
    for (String method : List.of("GET", "HEAD", "PUT")) {
      var request = request(); request.setMethod(method);
      assertSame(hosted, policy.prepare(hosted, "file", request));
    }
    var write = request(); write.setMethod("PUT");
    policy.prepare(runtime(1, RepositoryType.PROXY), "file", write);
    verifyNoInteractions(rules);
  }

  @Test void concurrentRequestsKeepReadingOldSnapshotDuringSlowRefresh() throws Exception {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(rules.bindings()).thenAnswer(ignored -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return List.of();
    });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> refresh = executor.submit(policy::syncDatabaseToMemory);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        List<Future<?>> requests = new ArrayList<>();
        for (int i = 0; i < 32; i++) requests.add(executor.submit(() -> {
          try {
            assertThrows(RepositoryRoutingPolicy.Blocked.class,
                () -> policy.prepare(proxy, "private/a", request()));
          } finally { RequestContextHolder.resetRequestAttributes(); }
        }));
        for (Future<?> request : requests) request.get(2, TimeUnit.SECONDS);
        policy.syncDatabaseToMemory(); // overlapping refresh must skip, not query or wait
        verify(rules, times(2)).bindings();
      } finally { release.countDown(); }
      refresh.get(5, TimeUnit.SECONDS);
    }
    assertSame(proxy, policy.prepare(proxy, "private/a", request()));
  }

  @Test void failedRefreshRetainsLastGoodSnapshotAndRecovers() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    when(rules.bindings()).thenThrow(new DataAccessResourceFailureException("unavailable"));
    policy.syncDatabaseToMemory();
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "private/a", request()));
    assertSame(proxy, policy.prepare(proxy, "public/a", request()));
    doReturn(List.of(binding("ALLOW", "/private/.*"))).when(rules).bindings();
    policy.syncDatabaseToMemory();
    assertSame(proxy, policy.prepare(proxy, "private/a", request()));
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "public/a", request()));
  }

  @Test void invalidRuleDuringRefreshCannotPublishAPartialSnapshot() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    when(rules.bindings()).thenReturn(List.of(binding("ALLOW", "/private/.*"),
        new Binding(2, new Rule(20, "invalid", "", "BLOCK", List.of("[")))));
    policy.syncDatabaseToMemory();
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "private/a", request()));
    assertSame(proxy, policy.prepare(proxy, "public/a", request()));
  }

  @Test void failedStartupCannotPublishAnEmptyPolicyOrLoadFromRequests() {
    when(rules.bindings()).thenThrow(new DataAccessResourceFailureException("unavailable"));
    assertThrows(DataAccessResourceFailureException.class, policy::initialize);
    assertThrows(IllegalStateException.class,
        () -> policy.prepare(runtime(1, RepositoryType.PROXY), "public/a", request()));
    verify(rules).bindings();
  }

  @Test void projectsNestedGroupsWithoutChangingCachedDefinitionsAndScopesGroupCaches() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    RepositoryRuntime hosted = runtime(2, RepositoryType.HOSTED);
    RepositoryRuntime nested = runtime(3, RepositoryType.GROUP).withMembers(List.of(proxy, hosted));
    RepositoryRuntime group = runtime(4, RepositoryType.GROUP).withMembers(List.of(nested));
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    RepositoryRuntime filtered = policy.prepare(group, "private/a", request());
    assertEquals(List.of(hosted), filtered.members().getFirst().members());
    assertEquals(List.of(proxy, hosted), group.members().getFirst().members());
    String blockedToken = RepositoryRoutingPolicy.cacheToken(group.id(), "1");
    assertNotEquals("1", blockedToken);
    policy.prepare(group, "public/a", request());
    assertNotEquals(blockedToken, RepositoryRoutingPolicy.cacheToken(group.id(), "1"));
    assertSame(group, policy.project(group));
    RequestContextHolder.resetRequestAttributes();
    assertEquals("1", RepositoryRoutingPolicy.cacheToken(group.id(), "1"));
  }

  @Test void durableGroupReloadsCannotRestoreDeniedMembers() {
    RepositoryRecord proxy = record(1, RepositoryType.PROXY);
    RepositoryRecord hosted = record(2, RepositoryType.HOSTED);
    RepositoryRecord group = record(3, RepositoryType.GROUP);
    when(repositories.findById(3L)).thenReturn(Optional.of(group));
    when(repositories.listMembers(3L)).thenReturn(List.of(proxy, hosted));
    RepositoryRuntime unfiltered = registry.resolveFreshById(3).orElseThrow();
    ReflectionTestUtils.setField(registry, "routingPolicy", policy);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/.*")));
    policy.initialize();
    policy.prepare(unfiltered, "item", request());
    assertEquals(List.of(2L), registry.resolveFreshById(3).orElseThrow().members().stream()
        .map(RepositoryRuntime::id).toList());
  }

  @Test void includesQueriesUsesWholePathAnyMatchAndDoesNotApplyToHostedOrWrites() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("ALLOW", "/a", "/b\\?x=1")));
    policy.initialize();
    var request = request(); request.addParameter("x", "1");
    assertSame(proxy, policy.prepare(proxy, "b", request));
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "aa", request()));
    assertSame(runtime(2, RepositoryType.HOSTED).type(),
        policy.prepare(runtime(2, RepositoryType.HOSTED), "aa", request()).type());
    var put = request(); put.setMethod("PUT");
    assertSame(proxy, policy.prepare(proxy, "aa", put));
  }

  @Test void decodesRawPathsOnceAndSeparatesDecodedQuestionMarksFromQueries() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*", "/a\\?b\\?x=1")));
    policy.initialize();
    var encoded = request(); encoded.setRequestURI("/repository/%74est/%70rivate/file");
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepareRepository(proxy, encoded));
    assertSame(proxy, policy.prepare(proxy, "/%2570rivate/file", request()));
    var query = request(); query.addParameter("x", "1");
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "/a%3Fb", query));
    assertSame(proxy, policy.prepare(proxy, "/public/a+b", request()));
  }

  @Test void alternatePathsCannotRestoreBlockedProxyMembersOrHitTheirCachedAssets() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    RepositoryRuntime hosted = runtime(2, RepositoryType.HOSTED);
    RepositoryRuntime group = runtime(3, RepositoryType.GROUP).withMembers(List.of(proxy, hosted));
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    for (String path : List.of("//private/file", "/public/../private/file", "/public/%2e%2e/private/file", "/./private/file")) {
      assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, path, request()), path);
      assertEquals(List.of(hosted), policy.prepare(group, path, request()).members(), path);
    }
    var invalid = assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> policy.prepare(proxy, "/../private/file", request()));
    assertEquals(400, invalid.getStatusCode().value());
  }

  @Test void malformedEncodingAndCorruptPersistedModesNeverAllowDownloads() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> policy.prepare(proxy, "/%GG", request()));
    assertEquals(400, failure.getStatusCode().value());
    when(rules.bindings()).thenReturn(List.of(binding("unknown", "/private/.*")));
    assertThrows(IllegalStateException.class, policy::initialize);
  }

  @Test void canonicalIdentitiesAreNotDecodedAgainAndStillFilterGroups() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    RepositoryRuntime hosted = runtime(2, RepositoryType.HOSTED);
    RepositoryRuntime group = runtime(3, RepositoryType.GROUP).withMembers(List.of(proxy, hosted));
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/simple/private/.*")));
    policy.initialize();
    assertThrows(RepositoryRoutingPolicy.Blocked.class,
        () -> policy.prepareCanonical(proxy, "simple/private/", request()));
    assertEquals(List.of(hosted), policy.prepareCanonical(group, "simple/private/", request()).members());
    assertSame(proxy, policy.prepareCanonical(proxy, "/simple/%70rivate/", request()));
    assertSame(proxy, policy.prepareCanonical(proxy, "/simple/private;x=2/", request()));
    var put = request(); put.setMethod("PUT");
    assertSame(proxy, policy.prepareCanonical(proxy, "simple/private/", put));
  }

  @Test void repositoryBoundarySupportsEncodedSegmentsCustomPrefixAndContext() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    policy.initialize();
    var request = request();
    request.setContextPath("/ctx");
    new com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix("/custom/repos")
        .onStartup(request.getServletContext());
    request.setRequestURI("/ctx/custom/%72epos/%74est/%70rivate/file");
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepareRepository(proxy, request));
    var opaque = request();
    opaque.setRequestURI("/repository/test/%2570rivate/file");
    assertSame(proxy, policy.prepareRepository(proxy, opaque));
  }

  private Binding binding(String mode, String... matchers) {
    return new Binding(1, new Rule(10, "rule", "", mode, List.of(matchers)));
  }
  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("GET", "/repository/test/file");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); return request;
  }
  private RepositoryRuntime runtime(long id, RepositoryType type) {
    when(repositories.findById(id)).thenReturn(Optional.of(record(id, type)));
    return registry.resolveFreshById(id).orElseThrow();
  }
  private RepositoryRecord record(long id, RepositoryType type) {
    return new RepositoryRecord(id, "repo" + id, RepositoryFormat.RAW, type,
        "raw-" + type.name().toLowerCase(), true, 1L, null, "https://example.com/",
        null, null, null, false, Map.of());
  }
}
