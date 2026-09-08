package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.repositories.RepositoryNotFoundException;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusAnonymousSettings;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusApiKey;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusApiKeyCommand;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusContentSelector;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusCreatedApiKey;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusCreateUser;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusPrivilege;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusRealm;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusRole;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUser;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUserSource;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.CreatedApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/service/rest/v1/security")
public class NexusSecurityRestController {
  private static final String DEFAULT_SOURCE = "Local";
  private static final String NEXUS_AUTHENTICATING_REALM = "NexusAuthenticatingRealm";
  private static final String NEXUS_AUTHORIZING_REALM = "NexusAuthorizingRealm";
  private static final String LDAP_REALM = "LdapRealm";
  private static final String OIDC_REALM = "OidcRealm";
  private static final Pattern NEXUS_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-][a-zA-Z0-9_\\-.]*$");
  private static final Set<String> BREAD_ACTIONS = Set.of("browse", "read", "edit", "add", "delete", "*");
  private static final Set<String> CRUD_ACTIONS = Set.of(
      "read", "edit", "add", "delete", "associate", "disassociate", "*");
  private static final Set<String> SUPPORTED_REPOSITORY_FORMATS = Set.of(
      "*", "maven2", "npm", "pypi", "go", "helm", "raw");

  private final SecurityManagementService securityService;
  private final RepositoryService repositoryService;

  public NexusSecurityRestController(SecurityManagementService securityService) {
    this(securityService, null);
  }

  @Autowired
  public NexusSecurityRestController(SecurityManagementService securityService, RepositoryService repositoryService) {
    this.securityService = securityService;
    this.repositoryService = repositoryService;
  }

  @GetMapping("/users")
  public List<NexusUser> users(
      @RequestParam(name = "userId", required = false) String userId,
      @RequestParam(name = "source", required = false) String source) {
    return securityService.listUsers(source, userId).stream()
        .map(this::toNexusUser)
        .toList();
  }

  @GetMapping("/user-sources")
  public List<NexusUserSource> userSources() {
    Map<String, NexusUserSource> sources = new LinkedHashMap<>();
    for (RealmView realm : securityService.listRealms()) {
      String source = sourceForRealm(realm);
      sources.putIfAbsent(source, new NexusUserSource(source, realm.name()));
    }
    return List.copyOf(sources.values());
  }

  @PostMapping("/users")
  public NexusUser createUser(@RequestBody NexusCreateUser request) {
    String userId = requireText(request.userId(), "userId");
    if (blankToNull(request.password()) == null) {
      throw new SecurityValidationException("A non-empty password is required.");
    }
    if (securityService.findUser(DEFAULT_SOURCE, userId).isPresent()) {
      throw new SecurityValidationException("User already exists: " + userId);
    }
    return toNexusUser(securityService.saveUser(new UserCommand(
        DEFAULT_SOURCE,
        userId,
        request.firstName(),
        request.lastName(),
        request.emailAddress(),
        request.password(),
        null,
        toStoredStatus(request.status()),
        null,
        toList(request.roles()),
        Map.of())));
  }

  @PutMapping("/users/{userId}")
  public ResponseEntity<Void> updateUser(
      @PathVariable("userId") String userId,
      @RequestBody NexusUser request) {
    if (!userId.equals(request.userId())) {
      throw new SecurityValidationException("The path's userId does not match the body");
    }
    String source = normalizeSource(request.source());
    UserView existing = securityService.findUser(source, userId)
        .orElseThrow(() -> notFound("User not found: " + userId));
    boolean local = DEFAULT_SOURCE.equals(source);
    securityService.saveUser(new UserCommand(
        source,
        userId,
        local ? request.firstName() : existing.firstName(),
        local ? request.lastName() : existing.lastName(),
        local ? request.emailAddress() : existing.email(),
        null,
        null,
        local ? toStoredStatus(request.status()) : existing.status(),
        existing.externalId(),
        local ? toList(request.roles()) : NexusExternalRoles.localRoleList(existing, request.roles()),
        existing.attributes()));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/users/{userId}")
  public ResponseEntity<Void> deleteUser(
      @PathVariable("userId") String userId,
      @RequestParam(name = "source", required = false) String source) {
    UserView user = findUserForDelete(source, userId);
    if (!DEFAULT_SOURCE.equals(user.source())) {
      throw new SecurityValidationException("Non-local user cannot be deleted.");
    }
    securityService.deleteUser(user.source(), user.userId());
    return ResponseEntity.noContent().build();
  }

  @PutMapping(value = "/users/{userId}/change-password", consumes = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<Void> changePassword(
      @PathVariable("userId") String userId,
      @RequestBody String password) {
    if (blankToNull(password) == null) {
      throw new SecurityValidationException("Password must be supplied.");
    }
    if (securityService.findUser(DEFAULT_SOURCE, userId).isEmpty()) {
      throw notFound("User not found: " + userId);
    }
    securityService.changePassword(userId, password);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/roles")
  public List<NexusRole> roles(@RequestParam(name = "source", required = false) String source) {
    return securityService.listRoles().stream()
        .sorted(Comparator.comparing(RoleView::roleId))
        .map(this::toNexusRole)
        .toList();
  }

  @PostMapping("/roles")
  public NexusRole createRole(@RequestBody NexusRole request) {
    String roleId = requireText(request.id(), "id");
    validateRoleRequest(roleId, request);
    if (securityService.findRole(roleId).isPresent()) {
      throw new SecurityValidationException("Role already exists: " + roleId);
    }
    return toNexusRole(securityService.saveRole(toRoleCommand(roleId, request, false)));
  }

  @GetMapping("/roles/{id}")
  public NexusRole getRole(
      @PathVariable("id") String id,
      @RequestParam(name = "source", required = false) String source) {
    RoleView role = securityService.findRole(id)
        .orElseThrow(() -> notFound("Role not found: " + id));
    String sourceFilter = roleSourceForRead(source);
    if (!sourceFilter.equals(role.source())) {
      throw notFound("Role not found: " + id);
    }
    return toNexusRole(role);
  }

  @PutMapping("/roles/{id}")
  public ResponseEntity<Void> updateRole(
      @PathVariable("id") String id,
      @RequestBody NexusRole request) {
    if (!id.equals(request.id())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Role id " + request.id() + " does not match path " + id);
    }
    findDefaultRoleForWrite(id);
    validateRoleRequest(id, request);
    securityService.saveRole(toRoleCommand(id, request, false));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/roles/{id}")
  public ResponseEntity<Void> deleteRole(@PathVariable("id") String id) {
    findDefaultRoleForWrite(id);
    securityService.deleteRole(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/privileges")
  public List<NexusPrivilege> privileges() {
    return securityService.listPrivileges().stream()
        .sorted(Comparator.comparing(PrivilegeView::name))
        .map(this::toNexusPrivilege)
        .toList();
  }

  @GetMapping("/privileges/{privilegeId}")
  public NexusPrivilege getPrivilege(@PathVariable("privilegeId") String privilegeId) {
    return securityService.findPrivilege(privilegeId)
        .map(this::toNexusPrivilege)
        .orElseThrow(() -> notFound("Privilege not found: " + privilegeId));
  }

  @DeleteMapping("/privileges/{privilegeId}")
  public ResponseEntity<Void> deletePrivilege(@PathVariable("privilegeId") String privilegeId) {
    if (securityService.findPrivilege(privilegeId).isEmpty()) {
      throw notFound("Privilege not found: " + privilegeId);
    }
    securityService.deletePrivilege(privilegeId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/privileges/application")
  public ResponseEntity<Void> createApplicationPrivilege(@RequestBody NexusPrivilege request) {
    return createPrivilege("application", request);
  }

  @PutMapping("/privileges/application/{privilegeId}")
  public ResponseEntity<Void> updateApplicationPrivilege(
      @PathVariable("privilegeId") String privilegeId,
      @RequestBody NexusPrivilege request) {
    return updatePrivilege(privilegeId, "application", request);
  }

  @PostMapping("/privileges/wildcard")
  public ResponseEntity<Void> createWildcardPrivilege(@RequestBody NexusPrivilege request) {
    return createPrivilege("wildcard", request);
  }

  @PutMapping("/privileges/wildcard/{privilegeId}")
  public ResponseEntity<Void> updateWildcardPrivilege(
      @PathVariable("privilegeId") String privilegeId,
      @RequestBody NexusPrivilege request) {
    return updatePrivilege(privilegeId, "wildcard", request);
  }

  @PostMapping("/privileges/repository-admin")
  public ResponseEntity<Void> createRepositoryAdminPrivilege(@RequestBody NexusPrivilege request) {
    return createPrivilege("repository-admin", request);
  }

  @PutMapping("/privileges/repository-admin/{privilegeId}")
  public ResponseEntity<Void> updateRepositoryAdminPrivilege(
      @PathVariable("privilegeId") String privilegeId,
      @RequestBody NexusPrivilege request) {
    return updatePrivilege(privilegeId, "repository-admin", request);
  }

  @PostMapping("/privileges/repository-view")
  public ResponseEntity<Void> createRepositoryViewPrivilege(@RequestBody NexusPrivilege request) {
    return createPrivilege("repository-view", request);
  }

  @PutMapping("/privileges/repository-view/{privilegeId}")
  public ResponseEntity<Void> updateRepositoryViewPrivilege(
      @PathVariable("privilegeId") String privilegeId,
      @RequestBody NexusPrivilege request) {
    return updatePrivilege(privilegeId, "repository-view", request);
  }

  @PostMapping("/privileges/repository-content-selector")
  public ResponseEntity<Void> createRepositoryContentSelectorPrivilege(@RequestBody NexusPrivilege request) {
    return createPrivilege("repository-content-selector", request);
  }

  @PutMapping("/privileges/repository-content-selector/{privilegeId}")
  public ResponseEntity<Void> updateRepositoryContentSelectorPrivilege(
      @PathVariable("privilegeId") String privilegeId,
      @RequestBody NexusPrivilege request) {
    return updatePrivilege(privilegeId, "repository-content-selector", request);
  }

  @GetMapping("/realms/available")
  public List<NexusRealm> availableRealms() {
    return securityService.listNexusRealmTypes().stream()
        .map(realm -> new NexusRealm(realm.id(), realm.name()))
        .toList();
  }

  @GetMapping("/realms/active")
  public List<String> activeRealms() {
    return securityService.listActiveNexusRealmNames();
  }

  @PutMapping("/realms/active")
  public ResponseEntity<Void> updateActiveRealms(@RequestBody List<String> realmIds) {
    securityService.saveActiveNexusRealmNames(realmIds);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/anonymous")
  public NexusAnonymousSettings anonymousSettings() {
    return toNexusAnonymousSettings(securityService.anonymousSettings());
  }

  @PutMapping("/anonymous")
  public NexusAnonymousSettings updateAnonymousSettings(@RequestBody NexusAnonymousSettings request) {
    AnonymousSettingsView updated = securityService.saveAnonymousSettings(new AnonymousSettingsCommand(
        request.enabled(),
        null,
        request.userId(),
        request.realmName()));
    return toNexusAnonymousSettings(updated);
  }

  @GetMapping("/content-selectors")
  public List<NexusContentSelector> contentSelectors() {
    return securityService.listRepositoryTargets().stream()
        .filter(this::isContentSelector)
        .map(this::toNexusContentSelector)
        .toList();
  }

  @PostMapping("/content-selectors")
  public ResponseEntity<Void> createContentSelector(@RequestBody NexusContentSelector request) {
    String name = requireText(request.name(), "name");
    validateNexusName(name, "name");
    if (securityService.findRepositoryTarget(name).isPresent()) {
      throw new SecurityValidationException("Content selector already exists: " + name);
    }
    securityService.saveRepositoryTarget(toRepositoryTargetCommand(name, request, "csel"));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/content-selectors/{name}")
  public NexusContentSelector getContentSelector(@PathVariable("name") String name) {
    return securityService.findRepositoryTarget(name)
        .filter(this::isContentSelector)
        .map(this::toNexusContentSelector)
        .orElseThrow(() -> notFound("Content selector not found: " + name));
  }

  @PutMapping("/content-selectors/{name}")
  public ResponseEntity<Void> updateContentSelector(
      @PathVariable("name") String name,
      @RequestBody NexusContentSelector request) {
    RepositoryTargetView existing = securityService.findRepositoryTarget(name)
        .orElseThrow(() -> notFound("Content selector not found: " + name));
    if (!isContentSelector(existing)) {
      throw notFound("Content selector not found: " + name);
    }
    securityService.saveRepositoryTarget(toRepositoryTargetCommand(name, request, contentSelectorType(existing)));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/content-selectors/{name}")
  public ResponseEntity<Void> deleteContentSelector(@PathVariable("name") String name) {
    RepositoryTargetView existing = securityService.findRepositoryTarget(name)
        .orElseThrow(() -> notFound("Content selector not found: " + name));
    if (!isContentSelector(existing)) {
      throw notFound("Content selector not found: " + name);
    }
    List<String> usedBy = contentSelectorUsedBy(name);
    if (!usedBy.isEmpty()) {
      throw new SecurityValidationException("Selector is used by privileges: " + String.join(", ", usedBy));
    }
    securityService.deleteRepositoryTarget(name);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api-keys")
  public List<NexusApiKey> apiKeys(
      @RequestParam(name = "domain", required = false) String domain,
      @RequestParam(name = "ownerSource", required = false) String ownerSource,
      @RequestParam(name = "ownerUserId", required = false) String ownerUserId) {
    String domainFilter = blankToNull(domain);
    String sourceFilter = blankToNull(ownerSource) == null ? null : normalizeSource(ownerSource);
    String userFilter = blankToNull(ownerUserId);
    return securityService.listApiKeys().stream()
        .filter(apiKey -> domainFilter == null || domainFilter.equals(apiKey.domain()))
        .filter(apiKey -> sourceFilter == null || sourceFilter.equals(apiKey.ownerSource()))
        .filter(apiKey -> userFilter == null || userFilter.equals(apiKey.ownerUserId()))
        .map(this::toNexusApiKey)
        .toList();
  }

  @GetMapping("/api-keys/current")
  public List<NexusApiKey> currentApiKeys(HttpServletRequest request) {
    AuthenticatedSubject subject = currentSubject(request);
    return securityService.listApiKeysForOwner(subject.source(), subject.userId()).stream()
        .map(this::toNexusApiKey)
        .toList();
  }

  @GetMapping("/api-keys/{id}")
  public NexusApiKey getApiKey(@PathVariable("id") long id) {
    return securityService.findApiKey(id)
        .map(this::toNexusApiKey)
        .orElseThrow(() -> notFound("API key not found: " + id));
  }

  @PostMapping("/api-keys")
  public NexusCreatedApiKey createApiKey(@RequestBody NexusApiKeyCommand request) {
    CreatedApiKeyView created = securityService.createApiKey(new ApiKeyCommand(
        request.domain(),
        request.ownerSource(),
        request.ownerUserId(),
        request.displayName(),
        request.status(),
        toList(request.scopes()),
        request.expiresAt(),
        request.encryptedPayload(),
        request.apiKeyHash()));
    return new NexusCreatedApiKey(toNexusApiKey(created.apiKey()), created.token());
  }

  @PostMapping("/api-keys/current")
  public NexusCreatedApiKey createCurrentApiKey(
      HttpServletRequest request,
      @RequestBody(required = false) NexusApiKeyCommand requestBody) {
    AuthenticatedSubject subject = currentSubject(request);
    NexusApiKeyCommand command = requestBody == null
        ? new NexusApiKeyCommand(null, null, null, null, null, null, null, null, null)
        : requestBody;
    CreatedApiKeyView created = securityService.createApiKeyForOwner(
        subject.source(),
        subject.userId(),
        new ApiKeyCommand(
            command.domain(),
            command.ownerSource(),
            command.ownerUserId(),
            command.displayName(),
            command.status(),
            toList(command.scopes()),
            command.expiresAt(),
            command.encryptedPayload(),
            command.apiKeyHash()));
    return new NexusCreatedApiKey(toNexusApiKey(created.apiKey()), created.token());
  }

  @PostMapping("/api-keys/{id}/reset")
  public NexusCreatedApiKey resetApiKey(@PathVariable("id") long id) {
    if (securityService.findApiKey(id).isEmpty()) {
      throw notFound("API key not found: " + id);
    }
    CreatedApiKeyView reset = securityService.resetApiKey(id);
    return new NexusCreatedApiKey(toNexusApiKey(reset.apiKey()), reset.token());
  }

  @PostMapping("/api-keys/current/{id}/reset")
  public NexusCreatedApiKey resetCurrentApiKey(
      HttpServletRequest request,
      @PathVariable("id") long id) {
    AuthenticatedSubject subject = currentSubject(request);
    CreatedApiKeyView reset = securityService.resetApiKeyForOwner(id, subject.source(), subject.userId());
    return new NexusCreatedApiKey(toNexusApiKey(reset.apiKey()), reset.token());
  }

  @DeleteMapping("/api-keys/{id}")
  public ResponseEntity<Void> deleteApiKey(@PathVariable("id") long id) {
    if (securityService.findApiKey(id).isEmpty()) {
      throw notFound("API key not found: " + id);
    }
    securityService.deleteApiKey(id);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api-keys/current/{id}")
  public ResponseEntity<Void> deleteCurrentApiKey(
      HttpServletRequest request,
      @PathVariable("id") long id) {
    AuthenticatedSubject subject = currentSubject(request);
    securityService.deleteApiKeyForOwner(id, subject.source(), subject.userId());
    return ResponseEntity.noContent().build();
  }

  private void validateRoleRequest(String roleId, NexusRole request) {
    requireText(request.name(), "name");
    for (String privilegeId : toList(request.privileges())) {
      if (securityService.findPrivilege(privilegeId).isEmpty()) {
        throw new SecurityValidationException(
            "\"Privilege '" + privilegeId + "' contained in role '" + roleId + "' not found.\"");
      }
    }
    for (String childRoleId : toList(request.roles())) {
      if (roleId.equals(childRoleId)) {
        throw new SecurityValidationException(
            "\"Role '" + roleId + "' cannot contain itself either directly or indirectly through child roles.\"");
      }
      if (securityService.findRole(childRoleId).isEmpty()) {
        throw new SecurityValidationException(
            "\"Role '" + childRoleId + "' contained in role '" + roleId + "' not found.\"");
      }
    }
  }

  private ResponseEntity<Void> createPrivilege(String type, NexusPrivilege request) {
    String name = requireText(request.name(), "name");
    validateNexusName(name, "name");
    if (securityService.findPrivilege(name).isPresent()) {
      throw new SecurityValidationException("Privilege already exists: " + name);
    }
    validatePrivilegeRequest(type, request);
    securityService.savePrivilege(toPrivilegeCommand(type, name, request, false));
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  private ResponseEntity<Void> updatePrivilege(String privilegeId, String type, NexusPrivilege request) {
    if (!privilegeId.equals(request.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Privilege name " + request.name() + " does not match path " + privilegeId);
    }
    validateNexusName(privilegeId, "name");
    validatePrivilegeRequest(type, request);
    PrivilegeView existing = securityService.findPrivilege(privilegeId)
        .orElseThrow(() -> notFound("Privilege not found: " + privilegeId));
    securityService.savePrivilege(toPrivilegeCommand(existing.type(), privilegeId, request, existing.readOnly()));
    return ResponseEntity.noContent().build();
  }

  private void validatePrivilegeRequest(String type, NexusPrivilege request) {
    switch (type) {
      case "wildcard" -> requireText(
          propertyString(request, request.pattern(), "pattern", "permission", "wildcard"),
          "pattern");
      case "application" -> {
        requireText(propertyString(request, request.domain(), "domain", "application"), "domain");
        normalizedActionsForType(type, actionValues(request));
      }
      case "repository-view", "repository-admin" -> {
        normalizedActionsForType(type, actionValues(request));
        validateRepositoryPrivilege(request);
      }
      case "repository-content-selector" -> {
        normalizedActionsForType(type, actionValues(request));
        validateRepositoryPrivilege(request);
        validateContentSelectorPrivilege(request);
      }
      default -> {
        // Unknown privilege types are intentionally left flexible for kkrepo native extensions.
      }
    }
  }

  private void validateRepositoryPrivilege(NexusPrivilege request) {
    String format = requireText(
        propertyString(request, request.format(), "format", "repositoryFormat", "formatId"),
        "format");
    String repository = requireText(
        propertyString(request, request.repository(), "repository", "name", "repositoryId", "repositoryName"),
        "repository");
    String normalizedFormat = ContentSelectorExpressionEvaluator.nexusFormat(format);
    if (!SUPPORTED_REPOSITORY_FORMATS.contains(normalizedFormat)) {
      throw new SecurityValidationException("\"Invalid format '" + format + "' supplied.\"");
    }
    if ("*".equals(repository) || repositoryService == null) {
      return;
    }
    RepositoryView repositoryView;
    try {
      repositoryView = repositoryService.get(repository);
    } catch (RepositoryNotFoundException e) {
      throw new SecurityValidationException("\"Invalid repository '" + repository + "' supplied.\"");
    }
    String repositoryFormat = ContentSelectorExpressionEvaluator.nexusFormat(repositoryView.format().name());
    if (!"*".equals(normalizedFormat) && !normalizedFormat.equals(repositoryFormat)) {
      throw new SecurityValidationException(
          "\"Invalid format '" + format + "' supplied for repository '" + repository + "'.\"");
    }
  }

  private void validateContentSelectorPrivilege(NexusPrivilege request) {
    String contentSelector = requireText(
        propertyString(
            request,
            request.contentSelector(),
            "contentSelector",
            "selector",
            "selectorName",
            "contentSelectorName"),
        "contentSelector");
    RepositoryTargetView selector = securityService.findRepositoryTarget(contentSelector)
        .orElseThrow(() -> new SecurityValidationException("\"Invalid selector '" + contentSelector + "' supplied.\""));
    if (!isContentSelector(selector)) {
      throw new SecurityValidationException("\"Invalid selector '" + contentSelector + "' supplied.\"");
    }
  }

  private UserView findUserForDelete(String source, String userId) {
    String normalizedUserId = requireText(userId, "userId");
    if (blankToNull(source) != null) {
      return securityService.findUser(normalizeSource(source), normalizedUserId)
          .orElseThrow(() -> notFound("User not found: " + normalizedUserId));
    }
    return securityService.findUser(DEFAULT_SOURCE, normalizedUserId)
        .or(() -> securityService.listUsers(null, null).stream()
            .filter(user -> normalizedUserId.equals(user.userId()))
            .findFirst())
        .orElseThrow(() -> notFound("User not found: " + normalizedUserId));
  }

  private RoleView findDefaultRoleForWrite(String id) {
    RoleView role = securityService.findRole(id)
        .orElseThrow(() -> notFound("Role not found: " + id));
    if (!DEFAULT_SOURCE.equals(role.source())) {
      throw notFound("Role not found: " + id);
    }
    return role;
  }

  private NexusUser toNexusUser(UserView user) {
    return new NexusUser(
        user.userId(),
        user.firstName(),
        user.lastName(),
        user.email(),
        user.source(),
        toNexusStatus(user.status()),
        user.external(),
        NexusExternalRoles.visibleRoles(user),
        NexusExternalRoles.externalRoles(user));
  }

  private RoleCommand toRoleCommand(String roleId, NexusRole request, boolean readOnly) {
    return new RoleCommand(
        roleId,
        DEFAULT_SOURCE,
        request.name(),
        request.description(),
        readOnly,
        toList(request.privileges()),
        toList(request.roles()),
        Map.of());
  }

  private String roleSourceForRead(String source) {
    return DEFAULT_SOURCE;
  }

  private NexusRole toNexusRole(RoleView role) {
    return new NexusRole(
        role.roleId(),
        role.source(),
        defaultString(role.name(), role.roleId()),
        defaultString(role.description(), role.roleId()),
        toSet(role.privileges()),
        toSet(role.roles()));
  }

  private PrivilegeCommand toPrivilegeCommand(String type, String privilegeId, NexusPrivilege request) {
    return toPrivilegeCommand(type, privilegeId, request, false);
  }

  private PrivilegeCommand toPrivilegeCommand(
      String type,
      String privilegeId,
      NexusPrivilege request,
      Boolean readOnly) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (request.properties() != null) {
      properties.putAll(request.properties());
    }
    putIfPresent(properties, "pattern", request.pattern());
    putIfPresent(properties, "domain", request.domain());
    List<String> actions = actionValues(request);
    if (actions != null) {
      properties.put("actions", normalizedActionsForType(type, actions));
    }
    putIfPresent(properties, "format", request.format());
    putIfPresent(properties, "repository", request.repository());
    putIfPresent(properties, "contentSelector", request.contentSelector());
    putIfPresent(properties, "permission", request.permission());
    return new PrivilegeCommand(
        privilegeId,
        defaultString(request.name(), privilegeId),
        request.description(),
        type,
        readOnly,
        properties);
  }

  private NexusPrivilege toNexusPrivilege(PrivilegeView privilege) {
    Map<String, Object> properties = privilege.properties() == null ? Map.of() : privilege.properties();
    String type = privilege.type();
    String pattern = null;
    String domain = null;
    List<String> actions = null;
    String format = null;
    String repository = null;
    String contentSelector = null;
    Map<String, Object> extraProperties = null;
    String permission = null;

    switch (defaultString(type, "wildcard")) {
      case "wildcard" -> pattern = asString(firstPresent(properties, "pattern", "permission", "wildcard"));
      case "application" -> {
        domain = asString(firstPresent(properties, "domain", "application"));
        actions = stringList(firstPresent(properties, "actions", "action"));
      }
      case "repository-view", "repository-admin" -> {
        format = formatName(properties);
        repository = repositoryName(properties);
        actions = stringList(firstPresent(properties, "actions", "action"));
      }
      case "repository-content-selector" -> {
        format = formatName(properties);
        repository = repositoryName(properties);
        contentSelector = contentSelectorName(properties);
        actions = stringList(firstPresent(properties, "actions", "action"));
      }
      default -> {
        extraProperties = properties;
        permission = privilege.permission();
      }
    }

    return new NexusPrivilege(
        type,
        privilege.name(),
        privilege.description(),
        privilege.readOnly(),
        pattern,
        domain,
        actions,
        format,
        repository,
        contentSelector,
        extraProperties,
        permission);
  }

  private String sourceForRealm(RealmView realm) {
    Object source = realm.attributes() == null ? null : realm.attributes().get("source");
    String normalized = source == null ? null : blankToNull(String.valueOf(source));
    if (normalized != null) {
      return normalized;
    }
    return "local".equalsIgnoreCase(realm.realmId()) ? DEFAULT_SOURCE : realm.realmId();
  }

  private static String normalizeSource(String source) {
    String normalized = defaultString(source, DEFAULT_SOURCE);
    if ("nexus".equalsIgnoreCase(normalized)
        || "local".equalsIgnoreCase(normalized)
        || "default".equalsIgnoreCase(normalized)
        || NEXUS_AUTHENTICATING_REALM.equalsIgnoreCase(normalized)
        || NEXUS_AUTHORIZING_REALM.equalsIgnoreCase(normalized)) {
      return DEFAULT_SOURCE;
    }
    if ("ldap".equalsIgnoreCase(normalized) || LDAP_REALM.equalsIgnoreCase(normalized)) {
      return "LDAP";
    }
    if ("oidc".equalsIgnoreCase(normalized) || OIDC_REALM.equalsIgnoreCase(normalized)) {
      return "OIDC";
    }
    return normalized;
  }

  private RepositoryTargetCommand toRepositoryTargetCommand(
      String name,
      NexusContentSelector request,
      String selectorType) {
    String expression = requireText(request.expression(), "expression");
    return new RepositoryTargetCommand(
        name,
        name,
        "*",
        expression,
        List.of(),
        Map.of(
            "source", "nexus-content-selector",
            "type", defaultString(selectorType, "csel"),
            "description", defaultString(request.description(), "")));
  }

  private String contentSelectorType(RepositoryTargetView target) {
    return defaultString(asString(target.attributes() == null ? null : target.attributes().get("type")), "csel");
  }

  private NexusAnonymousSettings toNexusAnonymousSettings(AnonymousSettingsView settings) {
    return new NexusAnonymousSettings(settings.enabled(), settings.userId(), settings.realmName());
  }

  private boolean isContentSelector(RepositoryTargetView target) {
    Map<String, Object> attributes = target.attributes() == null ? Map.of() : target.attributes();
    String source = asString(attributes.get("source"));
    if ("nexus-content-selector".equals(source)) {
      return true;
    }
    String type = asString(attributes.get("type"));
    return type != null
        && target.contentExpression() != null
        && (target.pathPatterns() == null || target.pathPatterns().isEmpty());
  }

  private List<String> contentSelectorUsedBy(String selectorName) {
    return securityService.listPrivileges().stream()
        .filter(privilege -> "repository-content-selector".equals(defaultString(privilege.type(), "")))
        .filter(privilege -> selectorName.equals(contentSelectorName(
            privilege.properties() == null ? Map.of() : privilege.properties())))
        .map(privilege -> defaultString(privilege.name(), privilege.privilegeId()))
        .toList();
  }

  private NexusContentSelector toNexusContentSelector(RepositoryTargetView target) {
    String type = asString(target.attributes() == null ? null : target.attributes().get("type"));
    String description = target.attributes() == null ? null : asString(target.attributes().get("description"));
    return new NexusContentSelector(
        target.targetId(),
        defaultString(type, "csel"),
        description,
        target.contentExpression());
  }

  private NexusApiKey toNexusApiKey(ApiKeyView apiKey) {
    return new NexusApiKey(
        apiKey.id(),
        apiKey.domain(),
        apiKey.ownerSource(),
        apiKey.ownerUserId(),
        apiKey.displayName(),
        apiKey.status(),
        apiKey.tokenPrefix(),
        toSet(apiKey.scopes()),
        apiKey.createdAt(),
        apiKey.updatedAt(),
        apiKey.expiresAt(),
        apiKey.lastUsedAt());
  }

  private AuthenticatedSubject currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return authenticated;
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  private static void putIfPresent(Map<String, Object> properties, String key, Object value) {
    if (value != null) {
      properties.put(key, value);
    }
  }

  private static void validateNexusName(String value, String field) {
    if (!NEXUS_NAME_PATTERN.matcher(value).matches()) {
      throw new SecurityValidationException(
          field + " must match Nexus name pattern: " + NEXUS_NAME_PATTERN.pattern());
    }
  }

  private static String propertyString(NexusPrivilege request, String directValue, String... keys) {
    String normalized = blankToNull(directValue);
    if (normalized != null) {
      return normalized;
    }
    Map<String, Object> properties = request.properties() == null ? Map.of() : request.properties();
    return asString(firstPresent(properties, keys));
  }

  private static List<String> actionValues(NexusPrivilege request) {
    if (request.actions() != null) {
      return request.actions();
    }
    Map<String, Object> properties = request.properties() == null ? Map.of() : request.properties();
    return stringList(firstPresent(properties, "actions", "action"));
  }

  private static List<String> normalizedActionsForType(String type, Collection<String> actions) {
    List<String> expandedActions = expandedActions(actions);
    if (expandedActions.isEmpty()) {
      throw new SecurityValidationException("actions is required");
    }
    Set<String> allowedActions = "application".equals(type) ? CRUD_ACTIONS : BREAD_ACTIONS;
    Set<String> invalidActions = new LinkedHashSet<>();
    Set<String> normalizedActions = new LinkedHashSet<>();
    for (String action : expandedActions) {
      String baseAction = baseAction(action);
      if (!allowedActions.contains(baseAction)) {
        invalidActions.add(actionName(baseAction));
      } else {
        normalizedActions.add(actionForStorage(type, baseAction));
      }
    }
    if (!invalidActions.isEmpty()) {
      throw new SecurityValidationException(String.format(
          "\"Privilege of type '%s' cannot use action(s) of type '%s'.\"",
          type,
          String.join(",", invalidActions)));
    }
    return List.copyOf(normalizedActions);
  }

  private static List<String> expandedActions(Collection<String> actions) {
    if (actions == null) {
      return List.of();
    }
    return actions.stream()
        .filter(value -> value != null)
        .flatMap(value -> java.util.Arrays.stream(value.split(",")))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private static String baseAction(String action) {
    String normalized = action.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "create" -> "add";
      case "update" -> "edit";
      case "all" -> "*";
      default -> normalized;
    };
  }

  private static String actionForStorage(String type, String baseAction) {
    if (!"application".equals(type)) {
      return baseAction;
    }
    return switch (baseAction) {
      case "add" -> "create";
      case "edit" -> "update";
      default -> baseAction;
    };
  }

  private static String actionName(String action) {
    return "*".equals(action) ? "ALL" : action.toUpperCase(Locale.ROOT);
  }

  private static Object firstPresent(Map<String, Object> properties, String... keys) {
    for (String key : keys) {
      Object value = properties.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String asString(Object value) {
    if (value instanceof Collection<?> values) {
      return String.join(",", values.stream().map(String::valueOf).toList());
    }
    return value == null ? null : String.valueOf(value);
  }

  private static String repositoryName(Map<String, Object> properties) {
    return asString(firstPresent(properties, "repository", "name", "repositoryId", "repositoryName"));
  }

  private static String contentSelectorName(Map<String, Object> properties) {
    return asString(firstPresent(properties, "contentSelector", "selector", "selectorName", "contentSelectorName"));
  }

  private static String formatName(Map<String, Object> properties) {
    return asString(firstPresent(properties, "format", "repositoryFormat", "formatId"));
  }

  private static List<String> stringList(Object value) {
    if (value instanceof Collection<?> collection) {
      return collection.stream()
          .map(String::valueOf)
          .filter(item -> !item.isBlank())
          .toList();
    }
    if (value instanceof String text && !text.isBlank()) {
      return java.util.Arrays.stream(text.split(","))
          .map(String::trim)
          .filter(item -> !item.isBlank())
          .toList();
    }
    return null;
  }

  private static List<String> toList(Collection<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(NexusSecurityRestController::blankToNull)
        .filter(value -> value != null)
        .distinct()
        .toList();
  }

  private static Set<String> toSet(Collection<String> values) {
    return values == null ? Set.of() : new LinkedHashSet<>(values);
  }

  private static String toNexusStatus(String status) {
    return defaultString(status, "active").toLowerCase(Locale.ROOT);
  }

  private static String toStoredStatus(String status) {
    return defaultString(status, "ACTIVE").toUpperCase(Locale.ROOT);
  }

  private static String requireText(String value, String field) {
    String normalized = blankToNull(value);
    if (normalized == null) {
      throw new SecurityValidationException(field + " is required");
    }
    return normalized;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String defaultString(String value, String fallback) {
    String normalized = blankToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
