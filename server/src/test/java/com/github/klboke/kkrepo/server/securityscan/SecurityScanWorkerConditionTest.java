package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeEventMode;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class SecurityScanWorkerConditionTest {
  @Test
  void artifactChangeModeFollowsTheSingleDeploymentGate() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    SecurityScanningConfiguration configuration = new SecurityScanningConfiguration();

    assertFalse(configuration.artifactChangeEventMode(properties).enabled());
    properties.setEnabled(true);
    ArtifactChangeEventMode enabled = configuration.artifactChangeEventMode(properties);
    assertTrue(enabled.enabled());
  }

  @Test
  void historicalWorkersAndMaintenanceAreAbsentByDefault() {
    try (AnnotationConfigApplicationContext context = context(false)) {
      assertTrue(context.getBeansOfType(SecurityScanArtifactChangeWorker.class).isEmpty());
      assertTrue(
          context.getBeansOfType(SecurityScanArtifactReconciliationWorker.class).isEmpty());
      assertTrue(context.getBeansOfType(SecurityScanArtifactChangeMetrics.class).isEmpty());
      assertTrue(context.getBeansOfType(SecurityScanRetentionWorker.class).isEmpty());
    }
  }

  @Test
  void explicitDeploymentOptInCreatesHistoricalWorkersAndMaintenance() {
    try (AnnotationConfigApplicationContext context = context(true)) {
      assertFalse(context.getBeansOfType(SecurityScanArtifactChangeWorker.class).isEmpty());
      assertFalse(
          context.getBeansOfType(SecurityScanArtifactReconciliationWorker.class).isEmpty());
      assertFalse(context.getBeansOfType(SecurityScanArtifactChangeMetrics.class).isEmpty());
      assertFalse(context.getBeansOfType(SecurityScanRetentionWorker.class).isEmpty());
    }
  }

  private static AnnotationConfigApplicationContext context(boolean enabled) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource(
            "security-scan-test",
            Map.of("kkrepo.security-scanning.enabled", Boolean.toString(enabled))));
    context.registerBean(
        SecurityScanArtifactChangeService.class,
        () -> mock(SecurityScanArtifactChangeService.class));
    context.registerBean(
        SecurityScanArtifactReconciliationService.class,
        () -> mock(SecurityScanArtifactReconciliationService.class));
    context.registerBean(ArtifactChangeDao.class, () -> mock(ArtifactChangeDao.class));
    context.registerBean(SecurityScanDao.class, () -> mock(SecurityScanDao.class));
    context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
    context.registerBean(SecurityScanningProperties.class, SecurityScanningProperties::new);
    context.registerBean(SecurityScanMetrics.class, () -> mock(SecurityScanMetrics.class));
    context.register(
        SecurityScanArtifactChangeWorker.class,
        SecurityScanArtifactReconciliationWorker.class,
        SecurityScanArtifactChangeMetrics.class,
        SecurityScanRetentionWorker.class);
    context.refresh();
    return context;
  }
}
