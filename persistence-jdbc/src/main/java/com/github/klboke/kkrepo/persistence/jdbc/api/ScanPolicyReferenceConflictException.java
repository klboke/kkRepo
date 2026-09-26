package com.github.klboke.kkrepo.persistence.jdbc.api;

/** A policy selected by a writer was deleted before its reference could be persisted. */
public final class ScanPolicyReferenceConflictException extends IllegalStateException {
  public ScanPolicyReferenceConflictException(long policyId) {
    super("Scan policy " + policyId + " no longer exists; refresh and select an existing policy");
  }
}
