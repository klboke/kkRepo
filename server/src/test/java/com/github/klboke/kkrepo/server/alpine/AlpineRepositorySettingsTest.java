package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

class AlpineRepositorySettingsTest {
  private final RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);

  @Test
  void parsesValidatedSettingsAndCachesByConfigurationVersion() {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "My Alpine Repo");
    Map<String, Object> alpine = Map.of(
        "distributions", List.of("V3.20", "v3.20", "edge"),
        "channels", List.of("MAIN", "testing"),
        "architectures", List.of("X86_64", "aarch64"),
        "metadataMode", "resign",
        "signatureType", "rsa",
        "keyFilename", "custom.rsa.pub",
        "verifyUpstreamSignatures", "false",
        "staleIfError", false,
        "description", " fixture ",
        "upstreamPublicKeys", List.of("key-a", "", nullSafe()));
    when(repositories.configurationVersion()).thenReturn(7L);
    when(repositories.findRecordById(runtime.id()))
        .thenReturn(Optional.of(record(runtime, Map.of("alpine", alpine))));
    AlpineRepositorySettings settings = new AlpineRepositorySettings(repositories, 30);

    AlpineRepositorySettings.Settings first = settings.get(runtime);
    AlpineRepositorySettings.Settings second = settings.get(runtime);

    assertSame(first, second);
    assertEquals(List.of("v3.20", "edge"), first.distributions());
    assertEquals(List.of("main", "testing"), first.channels());
    assertEquals(List.of("x86_64", "aarch64"), first.architectures());
    assertTrue(first.resign());
    assertFalse(first.verifyUpstreamSignatures());
    assertFalse(first.staleIfError());
    assertEquals("custom.rsa.pub", first.keyFilename());
    assertEquals("fixture", first.description());
    assertEquals(List.of("key-a"), first.upstreamPublicKeys());
    assertTrue(first.allows("v3.20", "main", "x86_64"));
    assertFalse(first.allows("v3.19", "main", "x86_64"));
    verify(repositories).findRecordById(runtime.id());

    when(repositories.configurationVersion()).thenReturn(8L);
    settings.get(runtime);
    verify(repositories, times(2)).findRecordById(runtime.id());
  }

  @Test
  void appliesTypeDefaultsAndSanitizesDefaultKeyName() {
    RepositoryRuntime proxy = runtime(RepositoryType.PROXY, "Fancy Alpine / Proxy");
    when(repositories.configurationVersion()).thenReturn(1L);
    when(repositories.findRecordById(proxy.id()))
        .thenReturn(Optional.of(record(proxy, Map.of())));

    AlpineRepositorySettings.Settings defaults =
        new AlpineRepositorySettings(repositories, 0).get(proxy);

    assertFalse(defaults.resign());
    assertFalse(defaults.verifyUpstreamSignatures());
    assertTrue(defaults.staleIfError());
    assertEquals("fancy-alpine---proxy.rsa.pub", defaults.keyFilename());
    assertTrue(defaults.allows("edge", "community", "armv7"));
  }

  @Test
  void rejectsWrongRuntimeMissingDefinitionAndInvalidConfiguration() {
    AlpineRepositorySettings settings = new AlpineRepositorySettings(repositories, 0);
    assertThrows(IllegalArgumentException.class, () -> settings.get(null));
    assertThrows(IllegalArgumentException.class,
        () -> settings.get(new RepositoryRuntime(
            2L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
            1L, "ALLOW", null, null, true, null, 1, 1, true, null, List.of())));

    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "alpine");
    when(repositories.findRecordById(runtime.id())).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> settings.get(runtime));

    assertInvalid(runtime, Map.of("metadataMode", "mirror"));
    assertInvalid(runtime, Map.of("signatureType", "DSA"));
    assertInvalid(runtime, Map.of("keyFilename", "../unsafe.pub"));
    assertInvalid(runtime, Map.of("distributions", List.of("bad/value")));
    assertInvalid(runtime, Map.of("channels", List.of("bad value")));
    assertInvalid(runtime, Map.of("architectures", List.of("bad/value")));
  }

  private void assertInvalid(RepositoryRuntime runtime, Map<String, Object> alpine) {
    when(repositories.findRecordById(runtime.id()))
        .thenReturn(Optional.of(record(runtime, Map.of("alpine", alpine))));
    assertThrows(IllegalArgumentException.class,
        () -> new AlpineRepositorySettings(repositories, 0).get(runtime));
  }

  private static String nullSafe() {
    return "";
  }

  private static RepositoryRuntime runtime(RepositoryType type, String name) {
    return new RepositoryRuntime(
        1L, name, RepositoryFormat.ALPINE, type, "alpine-" + type.name().toLowerCase(), true,
        1L, "ALLOW", null, null, true,
        type == RepositoryType.PROXY ? "https://example.invalid/alpine/" : null,
        60, 60, true, null, List.of());
  }

  private static RepositoryRecord record(
      RepositoryRuntime runtime, Map<String, Object> attributes) {
    return new RepositoryRecord(
        runtime.id(), runtime.name(), runtime.format(), runtime.type(), runtime.recipeName(), true,
        1L, null, runtime.proxyRemoteUrl(), null, null, runtime.writePolicy(), true, attributes);
  }
}
