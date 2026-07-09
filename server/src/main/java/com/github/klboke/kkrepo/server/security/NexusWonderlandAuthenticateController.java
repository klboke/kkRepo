package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusAuthTicket;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusAuthToken;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Legacy Nexus Wonderland authentication compatibility endpoint.
 *
 * @deprecated This endpoint is kept for Nexus UI password-ticket compatibility only. New kkrepo
 *     management flows should use the REST or internal UI authentication APIs instead.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/rest/wonderland/authenticate")
public class NexusWonderlandAuthenticateController {
  private final SecurityAuthenticationService authenticationService;
  private final NexusAuthenticationTicketService authenticationTickets;

  public NexusWonderlandAuthenticateController(
      SecurityAuthenticationService authenticationService,
      NexusAuthenticationTicketService authenticationTickets) {
    this.authenticationService = authenticationService;
    this.authenticationTickets = authenticationTickets;
  }

  @PostMapping
  public NexusAuthTicket authenticate(
      @RequestBody NexusAuthToken token,
      HttpServletRequest request) {
    String username = decodeBase64(token == null ? null : token.u(), "u");
    String password = decodeBase64(token == null ? null : token.p(), "p");
    String principal = currentSubject(request).map(AuthenticatedSubject::userId).orElse("");
    if (!principal.equals(username)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username mismatch");
    }
    AuthenticatedSubject authenticated = authenticationService.authenticateCredentials(username, password)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication failed"));
    return new NexusAuthTicket(authenticationTickets.createTicket(authenticated.userId()));
  }

  private Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticatedSubject) {
      return Optional.of(authenticatedSubject);
    }
    Optional<AuthenticatedSubject> authenticated = authenticationService.authenticate(request);
    authenticated.ifPresent(value -> request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, value));
    return authenticated;
  }

  private static String decodeBase64(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    try {
      return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be base64 encoded", e);
    }
  }
}
