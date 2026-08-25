package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PypiRepositorySettingsTest {
  private final RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);

  @Test
  void defaultsLegacyRepositoriesToSimpleAndPreservesAnEmptyRootIndex() {
    RepositoryRuntime runtime = runtime();
    when(repositories.findRecordById(runtime.id()))
        .thenReturn(Optional.of(record(runtime, Map.of())));

    PypiRepositorySettings settings = new PypiRepositorySettings(repositories, 0);
    assertEquals("/simple", settings.get(runtime).indexPath());

    when(repositories.findRecordById(runtime.id())).thenReturn(Optional.of(record(
        runtime, Map.of("pypi", Map.of("indexPath", "")))));
    assertEquals("", settings.get(runtime).indexPath());
  }

  @Test
  void canonicalizesCustomPathsAndInvalidatesTheLocalHotCacheByCatalogVersion() {
    RepositoryRuntime runtime = runtime();
    when(repositories.configurationVersion()).thenReturn(7L);
    when(repositories.findRecordById(runtime.id())).thenReturn(Optional.of(record(
        runtime, Map.of("pypi", Map.of("indexPath", " api/simple/ ")))));
    PypiRepositorySettings settings = new PypiRepositorySettings(repositories, 30);

    assertEquals("/api/simple", settings.get(runtime).indexPath());
    assertEquals("/api/simple", settings.get(runtime).indexPath());
    verify(repositories).findRecordById(runtime.id());

    when(repositories.configurationVersion()).thenReturn(8L);
    when(repositories.findRecordById(runtime.id())).thenReturn(Optional.of(record(
        runtime, Map.of("pypi", Map.of("indexPath", "")))));
    assertEquals("", settings.get(runtime).indexPath());
    verify(repositories, times(2)).findRecordById(runtime.id());
  }

  @Test
  void rejectsNonProxyRuntimeMissingDefinitionAndInvalidPaths() {
    PypiRepositorySettings settings = new PypiRepositorySettings(repositories, 0);
    assertThrows(IllegalArgumentException.class, () -> settings.get(null));
    RepositoryRuntime hosted = new RepositoryRuntime(
        2L, "pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED, "pypi-hosted",
        true, 1L, "ALLOW", null, null, true, null, 1, 1, true, null, List.of());
    assertThrows(IllegalArgumentException.class, () -> settings.get(hosted));

    RepositoryRuntime runtime = runtime();
    when(repositories.findRecordById(runtime.id())).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> settings.get(runtime));

    when(repositories.findRecordById(runtime.id())).thenReturn(Optional.of(record(
        runtime, Map.of("pypi", Map.of("indexPath", "/../private")))));
    assertThrows(IllegalArgumentException.class, () -> settings.get(runtime));
  }

  @Test
  void mapsConfiguredIndexPathBelowTheRemoteUrl() {
    assertEquals("simple/", PypiRemoteIndexPath.upstreamPath("/simple", null));
    assertEquals("simple/demo/", PypiRemoteIndexPath.upstreamPath("/simple", "demo"));
    assertEquals("", PypiRemoteIndexPath.upstreamPath("", null));
    assertEquals("demo/", PypiRemoteIndexPath.upstreamPath("", "demo"));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "pypi-proxy", RepositoryFormat.PYPI, RepositoryType.PROXY, "pypi-proxy",
        true, 1L, null, null, null, true, "https://pypi.example/", 1, 1, true, null,
        List.of());
  }

  private static RepositoryRecord record(
      RepositoryRuntime runtime, Map<String, Object> attributes) {
    return new RepositoryRecord(
        runtime.id(), runtime.name(), runtime.format(), runtime.type(), runtime.recipeName(), true,
        runtime.blobStoreId(), null, runtime.proxyRemoteUrl(), null, null, null, true, attributes);
  }
}
