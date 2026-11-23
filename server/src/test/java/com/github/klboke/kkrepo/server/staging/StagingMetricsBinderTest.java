package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StagingMetricsBinderTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final StagingMetricsBinder binder = new StagingMetricsBinder(staging, registry);

  @Test
  void publishesSessionItemAndGroupCounts() {
    when(staging.countSessionsByState())
        .thenReturn(Map.of(StagingDao.SESSION_OPEN, 2L, StagingDao.SESSION_PROMOTED, 1L));
    when(staging.countItemsByStatus())
        .thenReturn(Map.of(StagingDao.ITEM_STAGED, 3L));
    when(staging.countGroupChangesByStatus())
        .thenReturn(Map.of(StagingDao.GROUP_STAGED, 1L));

    binder.refresh();

    assertEquals(2.0, registry.get("staging.sessions")
        .tag("state", StagingDao.SESSION_OPEN).gauge().value());
    assertEquals(1.0, registry.get("staging.sessions")
        .tag("state", StagingDao.SESSION_PROMOTED).gauge().value());
    assertEquals(3.0, registry.get("staging.items")
        .tag("status", StagingDao.ITEM_STAGED).gauge().value());
    assertEquals(1.0, registry.get("staging.group_changes")
        .tag("status", StagingDao.GROUP_STAGED).gauge().value());
  }

  @Test
  void keepsServingExistingMetricsWhenTheDatabaseFails() {
    when(staging.countSessionsByState()).thenThrow(new IllegalStateException("down"));

    binder.refresh();

    assertEquals(0, registry.find("staging.sessions").gauges().size());
  }
}
