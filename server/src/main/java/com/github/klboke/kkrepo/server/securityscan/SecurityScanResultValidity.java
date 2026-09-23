package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;

/** Shared by result finalization and the management view; never cached independently. */
public record SecurityScanResultValidity(Long maxResultAgeSeconds, String source) {
  static SecurityScanResultValidity resolve(RepositoryScanConfig config, ScanPolicy policy) {
    Long repositoryAge = config.maxResultAgeSeconds();
    Long policyAge = policy == null || !policy.enabled() ? null : policy.maxResultAgeSeconds();
    if (repositoryAge == null && policyAge == null) {
      return new SecurityScanResultValidity(null, "NO_EXPIRY");
    }
    if (repositoryAge == null) {
      return new SecurityScanResultValidity(policyAge, "POLICY");
    }
    if (policyAge == null) {
      return new SecurityScanResultValidity(repositoryAge, "REPOSITORY");
    }
    return new SecurityScanResultValidity(Math.min(repositoryAge, policyAge), "BOTH");
  }
}
