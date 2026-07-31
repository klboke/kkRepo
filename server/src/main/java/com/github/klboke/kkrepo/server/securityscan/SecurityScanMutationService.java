package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.ConfigCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.PolicyCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.WaiverCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Commits each administrative mutation and its operation-specific audit record atomically. */
@Service
@Transactional
public class SecurityScanMutationService {
  private static final Logger log =
      LoggerFactory.getLogger(SecurityScanMutationService.class);
  private final SecurityScanManagementService management;
  private final SecurityScanAuditService audit;
  private final Adapter adapter;

  public SecurityScanMutationService(
      SecurityScanManagementService management,
      SecurityScanAuditService audit,
      Adapter adapter) {
    this.management = management;
    this.audit = audit;
    this.adapter = adapter;
  }

  public long rescan(
      HttpServletRequest request, AuthenticatedSubject actor, long assetId) {
    long taskId = management.rescan(actor, assetId);
    audit.record(
        request, actor, "RESCAN", null, Map.of("assetId", assetId, "taskId", taskId));
    return taskId;
  }

  public void retry(
      HttpServletRequest request, AuthenticatedSubject actor, long taskId) {
    management.retry(actor, taskId);
    audit.record(request, actor, "RETRY", null, Map.of("taskId", taskId));
  }

  public void cancel(
      HttpServletRequest request, AuthenticatedSubject actor, long taskId) {
    String executionId = management.cancel(actor, taskId);
    audit.record(request, actor, "CANCEL", null, Map.of("taskId", taskId));
    cancelAdapterAfterCommit(taskId, executionId);
  }

  private void cancelAdapterAfterCommit(long taskId, String executionId) {
    if (executionId == null) return;
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      broadcastCancellation(taskId, executionId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            broadcastCancellation(taskId, executionId);
          }
        });
  }

  private void broadcastCancellation(long taskId, String executionId) {
    try {
      adapter.cancel(executionId);
    } catch (RuntimeException failure) {
      // The committed task row remains the publication fence and the owner heartbeat provides a
      // retry-independent fallback. Immediate adapter cancellation is deliberately best effort.
      log.warn("Unable to broadcast cancellation for security scan task {}", taskId, failure);
    }
  }

  public RepositoryScanConfig updateRepositoryConfig(
      HttpServletRequest request,
      AuthenticatedSubject actor,
      long repositoryId,
      ConfigCommand command) {
    RepositoryScanConfig result =
        management.updateRepositoryConfig(actor, repositoryId, command);
    audit.record(
        request,
        actor,
        "REPOSITORY_CONFIG",
        repositoryId,
        Map.of(
            "enabled", result.enabled(),
            "profileId", result.profileId(),
            "enforcementMode", result.enforcementMode().name(),
            "configRevision", result.configRevision()));
    return result;
  }

  public ScanPolicy createPolicy(
      HttpServletRequest request,
      AuthenticatedSubject actor,
      PolicyCommand command) {
    ScanPolicy policy = management.createPolicy(actor, command);
    audit.record(
        request,
        actor,
        "POLICY_CREATE",
        null,
        Map.of("policyId", policy.id(), "policyRevision", policy.revision()));
    return policy;
  }

  public ScanPolicy revisePolicy(
      HttpServletRequest request,
      AuthenticatedSubject actor,
      long policyId,
      PolicyCommand command) {
    ScanPolicy policy = management.revisePolicy(actor, policyId, command);
    audit.record(
        request,
        actor,
        "POLICY_REVISE",
        null,
        Map.of(
            "previousPolicyId", policyId,
            "policyId", policy.id(),
            "policyRevision", policy.revision()));
    return policy;
  }

  public ScanWaiver createWaiver(
      HttpServletRequest request,
      AuthenticatedSubject actor,
      WaiverCommand command) {
    ScanWaiver waiver = management.createWaiver(actor, command);
    audit.record(
        request,
        actor,
        "WAIVER_CREATE",
        waiver.repositoryId(),
        Map.of(
            "waiverId", waiver.id(),
            "scopeType", waiver.scopeType(),
            "expiresAt", waiver.expiresAt() == null ? "none" : waiver.expiresAt().toString()));
    return waiver;
  }

  public ScanWaiver deleteWaiver(
      HttpServletRequest request, AuthenticatedSubject actor, long waiverId) {
    ScanWaiver waiver = management.deleteWaiver(actor, waiverId);
    audit.record(
        request,
        actor,
        "WAIVER_DELETE",
        waiver.repositoryId(),
        Map.of("waiverId", waiverId));
    return waiver;
  }
}
