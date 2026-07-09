package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusAnonymousSettings;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy Nexus internal UI anonymous-settings compatibility endpoint.
 *
 * @deprecated This endpoint is kept for Nexus internal UI compatibility only. New kkrepo
 *     management flows should use {@code /internal/security/anonymous} or the Nexus REST API
 *     surface instead.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/rest/internal/ui/anonymous-settings")
public class NexusAnonymousSettingsController {
  private final SecurityManagementService securityService;

  public NexusAnonymousSettingsController(SecurityManagementService securityService) {
    this.securityService = securityService;
  }

  @GetMapping
  public NexusAnonymousSettings read() {
    AnonymousSettingsView settings = securityService.anonymousSettings();
    return new NexusAnonymousSettings(
        settings.enabled(),
        settings.userId(),
        settings.realmName());
  }

  @PutMapping
  public ResponseEntity<Void> update(@RequestBody NexusAnonymousSettings request) {
    securityService.saveAnonymousSettings(new AnonymousSettingsCommand(
        request.enabled(),
        null,
        request.userId(),
        request.realmName()));
    return ResponseEntity.noContent().build();
  }
}
