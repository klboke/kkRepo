package com.github.klboke.kkrepo.scanner;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs one coordinated Grype database update for the egress-isolated Helm updater workload. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.scanner",
    name = "database-update-only",
    havingValue = "true")
public class ScannerDatabaseUpdateOnlyRunner implements ApplicationRunner {
  private final ScannerDatabaseUpdater updater;

  public ScannerDatabaseUpdateOnlyRunner(ScannerDatabaseUpdater updater) {
    this.updater = updater;
  }

  @Override
  public void run(ApplicationArguments args) {
    ScannerDatabaseCoordinator.UpdateResult result = updater.updateOnce();
    if (result == ScannerDatabaseCoordinator.UpdateResult.BUSY) {
      throw new IllegalStateException(
          "Scanner vulnerability database updater could not acquire the publication lock");
    }
  }
}
