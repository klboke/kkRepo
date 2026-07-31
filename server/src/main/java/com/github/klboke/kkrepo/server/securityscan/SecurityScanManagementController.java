package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.AssetDetail;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.ConfigCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.CursorPage;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.FindingView;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.FindingWaiverContext;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.FindingWaiverDetail;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.Overview;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.PolicyCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.RepositoryView;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.RunView;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.TaskView;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.WaiverCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.WaiverView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/internal/security/scanning")
public class SecurityScanManagementController {
  private static final MediaType CYCLONEDX =
      MediaType.parseMediaType("application/vnd.cyclonedx+json");

  private final SecurityScanManagementService service;
  private final SecurityScanMutationService mutations;

  public SecurityScanManagementController(
      SecurityScanManagementService service, SecurityScanMutationService mutations) {
    this.service = service;
    this.mutations = mutations;
  }

  @GetMapping("/summary")
  public Overview summary(HttpServletRequest request) {
    return service.overview(actor(request));
  }

  @GetMapping("/repositories")
  public CursorPage<RepositoryView> repositories(
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "25") int limit,
      HttpServletRequest request) {
    return service.repositoryPage(actor(request), query, after, limit);
  }

  @GetMapping("/tasks")
  public CursorPage<TaskView> tasks(
      @RequestParam(name = "repositoryId", required = false) Long repositoryId,
      @RequestParam(name = "status", required = false) TaskStatus status,
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "25") int limit,
      HttpServletRequest request) {
    return service.taskPage(actor(request), repositoryId, status, query, after, limit);
  }

  @GetMapping("/runs")
  public CursorPage<RunView> runs(
      @RequestParam(name = "repositoryId", required = false) Long repositoryId,
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "25") int limit,
      HttpServletRequest request) {
    return service.runPage(actor(request), repositoryId, query, after, limit);
  }

  @GetMapping("/findings")
  public CursorPage<FindingView> findings(
      @RequestParam(name = "repositoryId", required = false) Long repositoryId,
      @RequestParam(name = "runId", required = false) Long runId,
      @RequestParam(name = "severity", required = false) Severity severity,
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "25") int limit,
      HttpServletRequest request) {
    return service.findingPage(
        actor(request), repositoryId, runId, severity, query, after, limit);
  }

  @GetMapping("/findings/{findingId}/waiver-context")
  public FindingWaiverContext findingWaiverContext(
      @PathVariable("findingId") long findingId, HttpServletRequest request) {
    return service.findingWaiverContext(actor(request), findingId);
  }

  @GetMapping("/findings/{findingId}/waivers")
  public FindingWaiverDetail findingWaivers(
      @PathVariable("findingId") long findingId, HttpServletRequest request) {
    return service.findingWaivers(actor(request), findingId);
  }

  @GetMapping("/assets/{assetId}")
  public AssetDetail asset(
      @PathVariable("assetId") long assetId, HttpServletRequest request) {
    return service.asset(actor(request), assetId);
  }

  @PostMapping("/assets/{assetId}/rescan")
  public Map<String, Long> rescan(
      @PathVariable("assetId") long assetId, HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    long taskId = mutations.rescan(request, actor, assetId);
    return Map.of("taskId", taskId);
  }

  @PostMapping("/tasks/{taskId}/retry")
  public Map<String, Object> retry(
      @PathVariable("taskId") long taskId, HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    mutations.retry(request, actor, taskId);
    return Map.of("taskId", taskId, "status", "PENDING");
  }

  @PostMapping("/tasks/{taskId}/cancel")
  public Map<String, Object> cancel(
      @PathVariable("taskId") long taskId, HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    mutations.cancel(request, actor, taskId);
    return Map.of("taskId", taskId, "status", "CANCELLED");
  }

  @GetMapping("/repositories/{repositoryId}/config")
  public RepositoryScanConfig repositoryConfig(
      @PathVariable("repositoryId") long repositoryId, HttpServletRequest request) {
    return service.repositoryConfig(actor(request), repositoryId);
  }

  @PutMapping("/repositories/{repositoryId}/config")
  public RepositoryScanConfig updateRepositoryConfig(
      @PathVariable("repositoryId") long repositoryId,
      @RequestBody ConfigCommand command,
      HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    return mutations.updateRepositoryConfig(
        request, actor, repositoryId, command);
  }

  @GetMapping("/policies")
  public CursorPage<ScanPolicy> policies(
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "25") int limit,
      HttpServletRequest request) {
    return service.policyPage(actor(request), query, after, limit);
  }

  @PostMapping("/policies")
  public ScanPolicy createPolicy(
      @RequestBody PolicyCommand command, HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    return mutations.createPolicy(request, actor, command);
  }

  @PutMapping("/policies/{policyId}")
  public ScanPolicy revisePolicy(
      @PathVariable("policyId") long policyId,
      @RequestBody PolicyCommand command,
      HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    return mutations.revisePolicy(request, actor, policyId, command);
  }

  @GetMapping("/waivers")
  public CursorPage<WaiverView> waivers(
      @RequestParam(name = "repositoryId", required = false) Long repositoryId,
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "after", defaultValue = "0") long after,
      @RequestParam(name = "limit", defaultValue = "25") int limit,
      HttpServletRequest request) {
    return service.waiverPage(actor(request), repositoryId, query, after, limit);
  }

  @PostMapping("/waivers")
  public ScanWaiver createWaiver(
      @RequestBody WaiverCommand command, HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    return mutations.createWaiver(request, actor, command);
  }

  @DeleteMapping("/waivers/{waiverId}")
  public ResponseEntity<Void> deleteWaiver(
      @PathVariable("waiverId") long waiverId, HttpServletRequest request) {
    AuthenticatedSubject actor = actor(request);
    mutations.deleteWaiver(request, actor, waiverId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/sboms/{sbomId}")
  public ResponseEntity<InputStreamResource> sbom(
      @PathVariable("sbomId") long sbomId, HttpServletRequest request) {
    var download = service.sbom(actor(request), sbomId);
    return ResponseEntity.ok()
        .contentType(CYCLONEDX)
        .cacheControl(CacheControl.noStore())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"kkrepo-sbom-" + sbomId + ".cdx.json\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(new InputStreamResource(download.input()));
  }

  private static AuthenticatedSubject actor(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (value instanceof AuthenticatedSubject actor) return actor;
    throw new ResponseStatusException(
        org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required");
  }
}
