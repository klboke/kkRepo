package com.github.klboke.kkrepo.scanner;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kkrepo.scanner")
public class ScannerAdapterProperties {
  private String serviceCredential = "";
  private Path workDirectory = Path.of(System.getProperty("java.io.tmpdir"), "kkrepo-scanner");
  private String syftExecutable = "syft";
  private String grypeExecutable = "grype";
  private Path vulnerabilityDatabaseDirectory =
      Path.of(System.getProperty("java.io.tmpdir"), "kkrepo-scanner-db");
  private boolean vulnerabilityDatabaseAutoUpdate;
  private Duration vulnerabilityDatabaseUpdateInterval = Duration.ofHours(6);
  private long maxInputBytes = 2L * 1024 * 1024 * 1024;
  private long maxOutputBytes = 64L * 1024 * 1024;
  private long maxStderrBytes = 256L * 1024;
  private Duration readinessCache = Duration.ofSeconds(30);

  public String getServiceCredential() {
    return serviceCredential;
  }

  public void setServiceCredential(String serviceCredential) {
    this.serviceCredential = serviceCredential == null ? "" : serviceCredential;
  }

  public Path getWorkDirectory() {
    return workDirectory;
  }

  public void setWorkDirectory(Path workDirectory) {
    this.workDirectory = workDirectory;
  }

  public String getSyftExecutable() {
    return syftExecutable;
  }

  public void setSyftExecutable(String syftExecutable) {
    this.syftExecutable = syftExecutable;
  }

  public String getGrypeExecutable() {
    return grypeExecutable;
  }

  public void setGrypeExecutable(String grypeExecutable) {
    this.grypeExecutable = grypeExecutable;
  }

  public Path getVulnerabilityDatabaseDirectory() {
    return vulnerabilityDatabaseDirectory;
  }

  public void setVulnerabilityDatabaseDirectory(Path vulnerabilityDatabaseDirectory) {
    this.vulnerabilityDatabaseDirectory = vulnerabilityDatabaseDirectory;
  }

  public boolean isVulnerabilityDatabaseAutoUpdate() {
    return vulnerabilityDatabaseAutoUpdate;
  }

  public void setVulnerabilityDatabaseAutoUpdate(boolean vulnerabilityDatabaseAutoUpdate) {
    this.vulnerabilityDatabaseAutoUpdate = vulnerabilityDatabaseAutoUpdate;
  }

  public Duration getVulnerabilityDatabaseUpdateInterval() {
    return vulnerabilityDatabaseUpdateInterval;
  }

  public void setVulnerabilityDatabaseUpdateInterval(
      Duration vulnerabilityDatabaseUpdateInterval) {
    this.vulnerabilityDatabaseUpdateInterval = vulnerabilityDatabaseUpdateInterval == null
        ? Duration.ofHours(6) : vulnerabilityDatabaseUpdateInterval;
  }

  public long getMaxInputBytes() {
    return maxInputBytes;
  }

  public void setMaxInputBytes(long maxInputBytes) {
    this.maxInputBytes = Math.max(1024, maxInputBytes);
  }

  public long getMaxOutputBytes() {
    return maxOutputBytes;
  }

  public void setMaxOutputBytes(long maxOutputBytes) {
    this.maxOutputBytes = Math.max(1024, maxOutputBytes);
  }

  public long getMaxStderrBytes() {
    return maxStderrBytes;
  }

  public void setMaxStderrBytes(long maxStderrBytes) {
    this.maxStderrBytes = Math.max(1024, maxStderrBytes);
  }

  public Duration getReadinessCache() {
    return readinessCache;
  }

  public void setReadinessCache(Duration readinessCache) {
    this.readinessCache = readinessCache == null ? Duration.ofSeconds(30) : readinessCache;
  }
}
