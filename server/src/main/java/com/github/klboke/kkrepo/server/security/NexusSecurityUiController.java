package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiFormField;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPage;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPermission;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPrivilege;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPrivilegeType;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiReference;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiRole;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiUser;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiUserRoleMappings;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusRealmSettings;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmReference;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserView;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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

/**
 * Legacy Nexus internal UI security compatibility endpoint.
 *
 * @deprecated This endpoint is kept for Nexus internal UI compatibility only. New kkrepo
 *     management flows should use {@code /internal/security} or the Nexus REST API surface
 *     instead.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/rest/internal/ui/security")
public class NexusSecurityUiController {
  private static final String DEFAULT_SOURCE = "Local";
  private static final String NEXUS_SOURCE = "Nexus";
  private static final String PASSWORD_PLACEHOLDER = "#~NEXUS~PLACEHOLDER~PASSWORD~#";
  private static final String LEGACY_PASSWORD_PLACEHOLDER = "********";

  private final SecurityManagementService securityService;

  public NexusSecurityUiController(SecurityManagementService securityService) {
    this.securityService = securityService;
  }

  @GetMapping("/users")
  public List<NexusUiUser> users(
      @RequestParam(name = "source", required = false) String source,
      @RequestParam(name = "userId", required = false) String userId,
      @RequestParam(name = "limit", required = false) Integer limit) {
    List<NexusUiUser> users = securityService.listUsers(defaultString(source, DEFAULT_SOURCE), userId).stream()
        .map(this::toUiUser)
        .toList();
    if (limit == null || limit < 0 || limit >= users.size()) {
      return users;
    }
    return users.subList(0, limit);
  }

  @GetMapping("/users/{source}/{userId}")
  public NexusUiUser getUser(
      @PathVariable("source") String source,
      @PathVariable("userId") String userId) {
    return securityService.findUser(normalizeSource(source), userId)
        .map(this::toUiUser)
        .orElseThrow(() -> notFound("User not found: " + userId));
  }

  @GetMapping("/user-sources")
  public List<NexusUiReference> userSources() {
    return securityService.listRealms().stream()
        .map(realm -> new NexusUiReference(sourceForRealm(realm), realm.name()))
        .distinct()
        .toList();
  }

  @PostMapping("/users")
  public NexusUiUser createUser(@RequestBody NexusUiUser request) {
    String userId = requireText(request.userId(), "userId");
    if (securityService.findUser(DEFAULT_SOURCE, userId).isPresent()) {
      throw new SecurityValidationException("User already exists: " + userId);
    }
    return toUiUser(securityService.saveUser(toUserCommand(DEFAULT_SOURCE, userId, request, true)));
  }

  @PutMapping("/users/{source}/{userId}")
  public NexusUiUser updateUser(
      @PathVariable("source") String source,
      @PathVariable("userId") String userId,
      @RequestBody NexusUiUser request) {
    String normalizedSource = normalizeSource(source);
    UserView existing = securityService.findUser(normalizedSource, userId)
        .orElseThrow(() -> notFound("User not found: " + userId));
    if (existing.external()) {
      return toUiUser(securityService.saveUser(new UserCommand(
          existing.source(),
          existing.userId(),
          existing.firstName(),
          existing.lastName(),
          existing.email(),
          null,
          null,
          existing.status(),
          existing.externalId(),
          NexusExternalRoles.localRoleList(existing, request.roles()),
          existing.attributes())));
    }
    return toUiUser(securityService.saveUser(toUserCommand(normalizedSource, userId, request, false)));
  }

  @PutMapping("/users/{source}/{userId}/role-mappings")
  public NexusUiUser updateUserRoleMappings(
      @PathVariable("source") String source,
      @PathVariable("userId") String userId,
      @RequestBody NexusUiUserRoleMappings request) {
    String normalizedSource = normalizeSource(defaultString(request.realm(), source));
    UserView existing = securityService.findUser(normalizedSource, userId)
        .orElseThrow(() -> notFound("User not found: " + userId));
    return toUiUser(securityService.saveUser(new UserCommand(
        existing.source(),
        existing.userId(),
        existing.firstName(),
        existing.lastName(),
        existing.email(),
        null,
        null,
        existing.status(),
        existing.externalId(),
        NexusExternalRoles.localRoleList(existing, request.roles()),
        existing.attributes())));
  }

  @PutMapping("/users/{userId}/change-password")
  public ResponseEntity<Void> changePassword(
      @PathVariable("userId") String userId,
      @RequestBody String password) {
    securityService.changePassword(userId, password);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/users/{source}/{userId}")
  public ResponseEntity<Void> deleteUser(
      @PathVariable("source") String source,
      @PathVariable("userId") String userId,
      HttpServletRequest request) {
    securityService.deleteUser(normalizeSource(source), userId, authenticatedSubject(request).userId());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/roles")
  public List<NexusUiRole> roles(@RequestParam(name = "source", required = false) String source) {
    return securityService.listRoles().stream()
        .map(this::toUiRole)
        .toList();
  }

  @GetMapping("/roles/{id}")
  public NexusUiRole getRole(@PathVariable("id") String id) {
    return securityService.findRole(id)
        .map(this::toUiRole)
        .orElseThrow(() -> notFound("Role not found: " + id));
  }

  @GetMapping("/role-references")
  public List<NexusUiReference> roleReferences(@RequestParam(name = "source", required = false) String source) {
    return securityService.listRoles().stream()
        .map(role -> new NexusUiReference(role.roleId(), defaultString(role.name(), role.roleId())))
        .toList();
  }

  @GetMapping("/role-sources")
  public List<NexusUiReference> roleSources() {
    return List.of();
  }

  @PostMapping("/roles")
  public NexusUiRole createRole(@RequestBody NexusUiRole request) {
    String roleId = requireText(request.id(), "id");
    if (securityService.findRole(roleId).isPresent()) {
      throw new SecurityValidationException("Role already exists: " + roleId);
    }
    return toUiRole(securityService.saveRole(toRoleCommand(roleId, request)));
  }

  @PutMapping("/roles/{id}")
  public NexusUiRole updateRole(
      @PathVariable("id") String id,
      @RequestBody NexusUiRole request) {
    if (!id.equals(request.id())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Role id does not match path: " + id);
    }
    if (securityService.findRole(id).isEmpty()) {
      throw notFound("Role not found: " + id);
    }
    return toUiRole(securityService.saveRole(toRoleCommand(id, request)));
  }

  @DeleteMapping("/roles/{id}")
  public ResponseEntity<Void> deleteRole(@PathVariable("id") String id) {
    securityService.deleteRole(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/privileges")
  public NexusUiPage<NexusUiPrivilege> privileges(
      @RequestParam(name = "filter", required = false) String filter,
      @RequestParam(name = "start", required = false) Integer start,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "sort", required = false) String sort,
      @RequestParam(name = "dir", required = false) String direction) {
    List<NexusUiPrivilege> privileges = securityService.listPrivileges().stream()
        .map(this::toUiPrivilege)
        .filter(privilege -> privilegeMatches(privilege, filter))
        .sorted(privilegeComparator(sort, direction))
        .toList();
    int total = privileges.size();
    int from = Math.max(0, start == null ? 0 : start);
    int to = limit == null || limit < 0 ? total : Math.min(total, from + limit);
    if (from >= total) {
      return new NexusUiPage<>(total, List.of());
    }
    return new NexusUiPage<>(total, privileges.subList(from, to));
  }

  @GetMapping("/privileges/{id}")
  public NexusUiPrivilege getPrivilege(@PathVariable("id") String id) {
    return securityService.findPrivilege(id)
        .map(this::toUiPrivilege)
        .orElseThrow(() -> notFound("Privilege not found: " + id));
  }

  @GetMapping("/privilege-references")
  public List<NexusUiReference> privilegeReferences() {
    return securityService.listPrivileges().stream()
        .map(privilege -> new NexusUiReference(privilege.privilegeId(), defaultString(privilege.name(), privilege.privilegeId())))
        .toList();
  }

  @GetMapping("/privilege-types")
  public List<NexusUiPrivilegeType> privilegeTypes() {
    return List.of(
        new NexusUiPrivilegeType("application", "Application", List.of(
            field("domain", "string", "Domain", true, null),
            field("actions", "string", "Actions", true, "Comma-separated actions"))),
        new NexusUiPrivilegeType("wildcard", "Wildcard", List.of(
            field("pattern", "string", "Pattern", true, null))),
        new NexusUiPrivilegeType("repository-admin", "Repository Admin", List.of(
            field("format", "string", "Format", true, null),
            field("repository", "string", "Repository", true, null),
            field("actions", "string", "Actions", true, "Comma-separated actions"))),
        new NexusUiPrivilegeType("repository-view", "Repository View", List.of(
            field("format", "string", "Format", true, null),
            field("repository", "string", "Repository", true, null),
            field("actions", "string", "Actions", true, "Comma-separated actions"))),
        new NexusUiPrivilegeType("repository-content-selector", "Repository Content Selector", List.of(
            field("contentSelector", "string", "Content Selector", true, null),
            field("repository", "string", "Repository", true, null),
            field("actions", "string", "Actions", true, "Comma-separated actions"))));
  }

  @GetMapping("/permissions")
  public List<NexusUiPermission> permissions(HttpServletRequest request) {
    AuthenticatedSubject subject = authenticatedSubject(request);
    return securityService.listEffectivePermissions(subject.permissionSubject()).stream()
        .map(permission -> new NexusUiPermission(permission, true))
        .toList();
  }

  @GetMapping("/realm-settings")
  public NexusRealmSettings realmSettings() {
    return new NexusRealmSettings(securityService.listActiveNexusRealmNames());
  }

  @PutMapping("/realm-settings")
  public NexusRealmSettings updateRealmSettings(@RequestBody NexusRealmSettings request) {
    List<String> realms = request == null ? List.of() : request.realms();
    return new NexusRealmSettings(securityService.saveActiveNexusRealmNames(realms));
  }

  @GetMapping("/realm-types")
  public List<NexusUiReference> realmTypes() {
    return securityService.listNexusRealmTypes().stream()
        .map(this::toUiReference)
        .toList();
  }

  @PostMapping("/privileges")
  public NexusUiPrivilege createPrivilege(@RequestBody NexusUiPrivilege request) {
    String id = defaultString(request.id(), request.name());
    if (securityService.findPrivilege(id).isPresent()) {
      throw new SecurityValidationException("Privilege already exists: " + id);
    }
    return toUiPrivilege(securityService.savePrivilege(toPrivilegeCommand(id, request)));
  }

  @PutMapping("/privileges/{id}")
  public NexusUiPrivilege updatePrivilege(
      @PathVariable("id") String id,
      @RequestBody NexusUiPrivilege request) {
    if (securityService.findPrivilege(id).isEmpty()) {
      throw notFound("Privilege not found: " + id);
    }
    return toUiPrivilege(securityService.savePrivilege(toPrivilegeCommand(id, request)));
  }

  @DeleteMapping("/privileges/{id}")
  public ResponseEntity<Void> deletePrivilege(@PathVariable("id") String id) {
    securityService.deletePrivilege(id);
    return ResponseEntity.noContent().build();
  }

  private UserCommand toUserCommand(
      String source,
      String userId,
      NexusUiUser request,
      boolean create) {
    String password = create ? requireText(request.password(), "password") : blankToNull(request.password());
    if (isPasswordPlaceholder(password)) {
      password = null;
    }
    return new UserCommand(
        normalizeSource(source),
        userId,
        request.firstName(),
        request.lastName(),
        request.email(),
        password,
        null,
        toStoredStatus(request.status()),
        null,
        toList(request.roles()),
        Map.of());
  }

  private RoleCommand toRoleCommand(String roleId, NexusUiRole request) {
    return new RoleCommand(
        roleId,
        DEFAULT_SOURCE,
        request.name(),
        request.description(),
        request.readOnly(),
        toList(request.privileges()),
        toList(request.roles()),
        Map.of());
  }

  private PrivilegeCommand toPrivilegeCommand(String id, NexusUiPrivilege request) {
    return new PrivilegeCommand(
        id,
        defaultString(request.name(), id),
        request.description(),
        defaultString(request.type(), "wildcard"),
        request.readOnly(),
        request.properties() == null ? Map.of() : request.properties());
  }

  private NexusUiUser toUiUser(UserView user) {
    return new NexusUiUser(
        user.userId(),
        "0",
        user.source(),
        user.firstName(),
        user.lastName(),
        user.email(),
        toNexusStatus(user.status()),
        PASSWORD_PLACEHOLDER,
        NexusExternalRoles.visibleRoles(user),
        user.external(),
        NexusExternalRoles.externalRoles(user));
  }

  private NexusUiRole toUiRole(RoleView role) {
    return new NexusUiRole(
        role.roleId(),
        "0",
        toNexusRoleSource(role.source()),
        role.name(),
        role.description(),
        role.readOnly(),
        toSet(role.privileges()),
        toSet(role.roles()));
  }

  private NexusUiPrivilege toUiPrivilege(PrivilegeView privilege) {
    return new NexusUiPrivilege(
        privilege.privilegeId(),
        "0",
        privilege.name(),
        privilege.description(),
        privilege.type(),
        privilege.readOnly(),
        privilege.properties() == null ? Map.of() : privilege.properties(),
        privilege.permission());
  }

  private NexusUiReference toUiReference(RealmReference reference) {
    return new NexusUiReference(reference.id(), reference.name());
  }

  private static NexusUiFormField field(
      String id,
      String type,
      String label,
      boolean required,
      String helpText) {
    return new NexusUiFormField(
        id,
        type,
        label,
        helpText,
        required,
        false,
        false,
        null,
        null,
        Map.of(),
        null,
        null,
        null,
        null,
        false);
  }

  private static boolean privilegeMatches(NexusUiPrivilege privilege, String filter) {
    String normalized = blankToNull(filter);
    if (normalized == null) {
      return true;
    }
    String needle = normalized.toLowerCase(Locale.ROOT);
    return contains(privilege.name(), needle)
        || contains(privilege.description(), needle)
        || contains(privilege.permission(), needle)
        || contains(privilege.type(), needle);
  }

  private static boolean contains(String text, String needle) {
    return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static Comparator<NexusUiPrivilege> privilegeComparator(String sort, String direction) {
    String property = defaultString(sort, "name");
    Comparator<NexusUiPrivilege> comparator = switch (property) {
      case "id" -> Comparator.comparing(NexusUiPrivilege::id, Comparator.nullsLast(String::compareTo));
      case "description" -> Comparator.comparing(NexusUiPrivilege::description, Comparator.nullsLast(String::compareTo));
      case "permission" -> Comparator.comparing(NexusUiPrivilege::permission, Comparator.nullsLast(String::compareTo));
      case "type" -> Comparator.comparing(NexusUiPrivilege::type, Comparator.nullsLast(String::compareTo));
      default -> Comparator.comparing(NexusUiPrivilege::name, Comparator.nullsLast(String::compareTo));
    };
    if ("DESC".equalsIgnoreCase(direction)) {
      return comparator.reversed();
    }
    return comparator;
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
    return NEXUS_SOURCE.equalsIgnoreCase(normalized) || "default".equalsIgnoreCase(normalized)
        ? DEFAULT_SOURCE
        : normalized;
  }

  private static String toNexusRoleSource(String source) {
    return DEFAULT_SOURCE.equals(defaultString(source, DEFAULT_SOURCE)) ? NEXUS_SOURCE : source;
  }

  private static String toNexusStatus(String status) {
    return defaultString(status, "active").toLowerCase(Locale.ROOT);
  }

  private static String toStoredStatus(String status) {
    return defaultString(status, "ACTIVE").toUpperCase(Locale.ROOT);
  }

  private static List<String> toList(Set<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(NexusSecurityUiController::blankToNull)
        .filter(value -> value != null)
        .distinct()
        .toList();
  }

  private static Set<String> toSet(List<String> values) {
    return values == null ? Set.of() : new LinkedHashSet<>(values);
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

  private static boolean isPasswordPlaceholder(String value) {
    return PASSWORD_PLACEHOLDER.equals(value) || LEGACY_PASSWORD_PLACEHOLDER.equals(value);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static AuthenticatedSubject authenticatedSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticatedSubject) {
      return authenticatedSubject;
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }
}
