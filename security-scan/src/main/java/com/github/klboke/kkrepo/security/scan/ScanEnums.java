package com.github.klboke.kkrepo.security.scan;

import java.util.Locale;

/** Bounded values persisted by the security scanning subsystem. */
public final class ScanEnums {
  /** Durable failure reason used to requeue first-time scans after scanner observation recovers. */
  public static final String SCANNER_OBSERVATION_UNAVAILABLE =
      "SCANNER_OBSERVATION_UNAVAILABLE";

  private ScanEnums() {}

  public enum SubjectKind {
    ASSET_BLOB,
    CONAN_PACKAGE,
    CONDA_PACKAGE,
    OCI_MANIFEST
  }

  public enum TargetClassification {
    ARCHIVE,
    PACKAGE,
    MANIFEST,
    RAW_FILE,
    OCI_IMAGE
  }

  public enum CandidateDisposition {
    SCANNABLE,
    NOT_APPLICABLE,
    DEFERRED,
    REJECTED_BY_LIMIT
  }

  public enum ScanStage {
    CATALOG_AND_MATCH,
    MATCH_ONLY,
    POLICY_ONLY
  }

  public enum RequestReason {
    CONTENT_CHANGED,
    REPOSITORY_BACKFILL,
    MANUAL,
    PROFILE_CHANGED,
    SCANNER_CHANGED,
    VULNERABILITY_DB_CHANGED,
    POLICY_CHANGED,
    MAX_AGE_EXPIRED,
    RETRY
  }

  public enum TaskStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public enum ScanState {
    PENDING,
    RUNNING,
    COMPLETE,
    PARTIAL,
    FAILED,
    NOT_APPLICABLE,
    CANCELLED,
    STALE
  }

  public enum ScanCompleteness {
    COMPLETE,
    PARTIAL,
    UNKNOWN
  }

  public enum Severity {
    UNKNOWN(0),
    NEGLIGIBLE(1),
    LOW(2),
    MEDIUM(3),
    HIGH(4),
    CRITICAL(5);

    private final int rank;

    Severity(int rank) {
      this.rank = rank;
    }

    public int rank() {
      return rank;
    }

    public boolean atLeast(Severity other) {
      return rank >= other.rank;
    }

    public static Severity normalize(String value) {
      if (value == null || value.isBlank()) {
        return UNKNOWN;
      }
      return switch (value.trim().toUpperCase(Locale.ROOT)) {
        case "CRITICAL" -> CRITICAL;
        case "HIGH", "IMPORTANT" -> HIGH;
        case "MEDIUM", "MODERATE" -> MEDIUM;
        case "LOW" -> LOW;
        case "NEGLIGIBLE", "INFORMATIONAL", "INFO" -> NEGLIGIBLE;
        default -> UNKNOWN;
      };
    }
  }

  public enum PolicyDecision {
    ALLOW,
    BLOCK_PENDING,
    BLOCK_SCAN_FAILED,
    BLOCK_PARTIAL,
    BLOCK_VULNERABILITY;

    public boolean blocked() {
      return this != ALLOW;
    }
  }

  public enum EnforcementMode {
    AUDIT,
    ENFORCE
  }

  public enum PolicyAction {
    ALLOW,
    BLOCK
  }

  public enum OciPlatformPolicy {
    ALL,
    REQUIRED_SET
  }

  public enum BackfillStatus {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }
}
