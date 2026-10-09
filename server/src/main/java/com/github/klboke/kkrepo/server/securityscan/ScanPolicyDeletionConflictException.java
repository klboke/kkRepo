package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.PolicyDeletion;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Actionable deletion conflicts exposed only by the scan management API. */
final class ScanPolicyDeletionConflictException extends ResponseStatusException {
  private final String code;

  ScanPolicyDeletionConflictException(PolicyDeletion outcome) {
    super(HttpStatus.CONFLICT, switch (outcome) {
      case STALE_REVISION -> "This is a historical revision or the policy has changed. Refresh the policy list and delete from the latest revision.";
      case IN_USE -> "This policy cannot be deleted because one or more revisions are still referenced by a repository, retained scan state, or waiver. Remove those references before deleting the policy.";
      default -> throw new IllegalArgumentException("Not a policy deletion conflict: " + outcome);
    });
    code = "POLICY_" + outcome.name();
  }

  String code() {
    return code;
  }
}
