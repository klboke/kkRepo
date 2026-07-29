package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementFilter;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class NexusRepositoryManagementAuthorizer {
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;

  public NexusRepositoryManagementAuthorizer(
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.authenticationService = authenticationService;
    this.securityService = securityService;
  }

  public void requireRepositoryAction(
      HttpServletRequest request,
      RepositoryRecord repository,
      String path,
      PermissionAction action) {
    AuthenticatedSubject subject = requireSubject(request);
    String permission = "nexus:repository-view:"
        + nexusFormat(repository.format()) + ":" + repository.name() + ":" + action.nexusAction();
    setRequestContext(request, repository, permission);
    AccessDecision decision = securityService.decide(
        subject.permissionSubject(),
        new RepositoryPermission(repository.name(), repository.format(), path == null ? "" : path, action));
    requireAllowed(decision);
  }

  public void requireRepositoryAdmin(
      HttpServletRequest request,
      RepositoryFormat format,
      String repositoryName,
      String action) {
    AuthenticatedSubject subject = requireSubject(request);
    String permission = "nexus:repository-admin:"
        + nexusFormat(format) + ":" + repositoryName + ":" + action;
    request.setAttribute(SecurityManagementFilter.REQUESTED_PERMISSION_ATTRIBUTE, permission);
    requireAllowed(securityService.decide(subject.permissionSubject(), permission));
  }

  private AuthenticatedSubject requireSubject(HttpServletRequest request) {
    Optional<AuthenticatedSubject> subject = currentSubject(request)
        .or(() -> authenticationService.authenticate(request));
    if (subject.isEmpty() && !authenticationService.hasPresentedCredentials(request)) {
      subject = authenticationService.authenticateAnonymous();
    }
    if (subject.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject.get());
    return subject.get();
  }

  private static Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    return value instanceof AuthenticatedSubject subject ? Optional.of(subject) : Optional.empty();
  }

  private static void setRequestContext(
      HttpServletRequest request, RepositoryRecord repository, String permission) {
    request.setAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, repository);
    request.setAttribute(SecurityManagementFilter.REQUESTED_PERMISSION_ATTRIBUTE, permission);
  }

  private static void requireAllowed(AccessDecision decision) {
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
  }

  private static String nexusFormat(RepositoryFormat format) {
    return format == null ? "*" : format.name().toLowerCase(Locale.ROOT);
  }
}
