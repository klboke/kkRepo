package com.github.klboke.kkrepo.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusAnonymousSettings;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusCurrentUser;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusLdapMappedUser;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusLdapSchemaTemplate;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusLdapServer;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusRealmSettings;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiFormField;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPermission;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPrivilege;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPrivilegeType;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiRepositoryReference;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiRepositoryStatus;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiReference;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiRole;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiSelector;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiUser;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiUserRoleMappings;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy Nexus Rapture ExtDirect compatibility endpoint.
 *
 * @deprecated This endpoint is kept for mirrored Nexus UI traffic and black-box compatibility
 *     tests only. New kkrepo management flows should use the REST or internal UI APIs instead.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
@ConditionalOnProperty(name = "kkrepo.nexus.legacy-ui.enabled", havingValue = "true")
@RestController
@RequestMapping("/service/extdirect")
public class NexusExtDirectController {
  private static final String RPC_TYPE = "rpc";
  private static final String DEFAULT_SOURCE = "Local";
  private static final String NEXUS_SOURCE = "Nexus";
  private static final String PASSWORD_PLACEHOLDER = "#~NEXUS~PLACEHOLDER~PASSWORD~#";
  private static final String LEGACY_PASSWORD_PLACEHOLDER = "********";
  private static final String LDAP_REALM_ID = "ldap";
  private static final String LDAP_NEXUS_REALM = "LdapRealm";

  private final SecurityManagementService securityService;
  private final SecurityAuthenticationService authenticationService;
  private final NexusAuthenticationTicketService authenticationTickets;
  private final RepositoryService repositoryService;

  public NexusExtDirectController(
      SecurityManagementService securityService,
      SecurityAuthenticationService authenticationService) {
    this(securityService, authenticationService, null, null);
  }

  public NexusExtDirectController(
      SecurityManagementService securityService,
      SecurityAuthenticationService authenticationService,
      NexusAuthenticationTicketService authenticationTickets) {
    this(securityService, authenticationService, null, authenticationTickets);
  }

  @Autowired
  public NexusExtDirectController(
      SecurityManagementService securityService,
      SecurityAuthenticationService authenticationService,
      RepositoryService repositoryService,
      NexusAuthenticationTicketService authenticationTickets) {
    this.securityService = securityService;
    this.authenticationService = authenticationService;
    this.repositoryService = repositoryService;
    this.authenticationTickets = authenticationTickets;
  }

  @PostMapping
  public Object invoke(@RequestBody JsonNode request, HttpServletRequest servletRequest) {
    if (request != null && request.isArray()) {
      return java.util.stream.StreamSupport.stream(request.spliterator(), false)
          .map(call -> invokeOne(call, servletRequest))
          .toList();
    }
    return invokeOne(request, servletRequest);
  }

  private ExtDirectResponse invokeOne(JsonNode call, HttpServletRequest request) {
    String action = text(call, "action");
    String method = text(call, "method");
    Object tid = value(call, "tid");
    if ("rapture_Security".equals(action)) {
      return switch (method) {
        case "getUser" -> new ExtDirectResponse(
            RPC_TYPE, tid, action, method, success(currentUser(request)));
        case "authenticate" -> authenticate(tid, action, method, call, request);
        case "getPermissions" -> new ExtDirectResponse(
            RPC_TYPE, tid, action, method, success(currentPermissions(request)));
        case "authenticationToken" -> authenticationToken(tid, action, method, call, request);
        default -> unsupported(tid, action, method);
      };
    }
    if ("coreui_User".equals(action)) {
      return coreUiUser(tid, action, method, call, request);
    }
    if ("coreui_Role".equals(action)) {
      return coreUiRole(tid, action, method, call, request);
    }
    if ("coreui_Privilege".equals(action)) {
      return coreUiPrivilege(tid, action, method, call, request);
    }
    if ("coreui_AnonymousSettings".equals(action)) {
      return switch (method) {
        case "read" -> permitted(tid, action, method, request, "nexus:settings:read",
            () -> toAnonymousSettings(securityService.anonymousSettings()));
        case "update" -> permitted(tid, action, method, request, "nexus:settings:update",
            () -> updateAnonymousSettings(objectArg(call, 0)));
        default -> unsupported(tid, action, method);
      };
    }
    if ("coreui_Selector".equals(action)) {
      return coreUiSelector(tid, action, method, call, request);
    }
    if ("coreui_Repository".equals(action)) {
      return coreUiRepository(tid, action, method, call, request);
    }
    if ("coreui_RealmSettings".equals(action)) {
      return switch (method) {
        case "read" -> permitted(tid, action, method, request, "nexus:settings:read",
            () -> new NexusRealmSettings(securityService.listActiveNexusRealmNames()));
        case "readRealmTypes" -> permitted(tid, action, method, request, "nexus:settings:read",
            () -> securityService.listNexusRealmTypes().stream()
                .map(reference -> new NexusUiReference(reference.id(), reference.name()))
                .toList());
        case "update" -> permitted(tid, action, method, request, "nexus:settings:update",
            () -> new NexusRealmSettings(securityService.saveActiveNexusRealmNames(realmNames(call))));
        default -> unsupported(tid, action, method);
      };
    }
    if ("ldap_LdapServer".equals(action)) {
      return ldapServer(tid, action, method, call, request);
    }
    return unsupported(tid, action, method);
  }

  private ExtDirectResponse ldapServer(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    return switch (method) {
      case "read" -> permitted(tid, action, method, request, "nexus:ldap:read", this::readLdapServers);
      case "readTemplates" -> permitted(tid, action, method, request, "nexus:ldap:read", this::ldapTemplates);
      case "create" -> permitted(tid, action, method, request, "nexus:ldap:create",
          () -> saveLdapServer(objectArg(call, 0), false));
      case "update" -> permitted(tid, action, method, request, "nexus:ldap:update",
          () -> saveLdapServer(objectArg(call, 0), true));
      case "remove" -> permitted(tid, action, method, request, "nexus:ldap:delete",
          () -> {
            removeLdapServer(stringArg(call, 0));
            return null;
          });
      case "changeOrder" -> permitted(tid, action, method, request, "nexus:ldap:update",
          () -> {
            changeLdapOrder(objectArg(call, 0));
            return null;
          });
      case "clearCache" -> permitted(tid, action, method, request, "nexus:ldap:delete", () -> null);
      case "verifyConnection" -> permitted(tid, action, method, request, "nexus:ldap:read",
          () -> verifyLdapConnection(objectArg(call, 0)));
      case "verifyUserMapping" -> permitted(tid, action, method, request, "nexus:ldap:read",
          () -> verifyLdapUserMapping(objectArg(call, 0)));
      case "verifyLogin" -> permitted(tid, action, method, request, "nexus:ldap:read",
          () -> verifyLdapLogin(objectArg(call, 0), stringArg(call, 1), stringArg(call, 2)));
      default -> unsupported(tid, action, method);
    };
  }

