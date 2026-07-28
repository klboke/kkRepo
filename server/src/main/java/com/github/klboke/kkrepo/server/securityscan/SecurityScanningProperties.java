package com.github.klboke.kkrepo.server.securityscan;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Operational settings. Durable ownership and correctness remain in the relational database. */
@Component
@ConfigurationProperties(prefix = "kkrepo.security-scanning")
public class SecurityScanningProperties {
  private boolean enabled;
  private final Adapter adapter = new Adapter();
  private final Worker worker = new Worker();
  private final Retention retention = new Retention();
  private long maxOutputBytes = 64L * 1024 * 1024;
  private int metricsCountLimit = 10_000;
  private Duration scannerDatabaseMaxAge = Duration.ofHours(48);
  private Duration scannerObservationMaxAge = Duration.ofMinutes(2);
  private String ociRegistryUrl = "http://kkrepo:8080";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Adapter getAdapter() {
    return adapter;
  }

  public Worker getWorker() {
    return worker;
  }

  public Retention getRetention() {
    return retention;
  }

  public long getMaxOutputBytes() {
    return maxOutputBytes;
  }

  public void setMaxOutputBytes(long maxOutputBytes) {
    this.maxOutputBytes = Math.max(1024, maxOutputBytes);
  }

  public int getMetricsCountLimit() {
    return metricsCountLimit;
  }

  public void setMetricsCountLimit(int metricsCountLimit) {
    this.metricsCountLimit = Math.max(1, Math.min(1_000_000, metricsCountLimit));
  }

  public Duration getScannerDatabaseMaxAge() {
    return scannerDatabaseMaxAge;
  }

  public String getOciRegistryUrl() {
    return ociRegistryUrl;
  }

  public void setOciRegistryUrl(String ociRegistryUrl) {
    this.ociRegistryUrl = ociRegistryUrl;
  }

  public void setScannerDatabaseMaxAge(Duration scannerDatabaseMaxAge) {
    this.scannerDatabaseMaxAge =
        scannerDatabaseMaxAge == null ? Duration.ofHours(48) : scannerDatabaseMaxAge;
  }

  public Duration getScannerObservationMaxAge() {
    return scannerObservationMaxAge;
  }

  public void setScannerObservationMaxAge(Duration scannerObservationMaxAge) {
    this.scannerObservationMaxAge =
        scannerObservationMaxAge == null ? Duration.ofMinutes(2) : scannerObservationMaxAge;
  }

  public static final class Adapter {
    private String baseUrl = "http://scanner:8080";
    private String serviceCredential = "";
    private Duration connectTimeout = Duration.ofSeconds(10);

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getServiceCredential() {
      return serviceCredential;
    }

    public void setServiceCredential(String serviceCredential) {
      this.serviceCredential = serviceCredential == null ? "" : serviceCredential;
    }

    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
    }
  }

  public static final class Worker {
    private int batchSize = 4;
    private int leaseSeconds = 300;
    private int heartbeatSeconds = 60;
    private int maxAttempts = 5;
    private int maxBackoffSeconds = 1800;
    private int artifactChangeBatchSize = 1000;
    private int artifactChangeCleanupBatchSize = 5000;
    private int candidateBatchSize = 500;
    private int backfillBatchSize = 500;
    private int backfillMaxPagesPerRun = 20;
    private int snapshotRematchBatchSize = 200;
    private int snapshotRematchMaxBatches = 10;

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = Math.max(1, batchSize);
    }

    public int getLeaseSeconds() {
      return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
      this.leaseSeconds = Math.max(30, leaseSeconds);
    }

    public int getHeartbeatSeconds() {
      return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
      this.heartbeatSeconds = Math.max(5, heartbeatSeconds);
    }

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = Math.max(1, maxAttempts);
    }

    public int getMaxBackoffSeconds() {
      return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(int maxBackoffSeconds) {
      this.maxBackoffSeconds = Math.max(30, maxBackoffSeconds);
    }

    public int getArtifactChangeBatchSize() {
      return artifactChangeBatchSize;
    }

    public void setArtifactChangeBatchSize(int artifactChangeBatchSize) {
      this.artifactChangeBatchSize =
          Math.max(1, Math.min(10_000, artifactChangeBatchSize));
    }

    public int getArtifactChangeCleanupBatchSize() {
      return artifactChangeCleanupBatchSize;
    }

    public void setArtifactChangeCleanupBatchSize(int artifactChangeCleanupBatchSize) {
      this.artifactChangeCleanupBatchSize =
          Math.max(1, Math.min(50_000, artifactChangeCleanupBatchSize));
    }

    public int getCandidateBatchSize() {
      return candidateBatchSize;
    }

    public void setCandidateBatchSize(int candidateBatchSize) {
      this.candidateBatchSize = Math.max(1, Math.min(5000, candidateBatchSize));
    }

    public int getBackfillBatchSize() {
      return backfillBatchSize;
    }

    public void setBackfillBatchSize(int backfillBatchSize) {
      this.backfillBatchSize = Math.max(1, Math.min(5000, backfillBatchSize));
    }

    public int getBackfillMaxPagesPerRun() {
      return backfillMaxPagesPerRun;
    }

    public void setBackfillMaxPagesPerRun(int backfillMaxPagesPerRun) {
      this.backfillMaxPagesPerRun = Math.max(1, Math.min(1000, backfillMaxPagesPerRun));
    }

    public int getSnapshotRematchBatchSize() {
      return snapshotRematchBatchSize;
    }

    public void setSnapshotRematchBatchSize(int snapshotRematchBatchSize) {
      this.snapshotRematchBatchSize = Math.max(1, Math.min(1000, snapshotRematchBatchSize));
    }

    public int getSnapshotRematchMaxBatches() {
      return snapshotRematchMaxBatches;
    }

    public void setSnapshotRematchMaxBatches(int snapshotRematchMaxBatches) {
      this.snapshotRematchMaxBatches = Math.max(1, Math.min(100, snapshotRematchMaxBatches));
    }
  }

  public static final class Retention {
    private boolean enabled = true;
    private int terminalTaskDays = 30;
    private int resultDays = 90;
    private int batchSize = 200;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getTerminalTaskDays() {
      return terminalTaskDays;
    }

    public void setTerminalTaskDays(int terminalTaskDays) {
      this.terminalTaskDays = Math.max(1, terminalTaskDays);
    }

    public int getResultDays() {
      return resultDays;
    }

    public void setResultDays(int resultDays) {
      this.resultDays = Math.max(1, resultDays);
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = Math.max(1, Math.min(5000, batchSize));
    }
  }
}
