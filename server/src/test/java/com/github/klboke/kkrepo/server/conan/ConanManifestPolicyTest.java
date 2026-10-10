package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.protocol.conan.ConanManifestLimits;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ConanManifestPolicyTest {
  @Test
  void bindsPackagedDefaultsAndEnvironmentOverridesAtRuntime() throws Exception {
    for (Map<String, Object> environment : java.util.List.of(Map.<String, Object>of(), Map.<String, Object>of(
        "KKREPO_CONAN_MANIFEST_MAX_ENTRIES", "50000",
        "KKREPO_CONAN_MANIFEST_MAX_BYTES", "16777216"))) {

      try (var context = new AnnotationConfigApplicationContext()) {
        context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("manifest-test-env", environment));
        context.getEnvironment().getPropertySources().addLast(
            new ResourcePropertySource("classpath:application.properties"));
        context.register(ConanManifestPolicy.class);
        context.refresh();
        assertEquals(environment.isEmpty() ? ConanManifestLimits.DEFAULTS
                : new ConanManifestLimits(50000, 16777216),
            context.getBean(ConanManifestPolicy.class).forRepository(inheritingRuntime()));
      }
    }
  }

  @Test
  void independentlyInheritsUnsetOverridesFromTheRuntimeSnapshot() {
    ConanManifestPolicy policy = new ConanManifestPolicy(50000, 16777216);
    RepositoryRuntime runtime = inheritingRuntime();
    assertEquals(new ConanManifestLimits(50000, 16777216), policy.forRepository(runtime));
    when(runtime.conanManifestMaxEntries()).thenReturn(7922);
    assertEquals(new ConanManifestLimits(7922, 16777216), policy.forRepository(runtime));
    when(runtime.conanManifestMaxBytes()).thenReturn(2000000);
    assertEquals(new ConanManifestLimits(7922, 2000000), policy.forRepository(runtime));
    when(runtime.conanManifestMaxEntries()).thenReturn(null);
    assertEquals(new ConanManifestLimits(50000, 2000000), policy.forRepository(runtime));
  }

  @Test
  void rejectsInvalidServerDefaults() {
    assertThrows(IllegalArgumentException.class, () -> new ConanManifestPolicy(0, 100));
    assertThrows(IllegalArgumentException.class, () -> new ConanManifestPolicy(1, -1));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanManifestPolicy(1, Integer.MAX_VALUE));
  }

  private static RepositoryRuntime inheritingRuntime() {
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    when(runtime.conanManifestMaxEntries()).thenReturn(null);
    when(runtime.conanManifestMaxBytes()).thenReturn(null);
    return runtime;
  }
}
