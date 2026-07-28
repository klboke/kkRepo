package com.github.klboke.kkrepo.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Updates the rebuildable vulnerability database independently from scan requests. Production
 * egress policy can enable this job only for the dedicated update path.
 */
@Component
public class ScannerDatabaseUpdater {
  private static final Logger log = LoggerFactory.getLogger(ScannerDatabaseUpdater.class);

  private final ScannerAdapterProperties properties;
  private final BoundedProcessRunner processes;
  private final ScannerEngineService engine;
  private final ScannerDatabaseCoordinator database;
  private final ScannerAdapterMetrics metrics;

  public ScannerDatabaseUpdater(
      ScannerAdapterProperties properties,
      BoundedProcessRunner processes,
      ScannerEngineService engine,
      ScannerDatabaseCoordinator database,
      ScannerAdapterMetrics metrics) {
    this.properties = properties;
    this.processes = processes;
    this.engine = engine;
    this.database = database;
    this.metrics = metrics;
  }

  @Scheduled(
      initialDelayString = "${kkrepo.scanner.vulnerability-database-update-initial-delay:30s}",
      fixedDelayString =
          "${kkrepo.scanner.vulnerability-database-update-check-interval:1m}")
  public void update() {
    if (!properties.isVulnerabilityDatabaseAutoUpdate()) {
      return;
    }
    try {
      ScannerDatabaseCoordinator.UpdateResult result = database.updateIfDue(
          properties.getVulnerabilityDatabaseUpdateInterval(), this::runUpdate);
      metrics.recordDatabaseUpdate(result.name().toLowerCase(java.util.Locale.ROOT));
      if (result == ScannerDatabaseCoordinator.UpdateResult.UPDATED) {
        engine.invalidateReadiness();
      }
    } catch (RuntimeException e) {
      metrics.recordDatabaseUpdate("failed");
      log.warn("Scanner vulnerability database update failed: {}", safeCode(e));
    }
  }

  private void runUpdate() {
    Path workspace = null;
    try {
      Files.createDirectories(properties.getWorkDirectory());
      workspace = Files.createTempDirectory(properties.getWorkDirectory(), "db-update-");
      processes.run(
          List.of(properties.getGrypeExecutable(), "db", "update"),
          workspace,
          workspace.resolve("stdout.log"),
          Duration.ofMinutes(30),
          Map.of("GRYPE_DB_AUTO_UPDATE", "true"));
    } catch (IOException e) {
      throw new ScannerRequestException(
          "SCANNER_DATABASE_UPDATE_IO",
          "Unable to prepare vulnerability database update workspace",
          503,
          true,
          e);
    } finally {
      TempDirectories.deleteRecursively(workspace);
    }
  }

  private static String safeCode(RuntimeException failure) {
    return failure instanceof ScannerRequestException scanner
        ? scanner.code() : failure.getClass().getSimpleName();
  }
}
