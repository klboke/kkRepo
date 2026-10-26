package com.github.klboke.kkrepo.server.routingrules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

class RoutingRuleSnapshotLifecycleTest {
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class Scheduling {}

  @Test void eachReplicaRefreshesWithoutAnyTrafficAndContinuesAfterFailure() throws Exception {
    var phase = new AtomicInteger();
    var firstRules = mock(RoutingRuleDao.class);
    var secondRules = mock(RoutingRuleDao.class);
    var failures = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1)};
    var recoveries = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1)};
    var daos = List.of(firstRules, secondRules);
    for (int i = 0; i < daos.size(); i++) {
      int replica = i;
      when(daos.get(i).bindings()).thenAnswer(invocation -> {
        int current = phase.get();
        if (current == 1) {
          failures[replica].countDown();
          throw new IllegalStateException("database unavailable");
        }
        if (current == 2) recoveries[replica].countDown();
        return List.of();
      });
    }
    try (var first = context(firstRules); var second = context(secondRules)) {
      first.refresh(); second.refresh();
      verify(firstRules, timeout(5000).atLeast(2)).bindings();
      verify(secondRules, timeout(5000).atLeast(2)).bindings();
      phase.set(1);
      for (var failure : failures) assertTrue(failure.await(5, TimeUnit.SECONDS));
      phase.set(2);
      for (var recovery : recoveries) assertTrue(recovery.await(5, TimeUnit.SECONDS));
    }
  }

  @Test void startupFailsWhenDatabaseOrPersistedRulesCannotLoad() {
    for (boolean corrupt : List.of(false, true)) {
      var rules = mock(RoutingRuleDao.class);
      if (corrupt) {
        when(rules.bindings()).thenReturn(List.of(new RoutingRuleDao.Binding(1,
            new RoutingRuleDao.Rule(1, "bad", "", "BLOCK", List.of("[")))));
      } else {
        when(rules.bindings()).thenThrow(new IllegalStateException("database unavailable"));
      }
      try (var context = context(rules)) {
        assertThrows(BeanCreationException.class, context::refresh);
      }
    }
  }

  private AnnotationConfigApplicationContext context(RoutingRuleDao rules) {
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
        Map.of("kkrepo.routing-rules.refresh-interval", "25ms")));
    context.register(Scheduling.class);
    context.registerBean(RoutingRuleDao.class, () -> rules);
    context.registerBean(RepositoryRoutingPolicy.class);
    return context;
  }
}
