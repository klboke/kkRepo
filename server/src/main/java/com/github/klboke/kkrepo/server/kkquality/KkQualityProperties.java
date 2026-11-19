package com.github.klboke.kkrepo.server.kkquality;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Operational configuration for kkQuality ingestion. */
@Component
@ConfigurationProperties(prefix = "kkrepo.kkq")
public class KkQualityProperties {
  private boolean enabled = true;
  private long maxSarifBytes = 64L * 1024 * 1024;
  private int maxIssues = 100_000;
  private String blobRepository = "kkquality";
  private String sarifPathPrefix = ".kkrepo/kkquality/sarif/";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getMaxSarifBytes() {
    return maxSarifBytes;
  }

  public void setMaxSarifBytes(long maxSarifBytes) {
    this.maxSarifBytes = Math.max(1024, maxSarifBytes);
  }

  /** Upper bound on normalized issues per analysis; larger documents are rejected (413). */
  public int getMaxIssues() {
    return maxIssues;
  }

  public void setMaxIssues(int maxIssues) {
    this.maxIssues = Math.max(1, maxIssues);
  }

  public String getBlobRepository() {
    return blobRepository;
  }

  public void setBlobRepository(String blobRepository) {
    this.blobRepository =
        blobRepository == null || blobRepository.isBlank() ? "kkquality" : blobRepository.trim();
  }

  public String getSarifPathPrefix() {
    return sarifPathPrefix;
  }

  public void setSarifPathPrefix(String sarifPathPrefix) {
    this.sarifPathPrefix = sarifPathPrefix == null || sarifPathPrefix.isBlank()
        ? ".kkrepo/kkquality/sarif/" : sarifPathPrefix.trim();
  }
}