  private ExtDirectResponse coreUiUser(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    return switch (method) {
      case "read" -> permitted(tid, action, method, request, "nexus:users:read",
          () -> readUsers(parameterObject(call)));
      case "get" -> permitted(tid, action, method, request, "nexus:users:read",
          () -> getUser(stringArg(call, 0), stringArg(call, 1)));
      case "readSources" -> permitted(tid, action, method, request, "nexus:users:read", this::userSources);
      case "create" -> permitted(tid, action, method, request, "nexus:users:create",
          () -> createUser(objectArg(call, 0)));
      case "update" -> permitted(tid, action, method, request, "nexus:users:update",
          () -> updateUser(objectArg(call, 0)));
      case "updateRoleMappings" -> permitted(tid, action, method, request, "nexus:users:update",
          () -> updateUserRoleMappings(objectArg(call, 0)));
      case "changePassword" -> permitted(tid, action, method, request, "nexus:userschangepw:create",
          () -> {
            String ticketUser = redeemAuthenticationTicket(request, stringArg(call, 0));
            String targetUser = stringArg(call, 1);
            if (!targetUser.equals(ticketUser) && !currentSubject(request)
                .map(subject -> securityService.decide(subject.permissionSubject(), "nexus:*").allowed())
                .orElse(false)) {
              throw new SecurityValidationException("Username mismatch");
            }
            securityService.changePassword(targetUser, stringArg(call, 2));
            return null;
          });
      case "remove" -> permitted(tid, action, method, request, "nexus:users:delete",
          () -> {
            securityService.deleteUser(
                normalizeSource(stringArg(call, 1)),
                stringArg(call, 0),
                currentSubject(request).map(AuthenticatedSubject::userId).orElse(null));
            return null;
          });
      default -> unsupported(tid, action, method);
    };
  }

  private ExtDirectResponse coreUiRole(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    return switch (method) {
      case "read" -> permitted(tid, action, method, request, "nexus:roles:read",
          () -> securityService.listRoles().stream().map(this::toUiRole).toList());
      case "readReferences" -> permitted(tid, action, method, request, "nexus:roles:read",
          () -> securityService.listRoles().stream()
              .map(role -> new NexusUiReference(role.roleId(), defaultString(role.name(), role.roleId())))
              .toList());
      case "readSources" -> permitted(tid, action, method, request, "nexus:roles:read", this::roleSources);
      case "readFromSource" -> permitted(tid, action, method, request, "nexus:roles:read",
          () -> readRolesFromSource(stringArg(call, 0)));
      case "create" -> permitted(tid, action, method, request, "nexus:roles:create",
          () -> saveRole(objectArg(call, 0)));
      case "update" -> permitted(tid, action, method, request, "nexus:roles:update",
          () -> saveRole(objectArg(call, 0)));
      case "remove" -> permitted(tid, action, method, request, "nexus:roles:delete",
          () -> {
            securityService.deleteRole(stringArg(call, 0));
            return null;
          });
      default -> unsupported(tid, action, method);
    };
  }

  private ExtDirectResponse coreUiPrivilege(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    return switch (method) {
      case "read" -> permitted(tid, action, method, request, "nexus:privileges:read",
          () -> readPrivileges(parameterObject(call)));
      case "readReferences" -> permitted(tid, action, method, request, "nexus:privileges:read",
          () -> securityService.listPrivileges().stream()
              .map(privilege -> new NexusUiReference(
                  privilege.privilegeId(), defaultString(privilege.name(), privilege.privilegeId())))
              .toList());
      case "readTypes" -> permitted(tid, action, method, request, "nexus:privileges:read", this::privilegeTypes);
      case "create" -> permitted(tid, action, method, request, "nexus:privileges:create",
          () -> savePrivilege(objectArg(call, 0)));
      case "update" -> permitted(tid, action, method, request, "nexus:privileges:update",
          () -> savePrivilege(objectArg(call, 0)));
      case "remove" -> permitted(tid, action, method, request, "nexus:privileges:delete",
          () -> {
            securityService.deletePrivilege(stringArg(call, 0));
            return null;
          });
      default -> unsupported(tid, action, method);
    };
  }

  private ExtDirectResponse coreUiSelector(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    return switch (method) {
      case "read" -> permitted(tid, action, method, request, "nexus:selectors:read",
          () -> readSelectors(canReadPrivileges(request)));
      case "readReferences" -> permitted(tid, action, method, request, "nexus:selectors:read",
          () -> contentSelectors().stream()
              .map(selector -> new NexusUiReference(selector.targetId(), selector.targetId()))
              .toList());
      case "create" -> permitted(tid, action, method, request, "nexus:selectors:create",
          () -> createSelector(objectArg(call, 0)));
      case "update" -> permitted(tid, action, method, request, "nexus:selectors:update",
          () -> updateSelector(objectArg(call, 0)));
      case "remove" -> permitted(tid, action, method, request, "nexus:selectors:delete",
          () -> {
            removeSelector(stringArg(call, 0));
            return null;
          });
      default -> unsupported(tid, action, method);
    };
  }

  private ExtDirectResponse coreUiRepository(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    return switch (method) {
      case "readReferences" -> permitted(tid, action, method, request, "nexus:privileges:read",
          () -> repositoryReferences(parameterObject(call), false, false));
      case "readReferencesAddingEntryForAll" -> permitted(tid, action, method, request, "nexus:privileges:read",
          () -> repositoryReferences(parameterObject(call), true, false));
      case "readReferencesAddingEntriesForAllFormats" -> permitted(
          tid,
          action,
          method,
          request,
          "nexus:privileges:read",
          () -> repositoryReferences(parameterObject(call), true, true));
      default -> unsupported(tid, action, method);
    };
  }

  private ExtDirectResponse unsupported(Object tid, String action, String method) {
    return new ExtDirectResponse(
        RPC_TYPE,
        tid,
        action,
        method,
        new ExtDirectResult(false, null, "Unsupported ExtDirect method: " + action + "." + method, null));
  }

