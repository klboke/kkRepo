package com.github.klboke.kkrepo.server.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Legacy Nexus Rapture session compatibility endpoint.
 *
 * @deprecated This endpoint is kept for mirrored Nexus UI login/logout traffic only. New kkrepo
 *     management flows should use the REST or internal UI session APIs instead.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/rapture/session")
public class NexusRaptureSessionController {
  private static final String X_FRAME_OPTIONS = "X-Frame-Options";

  private final SecurityAuthenticationService authenticationService;

  public NexusRaptureSessionController(SecurityAuthenticationService authenticationService) {
    this.authenticationService = authenticationService;
  }

  @PostMapping
  public ResponseEntity<Void> create(
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String password,
      HttpServletRequest request) {
    AuthenticatedSubject subject = currentSubject(request)
        .or(() -> authenticateParameters(username, password))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication failed"));
    authenticationService.storeSessionSubject(request, subject);
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    return noContent();
  }

  @DeleteMapping
  public ResponseEntity<Void> delete(HttpServletRequest request) {
    authenticationService.clearSessionSubject(request);
    return noContent();
  }

  private static ResponseEntity<Void> noContent() {
    return ResponseEntity.noContent()
        .header(X_FRAME_OPTIONS, "DENY")
        .build();
  }

  private Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object existing = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (existing instanceof AuthenticatedSubject authenticatedSubject) {
      return Optional.of(authenticatedSubject);
    }
    return authenticationService.authenticate(request);
  }

  private Optional<AuthenticatedSubject> authenticateParameters(String encodedUsername, String encodedPassword) {
    if (encodedUsername == null || encodedPassword == null) {
      return Optional.empty();
    }
    try {
      String username = new String(Base64.getDecoder().decode(encodedUsername), StandardCharsets.UTF_8);
      String password = new String(Base64.getDecoder().decode(encodedPassword), StandardCharsets.UTF_8);
      return authenticationService.authenticateCredentials(username, password);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
