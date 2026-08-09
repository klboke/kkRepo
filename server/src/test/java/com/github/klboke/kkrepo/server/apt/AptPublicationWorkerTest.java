package com.github.klboke.kkrepo.server.apt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AptPublicationWorkerTest {

  @Test
  void publishesDebouncedDurableSuiteAndLeavesOfflineMigrationPending() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    AptRepositorySettings settings = mock(AptRepositorySettings.class);
    AptService service = mock(AptService.class);
    AptRegistryDao.SuiteState onlineSuite = suite(1, "stable", 4, 2);
    AptRegistryDao.SuiteState offlineSuite = suite(2, "migrated", 7, 0);
    RepositoryRuntime online = runtime(1, true, RepositoryType.HOSTED);
    RepositoryRuntime offline = runtime(2, false, RepositoryType.HOSTED);
    when(registry.listPendingSuites(any(), any(), any(), eq(16)))
        .thenReturn(List.of(onlineSuite, offlineSuite));
    when(runtimes.resolveById(1)).thenReturn(Optional.of(online));
    when(runtimes.resolveById(2)).thenReturn(Optional.of(offline));
    when(settings.get(online)).thenReturn(hostedSettings());
    when(service.publishPendingIfAvailable(online, "stable")).thenReturn(true);

    new AptPublicationWorker(
        registry, runtimes, settings, service, true, 16, 500, 30_000, 30_000)
        .publishPending();

    verify(service).publishPendingIfAvailable(online, "stable");
    verify(service, never()).publishPendingIfAvailable(offline, "migrated");
  }

  @Test
  void skipsPassthroughProxyAndThrottlesInvalidConfigurationFailures() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    AptRepositorySettings settings = mock(AptRepositorySettings.class);
    AptService service = mock(AptService.class);
    AptRegistryDao.SuiteState passthrough = suite(3, "stable", 2, 1);
    AptRegistryDao.SuiteState invalid = suite(4, "stable", 3, 1);
    RepositoryRuntime proxy = runtime(3, true, RepositoryType.PROXY);
    RepositoryRuntime hosted = runtime(4, true, RepositoryType.HOSTED);
    when(registry.listPendingSuites(any(), any(), any(), eq(8)))
        .thenReturn(List.of(passthrough, invalid));
    when(runtimes.resolveById(3)).thenReturn(Optional.of(proxy));
    when(runtimes.resolveById(4)).thenReturn(Optional.of(hosted));
    when(settings.get(proxy)).thenReturn(passthroughSettings());
    when(settings.get(hosted)).thenThrow(new IllegalArgumentException("bad APT settings"));

    new AptPublicationWorker(
        registry, runtimes, settings, service, true, 8, 0, 1_000, 1_000)
        .publishPending();

    verify(service, never()).publishPendingIfAvailable(any(), any());
    verify(registry).recordBuildFailure(
        eq(4L), eq("stable"), eq(3L), eq("bad APT settings"), any());
  }

  private static AptRegistryDao.SuiteState suite(
      long repositoryId, String distribution, long desired, long published) {
    return new AptRegistryDao.SuiteState(
        repositoryId,
        distribution,
        desired,
        Instant.EPOCH,
        published,
        1,
        Instant.EPOCH,
        null,
        null,
        Instant.EPOCH);
  }

  private static RepositoryRuntime runtime(
      long id, boolean online, RepositoryType type) {
    return new RepositoryRuntime(
        id,
        "apt-" + id,
        RepositoryFormat.APT,
        type,
        type == RepositoryType.HOSTED ? "apt-hosted" : "apt-proxy",
        online,
        1L,
        "ALLOW",
        null,
        null,
        true,
        type == RepositoryType.PROXY ? "https://apt.example/" : null,
        60,
        60,
        true,
        null,
        List.of());
  }

  private static AptRepositorySettings.Settings hostedSettings() {
    return new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, true, null, "kkRepo", "kkRepo");
  }

  private static AptRepositorySettings.Settings passthroughSettings() {
    return new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, false, null, "kkRepo", "kkRepo");
  }
}