  private ExtDirectResponse permitted(
      Object tid,
      String action,
      String method,
      HttpServletRequest request,
      String permission,
      Supplier<Object> data) {
    Optional<AuthenticatedSubject> subject = currentSubject(request);
    if (subject.isEmpty()) {
      return new ExtDirectResponse(
          RPC_TYPE,
          tid,
          action,
          method,
          new ExtDirectResult(false, null, "Authentication required", null));
    }
    AccessDecision decision = securityService.decide(subject.get().permissionSubject(), permission);
    if (!decision.allowed()) {
      return new ExtDirectResponse(
          RPC_TYPE,
          tid,
          action,
          method,
          new ExtDirectResult(false, null, decision.reason(), null));
    }
    try {
      Object result = data.get();
      ExtDirectResult response = result instanceof ExtDirectResult extDirectResult
          ? extDirectResult
          : success(result);
      return new ExtDirectResponse(RPC_TYPE, tid, action, method, response);
    } catch (RuntimeException e) {
      return new ExtDirectResponse(
          RPC_TYPE,
          tid,
          action,
          method,
          new ExtDirectResult(false, null, e.getMessage(), null));
    }
  }

  private List<NexusUiPermission> currentPermissions(HttpServletRequest request) {
    return currentSubject(request)
        .map(subject -> securityService.listEffectivePermissions(subject.permissionSubject()).stream()
            .map(permission -> new NexusUiPermission(permission, true))
            .toList())
        .orElse(null);
  }

  private NexusCurrentUser currentUser(HttpServletRequest request) {
    return currentSubject(request)
        .map(this::toCurrentUser)
        .orElse(null);
  }

  private NexusCurrentUser toCurrentUser(AuthenticatedSubject subject) {
    if (subject == null) {
      return null;
    }
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

  private ExtDirectResponse authenticate(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    try {
      String username = decodeBase64(stringArg(call, 0), "username");
      String password = decodeBase64(stringArg(call, 1), "password");
      AuthenticatedSubject authenticated = authenticationService.authenticateCredentials(username, password)
          .orElseThrow(() -> new SecurityValidationException("Authentication failed"));
      authenticationService.storeSessionSubject(request, authenticated);
      request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated);
      return new ExtDirectResponse(RPC_TYPE, tid, action, method, success(toCurrentUser(authenticated)));
    } catch (RuntimeException e) {
      return new ExtDirectResponse(
          RPC_TYPE,
          tid,
          action,
          method,
          new ExtDirectResult(false, null, e.getMessage(), null));
    }
  }

  private ExtDirectResponse authenticationToken(
      Object tid,
      String action,
      String method,
      JsonNode call,
      HttpServletRequest request) {
    try {
      String username = decodeBase64(stringArg(call, 0), "username");
      String password = decodeBase64(stringArg(call, 1), "password");
      AuthenticatedSubject authenticated = authenticationService.authenticateCredentials(username, password)
          .orElseThrow(() -> new SecurityValidationException("Authentication failed"));
      currentSubject(request).ifPresent(current -> {
        if (!current.userId().equals(authenticated.userId())) {
          throw new SecurityValidationException("Username mismatch");
        }
      });
      String token = authenticationTickets.createTicket(authenticated.userId());
      return new ExtDirectResponse(RPC_TYPE, tid, action, method, success(token));
    } catch (RuntimeException e) {
      return new ExtDirectResponse(
          RPC_TYPE,
          tid,
          action,
          method,
          new ExtDirectResult(false, null, e.getMessage(), null));
    }
  }

