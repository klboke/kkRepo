package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.security.SecurityPayloads.SessionView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UiContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregates the UI bootstrap, session, and permission probes into one request.
 *
 * <p>The session and security catalog remain backed by shared database state. This endpoint only
 * removes duplicate browser round trips and repeated request setup on repository-heavy deployments.
 */
@RestController
@RequestMapping("/internal/security")
public class SecurityUiContextController {
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;

  public SecurityUiContextController(
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.authenticationService = authenticationService;
    this.securityService = securityService;
  }

  @GetMapping("/context")
  public UiContext context(HttpServletRequest request) {
    Optional<AuthenticatedSubject> authenticated = currentSubject(request)
        .or(() -> authenticationService.authenticate(request));
    if (authenticated.isEmpty()) {
      return new UiContext(securityService.adminBootstrapStatus(), null, List.of());
    }
    AuthenticatedSubject subject = authenticated.orElseThrow();
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    List<String> roles = subject.permissionSubject() == null
            || subject.permissionSubject().groupIds() == null
        ? List.of()
        : subject.permissionSubject().groupIds().stream().sorted().toList();
    SessionView session = new SessionView(
        subject.source(),
        subject.userId(),
        subject.realmId(),
        subject.apiKeyId(),
        roles);
    return new UiContext(
        securityService.adminBootstrapStatus(),
        session,
        securityService.listEffectivePermissions(subject.permissionSubject()));
  }

  private Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return Optional.of(authenticated);
    }
    return Optional.empty();
  }
}
