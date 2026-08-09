package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AptRepositorySettingsTest {

  @Test
  void readsHostedDefaultsAndProxyDefaults() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AptRepositorySettings settings = new AptRepositorySettings(repositories);
    RepositoryRuntime hosted = runtime(RepositoryType.HOSTED);
    RepositoryRuntime proxy = runtime(RepositoryType.PROXY);
    when(repositories.findById(hosted.id())).thenReturn(Optional.of(record(hosted, null)));

    AptRepositorySettings.Settings hostedSettings = settings.get(hosted);
    assertEquals("stable", hostedSettings.distribution());
    assertEquals("main", hostedSettings.component());
    assertEquals(List.of("amd64"), hostedSettings.architectures());
    assertTrue(hostedSettings.enforceDistribution());
    assertTrue(hostedSettings.resign());
    assertFalse(hostedSettings.flat());
    assertNull(hostedSettings.validUntilDays());

    when(repositories.findById(proxy.id())).thenReturn(Optional.of(record(proxy, Map.of())));
    AptRepositorySettings.Settings proxySettings = settings.get(proxy);
    assertEquals("", proxySettings.distribution());
    assertFalse(proxySettings.enforceDistribution());
    assertFalse(proxySettings.resign());
  }

  @Test
  void normalizesCustomSettingsAndToleratesInvalidOptionalNumbers() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AptRepositorySettings settings = new AptRepositorySettings(repositories);
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    Map<String, Object> apt = Map.of(
        "distribution", " testing ",
        "component", " contrib ",
        "architectures", Arrays.asList(" AMD64 ", "", null, "amd64", "ARM64"),
        "flat", "true",
        "enforceDistribution", false,
        "metadataMode", " resign ",
        "validUntilDays", "invalid",
        "origin", " Example ",
        "label", " Packages ");
    when(repositories.findById(runtime.id())).thenReturn(Optional.of(record(runtime, Map.of(
        "apt", apt))));

    AptRepositorySettings.Settings actual = settings.get(runtime);
    assertEquals("testing", actual.distribution());
    assertEquals("contrib", actual.component());
    assertEquals(List.of("amd64", "arm64"), actual.architectures());
    assertTrue(actual.flat());
    assertFalse(actual.enforceDistribution());
    assertTrue(actual.resign());
    assertNull(actual.validUntilDays());
    assertEquals("Example", actual.origin());
    assertEquals("Packages", actual.label());

    when(repositories.findById(runtime.id())).thenReturn(Optional.of(record(runtime, Map.of(
        "apt", Map.of("validUntilDays", 7, "flat", true,
            "enforceDistribution", "true", "metadataMode", "passthrough")))));
    settings = new AptRepositorySettings(repositories);
    actual = settings.get(runtime);
    assertEquals(7, actual.validUntilDays());
    assertTrue(actual.flat());
    assertTrue(actual.enforceDistribution());
    assertFalse(actual.resign());

    when(repositories.findById(runtime.id())).thenReturn(Optional.of(record(runtime, Map.of(
        "apt", Map.of("validUntilDays", "8")))));
    settings = new AptRepositorySettings(repositories);
    assertEquals(8, settings.get(runtime).validUntilDays());
  }

  @Test
  void cachesParsedSettingsAndReloadsAfterRepositoryInvalidation() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED);
    when(repositories.findById(runtime.id())).thenReturn(Optional.of(record(runtime, Map.of(
        "apt", Map.of("distribution", "stable")))));
    RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(repositories, 60);
    AptRepositorySettings settings = new AptRepositorySettings(registry, 60);

    assertEquals("stable", settings.get(runtime).distribution());
    assertEquals("stable", settings.get(runtime).distribution());
    verify(repositories, times(1)).findById(runtime.id());

    when(repositories.findById(runtime.id())).thenReturn(Optional.of(record(runtime, Map.of(
        "apt", Map.of("distribution", "testing")))));
    registry.invalidate(runtime.name());

    assertEquals("testing", settings.get(runtime).distribution());
    verify(repositories, times(2)).findById(runtime.id());
  }

  @Test
  void rejectsWrongOrMissingRepositoryDefinitions() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    AptRepositorySettings settings = new AptRepositorySettings(repositories);
    assertThrows(IllegalArgumentException.class, () -> settings.get(null));
    RepositoryRuntime wrong = new RepositoryRuntime(
        2, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true, 1L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
    assertThrows(IllegalArgumentException.class, () -> settings.get(wrong));

    RepositoryRuntime apt = runtime(RepositoryType.HOSTED);
    when(repositories.findById(apt.id())).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> settings.get(apt));
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1, "apt", RepositoryFormat.APT, type, "apt-" + type.name().toLowerCase(), true, 1L,
        "ALLOW", null, null, true,
        type == RepositoryType.PROXY ? "https://apt.example/" : null,
        60, 60, true, null, List.of());
  }

  private static RepositoryRecord record(RepositoryRuntime runtime, Map<String, Object> attributes) {
    return new RepositoryRecord(
        runtime.id(), runtime.name(), runtime.format(), runtime.type(), runtime.recipeName(), true,
        runtime.blobStoreId(), null, runtime.proxyRemoteUrl(), null, null, runtime.writePolicy(),
        true, attributes);
  }
}