  private String redeemAuthenticationTicket(HttpServletRequest request, String token) {
    if (token == null || token.isBlank()) {
      throw new SecurityValidationException("Authentication token is required");
    }
    String ticketUser = authenticationTickets.redeemTicket(token)
        .orElseThrow(() -> new SecurityValidationException("Invalid authentication ticket"));
    currentSubject(request).ifPresent(current -> {
      if (!ticketUser.equals(current.userId())) {
        throw new SecurityValidationException("Username mismatch");
      }
    });
    return ticketUser;
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

  private static ExtDirectResult success(Object data) {
    return new ExtDirectResult(true, data, null, null);
  }

  private static ExtDirectResult page(int total, Object data) {
    return new ExtDirectResult(true, data, null, total);
  }

  private NexusAnonymousSettings toAnonymousSettings(AnonymousSettingsView settings) {
    return new NexusAnonymousSettings(
        settings.enabled(),
        settings.userId(),
        settings.realmName());
  }

  private NexusAnonymousSettings updateAnonymousSettings(JsonNode payload) {
    AnonymousSettingsView updated = securityService.saveAnonymousSettings(new AnonymousSettingsCommand(
        booleanValue(payload, "enabled"),
        null,
        text(payload, "userId"),
        text(payload, "realmName")));
    return toAnonymousSettings(updated);
  }

  private List<NexusLdapServer> readLdapServers() {
    return securityService.listRealms().stream()
        .filter(realm -> LDAP_REALM_ID.equals(realm.realmId()))
        .filter(realm -> ldapConfigured(realm.attributes()))
        .map(this::toLdapServer)
        .toList();
  }

  private NexusLdapServer saveLdapServer(JsonNode payload, boolean requireExisting) {
    RealmView existing = ldapRealm().orElse(null);
    if (requireExisting && (existing == null || !ldapConfigured(existing.attributes()))) {
      throw new SecurityValidationException("LDAP server not found");
    }
    if (!requireExisting && existing != null && ldapConfigured(existing.attributes())) {
      throw new SecurityValidationException("LDAP server already exists");
    }
    Map<String, Object> attributes = ldapAttributes(payload, existing == null ? Map.of() : existing.attributes());
    String name = defaultString(asString(attributes.get("name")), existing == null ? "LDAP" : existing.name());
    securityService.saveRealms(List.of(new RealmCommand(
        LDAP_REALM_ID,
        "LDAP",
        name,
        existing != null && existing.enabled(),
        existing == null ? 10 : existing.priority(),
        attributes)));
    return ldapRealm()
        .filter(realm -> ldapConfigured(realm.attributes()))
        .map(this::toLdapServer)
        .orElseThrow(() -> new SecurityValidationException("LDAP server not saved"));
  }

  private void removeLdapServer(String id) {
    RealmView existing = ldapRealm()
        .filter(realm -> ldapConfigured(realm.attributes()))
        .orElseThrow(() -> new SecurityValidationException("LDAP server not found: " + id));
    NexusLdapServer server = toLdapServer(existing);
    String requested = blankToNull(id);
    if (requested != null && !requested.equals(server.id()) && !requested.equals(server.name())) {
      throw new SecurityValidationException("LDAP server not found: " + id);
    }
    securityService.saveRealms(List.of(new RealmCommand(
        LDAP_REALM_ID,
        "LDAP",
        "LDAP",
        false,
        existing.priority(),
        Map.of("source", "LDAP", "nexusRealm", LDAP_NEXUS_REALM))));
  }

  private void changeLdapOrder(JsonNode orderNode) {
    RealmView existing = ldapRealm()
        .filter(realm -> ldapConfigured(realm.attributes()))
        .orElse(null);
    if (existing == null || orderNode == null || !orderNode.isArray()) {
      return;
    }
    NexusLdapServer server = toLdapServer(existing);
    int order = 1;
    for (int i = 0; i < orderNode.size(); i++) {
      String value = orderNode.get(i).asText();
      if (server.id().equals(value) || server.name().equals(value)) {
        order = i + 1;
        break;
      }
    }
    Map<String, Object> attributes = new LinkedHashMap<>(existing.attributes());
    attributes.put("order", order);
    securityService.saveRealms(List.of(new RealmCommand(
        LDAP_REALM_ID,
        "LDAP",
        existing.name(),
        existing.enabled(),
        existing.priority(),
        attributes)));
  }

  private Object verifyLdapConnection(JsonNode payload) {
    Map<String, Object> attributes = ldapAttributes(payload, Map.of());
    if (blankToNull(asString(attributes.get("url"))) == null) {
      throw new SecurityValidationException("LDAP URL is required");
    }
    return null;
  }

  private List<NexusLdapMappedUser> verifyLdapUserMapping(JsonNode payload) {
    verifyLdapConnection(payload);
    return List.of();
  }

  private Object verifyLdapLogin(JsonNode payload, String username, String password) {
    verifyLdapConnection(payload);
    if (blankToNull(username) == null || blankToNull(password) == null) {
      throw new SecurityValidationException("LDAP login credentials are required");
    }
    return null;
  }

  private Optional<RealmView> ldapRealm() {
    return securityService.listRealms().stream()
        .filter(realm -> LDAP_REALM_ID.equals(realm.realmId()))
        .findFirst();
  }

  private boolean ldapConfigured(Map<String, Object> attributes) {
    if (attributes == null) {
      return false;
    }
    Object configured = attributes.get("ldapConfigured");
    if (configured instanceof Boolean bool) {
      return bool;
    }
    return blankToNull(asString(attributes.get("url"))) != null
        || blankToNull(asString(attributes.get("host"))) != null
        || blankToNull(asString(attributes.get("ldapServerName"))) != null;
  }

  private NexusLdapServer toLdapServer(RealmView realm) {
    Map<String, Object> attributes = realm.attributes() == null ? Map.of() : realm.attributes();
    Map<String, Object> urlParts = ldapUrlParts(asString(attributes.get("url")));
    String id = defaultString(asString(firstPresent(attributes, "id", "ldapServerId")), LDAP_REALM_ID);
    String name = defaultString(asString(firstPresent(attributes, "name", "ldapServerName")), realm.name());
    String protocol = defaultString(asString(attributes.get("protocol")), asString(urlParts.get("protocol")));
    String host = defaultString(asString(attributes.get("host")), asString(urlParts.get("host")));
    Integer port = intObject(firstPresent(attributes, "port"));
    if (port == null) {
      port = intObject(urlParts.get("port"));
    }
    String searchBase = defaultString(asString(attributes.get("searchBase")), asString(urlParts.get("searchBase")));
    return new NexusLdapServer(
        id,
        name,
        defaultInt(attributes.get("order"), realm.priority()),
        asString(attributes.get("url")),
        defaultString(protocol, "ldap"),
        host,
        port,
        boolObject(attributes.get("useTrustStore")),
        searchBase,
        asString(attributes.get("authScheme")),
        asString(attributes.get("authRealm")),
        asString(firstPresent(attributes, "authUsername", "managerDn", "systemUsername", "bindDn")),
        null,
        intObject(attributes.get("connectionTimeout")),
        intObject(attributes.get("connectionRetryDelay")),
        intObject(attributes.get("maxIncidentsCount")),
        asString(firstPresent(attributes, "userBaseDn", "userSearchBase")),
        boolObject(attributes.get("userSubtree")),
        asString(attributes.get("userObjectClass")),
        asString(attributes.get("userLdapFilter")),
        asString(firstPresent(attributes, "userIdAttribute", "externalIdAttribute")),
        asString(firstPresent(attributes, "userRealNameAttribute", "firstNameAttribute")),
        asString(attributes.get("userMemberOfAttribute")),
        asString(firstPresent(attributes, "userEmailAddressAttribute", "emailAttribute")),
        asString(attributes.get("userPasswordAttribute")),
        boolObject(attributes.get("ldapGroupsAsRoles")),
        asString(attributes.get("groupType")),
        asString(firstPresent(attributes, "groupBaseDn", "groupSearchBase")),
        boolObject(attributes.get("groupSubtree")),
        asString(firstPresent(attributes, "groupIdAttribute", "groupNameAttribute")),
        asString(attributes.get("groupMemberAttribute")),
        asString(attributes.get("groupMemberFormat")),
        asString(attributes.get("groupObjectClass")));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> ldapAttributes(JsonNode payload, Map<String, Object> existing) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (existing != null) {
      attributes.putAll(existing);
    }
    attributes.put("source", "LDAP");
    attributes.put("nexusRealm", LDAP_NEXUS_REALM);
    attributes.put("ldapConfigured", true);
    putIfPresent(attributes, "id", jsonValue(payload, "id"));
    putIfPresent(attributes, "name", jsonValue(payload, "name"));
    for (String field : List.of(
        "order",
        "protocol",
        "host",
        "port",
        "useTrustStore",
        "searchBase",
        "authScheme",
        "authRealm",
        "authUsername",
        "connectionTimeout",
        "connectionRetryDelay",
        "maxIncidentsCount",
        "userBaseDn",
        "userSubtree",
        "userObjectClass",
        "userLdapFilter",
        "userIdAttribute",
        "userRealNameAttribute",
        "userMemberOfAttribute",
        "userEmailAddressAttribute",
        "userPasswordAttribute",
        "ldapGroupsAsRoles",
        "groupType",
        "groupBaseDn",
        "groupSubtree",
        "groupIdAttribute",
        "groupMemberAttribute",
        "groupMemberFormat",
        "groupObjectClass")) {
      putIfPresent(attributes, field, jsonValue(payload, field));
    }
    String authPassword = text(payload, "authPassword");
    if (blankToNull(authPassword) != null && !isPasswordPlaceholder(authPassword)) {
      attributes.put("authPassword", authPassword);
      attributes.put("managerPassword", authPassword);
      attributes.put("systemPassword", authPassword);
      attributes.put("bindPassword", authPassword);
    }
    boolean endpointChanged = jsonValue(payload, "protocol") != null
        || jsonValue(payload, "host") != null
        || jsonValue(payload, "port") != null
        || jsonValue(payload, "searchBase") != null;
    String url = text(payload, "url");
    if (blankToNull(url) == null && !endpointChanged) {
      url = asString(attributes.get("url"));
    }
    if (blankToNull(url) == null || endpointChanged) {
      url = ldapUrl(attributes);
    }
    putIfPresent(attributes, "url", url);
    alias(attributes, "managerDn", firstPresent(attributes, "authUsername", "managerDn", "systemUsername", "bindDn"));
    alias(attributes, "systemUsername", firstPresent(attributes, "authUsername", "managerDn", "systemUsername", "bindDn"));
    alias(attributes, "bindDn", firstPresent(attributes, "authUsername", "managerDn", "systemUsername", "bindDn"));
    alias(attributes, "userSearchBase", firstPresent(attributes, "userBaseDn", "userSearchBase"));
    alias(attributes, "externalIdAttribute", firstPresent(attributes, "userIdAttribute", "externalIdAttribute"));
    alias(attributes, "firstNameAttribute", firstPresent(attributes, "userRealNameAttribute", "firstNameAttribute"));
    alias(attributes, "emailAttribute", firstPresent(attributes, "userEmailAddressAttribute", "emailAttribute"));
    alias(attributes, "groupSearchBase", firstPresent(attributes, "groupBaseDn", "groupSearchBase"));
    alias(attributes, "groupNameAttribute", firstPresent(attributes, "groupIdAttribute", "groupNameAttribute"));
    String filter = ldapUserSearchFilter(attributes);
    if (filter != null) {
      attributes.put("userSearchFilter", filter);
    }
    String groupFilter = ldapGroupSearchFilter(attributes);
    if (groupFilter != null) {
      attributes.put("groupSearchFilter", groupFilter);
    }
    Integer timeoutSeconds = intObject(attributes.get("connectionTimeout"));
    if (timeoutSeconds != null) {
      attributes.put("timeoutMs", timeoutSeconds < 1000 ? timeoutSeconds * 1000 : timeoutSeconds);
    }
    if (blankToNull(asString(attributes.get("id"))) == null) {
      attributes.put("id", LDAP_REALM_ID);
    }
    if (blankToNull(asString(attributes.get("name"))) == null) {
      attributes.put("name", "LDAP");
    }
    return attributes;
  }

  private List<NexusLdapSchemaTemplate> ldapTemplates() {
    return List.of(
        new NexusLdapSchemaTemplate(
            "Generic LDAP",
            "",
            true,
            "inetOrgPerson",
            "",
            "uid",
            "cn",
            "mail",
            "userPassword",
            true,
            "memberOf",
            "static",
            "",
            true,
            "cn",
            "member",
            "${dn}",
            "groupOfNames"),
        new NexusLdapSchemaTemplate(
            "Active Directory",
            "",
            true,
            "user",
            "",
            "sAMAccountName",
            "cn",
            "mail",
            "",
            true,
            "memberOf",
            "dynamic",
            "",
            true,
            "cn",
            "member",
            "${dn}",
            "group"));
  }

  private static String ldapUrl(Map<String, Object> attributes) {
    String host = blankToNull(asString(attributes.get("host")));
    if (host == null) {
      return null;
    }
    String protocol = defaultString(asString(attributes.get("protocol")), "ldap");
    Integer port = intObject(attributes.get("port"));
    String searchBase = blankToNull(asString(attributes.get("searchBase")));
    return protocol + "://" + host + (port == null ? "" : ":" + port) + (searchBase == null ? "" : "/" + searchBase);
  }

  private static Map<String, Object> ldapUrlParts(String url) {
    if (blankToNull(url) == null) {
      return Map.of();
    }
    try {
      URI uri = URI.create(url);
      Map<String, Object> parts = new LinkedHashMap<>();
      putIfPresent(parts, "protocol", uri.getScheme());
      putIfPresent(parts, "host", uri.getHost());
      if (uri.getPort() > 0) {
        parts.put("port", uri.getPort());
      }
      String path = blankToNull(uri.getPath());
      if (path != null) {
        parts.put("searchBase", path.startsWith("/") ? path.substring(1) : path);
      }
      return parts;
    } catch (IllegalArgumentException e) {
      return Map.of();
    }
  }

  private static String ldapUserSearchFilter(Map<String, Object> attributes) {
    String idAttribute = defaultString(asString(firstPresent(attributes, "userIdAttribute", "externalIdAttribute")), "uid");
    String objectClass = blankToNull(asString(attributes.get("userObjectClass")));
    String extra = blankToNull(asString(attributes.get("userLdapFilter")));
    String userFilter = "(" + idAttribute + "={0})";
    if (objectClass == null && extra == null) {
      return userFilter;
    }
    StringBuilder filter = new StringBuilder("(&");
    if (objectClass != null) {
      filter.append("(objectClass=").append(objectClass).append(")");
    }
    filter.append(userFilter);
    if (extra != null) {
      filter.append(extra);
    }
    filter.append(")");
    return filter.toString();
  }

  private static String ldapGroupSearchFilter(Map<String, Object> attributes) {
    if (!Boolean.TRUE.equals(boolObject(attributes.get("ldapGroupsAsRoles")))) {
      return null;
    }
    if (!"static".equalsIgnoreCase(defaultString(asString(attributes.get("groupType")), "static"))) {
      return null;
    }
    String memberAttribute = defaultString(asString(attributes.get("groupMemberAttribute")), "member");
    String objectClass = blankToNull(asString(attributes.get("groupObjectClass")));
    String memberFilter = "(" + memberAttribute + "={1})";
    if (objectClass == null) {
      return memberFilter;
    }
    return "(&(objectClass=" + objectClass + ")" + memberFilter + ")";
  }

  private List<NexusUiSelector> readSelectors(boolean includeUsedBy) {
    return contentSelectors().stream()
        .map(selector -> toUiSelector(selector, includeUsedBy))
        .toList();
  }

  private NexusUiSelector createSelector(JsonNode payload) {
    String name = requireText(defaultString(text(payload, "name"), text(payload, "id")), "name");
    if (securityService.findRepositoryTarget(name).filter(this::isContentSelector).isPresent()) {
      throw new SecurityValidationException("Selector already exists: " + name);
    }
    return toUiSelector(securityService.saveRepositoryTarget(toSelectorCommand(name, payload, text(payload, "type"))), true);
  }

  private NexusUiSelector updateSelector(JsonNode payload) {
    String name = requireText(defaultString(text(payload, "name"), text(payload, "id")), "name");
    RepositoryTargetView existing = securityService.findRepositoryTarget(name)
        .filter(this::isContentSelector)
        .orElseThrow(() -> new SecurityValidationException("Selector not found: " + name));
    securityService.saveRepositoryTarget(toSelectorCommand(existing.targetId(), payload, contentSelectorType(existing)));
    return toUiSelector(securityService.findRepositoryTarget(existing.targetId()).orElseThrow(), true);
  }

  private void removeSelector(String name) {
    String selectorName = requireText(name, "name");
    RepositoryTargetView existing = securityService.findRepositoryTarget(selectorName)
        .filter(this::isContentSelector)
        .orElseThrow(() -> new SecurityValidationException("Selector not found: " + selectorName));
    List<String> usedBy = selectorUsedBy(existing.targetId());
    if (!usedBy.isEmpty()) {
      throw new SecurityValidationException("Selector is used by privileges: " + String.join(", ", usedBy));
    }
    securityService.deleteRepositoryTarget(selectorName);
  }

  private RepositoryTargetCommand toSelectorCommand(String name, JsonNode payload, String selectorType) {
    String expression = requireText(text(payload, "expression"), "expression");
    String type = defaultString(selectorType, "csel");
    String description = defaultString(text(payload, "description"), "");
    return new RepositoryTargetCommand(
        name,
        name,
        "*",
        expression,
        List.of(),
        Map.of(
            "source", "nexus-content-selector",
            "type", type,
            "description", description));
  }

  private String contentSelectorType(RepositoryTargetView target) {
    return defaultString(asString(target.attributes() == null ? null : target.attributes().get("type")), "csel");
  }

  private NexusUiSelector toUiSelector(RepositoryTargetView selector, boolean includeUsedBy) {
    Map<String, Object> attributes = selector.attributes() == null ? Map.of() : selector.attributes();
    List<String> usedBy = selectorUsedBy(selector.targetId());
    return new NexusUiSelector(
        selector.targetId(),
        selector.targetId(),
        defaultString(asString(attributes.get("type")), "csel"),
        asString(attributes.get("description")),
        selector.contentExpression(),
        includeUsedBy ? usedBy : List.of(),
        usedBy.size());
  }

  private List<RepositoryTargetView> contentSelectors() {
    return securityService.listRepositoryTargets().stream()
        .filter(this::isContentSelector)
        .toList();
  }

  private List<NexusUiRepositoryReference> repositoryReferences(
      JsonNode parameters,
      boolean includeAllRepositories,
      boolean includeAllFormats) {
    List<NexusUiRepositoryReference> references = new ArrayList<>();
    if (repositoryService != null) {
      String query = text(parameters, "query");
      for (RepositoryView repository : repositoryService.list()) {
        if (query != null && !repository.name().startsWith(query)) {
          continue;
        }
        references.add(toRepositoryReference(repository));
      }
    }
    if (includeAllRepositories) {
      references.add(new NexusUiRepositoryReference(
          "*",
          "(All Repositories)",
          null,
          null,
          null,
          null,
          null,
          1));
    }
    if (includeAllFormats) {
      for (RepositoryFormat format : RepositoryFormat.values()) {
        String id = "*-" + format.id();
        references.add(new NexusUiRepositoryReference(
            id,
            "(All " + format.id() + " Repositories)",
            null,
            null,
            null,
            null,
            null,
            0));
      }
    }
    return references;
  }

  private NexusUiRepositoryReference toRepositoryReference(RepositoryView repository) {
    return new NexusUiRepositoryReference(
        repository.name(),
        repository.name(),
        repository.type().name().toLowerCase(Locale.ROOT),
        repository.format().id(),
        repositoryVersionPolicy(repository),
        repository.url(),
        new NexusUiRepositoryStatus(repository.name(), repository.online(), null, null),
        0);
  }

  private String repositoryVersionPolicy(RepositoryView repository) {
    if (repository.hosted() != null) {
      return repository.hosted().versionPolicy();
    }
    if (repository.format() == RepositoryFormat.MAVEN2 && repository.type() == RepositoryType.GROUP) {
      return "MIXED";
    }
    return null;
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

  private List<String> selectorUsedBy(String selectorName) {
    return securityService.listPrivileges().stream()
        .filter(privilege -> "repository-content-selector".equals(defaultString(privilege.type(), "")))
        .filter(privilege -> selectorName.equals(asString(firstPresent(
            privilege.properties(),
            "contentSelector",
            "selector",
            "selectorName",
            "contentSelectorName"))))
        .map(privilege -> defaultString(privilege.name(), privilege.privilegeId()))
        .toList();
  }

  private boolean canReadPrivileges(HttpServletRequest request) {
    return currentSubject(request)
        .map(subject -> securityService.decide(subject.permissionSubject(), "nexus:privileges:read").allowed())
        .orElse(false);
  }

  private List<NexusUiUser> readUsers(JsonNode parameters) {
    String source = defaultString(filter(parameters, "source"), DEFAULT_SOURCE);
    String userId = filter(parameters, "userId");
    int limit = intValue(parameters, "limit", -1);
    List<NexusUiUser> users = securityService.listUsers(source, userId).stream()
        .map(this::toUiUser)
        .toList();
    if (limit < 0 || limit >= users.size()) {
      return users;
    }
    return users.subList(0, limit);
  }

  private NexusUiUser getUser(String userId, String source) {
    return securityService.findUser(normalizeSource(source), userId)
        .map(this::toUiUser)
        .orElseThrow(() -> new SecurityValidationException("User not found: " + userId));
  }

  private List<NexusUiReference> userSources() {
    return securityService.listRealms().stream()
        .map(realm -> new NexusUiReference(sourceForRealm(realm), realm.name()))
        .distinct()
        .toList();
  }

  private NexusUiUser createUser(JsonNode payload) {
    NexusUiUser user = toUiUserPayload(payload);
    String userId = requireText(user.userId(), "userId");
    return toUiUser(securityService.saveUser(new UserCommand(
        DEFAULT_SOURCE,
        userId,
        user.firstName(),
        user.lastName(),
        user.email(),
        requireText(user.password(), "password"),
        null,
        toStoredStatus(user.status()),
        null,
        toList(user.roles()),
        Map.of())));
  }

  private NexusUiUser updateUser(JsonNode payload) {
    NexusUiUser user = toUiUserPayload(payload);
    String source = normalizeSource(user.realm());
    String userId = requireText(user.userId(), "userId");
    UserView existing = securityService.findUser(source, userId)
        .orElseThrow(() -> new SecurityValidationException("User not found: " + userId));
    String password = blankToNull(user.password());
    if (isPasswordPlaceholder(password)) {
      password = null;
    }
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
          NexusExternalRoles.localRoleList(existing, user.roles()),
          existing.attributes())));
    }
    return toUiUser(securityService.saveUser(new UserCommand(
        existing.source(),
        existing.userId(),
        user.firstName(),
        user.lastName(),
        user.email(),
        password,
        null,
        toStoredStatus(user.status()),
        existing.externalId(),
        existing.external()
            ? NexusExternalRoles.localRoleList(existing, user.roles())
            : toList(user.roles()),
        existing.attributes())));
  }

  private NexusUiUser updateUserRoleMappings(JsonNode payload) {
    NexusUiUserRoleMappings mappings = toUiUserRoleMappingsPayload(payload);
    String source = normalizeSource(mappings.realm());
    String userId = requireText(mappings.userId(), "userId");
    UserView existing = securityService.findUser(source, userId)
        .orElseThrow(() -> new SecurityValidationException("User not found: " + userId));
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
        NexusExternalRoles.localRoleList(existing, mappings.roles()),
        existing.attributes())));
  }

  private List<NexusUiReference> roleSources() {
    return List.of();
  }

  private List<NexusUiRole> readRolesFromSource(String source) {
    return securityService.listRoles().stream()
        .map(this::toUiRole)
        .toList();
  }

  private NexusUiRole saveRole(JsonNode payload) {
    NexusUiRole role = toUiRolePayload(payload);
    String roleId = requireText(role.id(), "id");
    return toUiRole(securityService.saveRole(new RoleCommand(
        roleId,
        DEFAULT_SOURCE,
        role.name(),
        role.description(),
        role.readOnly(),
        toList(role.privileges()),
        toList(role.roles()),
        Map.of())));
  }

  private ExtDirectResult readPrivileges(JsonNode parameters) {
    String filter = filter(parameters, "filter");
    String sort = sortProperty(parameters, "name");
    String direction = sortDirection(parameters, "ASC");
    int start = intValue(parameters, "start", 0);
    int limit = intValue(parameters, "limit", -1);
    List<NexusUiPrivilege> privileges = securityService.listPrivileges().stream()
        .map(this::toUiPrivilege)
        .filter(privilege -> privilegeMatches(privilege, filter))
        .sorted(privilegeComparator(sort, direction))
        .toList();
    int total = privileges.size();
    int from = Math.max(0, start);
    int to = limit < 0 ? total : Math.min(total, from + limit);
    List<NexusUiPrivilege> data = from >= total ? List.of() : privileges.subList(from, to);
    return page(total, data);
  }

  private List<NexusUiPrivilegeType> privilegeTypes() {
    return List.of(
        new NexusUiPrivilegeType("application", "Application", List.of(
            field("domain", "string", "Domain", true, null),
            field("actions", "string", "Actions", true, "Comma-separated actions"))),
        new NexusUiPrivilegeType("wildcard", "Wildcard", List.of(
            field("pattern", "string", "Pattern", true, null))),
        new NexusUiPrivilegeType("repository-admin", "Repository Admin", List.of(
            field("format", "string", "Format", true, null),
            field("repository", "combobox", "Repository", true, "The repository name",
                "coreui_Repository.readReferencesAddingEntryForAll", true),
            field("actions", "string", "Actions", true, "Comma-separated actions"))),
        new NexusUiPrivilegeType("repository-view", "Repository View", List.of(
            field("format", "string", "Format", true, null),
            field("repository", "combobox", "Repository", true, "The repository name",
                "coreui_Repository.readReferencesAddingEntryForAll", true),
            field("actions", "string", "Actions", true, "Comma-separated actions"))),
        new NexusUiPrivilegeType("repository-content-selector", "Repository Content Selector", List.of(
            field("contentSelector", "combobox", "Content Selector", true,
                "The content selector for the repository", "coreui_Selector.readReferences", false),
            field("repository", "combobox", "Repository", true,
                "The repository or repositories to grant access",
                "coreui_Repository.readReferencesAddingEntriesForAllFormats", true),
            field("actions", "string", "Actions", true, "Comma-separated actions"))));
  }

  private static NexusUiFormField field(
      String id,
      String type,
      String label,
      boolean required,
      String helpText) {
    return field(id, type, label, required, helpText, null, false);
  }

  private static NexusUiFormField field(
      String id,
      String type,
      String label,
      boolean required,
      String helpText,
      String storeApi,
      boolean allowAutocomplete) {
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
        storeApi,
        null,
        null,
        null,
        allowAutocomplete);
  }

  private NexusUiPrivilege savePrivilege(JsonNode payload) {
    NexusUiPrivilege privilege = toUiPrivilegePayload(payload);
    String id = defaultString(privilege.id(), privilege.name());
    return toUiPrivilege(securityService.savePrivilege(new PrivilegeCommand(
        requireText(id, "id"),
        defaultString(privilege.name(), id),
        privilege.description(),
        defaultString(privilege.type(), "wildcard"),
        privilege.readOnly(),
        privilege.properties() == null ? Map.of() : privilege.properties())));
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

  private NexusUiUser toUiUserPayload(JsonNode node) {
    return new NexusUiUser(
        text(node, "userId"),
        text(node, "version"),
        text(node, "realm"),
        text(node, "firstName"),
        text(node, "lastName"),
        text(node, "email"),
        text(node, "status"),
        text(node, "password"),
        stringSet(node == null ? null : node.get("roles")),
        booleanValue(node, "external"),
        stringSet(node == null ? null : node.get("externalRoles")));
  }

  private NexusUiUserRoleMappings toUiUserRoleMappingsPayload(JsonNode node) {
    return new NexusUiUserRoleMappings(
        text(node, "userId"),
        text(node, "realm"),
        stringSet(node == null ? null : node.get("roles")));
  }

  private NexusUiRole toUiRolePayload(JsonNode node) {
    return new NexusUiRole(
        text(node, "id"),
        text(node, "version"),
        text(node, "source"),
        text(node, "name"),
        text(node, "description"),
        booleanValue(node, "readOnly"),
        stringSet(node == null ? null : node.get("privileges")),
        stringSet(node == null ? null : node.get("roles")));
  }

  @SuppressWarnings("unchecked")
  private NexusUiPrivilege toUiPrivilegePayload(JsonNode node) {
    return new NexusUiPrivilege(
        text(node, "id"),
        text(node, "version"),
        text(node, "name"),
        text(node, "description"),
        text(node, "type"),
        booleanValue(node, "readOnly"),
        (Map<String, Object>) objectValue(node == null ? null : node.get("properties")),
        text(node, "permission"));
  }

  private static JsonNode parameterObject(JsonNode call) {
    JsonNode data = call == null ? null : call.get("data");
    if (data != null && data.isArray() && !data.isEmpty()) {
      JsonNode first = data.get(0);
      return first == null || first.isNull() ? null : first;
    }
    return data == null || data.isNull() ? null : data;
  }

  private static JsonNode objectArg(JsonNode call, int index) {
    JsonNode data = call == null ? null : call.get("data");
    if (data == null || !data.isArray() || data.size() <= index || data.get(index).isNull()) {
      return null;
    }
    return data.get(index);
  }

  private static String stringArg(JsonNode call, int index) {
    JsonNode value = objectArg(call, index);
    return value == null ? null : value.asText();
  }

  private static String decodeBase64(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new SecurityValidationException(field + " is required");
    }
    try {
      return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new SecurityValidationException(field + " must be base64 encoded", e);
    }
  }

  private static String filter(JsonNode parameters, String property) {
    JsonNode directNode = parameters == null ? null : parameters.get(property);
    if (directNode != null && directNode.isValueNode()) {
      String direct = blankToNull(directNode.asText());
      if (direct != null) {
        return direct;
      }
    }
    JsonNode filters = parameters == null ? null : parameters.get("filter");
    if (filters != null && filters.isArray()) {
      for (JsonNode filter : filters) {
        String filterProperty = defaultString(text(filter, "property"), "filter");
        if (property.equals(filterProperty) || ("filter".equals(property) && "query".equals(filterProperty))) {
          return text(filter, "value");
        }
      }
    }
    return null;
  }

  private static String sortProperty(JsonNode parameters, String fallback) {
    JsonNode sorters = parameters == null ? null : parameters.get("sort");
    if (sorters != null && sorters.isArray() && !sorters.isEmpty()) {
      return defaultString(text(sorters.get(0), "property"), fallback);
    }
    return fallback;
  }

  private static String sortDirection(JsonNode parameters, String fallback) {
    JsonNode sorters = parameters == null ? null : parameters.get("sort");
    if (sorters != null && sorters.isArray() && !sorters.isEmpty()) {
      return defaultString(text(sorters.get(0), "direction"), fallback);
    }
    return fallback;
  }

  private static int intValue(JsonNode node, String field, int fallback) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? fallback : value.asInt(fallback);
  }

  private static Boolean booleanValue(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asBoolean();
  }

  private static Object objectValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (node.isObject()) {
      Map<String, Object> values = new LinkedHashMap<>();
      node.fields().forEachRemaining(entry -> values.put(entry.getKey(), objectValue(entry.getValue())));
      return values;
    }
    if (node.isArray()) {
      List<Object> values = new ArrayList<>();
      node.forEach(value -> values.add(objectValue(value)));
      return values;
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    return node.asText();
  }

  private static Object jsonValue(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return objectValue(value);
  }

  private static Set<String> stringSet(JsonNode node) {
    if (node == null || node.isNull()) {
      return Set.of();
    }
    if (node.isArray()) {
      LinkedHashSet<String> values = new LinkedHashSet<>();
      node.forEach(value -> {
        String text = value.asText();
        if (text != null && !text.isBlank()) {
          values.add(text);
        }
      });
      return values;
    }
    String text = node.asText();
    if (text == null || text.isBlank()) {
      return Set.of();
    }
    return new LinkedHashSet<>(java.util.Arrays.stream(text.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList());
  }

  private static List<String> toList(Set<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(NexusExtDirectController::blankToNull)
        .filter(value -> value != null)
        .distinct()
        .toList();
  }

  private static Set<String> toSet(List<String> values) {
    return values == null ? Set.of() : new LinkedHashSet<>(values);
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

  private static Object firstPresent(Map<String, Object> values, String... keys) {
    if (values == null || keys == null) {
      return null;
    }
    for (String key : keys) {
      Object value = values.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static void putIfPresent(Map<String, Object> values, String key, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String text && text.isBlank()) {
      return;
    }
    values.put(key, value);
  }

  private static void alias(Map<String, Object> values, String key, Object value) {
    putIfPresent(values, key, value);
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Integer intObject(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    String text = blankToNull(asString(value));
    if (text == null) {
      return null;
    }
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int defaultInt(Object value, int fallback) {
    Integer parsed = intObject(value);
    return parsed == null ? fallback : parsed;
  }

  private static Boolean boolObject(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    String text = blankToNull(asString(value));
    return text == null ? null : Boolean.parseBoolean(text);
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

  private static List<String> realmNames(JsonNode call) {
    JsonNode data = call == null ? null : call.get("data");
    JsonNode payload = data != null && data.isArray() && !data.isEmpty() ? data.get(0) : data;
    JsonNode realms = payload == null ? null : payload.get("realms");
    if (realms == null || realms.isNull()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    if (realms.isArray()) {
      realms.forEach(realm -> {
        if (!realm.isNull()) {
          result.add(realm.asText());
        }
      });
      return result;
    }
    String text = realms.asText();
    if (text == null || text.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(text.split(","))
        .map(String::trim)
        .filter(realm -> !realm.isBlank())
        .toList();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Object value(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isInt()) {
      return value.asInt();
    }
    if (value.isLong()) {
      return value.asLong();
    }
    if (value.isTextual()) {
      return value.asText();
    }
    return value.toString();
  }

  public record ExtDirectResponse(
      String type,
      Object tid,
      String action,
      String method,
      ExtDirectResult result) {
  }

  public record ExtDirectResult(
      Boolean success,
      Object data,
      String message,
      Integer total) {
  }
}
