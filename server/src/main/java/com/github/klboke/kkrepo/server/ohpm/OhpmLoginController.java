package com.github.klboke.kkrepo.server.ohpm;

import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ManagementAuditFilter;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ohpm/login")
public class OhpmLoginController {
  private final OhpmLoginService login;
  private final SecurityAuthenticationService authentication;
  private final SecurityManagementService security;

  public OhpmLoginController(OhpmLoginService login, SecurityAuthenticationService authentication,
      SecurityManagementService security) {
    this.login = login;
    this.authentication = authentication;
    this.security = security;
  }

  @GetMapping
  public OhpmLoginService.Configuration configuration(HttpServletRequest request) {
    requireAdmin(request);
    return login.configuration();
  }

  @GetMapping("/keys")
  public List<OhpmLoginService.KeyView> keys(@RequestParam(value = "offset", defaultValue = "0") int offset,
      HttpServletRequest request) {
    requireAdmin(request);
    return login.keys(offset);
  }

  @PutMapping("/keys")
  public OhpmLoginService.KeyView putKey(@RequestBody KeyCommand command, HttpServletRequest request) {
    requireAdmin(request);
    return login.putKey(command.source(), command.userId(), command.publicKey(), command.enabled(),
        () -> requireAdmin(request));
  }

  @DeleteMapping("/keys/{publishId}")
  public ResponseEntity<Void> deleteKey(@PathVariable("publishId") String publishId, HttpServletRequest request) {
    requireAdmin(request);
    login.deleteKey(publishId, () -> requireAdmin(request));
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/keys/{publishId}/enabled")
  public OhpmLoginService.KeyView setEnabled(@PathVariable("publishId") String publishId,
      @RequestBody EnabledCommand command, HttpServletRequest request) {
    requireAdmin(request);
    return login.setEnabled(publishId, command.enabled(), () -> requireAdmin(request));
  }

  private void requireAdmin(HttpServletRequest request) {
    var subject = authentication.authenticateFresh(request)
        .orElseThrow(() -> new OhpmException(401, "Authentication required"));
    if (!security.decideFresh(subject.permissionSubject(), "nexus:*").allowed()) {
      throw new OhpmException(403, "Administrator permission required");
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    request.setAttribute(ManagementAuditFilter.OPERATION_DETAILS_ATTRIBUTE,
        Map.of("format", "ohpm", "operation", "signing_key_configuration"));
  }

  @ExceptionHandler(OhpmException.class)
  ResponseEntity<Map<String, Object>> protocolError(OhpmException error) {
    return ResponseEntity.status(error.status()).body(error.response());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid OHPM public key or owner"));
  }

  public record KeyCommand(String source, String userId, String publicKey, boolean enabled) {}
  public record EnabledCommand(boolean enabled) {}
}
