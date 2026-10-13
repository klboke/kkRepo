package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ManagementAuditFilter;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementFilter;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/browse/{repository}/directory-deletion")
public class BrowseDirectoryDeletionController {
  private TransientTransactionRetry transactionRetry;

  @Autowired
  void setTransactionRetry(TransientTransactionRetry transactionRetry) {
    this.transactionRetry = transactionRetry;
  }

  private final BrowseDirectoryDeletionService deletion;
  private final SecurityAuthenticationService authentication;
  private final SecurityManagementService security;

  public BrowseDirectoryDeletionController(BrowseDirectoryDeletionService deletion,
      SecurityAuthenticationService authentication, SecurityManagementService security) {
    this.deletion = deletion;
    this.authentication = authentication;
    this.security = security;
  }

  @GetMapping
  public BrowseDirectoryDeletionService.Preview preview(
      @PathVariable("repository") String repository, @RequestParam("path") String path, HttpServletRequest request) {
    requireAdministrator(request);
    return deletion.preview(repository, path);
  }

  @PostMapping
  public BrowseDirectoryDeletionService.Result delete(
      @PathVariable("repository") String repository, @RequestBody BrowseDirectoryDeletionService.Command command,
      HttpServletRequest request) {
    AuthenticatedSubject subject = requireAdministrator(request);
    if (command == null) throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "A confirmed directory preview is required");
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("operation", "browse.directory.delete");
    details.put("repository", repository);
    if (command.path() != null) details.put("path", command.path());
    request.setAttribute(ManagementAuditFilter.OPERATION_DETAILS_ATTRIBUTE, details);
    var result = transactionRetry == null
        ? deletion.delete(repository, command, subject.userId())
        : transactionRetry.retryTransaction("browse-directory-delete",
            () -> deletion.delete(repository, command, subject.userId()));
    details.put("deletedAssets", result.deletedAssets());
    return result;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> rejected(ResponseStatusException failure) {
    return ResponseEntity.status(failure.getStatusCode()).body(Map.of(
        "message", failure.getReason() == null ? "Directory deletion failed" : failure.getReason()));
  }

  private AuthenticatedSubject requireAdministrator(HttpServletRequest request) {
    AuthenticatedSubject subject = authentication.authenticate(request)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    request.setAttribute(SecurityManagementFilter.REQUESTED_PERMISSION_ATTRIBUTE, "nexus:*");
    AccessDecision decision = security.decide(subject.permissionSubject(), "nexus:*");
    if (!decision.allowed()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    return subject;
  }
}
