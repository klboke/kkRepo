package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanFinding;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRunSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import java.time.Instant;
import java.util.List;

/** Shared waiver matching rules used by policy evaluation and management views. */
final class SecurityScanWaiverMatcher {
  private SecurityScanWaiverMatcher() {}

  static boolean matchesFinding(ScanWaiver waiver, ScanFinding finding) {
    if (waiver.findingId() != null && waiver.findingId().equals(finding.id())) return true;
    boolean hasAdvisory =
        waiver.advisorySelector() != null && !waiver.advisorySelector().isBlank();
    boolean hasPackage =
        waiver.packageSelector() != null && !waiver.packageSelector().isBlank();
    if (!hasAdvisory && !hasPackage) return false;
    boolean advisoryMatches = !hasAdvisory
        || waiver.advisorySelector().equalsIgnoreCase(finding.advisoryId())
        || finding.aliases().stream()
            .anyMatch(alias -> waiver.advisorySelector().equalsIgnoreCase(alias));
    boolean packageMatches = !hasPackage
        || waiver.packageSelector().equals(finding.packageUrl())
        || waiver.packageSelector().equals(finding.packageName());
    return advisoryMatches && packageMatches;
  }

  static boolean matchesAnySubject(ScanWaiver waiver, List<ScanRunSubject> subjects) {
    return subjects.stream().anyMatch(subject -> matchesSubject(waiver, subject));
  }

  static boolean matchesSubject(ScanWaiver waiver, ScanRunSubject subject) {
    return (waiver.repositoryId() == null
        || waiver.repositoryId().longValue() == subject.repositoryId())
        && (waiver.assetId() == null
            || waiver.assetId().longValue() == subject.assetId());
  }

  static boolean isApproved(ScanWaiver waiver) {
    return waiver.approvedBy() != null && !waiver.approvedBy().isBlank();
  }

  static boolean isActive(ScanWaiver waiver, Instant evaluatedAt) {
    return waiver.expiresAt() == null || waiver.expiresAt().isAfter(evaluatedAt);
  }
}
