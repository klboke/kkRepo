package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Internal API for multi-repository staging sessions. */
@RestController
@RequestMapping({"/internal/staging", "/service/rest/v1/staging"})
public class StagingController {
  private final StagingService staging;

  public StagingController(StagingService staging) {
    this.staging = staging;
  }

  @PostMapping("/sessions")
  public StagingService.StagingSessionView create(
      @RequestBody StagingService.CreateSessionRequest request,
      HttpServletRequest servlet,
      HttpServletResponse response) {
    StagingService.CreateResult result = staging.createWithCredential(
        request, subject(servlet), ClientAddress.from(servlet));
    if (result.token() != null && !result.token().isBlank()) {
      response.setHeader(StagingService.TOKEN_HEADER, result.token());
    }
    return result.session();
  }

  @GetMapping("/metrics")
  public StagingService.StagingMetrics metrics(HttpServletRequest servlet) {
    return staging.metrics(subject(servlet));
  }

  @GetMapping("/sessions")
  public List<StagingService.StagingSessionView> list(
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      HttpServletRequest servlet) {
    return staging.list(state, limit, subject(servlet));
  }

  @GetMapping("/sessions/{id}")
  public StagingService.StagingSessionView get(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.get(id, subject(servlet));
  }

  @DeleteMapping("/sessions/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    staging.delete(id, subject(servlet), ClientAddress.from(servlet));
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/sessions/{id}")
  public StagingService.StagingSessionView update(
      @PathVariable("id") long id,
      @RequestBody StagingService.UpdateSessionRequest request,
      HttpServletRequest servlet) {
    return staging.update(id, request, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/close")
  public StagingService.StagingSessionView close(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.close(id, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/credential")
  public StagingCredentialService.IssuedCredential rotateCredential(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.rotateCredential(
        id, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/commit")
  public StagingService.StagingReport commit(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.commit(id, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/rollback")
  public StagingService.StagingSessionView rollback(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.rollback(id, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/cancel")
  public StagingService.StagingSessionView cancel(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.cancel(id, subject(servlet), ClientAddress.from(servlet));
  }

  @GetMapping("/sessions/{id}/items")
  public List<StagingService.StagingItemView> items(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.items(id, subject(servlet));
  }

  @PostMapping("/sessions/{id}/repositories")
  public StagingService.StagingSessionView addRepository(
      @PathVariable("id") long id,
      @RequestBody AddRepositoryRequest request,
      HttpServletRequest servlet) {
    if (request == null || request.repository() == null || request.repository().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repository is required");
    }
    return staging.addRepository(
        id, request.repository().trim(), subject(servlet), ClientAddress.from(servlet));
  }

  @DeleteMapping("/sessions/{id}/repositories/{repositoryId}")
  public StagingService.StagingSessionView removeRepository(
      @PathVariable("id") long id,
      @PathVariable("repositoryId") long repositoryId,
      HttpServletRequest servlet) {
    return staging.removeRepository(
        id, repositoryId, subject(servlet), ClientAddress.from(servlet));
  }

  @GetMapping("/sessions/{id}/groups")
  public List<StagingService.GroupChangeView> groupChanges(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.groupChanges(id, subject(servlet));
  }

  @PostMapping("/sessions/{id}/groups/{groupId}/members")
  public StagingService.GroupChangeView addGroupChange(
      @PathVariable("id") long id,
      @PathVariable("groupId") long groupId,
      @RequestBody GroupChangeRequest request,
      HttpServletRequest servlet) {
    if (request == null || request.memberRepositoryId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "memberRepositoryId is required");
    }
    return staging.addGroupChange(
        id, groupId, request.memberRepositoryId(), request.action(), request.position(),
        subject(servlet), ClientAddress.from(servlet));
  }

  @DeleteMapping("/sessions/{id}/groups/{changeId}")
  public ResponseEntity<Void> removeGroupChange(
      @PathVariable("id") long id,
      @PathVariable("changeId") long changeId,
      HttpServletRequest servlet) {
    staging.removeGroupChange(id, changeId, subject(servlet), ClientAddress.from(servlet));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/sessions/{id}/approve")
  public StagingService.StagingSessionView approve(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.approve(id, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/compensate")
  public StagingService.StagingSessionView compensate(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.compensate(id, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/preflight")
  public StagingService.StagingReport preflight(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.preflight(id, subject(servlet), ClientAddress.from(servlet));
  }

  @PostMapping("/sessions/{id}/promote")
  public StagingService.StagingReport promote(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.promote(id, subject(servlet), ClientAddress.from(servlet));
  }

  @GetMapping("/sessions/{id}/report")
  public StagingService.StagingReport report(
      @PathVariable("id") long id, HttpServletRequest servlet) {
    return staging.report(id, subject(servlet));
  }

  private static PermissionSubject subject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.permissionSubject() != null) {
      return authenticated.permissionSubject();
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  public record AddRepositoryRequest(String repository) {
  }

  public record GroupChangeRequest(
      Long memberRepositoryId, String action, Integer position) {
  }
}
