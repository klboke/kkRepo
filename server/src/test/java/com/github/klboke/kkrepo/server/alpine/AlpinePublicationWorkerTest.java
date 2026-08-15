package com.github.klboke.kkrepo.server.alpine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlpinePublicationWorkerTest {
  private final AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final AlpineRepositorySettings settings = mock(AlpineRepositorySettings.class);
  private final AlpineService service = mock(AlpineService.class);

  @Test
  void disabledWorkerDoesNotReadQueue() {
    worker(false).publishPending();
    verify(registry, never()).listPendingSuites(any(), any(), any(), anyInt());
  }

  @Test
  void publishesEligibleHostedGroupAndResignedProxySuites() {
    AlpineRegistryDao.SuiteState hosted = suite(1L, "v3.20/main/x86_64");
    AlpineRegistryDao.SuiteState group = suite(2L, "edge/main/aarch64");
    AlpineRegistryDao.SuiteState proxy = suite(3L, "v3.19/community/x86_64");
    when(registry.listPendingSuites(any(), any(), any(), eq(16)))
        .thenReturn(List.of(hosted, group, proxy));
    RepositoryRuntime hostedRuntime = runtime(1L, RepositoryType.HOSTED, true);
    RepositoryRuntime groupRuntime = runtime(2L, RepositoryType.GROUP, true);
    RepositoryRuntime proxyRuntime = runtime(3L, RepositoryType.PROXY, true);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(hostedRuntime));
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(groupRuntime));
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(proxyRuntime));
    when(settings.get(any())).thenReturn(settings(true));

    worker(true).publishPending();

    verify(service).publishPendingIfAvailable(hostedRuntime, hosted.distribution());
    verify(service).publishPendingIfAvailable(groupRuntime, group.distribution());
    verify(service).publishPendingIfAvailable(proxyRuntime, proxy.distribution());
  }

  @Test
  void skipsMissingOfflineWrongFormatAndPassthroughProxy() {
    when(registry.listPendingSuites(any(), any(), any(), anyInt())).thenReturn(List.of(
        suite(1L, "one"), suite(2L, "two"), suite(3L, "three"), suite(4L, "four")));
    when(runtimes.resolveById(1L)).thenReturn(Optional.empty());
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(runtime(2L, RepositoryType.HOSTED, false)));
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(new RepositoryRuntime(
        3L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, "ALLOW", null, null, true, null, 1, 1, true, null, List.of())));
    RepositoryRuntime passthrough = runtime(4L, RepositoryType.PROXY, true);
    when(runtimes.resolveById(4L)).thenReturn(Optional.of(passthrough));
    when(settings.get(passthrough)).thenReturn(settings(false));

    worker(true).publishPending();
    verify(service, never()).publishPendingIfAvailable(any(), any());
  }

  @Test
  void recordsInvalidConfigurationAndContainsPublicationFailure() {
    AlpineRegistryDao.SuiteState invalid = suite(1L, "invalid");
    AlpineRegistryDao.SuiteState failing = suite(2L, "failing");
    when(registry.listPendingSuites(any(), any(), any(), anyInt()))
        .thenReturn(List.of(invalid, failing));
    RepositoryRuntime first = runtime(1L, RepositoryType.HOSTED, true);
    RepositoryRuntime second = runtime(2L, RepositoryType.HOSTED, true);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(first));
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(second));
    when(settings.get(first)).thenThrow(new IllegalArgumentException("bad settings"));
    when(settings.get(second)).thenReturn(settings(true));
    doThrow(new IllegalStateException("publish failed"))
        .when(service).publishPendingIfAvailable(second, "failing");

    worker(true).publishPending();

    verify(registry).recordBuildFailure(
        eq(1L), eq("invalid"), eq(2L), eq("bad settings"), any(Instant.class));
    verify(service).publishPendingIfAvailable(second, "failing");
  }

  private AlpinePublicationWorker worker(boolean enabled) {
    return new AlpinePublicationWorker(
        registry, runtimes, settings, service, enabled, 16, -1, 10, -1);
  }

  private static AlpineRegistryDao.SuiteState suite(long repositoryId, String namespace) {
    return new AlpineRegistryDao.SuiteState(
        repositoryId, namespace, 2L, Instant.EPOCH, 1L, 1, null, null, null, Instant.EPOCH);
  }

  private static AlpineRepositorySettings.Settings settings(boolean resign) {
    return new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), resign, false, true, "key.rsa.pub", "RSA", "test",
        List.of());
  }

  private static RepositoryRuntime runtime(long id, RepositoryType type, boolean online) {
    return new RepositoryRuntime(
        id, "alpine-" + id, RepositoryFormat.ALPINE, type,
        "alpine-" + type.name().toLowerCase(), online, 1L, "ALLOW", null, null, true,
        type == RepositoryType.PROXY ? "https://example.invalid/" : null,
        60, 60, true, null, List.of());
  }
}
