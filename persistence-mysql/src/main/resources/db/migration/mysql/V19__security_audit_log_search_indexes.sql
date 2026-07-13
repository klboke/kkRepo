CREATE INDEX idx_security_audit_log_outcome_status_time
  ON security_audit_log (outcome, status, occurred_at);

CREATE INDEX idx_security_audit_log_method_time
  ON security_audit_log (method, occurred_at);
