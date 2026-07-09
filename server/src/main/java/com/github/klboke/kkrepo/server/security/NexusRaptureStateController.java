package com.github.klboke.kkrepo.server.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusCurrentUser;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsView;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy Nexus Rapture state polling compatibility endpoint.
 *
 * @deprecated This endpoint is kept for mirrored Nexus UI polling traffic only. It is not part of
 *     kkrepo's primary management API surface.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/extdirect/poll")
public class NexusRaptureStateController {
  private static final String EVENT_TYPE = "event";
  private static final String EVENT_NAME = "rapture_State_get";
  private static final String X_FRAME_OPTIONS = "X-Frame-Options";
  private static final List<String> CAPABILITY_TYPES = List.of(
      "audit",
      "scheduling.scheduler",
      "rapture.settings",
      "node.identity",
      "OutreachManagementCapability",
      "OutreachMarkerCapability");
  private static final List<String> ACTIVE_BUNDLES = List.of(
      "com.sonatype.nexus.plugins.nexus-ldap-plugin",
      "org.sonatype.nexus.plugins.nexus-coreui-plugin",
      "org.sonatype.nexus.plugins.nexus-repository-go",
      "org.sonatype.nexus.plugins.nexus-repository-helm",
      "org.sonatype.nexus.plugins.nexus-repository-maven",
      "org.sonatype.nexus.plugins.nexus-repository-npm",
      "org.sonatype.nexus.plugins.nexus-repository-nuget",
      "org.sonatype.nexus.plugins.nexus-repository-pypi",
      "org.sonatype.nexus.plugins.nexus-repository-raw",
      "org.sonatype.nexus.plugins.nexus-repository-rubygems",
      "org.sonatype.nexus.plugins.nexus-repository-yum",
      "org.sonatype.nexus.rapture",
      "org.sonatype.nexus.repository-config",
      "org.sonatype.nexus.rest",
      "org.sonatype.nexus.security",
      "org.sonatype.nexus.selector",
      "org.sonatype.nexus.ui-plugin");

  private final SecurityManagementService securityService;
  private final SecurityAuthenticationService authenticationService;
  private final ObjectMapper objectMapper;
  private final String serverId = String.valueOf(System.nanoTime());

