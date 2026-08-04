package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunItem;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyCapabilities.FormatCapability;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.PolicyCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.PolicyView;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.ScheduleCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.SchedulePreview;
import com.github.klboke.kkrepo.server.cleanup.CleanupProtectionService.ProtectionCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupProtectionService.ProtectionView;
import com.github.klboke.kkrepo.server.cleanup.CleanupRunService.RunCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupRunService.RunView;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/cleanup")
public class CleanupManagementController {
  private final CleanupPolicyService policies;
  private final CleanupRunService runs;
  private final CleanupProtectionService protections;
  private final SecurityManagementService securityService;

  public CleanupManagementController(
      CleanupPolicyService policies,
      CleanupRunService runs,
      CleanupProtectionService protections,
      SecurityManagementService securityService) {
    this.policies = policies;
    this.runs = runs;
    this.protections = protections;
    this.securityService = securityService;
  }

  @GetMapping("/capabilities")
  public List<FormatCapability> capabilities(HttpServletRequest request) {
    requireAdmin(request);
    return policies.formatCapabilities();
  }

  @GetMapping("/policies")
  public List<PolicyView> policies(HttpServletRequest request) {
    requireAdmin(request);
    return policies.list();
  }

  @GetMapping("/policies/{policyId}")
  public PolicyView policy(
      @PathVariable("policyId") long policyId, HttpServletRequest request) {
    requireAdmin(request);
    return policies.get(policyId);
  }

  @PostMapping("/schedules/preview")
  public SchedulePreview previewSchedule(
      @RequestBody ScheduleCommand command, HttpServletRequest request) {
    requireAdmin(request);
    return policies.previewSchedule(command);
  }

  @PostMapping("/policies")
  public ResponseEntity<PolicyView> createPolicy(
      @RequestBody PolicyCommand command, HttpServletRequest request) {
    requireAdmin(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(policies.create(command));
  }

  @PutMapping("/policies/{policyId}")
  public PolicyView updatePolicy(
      @PathVariable("policyId") long policyId,
      @RequestBody PolicyCommand command,
      HttpServletRequest request) {
    requireAdmin(request);
    return policies.update(policyId, command);
  }

  @DeleteMapping("/policies/{policyId}")
  public ResponseEntity<Void> deletePolicy(
      @PathVariable("policyId") long policyId,
      @RequestParam("revision") long revision,
      HttpServletRequest request) {
    requireAdmin(request);
    policies.delete(policyId, revision);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/policies/{policyId}/runs")
  public ResponseEntity<RunView> startRun(
      @PathVariable("policyId") long policyId,
      @RequestBody RunCommand command,
      HttpServletRequest request) {
    AuthenticatedSubject actor = requireAdmin(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(runs.startManual(policyId, command, actor.userId()));
  }

  @GetMapping("/runs")
  public List<CleanupRun> listRuns(
      @RequestParam(name = "policyId", required = false) Long policyId,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "25") int limit,
      HttpServletRequest request) {
    requireAdmin(request);
    return runs.listRuns(policyId, after, limit);
  }

  @GetMapping("/runs/{runId}")
  public RunView run(@PathVariable("runId") long runId, HttpServletRequest request) {
    requireAdmin(request);
    return runs.getRun(runId);
  }

  @PostMapping("/runs/{runId}/cancel")
  public ResponseEntity<RunView> cancelRun(
      @PathVariable("runId") long runId, HttpServletRequest request) {
    requireAdmin(request);
    return ResponseEntity.accepted().body(runs.cancel(runId));
  }

  @GetMapping("/runs/{runId}/repositories/{runRepositoryId}/items")
  public List<CleanupRunItem> runItems(
      @PathVariable("runId") long runId,
      @PathVariable("runRepositoryId") long runRepositoryId,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      HttpServletRequest request) {
    requireAdmin(request);
    return runs.listItems(runId, runRepositoryId, after, limit);
  }

  @GetMapping("/protections")
  public List<ProtectionView> protections(
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly,
      HttpServletRequest request) {
    requireAdmin(request);
    return protections.list(after, limit, activeOnly);
  }

  @GetMapping("/protections/{protectionId}")
  public ProtectionView protection(
      @PathVariable("protectionId") long protectionId, HttpServletRequest request) {
    requireAdmin(request);
    return protections.get(protectionId);
  }

  @PostMapping("/protections")
  public ResponseEntity<ProtectionView> createProtection(
      @RequestBody ProtectionCommand command, HttpServletRequest request) {
    AuthenticatedSubject actor = requireAdmin(request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(protections.create(command, actor.userId()));
  }

  @PutMapping("/protections/{protectionId}")
  public ProtectionView updateProtection(
      @PathVariable("protectionId") long protectionId,
      @RequestBody ProtectionCommand command,
      HttpServletRequest request) {
    AuthenticatedSubject actor = requireAdmin(request);
    return protections.update(protectionId, command, actor.userId());
  }

  @DeleteMapping("/protections/{protectionId}")
  public ResponseEntity<Void> deleteProtection(
      @PathVariable("protectionId") long protectionId,
      @RequestParam("updatedAt") Instant updatedAt,
      HttpServletRequest request) {
    requireAdmin(request);
    protections.delete(protectionId, updatedAt);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(CleanupValidationException.class)
  public ResponseEntity<Map<String, Object>> validation(CleanupValidationException error) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", "CLEANUP_VALIDATION_FAILED",
        "message", error.getMessage()));
  }

  @ExceptionHandler(CleanupNotFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(CleanupNotFoundException error) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
        "code", "CLEANUP_NOT_FOUND",
        "message", error.getMessage()));
  }

  @ExceptionHandler(CleanupRevisionConflictException.class)
  public ResponseEntity<Map<String, Object>> revisionConflict(
      CleanupRevisionConflictException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "code", "CLEANUP_POLICY_REVISION_CONFLICT",
        "message", error.getMessage(),
        "policyId", error.policyId(),
        "currentRevision", error.currentRevision()));
  }

  @ExceptionHandler(CleanupProtectionConflictException.class)
  public ResponseEntity<Map<String, Object>> protectionConflict(
      CleanupProtectionConflictException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "code", "CLEANUP_PROTECTION_CONFLICT",
        "message", error.getMessage(),
        "protectionId", error.protectionId(),
        "currentUpdatedAt", error.currentUpdatedAt()));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "code", "CLEANUP_POLICY_CONFLICT",
        "message", "cleanup policy name or scheduled run already exists"));
  }

  private AuthenticatedSubject requireAdmin(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (!(value instanceof AuthenticatedSubject actor)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    AccessDecision decision = securityService.decide(actor.permissionSubject(), "nexus:*");
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
    return actor;
  }
}
