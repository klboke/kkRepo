package com.github.klboke.kkrepo.server.cleanup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Operational controls; all correctness-bearing ownership remains in the database. */
@Component
@ConfigurationProperties(prefix = "kkrepo.cleanup")
public class CleanupRuntimeProperties {
  private boolean enabled = true;
  private final Worker worker = new Worker();
  private final Usage usage = new Usage();
  private final History history = new History();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Worker getWorker() {
    return worker;
  }

  public Usage getUsage() {
    return usage;
  }

  public History getHistory() {
    return history;
  }

  public static final class Worker {
    private int batchSize = 2;
    private int concurrency = 2;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration heartbeatInterval = Duration.ofSeconds(20);
    private Duration retryBaseDelay = Duration.ofSeconds(5);
    private Duration retryMaxDelay = Duration.ofMinutes(5);
    private Duration idleBaseDelay = Duration.ofMillis(500);
    private Duration idleMaxDelay = Duration.ofSeconds(10);

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = Math.max(1, Math.min(32, batchSize));
    }

    public int getConcurrency() {
      return concurrency;
    }

    public void setConcurrency(int concurrency) {
      this.concurrency = Math.max(1, Math.min(32, concurrency));
    }

    public Duration getLeaseDuration() {
      return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
      this.leaseDuration = positive(leaseDuration, Duration.ofMinutes(2));
    }

    public Duration getHeartbeatInterval() {
      return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
      this.heartbeatInterval = positive(heartbeatInterval, Duration.ofSeconds(20));
    }

    public Duration getRetryBaseDelay() {
      return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
      this.retryBaseDelay = positive(retryBaseDelay, Duration.ofSeconds(5));
    }

    public Duration getRetryMaxDelay() {
      return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
      this.retryMaxDelay = positive(retryMaxDelay, Duration.ofMinutes(5));
    }

    public Duration getIdleBaseDelay() {
      return idleBaseDelay;
    }

    public void setIdleBaseDelay(Duration idleBaseDelay) {
      this.idleBaseDelay = positive(idleBaseDelay, Duration.ofMillis(500));
    }

    public Duration getIdleMaxDelay() {
      return idleMaxDelay.compareTo(idleBaseDelay) < 0 ? idleBaseDelay : idleMaxDelay;
    }

    public void setIdleMaxDelay(Duration idleMaxDelay) {
      this.idleMaxDelay = positive(idleMaxDelay, Duration.ofSeconds(10));
    }
  }

  public static final class Usage {
    private Duration coalescingTtl = Duration.ofMinutes(5);
    private Duration safetyLag = Duration.ofMinutes(5);
    private boolean failClosed = true;
    private int localCacheMaximum = 100_000;

    public Duration getCoalescingTtl() {
      return coalescingTtl;
    }

    public void setCoalescingTtl(Duration coalescingTtl) {
      this.coalescingTtl = nonNegative(coalescingTtl, Duration.ofMinutes(5));
    }

    public Duration getSafetyLag() {
      Duration minimum = coalescingTtl;
      return safetyLag.compareTo(minimum) < 0 ? minimum : safetyLag;
    }

    public void setSafetyLag(Duration safetyLag) {
      this.safetyLag = nonNegative(safetyLag, Duration.ofMinutes(5));
    }

    public boolean isFailClosed() {
      return failClosed;
    }

    public void setFailClosed(boolean failClosed) {
      this.failClosed = failClosed;
    }

    public int getLocalCacheMaximum() {
      return localCacheMaximum;
    }

    public void setLocalCacheMaximum(int localCacheMaximum) {
      this.localCacheMaximum = Math.max(1_000, Math.min(2_000_000, localCacheMaximum));
    }
  }

  public static final class History {
    private boolean enabled = true;
    private Duration retention = Duration.ofDays(90);
    private int batchSize = 25;
    private int maxBatchesPerRun = 10;
    private int minimumRunsPerPolicy = 10;
    private int itemBatchSize = 5_000;
    private Duration clusterInterval = Duration.ofMinutes(55);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getRetention() {
      return retention;
    }

    public void setRetention(Duration retention) {
      Duration fallback = Duration.ofDays(90);
      this.retention = retention == null
          ? fallback
          : retention.compareTo(Duration.ofDays(7)) < 0 ? Duration.ofDays(7) : retention;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = Math.max(1, Math.min(1000, batchSize));
    }

    public int getMaxBatchesPerRun() {
      return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
      this.maxBatchesPerRun = Math.max(1, Math.min(100, maxBatchesPerRun));
    }

    public int getMinimumRunsPerPolicy() {
      return minimumRunsPerPolicy;
    }

    public void setMinimumRunsPerPolicy(int minimumRunsPerPolicy) {
      this.minimumRunsPerPolicy = Math.max(1, Math.min(1000, minimumRunsPerPolicy));
    }

    public int getItemBatchSize() {
      return itemBatchSize;
    }

    public void setItemBatchSize(int itemBatchSize) {
      this.itemBatchSize = Math.max(100, Math.min(50_000, itemBatchSize));
    }

    public Duration getClusterInterval() {
      return clusterInterval;
    }

    public void setClusterInterval(Duration clusterInterval) {
      this.clusterInterval = positive(clusterInterval, Duration.ofMinutes(55));
    }
  }

  private static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static Duration nonNegative(Duration value, Duration fallback) {
    return value == null || value.isNegative() ? fallback : value;
  }
}
