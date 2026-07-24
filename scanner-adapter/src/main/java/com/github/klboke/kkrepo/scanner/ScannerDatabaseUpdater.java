package com.github.klboke.kkrepo.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Updates the rebuildable vulnerability database independently from scan requests. Production
 * egress policy can enable this job only for the dedicated update path.
 */
@Component
public class ScannerDatabaseUpdater {
  private final ScannerAdapterProperties properties;
  private final BoundedProcessRunner processes;
  private final ScannerEngineService engine;
  private final AtomicBoolean updating = new AtomicBoolean();

  public ScannerDatabaseUpdater(
      ScannerAdapterProperties properties,
      BoundedProcessRunner processes,
      ScannerEngineService engine) {
    this.properties = properties;
    this.processes = processes;
    this.engine = engine;
  }

  @Scheduled(
      initialDelayString = "${kkrepo.scanner.vulnerability-database-update-initial-delay:30s}",
      fixedDelayString = "${kkrepo.scanner.vulnerability-database-update-interval:6h}")
  public void update() {
    if (!properties.isVulnerabilityDatabaseAutoUpdate() || !updating.compareAndSet(false, true)) {
      return;
    }
    Path workspace = null;
    try {
      Files.createDirectories(properties.getVulnerabilityDatabaseDirectory());
      Files.createDirectories(properties.getWorkDirectory());
      workspace = Files.createTempDirectory(properties.getWorkDirectory(), "db-update-");
      processes.run(
          List.of(properties.getGrypeExecutable(), "db", "update"),
          workspace,
          workspace.resolve("stdout.log"),
          Duration.ofMinutes(30),
          Map.of("GRYPE_DB_AUTO_UPDATE", "true"));
      engine.invalidateReadiness();
    } catch (IOException | ScannerRequestException ignored) {
      // Readiness exposes an unavailable or stale database; no token or target data is logged here.
    } finally {
      TempDirectories.deleteRecursively(workspace);
      updating.set(false);
    }
  }
}
