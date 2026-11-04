package com.github.klboke.kkrepo.server.kkguard;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operational configuration for kkGuard and its Dependency-Track backend.
 *
 * <p>The configuration is node-local and identical on every replica; durable state and scan
 * ownership live in the {@code kkg_*} tables so a replica switch never changes behaviour.
 */
@Component
@ConfigurationProperties(prefix = "kkrepo.kkg")
public class KkGuardProperties {
  /** Enabled by default: kkGuard is only dark when an operator explicitly opts out. */
  private boolean enabled = true;
  private final DependencyTrack dependencyTrack = new DependencyTrack();
  private final Worker worker = new Worker();
  private long maxSbomBytes = 32L * 1024 * 1024;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public DependencyTrack getDependencyTrack() {
    return dependencyTrack;
  }

  public Worker getWorker() {
    return worker;
  }

  public long getMaxSbomBytes() {
    return maxSbomBytes;
  }

  public void setMaxSbomBytes(long maxSbomBytes) {
    this.maxSbomBytes = Math.max(1024, maxSbomBytes);
  }

  /** Dependency-Track connection settings. The API key is never exposed through the REST API. */
  public static final class DependencyTrack {
    private String url = "";
    private String apiKey = "";
    private boolean autoCreateProjects = true;
    private String projectVersionTemplate = "{sourceId}";
    private Duration pollInterval = Duration.ofSeconds(30);
    private Duration pollTimeout = Duration.ofMinutes(15);
    private boolean verifyTls = true;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int maxFindings = 20_000;

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url == null ? "" : url.trim();
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isAutoCreateProjects() {
      return autoCreateProjects;
    }

    public void setAutoCreateProjects(boolean autoCreateProjects) {
      this.autoCreateProjects = autoCreateProjects;
    }

    public String getProjectVersionTemplate() {
      return projectVersionTemplate;
    }

    public void setProjectVersionTemplate(String projectVersionTemplate) {
      this.projectVersionTemplate =
          projectVersionTemplate == null || projectVersionTemplate.isBlank()
              ? "{sourceId}" : projectVersionTemplate.trim();
    }

    public Duration getPollInterval() {
      return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
      this.pollInterval = pollInterval == null ? Duration.ofSeconds(30) : pollInterval;
    }

    public Duration getPollTimeout() {
      return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
      this.pollTimeout = pollTimeout == null ? Duration.ofMinutes(15) : pollTimeout;
    }

    public boolean isVerifyTls() {
      return verifyTls;
    }

    public void setVerifyTls(boolean verifyTls) {
      this.verifyTls = verifyTls;
    }

    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
    }

    public Duration getRequestTimeout() {
      return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
    }

    public int getMaxFindings() {
      return maxFindings;
    }

    public void setMaxFindings(int maxFindings) {
      this.maxFindings = Math.max(1, maxFindings);
    }

    /** True when kkGuard can talk to Dependency-Track. */
    public boolean isConfigured() {
      return !url.isBlank() && !apiKey.isBlank();
    }
  }

  /** Scan polling worker controls; ownership itself is fenced in the database. */
  public static final class Worker {
    private boolean enabled = true;
    private int batchSize = 4;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration idleDelay = Duration.ofSeconds(5);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = Math.max(1, batchSize);
    }

    public Duration getLeaseDuration() {
      return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
      this.leaseDuration =
          leaseDuration == null ? Duration.ofMinutes(2) : leaseDuration;
    }

    public Duration getIdleDelay() {
      return idleDelay;
    }

    public void setIdleDelay(Duration idleDelay) {
      this.idleDelay = idleDelay == null ? Duration.ofSeconds(5) : idleDelay;
    }
  }
}
