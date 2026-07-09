package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUserAccount;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUserAccountPassword;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Legacy Nexus internal UI user-account compatibility endpoint.
 *
 * @deprecated This endpoint is kept for Nexus internal UI current-user compatibility only. New
 *     kkrepo management flows should use {@code /internal/security} or the Nexus REST API surface
 *     instead.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/rest/internal/ui/user")
public class NexusUserAccountController {
  private final SecurityManagementService securityService;
  private final SecurityAuthenticationService authenticationService;
  private final NexusAuthenticationTicketService authenticationTickets;

  public NexusUserAccountController(
      SecurityManagementService securityService,
      SecurityAuthenticationService authenticationService,
      NexusAuthenticationTicketService authenticationTickets) {
    this.securityService = securityService;
    this.authenticationService = authenticationService;
    this.authenticationTickets = authenticationTickets;
  }

  @GetMapping
  public NexusUserAccount current(HttpServletRequest request) {
    return toAccount(currentUser(request));
  }

  @PutMapping
  public NexusUserAccount update(
      @RequestBody NexusUserAccount account,
      HttpServletRequest request) {
    UserView current = currentUser(request);
    UserView updated = securityService.saveUser(new UserCommand(
        current.source(),
        current.userId(),
        account == null ? null : account.firstName(),
        account == null ? null : account.lastName(),
        account == null ? null : account.email(),
        null,
        null,
        current.status(),
        current.externalId(),
        current.roles() == null ? List.of() : current.roles(),
        current.attributes() == null ? Map.of() : current.attributes()));
    return toAccount(updated);
  }

  @PutMapping("/{userId}/password")
  public ResponseEntity<Void> changePassword(
      @PathVariable("userId") String userId,
      @RequestBody NexusUserAccountPassword password,
      HttpServletRequest request) {
    AuthenticatedSubject current = currentSubject(request);
    String ticketUser = authenticationTickets.redeemTicket(password == null ? null : password.authToken())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid authentication ticket"));
    if (!ticketUser.equals(current.userId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Username mismatch");
    }
    if (!userId.equals(current.userId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Username mismatch");
    }
    securityService.changePassword(userId, password == null ? null : password.password());
    return ResponseEntity.noContent().build();
  }

  private UserView currentUser(HttpServletRequest request) {
    AuthenticatedSubject subject = currentSubject(request);
    return securityService.findUser(subject.source(), subject.userId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + subject.userId()));
  }

  private AuthenticatedSubject currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return authenticated;
    }
    Optional<AuthenticatedSubject> authenticated = authenticationService.authenticate(request);
    authenticated.ifPresent(value -> request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, value));
    return authenticated.orElseThrow(() ->
        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
  }

  private static NexusUserAccount toAccount(UserView user) {
    return new NexusUserAccount(
        user.userId(),
        user.firstName(),
        user.lastName(),
        user.email(),
        user.external());
  }
}