  public NexusRaptureStateController(
      SecurityManagementService securityService,
      SecurityAuthenticationService authenticationService,
      ObjectMapper objectMapper) {
    this.securityService = securityService;
    this.authenticationService = authenticationService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/rapture_State_get")
  public ResponseEntity<RaptureStateEvent> getState(
      @RequestParam Map<String, String> hashes,
      HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(X_FRAME_OPTIONS, "DENY")
        .body(new RaptureStateEvent(
            new RaptureStateResult(true, stateValues(normalizeHashes(hashes), request)),
            EVENT_NAME,
            EVENT_TYPE));
  }

  private Map<String, Object> stateValues(Map<String, String> hashes, HttpServletRequest request) {
    Map<String, Object> values = new LinkedHashMap<>();
    hashes.keySet().forEach(key -> values.put(key, null));

    AnonymousSettingsView anonymous = securityService.anonymousSettings();
    maybeSend(values, hashes, "anonymousUsername", anonymous.enabled() ? anonymous.userId() : null);
    maybeSend(values, hashes, "clm", clm());
    maybeSend(values, hashes, "upload", true);
    maybeSend(values, hashes, "quorum", quorum());
    maybeSend(values, hashes, "uiSettings", uiSettings());
    maybeSend(values, hashes, "frozen", false);
    maybeSend(values, hashes, "datastores", false);
    maybeSend(values, hashes, "allowScriptCreation", false);
    maybeSend(values, hashes, "serverId", serverId);
    maybeSend(values, hashes, "browseTreeMaxNodes", 10000);
    maybeSend(values, hashes, "capabilityActiveTypes", CAPABILITY_TYPES);
    maybeSend(values, hashes, "activeBundles", ACTIVE_BUNDLES);
    maybeSend(values, hashes, "license", license());
    maybeSend(values, hashes, "rebuildingRepositories", List.of());
    maybeSend(values, hashes, "capabilityCreatedTypes", CAPABILITY_TYPES);
    maybeSend(values, hashes, "frozenManually", false);
    maybeSend(values, hashes, "migration", Map.of("enabled", false));
    maybeSend(values, hashes, "api", true);
    maybeSend(values, hashes, "browseableformats", browseableFormats());
    maybeSend(values, hashes, "user", currentUser(request));
    maybeSend(values, hashes, "health_checks_failed", false);
    maybeSend(values, hashes, "status", status());
    return values;
  }

  private void maybeSend(
      Map<String, Object> values,
      Map<String, String> hashes,
      String key,
      Object value) {
    values.remove(key);
    String hash = hash(value);
    if (!Objects.equals(hash, hashes.get(key))) {
      values.put(key, new StateValue(hash, value));
    }
  }

  private NexusCurrentUser currentUser(HttpServletRequest request) {
    return currentSubject(request)
        .map(this::toCurrentUser)
        .orElse(null);
  }

  private Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object existing = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (existing instanceof AuthenticatedSubject authenticatedSubject) {
      return Optional.of(authenticatedSubject);
    }
    Optional<AuthenticatedSubject> authenticated = authenticationService.authenticate(request);
    authenticated.ifPresent(subject -> request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject));
    return authenticated;
  }

  private NexusCurrentUser toCurrentUser(AuthenticatedSubject subject) {
    return new NexusCurrentUser(
        subject.userId(),
        true,
        securityService.decide(subject.permissionSubject(), "nexus:*").allowed(),
        authenticatedRealms(subject.realmId()));
  }

  private Set<String> authenticatedRealms(String realmId) {
    String normalized = defaultString(realmId, "").toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "local" -> Set.of("NexusAuthenticatingRealm");
      case "ldap" -> Set.of("LdapRealm");
      case "oidc" -> Set.of("OidcRealm");
      default -> blankToNull(realmId) == null ? Set.of() : Set.of(realmId);
    };
  }

  private String hash(Object value) {
    if (value == null) {
      return null;
    }
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-1")
          .digest(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
      throw new IllegalStateException("Unable to hash Rapture state value", e);
    }
  }

  private static Map<String, String> normalizeHashes(Map<String, String> hashes) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (hashes == null) {
      return normalized;
    }
    hashes.forEach((key, value) -> {
      if (key != null && !key.isBlank() && !key.startsWith("_")) {
        normalized.put(key, value);
      }
    });
    return normalized;
  }

  private static Map<String, Object> clm() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("licenseValid", true);
    value.put("enabled", false);
    value.put("url", null);
    value.put("showLink", null);
    return value;
  }

  private static Map<String, Object> quorum() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("activeMembers", 1);
    value.put("minimumForQuorum", 1);
    value.put("quorumPresent", true);
    value.put("databaseName", null);
    return value;
  }

  private static Map<String, Object> uiSettings() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("debugAllowed", true);
    value.put("statusIntervalAuthenticated", 5);
    value.put("statusIntervalAnonymous", 60);
    value.put("sessionTimeout", 30);
    value.put("requestTimeout", 60);
    value.put("longRequestTimeout", 180);
    value.put("searchRequestTimeout", 0);
    value.put("title", "Nexus Repository Manager");
    return value;
  }

  private static Map<String, Object> license() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("required", false);
    value.put("installed", false);
    value.put("valid", false);
    value.put("daysToExpiry", 0);
    value.put("features", List.of());
    return value;
  }

  private static List<Map<String, String>> browseableFormats() {
    return List.of(
        Map.of("id", "pypi"),
        Map.of("id", "go"),
        Map.of("id", "maven2"),
        Map.of("id", "raw"),
        Map.of("id", "npm"),
        Map.of("id", "helm"),
        Map.of("id", "nuget"),
        Map.of("id", "rubygems"),
        Map.of("id", "yum"));
  }

  private static Map<String, Object> status() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("edition", "OSS");
    value.put("version", "3.29.2-02");
    value.put("buildRevision", "kkrepo");
    value.put("buildTimestamp", "kkrepo");
    return value;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public record RaptureStateEvent(
      RaptureStateResult data,
      String name,
      String type) {
  }

  public record RaptureStateResult(
      Boolean success,
      Map<String, Object> data) {
  }

  public record StateValue(
      String hash,
      Object value) {
  }
}
