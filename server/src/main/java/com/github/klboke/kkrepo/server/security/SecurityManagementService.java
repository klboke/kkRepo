package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.security.ApiKeyTokenPayloads;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import com.github.klboke.kkrepo.persistence.mysql.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityUserRecord;
import com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache.AuthorizationSnapshot;
import com.github.klboke.kkrepo.server.security.SecurityCatalogCache.SecurityCatalog;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapStatus;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.CreatedApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.LdapSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.LdapSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.OidcSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.OidcSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmReference;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.SecuritySummary;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserView;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SecurityManagementService implements AccessDecisionService {
  private static final String DEFAULT_SOURCE = "Local";
  private static final String DEFAULT_ADMIN_USER_ID = "admin";
  private static final String ADMIN_ROLE_ID = "nx-admin";
  private static final String DEFAULT_ANONYMOUS_USER_ID = "anonymous";
  private static final int ADMIN_BOOTSTRAP_MIN_PASSWORD_LENGTH = 8;
  private static final String DEFAULT_ANONYMOUS_REALM_NAME = "NexusAuthorizingRealm";
  private static final String PASSWORD_PLACEHOLDER = "********";
  private static final String NEXUS_AUTHENTICATING_REALM = "NexusAuthenticatingRealm";
  private static final String NEXUS_AUTHORIZING_REALM = "NexusAuthorizingRealm";
  private static final String LDAP_REALM_ID = "ldap";
  private static final String LDAP_REALM = "LdapRealm";
  private static final String OIDC_REALM = "OidcRealm";
  private static final Set<String> SUPPORTED_REALMS = Set.of("local", "ldap", "oidc");
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final SecurityDao securityDao;
  private final SecurityAuthorizationCache authorizationCache;
  private final ApiKeyAuthCache apiKeyAuthCache;
  private final BasicAuthCache basicAuthCache;
  private final SecurityCatalogCache securityCatalogCache;

  public SecurityManagementService(SecurityDao securityDao) {
    this(securityDao, null, null, null);
  }

  public SecurityManagementService(SecurityDao securityDao, SecurityAuthorizationCache authorizationCache) {
    this(securityDao, authorizationCache, null, null);
  }

  public SecurityManagementService(
      SecurityDao securityDao,
      SecurityAuthorizationCache authorizationCache,
      ApiKeyAuthCache apiKeyAuthCache) {
    this(securityDao, authorizationCache, apiKeyAuthCache, null, null);
  }

  public SecurityManagementService(
      SecurityDao securityDao,
      SecurityAuthorizationCache authorizationCache,
      ApiKeyAuthCache apiKeyAuthCache,
      SecurityCatalogCache securityCatalogCache) {
    this(securityDao, authorizationCache, apiKeyAuthCache, null, securityCatalogCache);
  }

  @Autowired
  public SecurityManagementService(
      SecurityDao securityDao,
      SecurityAuthorizationCache authorizationCache,
      ApiKeyAuthCache apiKeyAuthCache,
      BasicAuthCache basicAuthCache,
      SecurityCatalogCache securityCatalogCache) {
    this.securityDao = securityDao;
    this.authorizationCache = authorizationCache;
    this.apiKeyAuthCache = apiKeyAuthCache;
    this.basicAuthCache = basicAuthCache;
    this.securityCatalogCache = securityCatalogCache;
  }

  @Transactional(readOnly = true)
  public SecuritySummary summary() {
    List<RealmView> realms = listRealms();
    return new SecuritySummary(
        listUsers(null, null).size(),
        listRoles().size(),
        listPrivileges().size(),
        (int) realms.stream().filter(RealmView::enabled).count(),
        listApiKeys().size());
  }

  @Transactional(readOnly = true)
  public List<UserView> listUsers(String source, String userId) {
    String sourceFilter = blankToNull(source) == null ? null : normalizeSource(source);
    String userFilter = blankToNull(userId);
    return securityDao.listUsers().stream()
        .filter(record -> sourceFilter == null || record.source().equals(sourceFilter))
        .filter(record -> userFilter == null || record.userId().contains(userFilter))
        .map(this::toUserView)
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<UserView> findUser(String source, String userId) {
    return securityDao.findUser(normalizeSource(source), requireText(userId, "userId"))
        .map(this::toUserView);
  }

  @Transactional(readOnly = true)
  public AdminBootstrapStatus adminBootstrapStatus() {
    return new AdminBootstrapStatus(
        adminBootstrapRequired(),
        DEFAULT_SOURCE,
        DEFAULT_ADMIN_USER_ID,
        ADMIN_ROLE_ID,
        ADMIN_BOOTSTRAP_MIN_PASSWORD_LENGTH,
        anonymousSettings().enabled());
  }

  @Transactional
  public UserView initializeAdmin(AdminBootstrapCommand command) {
    if (!adminBootstrapRequired()) {
      throw new SecurityValidationException("Administrator is already initialized.");
    }
    if (securityDao.findRole(ADMIN_ROLE_ID).isEmpty()) {
      throw new SecurityValidationException("Role not found: " + ADMIN_ROLE_ID);
    }
    String password = requireText(command == null ? null : command.password(), "password");
    String passwordConfirm = requireText(command == null ? null : command.passwordConfirm(), "passwordConfirm");
    if (!password.equals(passwordConfirm)) {
      throw new SecurityValidationException("passwordConfirm does not match password");
    }
    if (password.length() < ADMIN_BOOTSTRAP_MIN_PASSWORD_LENGTH) {
      throw new SecurityValidationException(
          "password must be at least " + ADMIN_BOOTSTRAP_MIN_PASSWORD_LENGTH + " characters");
    }
    UserView administrator = saveUser(new UserCommand(
        DEFAULT_SOURCE,
        DEFAULT_ADMIN_USER_ID,
        "Administrator",
        "User",
        "admin@example.invalid",
        password,
        null,
        "ACTIVE",
        null,
        List.of(ADMIN_ROLE_ID),
        Map.of("source", "nexus-bootstrap")));
    saveAnonymousSettings(new AnonymousSettingsCommand(
        Boolean.TRUE.equals(command.anonymousAccessEnabled()),
        DEFAULT_SOURCE,
        DEFAULT_ANONYMOUS_USER_ID,
        DEFAULT_ANONYMOUS_REALM_NAME));
    return administrator;
  }

  @Transactional
  public UserView saveUser(UserCommand command) {
    String source = normalizeSource(command.source());
    String userId = requireText(command.userId(), "userId");
    String status = defaultString(command.status(), "ACTIVE");
    String passwordHash = resolvePasswordHash(command);
    Map<String, Object> attributes = copyMap(command.attributes());
    List<String> roleIds = normalizeList(command.roles());
    validateUserRoleReferences(roleIds);
    SecurityUserRecord record = new SecurityUserRecord(
        null,
        source,
        userId,
        blankToNull(command.firstName()),
        blankToNull(command.lastName()),
        blankToNull(command.email()),
        passwordHash,
        status,
        blankToNull(command.externalId()),
        attributes);
    SecurityUserRecord stored = securityDao.findUser(source, userId)
        .map(existing -> new SecurityUserRecord(
            existing.id(), source, userId, record.firstName(), record.lastName(), record.email(),
            passwordHash == null ? existing.passwordHash() : passwordHash,
            record.status(), record.externalId(), record.attributes()))
        .orElse(record);
    if (stored.id() == null) {
      long id = securityDao.insertUser(stored);
      securityDao.replaceUserRoles(id, roleIds);
    } else {
      securityDao.updateUser(stored);
      securityDao.replaceUserRoles(stored.id(), roleIds);
    }
    invalidateAuthorizationCacheAfterCommit();
    return toUserView(securityDao.findUser(source, userId).orElseThrow());
  }

  @Transactional
  public void deleteUser(String source, String userId) {
    deleteUser(source, userId, null);
  }

  @Transactional
  public void deleteUser(String source, String userId, String currentUserId) {
    String normalizedSource = normalizeSource(source);
    String normalizedUserId = requireText(userId, "userId");
    if (isAnonymousUser(normalizedUserId)) {
      throw new SecurityValidationException(
          "User " + normalizedUserId + " cannot be deleted, since is marked as the Anonymous user");
    }
    if (normalizedUserId.equals(blankToNull(currentUserId))) {
      throw new SecurityValidationException(
          "User " + normalizedUserId + " cannot be deleted, since is the user currently logged into the application");
    }
    int removed = securityDao.deleteUser(normalizedSource, normalizedUserId);
    if (removed == 0) {
      throw new SecurityValidationException("User not found: " + source + "/" + userId);
    }
    securityDao.deleteApiKeysForOwner(normalizedSource, normalizedUserId);
    invalidateAuthorizationCacheAfterCommit();
  }

  @Transactional
  public void changePassword(String userId, String password) {
    if (password == null || password.isBlank()) {
      throw new SecurityValidationException("password is required");
    }
    if (isAnonymousUser(userId)) {
      throw new SecurityValidationException(
          "Password cannot be changed for user " + userId + ", since is marked as the Anonymous user");
    }
    UserView current = findUser(DEFAULT_SOURCE, userId)
        .orElseThrow(() -> new SecurityValidationException("User not found: " + userId));
    saveUser(new UserCommand(
        DEFAULT_SOURCE,
        current.userId(),
        current.firstName(),
        current.lastName(),
        current.email(),
        password,
        null,
        current.status(),
        current.externalId(),
        current.roles(),
        current.attributes()));
  }

  @Transactional(readOnly = true)
  public List<RoleView> listRoles() {
    return securityDao.listRoles().stream().map(this::toRoleView).toList();
  }

  @Transactional(readOnly = true)
  public Optional<RoleView> findRole(String roleId) {
    return securityDao.findRole(requireText(roleId, "roleId")).map(this::toRoleView);
  }

  @Transactional
  public RoleView saveRole(RoleCommand command) {
    String roleId = requireText(command.roleId(), "roleId");
    SecurityRoleRecord existing = securityDao.findRole(roleId).orElse(null);
    if (existing != null && existing.readOnly()) {
      throw new SecurityValidationException("Role is read-only: " + roleId);
    }
    List<String> privilegeIds = normalizeList(command.privileges());
    List<String> childRoleIds = normalizeList(command.roles());
    validateRoleReferences(roleId, privilegeIds, childRoleIds);
    SecurityRoleRecord record = new SecurityRoleRecord(
        roleId,
        DEFAULT_SOURCE,
        defaultString(command.name(), roleId),
        blankToNull(command.description()),
        Boolean.TRUE.equals(command.readOnly()),
        copyMap(command.attributes()));
    securityDao.upsertRole(record);
    securityDao.replaceRolePrivileges(roleId, privilegeIds);
    securityDao.replaceRoleInheritance(roleId, childRoleIds);
    invalidateAuthorizationCacheAfterCommit();
    return toRoleView(securityDao.findRole(roleId).orElseThrow());
  }

  @Transactional
  public void deleteRole(String roleId) {
    SecurityRoleRecord existing = securityDao.findRole(requireText(roleId, "roleId"))
        .orElseThrow(() -> new SecurityValidationException("Role not found: " + roleId));
    if (existing.readOnly()) {
      throw new SecurityValidationException("Role is read-only: " + roleId);
    }
    securityDao.removeRoleReferences(roleId);
    int removed = securityDao.deleteRole(roleId);
    if (removed == 0) {
      throw new SecurityValidationException("Role not found: " + roleId);
    }
    invalidateAuthorizationCacheAfterCommit();
  }

  @Transactional(readOnly = true)
  public List<PrivilegeView> listPrivileges() {
    return securityDao.listPrivileges().stream().map(this::toPrivilegeView).toList();
  }

  @Transactional(readOnly = true)
  public Optional<PrivilegeView> findPrivilege(String privilegeId) {
    return securityDao.findPrivilege(requireText(privilegeId, "privilegeId")).map(this::toPrivilegeView);
  }

  @Transactional
  public PrivilegeView savePrivilege(PrivilegeCommand command) {
    String privilegeId = defaultString(command.privilegeId(), command.name());
    privilegeId = requireText(privilegeId, "privilegeId");
    SecurityPrivilegeRecord existing = securityDao.findPrivilege(privilegeId).orElse(null);
    if (existing != null && existing.readOnly()) {
      throw new SecurityValidationException("Privilege is read-only: " + privilegeId);
    }
    SecurityPrivilegeRecord record = new SecurityPrivilegeRecord(
        privilegeId,
        defaultString(command.name(), privilegeId),
        blankToNull(command.description()),
        defaultString(command.type(), "wildcard"),
        Boolean.TRUE.equals(command.readOnly()),
        copyMap(command.properties()));
    securityDao.upsertPrivilege(record);
    invalidateAuthorizationCacheAfterCommit();
    return toPrivilegeView(securityDao.findPrivilege(privilegeId).orElseThrow());
  }

  @Transactional
  public void deletePrivilege(String privilegeId) {
    SecurityPrivilegeRecord existing = securityDao.findPrivilege(requireText(privilegeId, "privilegeId"))
        .orElseThrow(() -> new SecurityValidationException("Privilege not found: " + privilegeId));
    if (existing.readOnly()) {
      throw new SecurityValidationException("Privilege is read-only: " + privilegeId);
    }
    securityDao.removePrivilegeReferences(privilegeId);
    int removed = securityDao.deletePrivilege(privilegeId);
    if (removed == 0) {
      throw new SecurityValidationException("Privilege not found: " + privilegeId);
    }
    invalidateAuthorizationCacheAfterCommit();
  }

  @Transactional(readOnly = true)
  public List<RealmView> listRealms() {
    return securityDao.listRealms().stream().map(this::toRealmView).toList();
  }

  @Transactional(readOnly = true)
  public List<String> listActiveRealmIds() {
    return securityDao.listRealms().stream()
        .filter(SecurityRealmRecord::enabled)
        .sorted((left, right) -> Integer.compare(left.priority(), right.priority()))
        .map(SecurityRealmRecord::realmId)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<String> listActiveNexusRealmNames() {
    return securityDao.listRealms().stream()
        .filter(SecurityRealmRecord::enabled)
        .sorted((left, right) -> Integer.compare(left.priority(), right.priority()))
        .flatMap(record -> nexusRealmNames(record).stream())
        .toList();
  }

  @Transactional(readOnly = true)
  public List<RealmReference> listNexusRealmTypes() {
    List<RealmReference> references = new ArrayList<>();
    for (SecurityRealmRecord record : securityDao.listRealms()) {
      switch (record.realmId()) {
        case "local" -> {
          references.add(new RealmReference(NEXUS_AUTHENTICATING_REALM, "Local Authenticating Realm"));
          references.add(new RealmReference(NEXUS_AUTHORIZING_REALM, "Local Authorizing Realm"));
        }
        case "ldap" -> references.add(new RealmReference(LDAP_REALM, "LDAP Realm"));
        case "oidc" -> references.add(new RealmReference(OIDC_REALM, "OIDC Realm"));
        default -> references.add(new RealmReference(nexusRealmName(record), record.name()));
      }
    }
    return references.stream()
        .distinct()
        .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
        .toList();
  }

  @Transactional
  public List<RealmView> saveActiveRealmIds(List<String> realmIds) {
    List<String> normalized = normalizeList(realmIds).stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .toList();
    Set<String> requested = new LinkedHashSet<>(normalized);
    Set<String> known = securityDao.listRealms().stream()
        .map(SecurityRealmRecord::realmId)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    for (String realmId : requested) {
      if (!known.contains(realmId)) {
        throw new SecurityValidationException("Unknown realm: " + realmId);
      }
    }

    Map<String, Integer> priorities = new LinkedHashMap<>();
    int priority = 0;
    for (String realmId : requested) {
      priorities.put(realmId, priority);
      priority += 10;
    }

    List<RealmCommand> commands = new ArrayList<>();
    for (SecurityRealmRecord existing : securityDao.listRealms()) {
      boolean requiredLocal = "local".equals(existing.realmId());
      boolean enabled = requiredLocal || requested.contains(existing.realmId());
      Integer requestedPriority = priorities.get(existing.realmId());
      commands.add(new RealmCommand(
          existing.realmId(),
          existing.type(),
          existing.name(),
          enabled,
          requestedPriority == null ? existing.priority() : requestedPriority,
          existing.attributes()));
    }
    return saveRealms(commands);
  }

  @Transactional
  public List<String> saveActiveNexusRealmNames(List<String> nexusRealmNames) {
    List<String> realmIds = normalizeList(nexusRealmNames).stream()
        .map(SecurityManagementService::internalRealmIdForNexusRealm)
        .filter(realmId -> realmId != null)
        .distinct()
        .toList();
    saveActiveRealmIds(realmIds);
    return listActiveNexusRealmNames();
  }

  @Transactional
  public List<RealmView> saveRealms(List<RealmCommand> commands) {
    if (commands == null) {
      commands = List.of();
    }
    int defaultPriority = 0;
    for (RealmCommand command : commands) {
      String realmId = requireText(command.realmId(), "realmId").toLowerCase(Locale.ROOT);
      if (!SUPPORTED_REALMS.contains(realmId)) {
        throw new SecurityValidationException("Unsupported realm: " + realmId);
      }
      SecurityRealmRecord existing = securityDao.findRealm(realmId).orElse(null);
      SecurityRealmRecord record = new SecurityRealmRecord(
          existing == null ? null : existing.id(),
          realmId,
          defaultString(command.type(), existing == null ? realmId.toUpperCase(Locale.ROOT) : existing.type()),
          defaultString(command.name(), existing == null ? realmId : existing.name()),
          "local".equals(realmId) || (command.enabled() == null
              ? existing != null && existing.enabled()
              : command.enabled()),
          command.priority() == null ? defaultPriority : command.priority(),
          command.attributes() == null && existing != null ? existing.attributes() : copyMap(command.attributes()));
      securityDao.upsertRealm(record);
      defaultPriority += 10;
    }
    List<String> active = securityDao.listRealms().stream()
        .filter(SecurityRealmRecord::enabled)
        .sorted((left, right) -> Integer.compare(left.priority(), right.priority()))
        .map(SecurityRealmRecord::realmId)
        .toList();
    securityDao.updateRealmConfig(active);
    return listRealms();
  }

  @Transactional(readOnly = true)
    public OidcSettingsView oidcSettings() {
      SecurityRealmRecord record = securityDao.findRealm("oidc")
          .orElseGet(() -> new SecurityRealmRecord(
              null,
            "oidc",
            "OIDC",
            "OIDC",
            false,
            20,
            Map.of("source", "OIDC", "nexusRealm", OIDC_REALM)));
      return toOidcSettingsView(record);
    }

    @Transactional(readOnly = true)
    public LdapSettingsView ldapSettings() {
      SecurityRealmRecord record = securityDao.findRealm(LDAP_REALM_ID)
          .orElseGet(() -> new SecurityRealmRecord(
              null,
              LDAP_REALM_ID,
              "LDAP",
              "LDAP",
              false,
              10,
              Map.of("source", "LDAP", "nexusRealm", LDAP_REALM)));
      return toLdapSettingsView(record);
    }

    @Transactional
    public LdapSettingsView saveLdapSettings(LdapSettingsCommand command) {
      if (command == null) {
        command = emptyLdapSettingsCommand();
      }
      SecurityRealmRecord existing = securityDao.findRealm(LDAP_REALM_ID).orElse(null);
      Map<String, Object> attributes = ldapAttributes(
          command,
          existing == null ? Map.of() : existing.attributes());
      String name = firstNonBlank(
          command.name(),
          asString(attributes.get("name")),
          existing == null ? null : existing.name(),
          "LDAP");
      boolean enabled = command.enabled() == null ? existing != null && existing.enabled() : command.enabled();
      if (enabled && blankToNull(asString(attributes.get("url"))) == null) {
        throw new SecurityValidationException("LDAP URL is required when LDAP realm is enabled");
      }
      int priority = command.priority() == null ? existing == null ? 10 : existing.priority() : command.priority();
      saveRealms(List.of(new RealmCommand(
          LDAP_REALM_ID,
          "LDAP",
          name,
          enabled,
          priority,
          attributes)));
      return ldapSettings();
    }

    @Transactional
    public OidcSettingsView saveOidcSettings(OidcSettingsCommand command) {
    if (command == null) {
      command = new OidcSettingsCommand(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null);
    }
    SecurityRealmRecord existing = securityDao.findRealm("oidc").orElse(null);
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (existing != null && existing.attributes() != null) {
      attributes.putAll(existing.attributes());
    }
    if (command.attributes() != null) {
      attributes.putAll(command.attributes());
    }

    attributes.put("source", normalizeSource(defaultString(
        command.source(),
        defaultString(asString(attributes.get("source")), "OIDC"))));
    attributes.put("nexusRealm", OIDC_REALM);

    String issuer = firstNonBlank(
        command.issuer(),
        command.issuerUri(),
        asString(firstPresent(attributes, "issuer", "issuerUri")));
    String issuerUri = firstNonBlank(
        command.issuerUri(),
        command.issuer(),
        asString(firstPresent(attributes, "issuerUri", "issuer")));
    String jwksUri = firstNonBlank(
        command.jwksUri(),
        asString(firstPresent(attributes, "jwksUri", "jwks_url", "jwkSetUri")));
    String audience = firstNonBlank(
        command.audience(),
        command.clientId(),
        asString(firstPresent(attributes, "audience", "clientId")));
    String clientId = firstNonBlank(
        command.clientId(),
        command.audience(),
        asString(firstPresent(attributes, "clientId", "audience")));
    String clientSecret = blankToNull(command.clientSecret());
    String authorizationEndpoint = firstNonBlank(
        command.authorizationEndpoint(),
        asString(firstPresent(attributes, "authorizationEndpoint", "authorizationEndpointUri", "authorization_endpoint")));
    String tokenEndpoint = firstNonBlank(
        command.tokenEndpoint(),
        asString(firstPresent(attributes, "tokenEndpoint", "tokenEndpointUri", "token_endpoint")));
    String redirectUri = firstNonBlank(
        command.redirectUri(),
        asString(firstPresent(attributes, "redirectUri", "redirect_uri")));
    String scopes = firstNonBlank(
        command.scopes(),
        asString(firstPresent(attributes, "scopes", "scope")));

    putText(attributes, "issuer", issuer);
    putText(attributes, "issuerUri", issuerUri);
    putText(attributes, "jwksUri", jwksUri);
    putText(attributes, "jwkSetUri", jwksUri);
    putText(attributes, "audience", audience);
    putText(attributes, "clientId", clientId);
    if (clientSecret != null && !PASSWORD_PLACEHOLDER.equals(clientSecret)) {
      putText(attributes, "clientSecret", clientSecret);
    }
    putText(attributes, "authorizationEndpoint", authorizationEndpoint);
    putText(attributes, "tokenEndpoint", tokenEndpoint);
    putText(attributes, "redirectUri", redirectUri);
    putText(attributes, "scopes", scopes);
    putText(attributes, "scope", scopes);
    putText(attributes, "userIdClaim", firstNonBlank(command.userIdClaim(), asString(attributes.get("userIdClaim"))));
    putText(attributes, "firstNameClaim", firstNonBlank(command.firstNameClaim(), asString(attributes.get("firstNameClaim"))));
    putText(attributes, "lastNameClaim", firstNonBlank(command.lastNameClaim(), asString(attributes.get("lastNameClaim"))));
    putText(attributes, "emailClaim", firstNonBlank(command.emailClaim(), asString(attributes.get("emailClaim"))));
    putText(attributes, "groupsClaim", firstNonBlank(command.groupsClaim(), asString(attributes.get("groupsClaim"))));
    putText(attributes, "rolesClaim", firstNonBlank(command.rolesClaim(), asString(attributes.get("rolesClaim"))));
    putInteger(attributes, "clockSkewSeconds",
        firstNonNull(command.clockSkewSeconds(), asInteger(attributes.get("clockSkewSeconds"))));
    putInteger(attributes, "jwksCacheSeconds",
        firstNonNull(command.jwksCacheSeconds(), asInteger(attributes.get("jwksCacheSeconds"))));

    boolean enabled = command.enabled() == null ? existing != null && existing.enabled() : command.enabled();
    if (enabled && blankToNull(asString(firstPresent(attributes, "jwksUri", "jwks_url", "jwkSetUri"))) == null) {
      throw new SecurityValidationException("jwksUri is required when OIDC realm is enabled");
    }
    int priority = command.priority() == null ? existing == null ? 20 : existing.priority() : command.priority();
    saveRealms(List.of(new RealmCommand(
        "oidc",
        "OIDC",
        "OIDC",
        enabled,
        priority,
        attributes)));
    return oidcSettings();
  }

  @Transactional(readOnly = true)
  public AnonymousSettingsView anonymousSettings() {
    return securityDao.findAnonymousConfig()
        .map(this::toAnonymousSettingsView)
        .orElseGet(() -> new AnonymousSettingsView(
            false,
            DEFAULT_SOURCE,
            DEFAULT_ANONYMOUS_USER_ID,
            DEFAULT_ANONYMOUS_REALM_NAME));
  }

  @Transactional
  public AnonymousSettingsView saveAnonymousSettings(AnonymousSettingsCommand command) {
    if (command == null) {
      command = new AnonymousSettingsCommand(null, null, null, null);
    }
    String realmName = defaultString(command.realmName(), DEFAULT_ANONYMOUS_REALM_NAME);
    validateAnonymousRealmName(realmName);
    String userSource = DEFAULT_SOURCE;
    String userId = defaultString(command.userId(), DEFAULT_ANONYMOUS_USER_ID);
    securityDao.upsertAnonymousConfig(new SecurityAnonymousConfigRecord(
        Boolean.TRUE.equals(command.enabled()),
        userSource,
        userId,
        realmName));
    invalidateAuthorizationCacheAfterCommit();
    return anonymousSettings();
  }

  @Transactional(readOnly = true)
  public List<RepositoryTargetView> listRepositoryTargets() {
    return securityDao.listRepositoryTargets().stream().map(this::toRepositoryTargetView).toList();
  }

  @Transactional(readOnly = true)
  public Optional<RepositoryTargetView> findRepositoryTarget(String targetId) {
    return securityDao.findRepositoryTarget(requireText(targetId, "targetId")).map(this::toRepositoryTargetView);
  }

  @Transactional
  public RepositoryTargetView saveRepositoryTarget(RepositoryTargetCommand command) {
    String targetId = requireText(command.targetId(), "targetId");
    List<String> pathPatterns = normalizeList(command.pathPatterns());
    SecurityRepositoryTargetRecord record = new SecurityRepositoryTargetRecord(
        null,
        targetId,
        defaultString(command.name(), targetId),
        defaultString(command.format(), "*").toLowerCase(Locale.ROOT),
        blankToNull(command.contentExpression()),
        Map.of("patterns", pathPatterns),
        copyMap(command.attributes()));
    securityDao.upsertRepositoryTarget(record);
    invalidateAuthorizationCacheAfterCommit();
    return toRepositoryTargetView(securityDao.findRepositoryTarget(targetId).orElseThrow());
  }

  @Transactional
  public void deleteRepositoryTarget(String targetId) {
    int removed = securityDao.deleteRepositoryTarget(requireText(targetId, "targetId"));
    if (removed == 0) {
      throw new SecurityValidationException("Repository target not found: " + targetId);
    }
    invalidateAuthorizationCacheAfterCommit();
  }

  @Transactional(readOnly = true)
  public List<ApiKeyView> listApiKeys() {
    return securityDao.listApiKeys().stream().map(this::toApiKeyView).toList();
  }

  @Transactional(readOnly = true)
  public List<ApiKeyView> listApiKeysForOwner(String ownerSource, String ownerUserId) {
    return securityDao.listApiKeysForOwner(
            normalizeSource(ownerSource),
            requireText(ownerUserId, "ownerUserId"))
        .stream()
        .map(this::toApiKeyView)
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<ApiKeyView> findApiKey(long id) {
    return securityDao.findApiKey(id).map(this::toApiKeyView);
  }

  @Transactional(readOnly = true)
  public Optional<ApiKeyView> findApiKeyForOwner(long id, String ownerSource, String ownerUserId) {
    return securityDao.findApiKeyForOwner(
            id,
            normalizeSource(ownerSource),
            requireText(ownerUserId, "ownerUserId"))
        .map(this::toApiKeyView);
  }

  @Transactional
  public CreatedApiKeyView createApiKey(ApiKeyCommand command) {
    return createApiKey(command, true);
  }

  private CreatedApiKeyView createApiKey(ApiKeyCommand command, boolean reuseExisting) {
    if (command == null) {
      command = new ApiKeyCommand(null, null, null, null, null, null, null, null, null);
    }
    String domain = defaultString(command.domain(), "nexus");
    String ownerSource = normalizeSource(command.ownerSource());
    String ownerUserId = requireText(command.ownerUserId(), "ownerUserId");
    String generatedToken = null;
    String hash = blankToNull(command.apiKeyHash());
    String tokenPrefix = null;
    String encryptedPayload = blankToNull(command.encryptedPayload());
    if (hash == null) {
      if (reuseExisting) {
        Optional<CreatedApiKeyView> existing = existingApiKeyToken(domain, ownerSource, ownerUserId);
        if (existing.isPresent()) {
          return existing.get();
        }
      }
      String rawToken = randomToken();
      generatedToken = domain + "." + rawToken;
      hash = SecurityHashing.sha256(rawToken);
      tokenPrefix = generatedToken.substring(0, Math.min(12, generatedToken.length()));
      encryptedPayload = ApiKeyTokenPayloads.encryptRawToken(rawToken);
    }
    ApiKeyRecord record = new ApiKeyRecord(
        null,
        domain,
        ownerSource,
        ownerUserId,
        blankToNull(command.displayName()),
        defaultString(command.status(), "ACTIVE"),
        hash,
        tokenPrefix,
        Map.of("values", normalizeList(command.scopes())),
        encryptedPayload == null ? "" : encryptedPayload,
        null,
        null,
        command.expiresAt(),
        null);
    securityDao.upsertApiKey(record);
    evictAuthCachesAfterCommit();
    ApiKeyView view = securityDao.findApiKey(domain, ownerSource, ownerUserId)
        .map(this::toApiKeyView)
        .orElseThrow();
    return new CreatedApiKeyView(view, generatedToken);
  }

  private Optional<CreatedApiKeyView> existingApiKeyToken(
      String domain,
      String ownerSource,
      String ownerUserId) {
    return securityDao.findApiKey(domain, ownerSource, ownerUserId)
        .flatMap(record -> ApiKeyTokenPayloads.decryptRawToken(record.encryptedPayload())
            .filter(rawToken -> SecurityHashing.sha256(rawToken).equals(record.apiKeyHash()))
            .map(rawToken -> new CreatedApiKeyView(toApiKeyView(record), domain + "." + rawToken)));
  }

  @Transactional
  public CreatedApiKeyView createApiKeyForOwner(
      String ownerSource,
      String ownerUserId,
      ApiKeyCommand command) {
    if (command == null) {
      command = new ApiKeyCommand(null, null, null, null, null, null, null, null, null);
    }
    return createApiKey(new ApiKeyCommand(
        command.domain(),
        normalizeSource(ownerSource),
        requireText(ownerUserId, "ownerUserId"),
        command.displayName(),
        "ACTIVE",
        command.scopes(),
        command.expiresAt(),
        null,
        null));
  }

  @Transactional
  public CreatedApiKeyView resetApiKey(long id) {
    ApiKeyView existing = findApiKey(id)
        .orElseThrow(() -> new SecurityValidationException("API key not found: " + id));
    return resetApiKey(existing);
  }

  @Transactional
  public CreatedApiKeyView resetApiKeyForOwner(long id, String ownerSource, String ownerUserId) {
    ApiKeyView existing = findApiKeyForOwner(id, ownerSource, ownerUserId)
        .orElseThrow(() -> new SecurityValidationException("API key not found: " + id));
    return resetApiKey(existing);
  }

  private CreatedApiKeyView resetApiKey(ApiKeyView existing) {
    return createApiKey(new ApiKeyCommand(
        existing.domain(),
        existing.ownerSource(),
        existing.ownerUserId(),
        existing.displayName(),
        existing.status(),
        existing.scopes(),
        existing.expiresAt(),
        null,
        null),
        false);
  }

  @Transactional
  public void deleteApiKey(long id) {
    int removed = securityDao.deleteApiKey(id);
    if (removed == 0) {
      throw new SecurityValidationException("API key not found: " + id);
    }
    evictAuthCachesAfterCommit();
  }

  @Transactional
  public void deleteApiKeyForOwner(long id, String ownerSource, String ownerUserId) {
    findApiKeyForOwner(id, ownerSource, ownerUserId)
        .orElseThrow(() -> new SecurityValidationException("API key not found: " + id));
    deleteApiKey(id);
  }

  @Transactional
  public boolean deleteApiKeyForOwner(String domain, String ownerSource, String ownerUserId) {
    Optional<ApiKeyView> apiKey = listApiKeysForOwner(ownerSource, ownerUserId).stream()
        .filter(candidate -> candidate.domain().equals(defaultString(domain, "nexus")))
        .findFirst();
    if (apiKey.isEmpty()) {
      return false;
    }
    deleteApiKey(apiKey.get().id());
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public AccessDecision decide(PermissionSubject subject, RepositoryPermission permission) {
    if (subject == null || permission == null) {
      return AccessDecision.deny("missing subject or permission");
    }
    return decide(subject, repositoryPermissionString(permission), permission);
  }

  @Transactional(readOnly = true)
  public AccessDecision decide(PermissionSubject subject, String requestedPermission) {
    if (subject == null || requestedPermission == null || requestedPermission.isBlank()) {
      return AccessDecision.deny("missing subject or permission");
    }
    return decide(subject, requestedPermission.trim(), null);
  }

  @Transactional(readOnly = true)
  public List<String> listEffectivePermissions(PermissionSubject subject) {
    if (subject == null) {
      return List.of();
    }
    SecurityCatalog catalog = currentSecurityCatalog().orElse(null);
    if (catalog != null) {
      Set<String> roleIds = catalog.effectiveRoleIds(subject);
      if (roleIds.isEmpty()) {
        return List.of();
      }
      List<SecurityPrivilegeRecord> grantedPrivileges = catalog.privilegesForRoles(roleIds);
      Map<String, SecurityRepositoryTargetRecord> repositoryTargets = catalog.repositoryTargets();
      return catalog.privileges().stream()
          .map(this::toPermission)
          .map(SecurityManagementService::blankToNull)
          .filter(permission -> permission != null)
          .map(permission -> permission.toLowerCase(Locale.ROOT))
          .filter(permission -> permissionAllowed(grantedPrivileges, permission, null, repositoryTargets))
          .distinct()
          .sorted()
          .toList();
    }
    return securityDao.listPrivileges().stream()
        .map(this::toPermission)
        .map(SecurityManagementService::blankToNull)
        .filter(permission -> permission != null)
        .map(permission -> permission.toLowerCase(Locale.ROOT))
        .filter(permission -> decide(subject, permission).allowed())
        .distinct()
        .sorted()
        .toList();
  }

  private AccessDecision decide(
      PermissionSubject subject,
      String requestedPermission,
      RepositoryPermission repositoryPermission) {
    AuthorizationSnapshot snapshot = authorizationSnapshot(subject);
    if (snapshot.roleIds().isEmpty()) {
      return AccessDecision.deny("no roles assigned");
    }
    if (permissionAllowed(
        snapshot.privileges(),
        requestedPermission,
        repositoryPermission,
        snapshot.repositoryTargets())) {
      return AccessDecision.allow();
    }
    return AccessDecision.deny("missing permission " + requestedPermission);
  }

  private boolean permissionAllowed(
      List<SecurityPrivilegeRecord> privileges,
      String requestedPermission,
      RepositoryPermission repositoryPermission,
      Map<String, SecurityRepositoryTargetRecord> repositoryTargets) {
    for (SecurityPrivilegeRecord privilege : privileges) {
      String granted = toPermission(privilege);
      if (wildcardMatches(granted, requestedPermission)) {
        return true;
      }
      if (repositoryPermission != null
          && repositoryContentSelectorAllows(privilege, repositoryPermission, repositoryTargets)) {
        return true;
      }
    }
    return false;
  }

  private AuthorizationSnapshot authorizationSnapshot(PermissionSubject subject) {
    SecurityCatalog catalog = currentSecurityCatalog().orElse(null);
    if (catalog != null) {
      Set<String> roleIds = catalog.effectiveRoleIds(subject);
      return new AuthorizationSnapshot(
          roleIds,
          catalog.privilegesForRoles(roleIds),
          catalog.repositoryTargets()).normalized();
    }
    if (authorizationCache == null) {
      return buildAuthorizationSnapshot(subject);
    }
    return authorizationCache.getOrLoad(subject, () -> buildAuthorizationSnapshot(subject));
  }

  private AuthorizationSnapshot buildAuthorizationSnapshot(PermissionSubject subject) {
    Set<String> roleIds = effectiveRoleIds(subject);
    Map<String, SecurityRepositoryTargetRecord> repositoryTargets = new LinkedHashMap<>();
    for (SecurityRepositoryTargetRecord target : securityDao.listRepositoryTargets()) {
      repositoryTargets.put(target.targetId(), target);
    }
    return new AuthorizationSnapshot(
        roleIds,
        securityDao.listPrivilegesForRoles(List.copyOf(roleIds)),
        repositoryTargets);
  }

  private UserView toUserView(SecurityUserRecord record) {
    return new UserView(
        record.id(),
        record.source(),
        record.userId(),
        record.firstName(),
        record.lastName(),
        record.email(),
        record.status(),
        record.externalId(),
        !DEFAULT_SOURCE.equals(record.source()),
        securityDao.listUserRoleIds(record.id()),
        record.attributes());
  }

  private boolean adminBootstrapRequired() {
    return !hasAdministrativeUser();
  }

  private boolean hasAdministrativeUser() {
    return listUsers(null, null).stream()
        .filter(user -> "ACTIVE".equalsIgnoreCase(defaultString(user.status(), "ACTIVE")))
        .anyMatch(this::hasAdministrativePermission);
  }

  private boolean hasAdministrativePermission(UserView user) {
    PermissionSubject subject = new PermissionSubject(
        user.source(),
        user.userId(),
        Set.copyOf(user.roles()),
        null);
    return decide(subject, "nexus:*").allowed();
  }

  private RoleView toRoleView(SecurityRoleRecord record) {
    return new RoleView(
        record.roleId(),
        record.source(),
        record.name(),
        record.description(),
        record.readOnly(),
        securityDao.listRolePrivilegeIds(record.roleId()),
        securityDao.listRoleChildIds(record.roleId()),
        record.attributes());
  }

  private PrivilegeView toPrivilegeView(SecurityPrivilegeRecord record) {
    return new PrivilegeView(
        record.privilegeId(),
        record.name(),
        record.description(),
        record.type(),
        record.readOnly(),
        record.properties(),
        toPermission(record));
  }

  private RealmView toRealmView(SecurityRealmRecord record) {
    return new RealmView(
        record.id(),
        record.realmId(),
        record.type(),
        record.name(),
        record.enabled(),
        record.priority(),
        record.attributes());
  }

    private OidcSettingsView toOidcSettingsView(SecurityRealmRecord record) {
      Map<String, Object> attributes = copyMap(record.attributes());
      String clientSecret = firstNonBlank(asString(firstPresent(attributes, "clientSecret", "client_secret"))) == null
          ? null
          : PASSWORD_PLACEHOLDER;
      attributes.remove("clientSecret");
      attributes.remove("client_secret");
      return new OidcSettingsView(
          record.realmId(),
        record.type(),
        record.name(),
        record.enabled(),
        record.priority(),
        normalizeSource(defaultString(asString(attributes.get("source")), "OIDC")),
        asString(firstPresent(attributes, "issuer", "issuerUri")),
        asString(firstPresent(attributes, "issuerUri", "issuer")),
        asString(firstPresent(attributes, "jwksUri", "jwks_url", "jwkSetUri")),
        asString(firstPresent(attributes, "audience", "clientId")),
        asString(firstPresent(attributes, "clientId", "audience")),
        clientSecret,
        asString(firstPresent(attributes, "authorizationEndpoint", "authorizationEndpointUri", "authorization_endpoint")),
        asString(firstPresent(attributes, "tokenEndpoint", "tokenEndpointUri", "token_endpoint")),
        asString(firstPresent(attributes, "redirectUri", "redirect_uri")),
        asString(firstPresent(attributes, "scopes", "scope")),
        asString(attributes.get("userIdClaim")),
        asString(attributes.get("firstNameClaim")),
        asString(attributes.get("lastNameClaim")),
        asString(attributes.get("emailClaim")),
        asString(attributes.get("groupsClaim")),
        asString(attributes.get("rolesClaim")),
        asInteger(attributes.get("clockSkewSeconds")),
          asInteger(attributes.get("jwksCacheSeconds")),
          attributes);
    }

    private LdapSettingsView toLdapSettingsView(SecurityRealmRecord record) {
      Map<String, Object> attributes = copyMap(record.attributes());
      Map<String, Object> urlParts = ldapUrlParts(asString(attributes.get("url")));
      String protocol = firstNonBlank(asString(attributes.get("protocol")), asString(urlParts.get("protocol")), "ldap");
      Integer port = asInteger(firstPresent(attributes, "port"));
      if (port == null) {
        port = asInteger(urlParts.get("port"));
      }
      String authPassword = firstNonBlank(asString(firstPresent(
          attributes,
          "authPassword",
          "managerPassword",
          "systemPassword",
          "bindPassword"))) == null ? null : PASSWORD_PLACEHOLDER;
      attributes.remove("authPassword");
      attributes.remove("managerPassword");
      attributes.remove("systemPassword");
      attributes.remove("bindPassword");
      return new LdapSettingsView(
          record.realmId(),
          record.type(),
          record.name(),
          record.enabled(),
          record.priority(),
          normalizeSource(defaultString(asString(attributes.get("source")), "LDAP")),
          asString(attributes.get("url")),
          protocol,
          firstNonBlank(asString(attributes.get("host")), asString(urlParts.get("host"))),
          port,
          asBoolean(attributes.get("useTrustStore")),
          firstNonBlank(asString(attributes.get("searchBase")), asString(urlParts.get("searchBase"))),
          asString(attributes.get("authScheme")),
          asString(attributes.get("authRealm")),
          asString(firstPresent(attributes, "authUsername", "managerDn", "systemUsername", "bindDn")),
          authPassword,
          asInteger(attributes.get("connectionTimeout")),
          asInteger(attributes.get("connectionRetryDelay")),
          asInteger(attributes.get("maxIncidentsCount")),
          asString(firstPresent(attributes, "userBaseDn", "userSearchBase")),
          asBoolean(attributes.get("userSubtree")),
          asString(attributes.get("userObjectClass")),
          asString(attributes.get("userLdapFilter")),
          asString(firstPresent(attributes, "userIdAttribute", "externalIdAttribute")),
          asString(firstPresent(attributes, "userRealNameAttribute", "firstNameAttribute")),
          asString(attributes.get("userMemberOfAttribute")),
          asString(firstPresent(attributes, "userEmailAddressAttribute", "emailAttribute")),
          asString(attributes.get("userPasswordAttribute")),
          asBoolean(attributes.get("ldapGroupsAsRoles")),
          asString(attributes.get("groupType")),
          asString(firstPresent(attributes, "groupBaseDn", "groupSearchBase")),
          asBoolean(attributes.get("groupSubtree")),
          asString(firstPresent(attributes, "groupIdAttribute", "groupNameAttribute")),
          asString(attributes.get("groupMemberAttribute")),
          asString(attributes.get("groupMemberFormat")),
          asString(attributes.get("groupObjectClass")),
          attributes);
    }

    private RepositoryTargetView toRepositoryTargetView(SecurityRepositoryTargetRecord record) {
    return new RepositoryTargetView(
        record.id(),
        record.targetId(),
        record.name(),
        record.format(),
        record.contentExpression(),
        stringList(record.pathPatterns().get("patterns")),
        record.attributes());
  }

  private AnonymousSettingsView toAnonymousSettingsView(SecurityAnonymousConfigRecord record) {
    String realmName = defaultString(record.realmName(), DEFAULT_ANONYMOUS_REALM_NAME);
    return new AnonymousSettingsView(
        record.enabled(),
        DEFAULT_SOURCE,
        defaultString(record.userId(), DEFAULT_ANONYMOUS_USER_ID),
        realmName);
  }

  private boolean isAnonymousUser(String userId) {
    AnonymousSettingsView settings = anonymousSettings();
    return settings.enabled() && requireText(userId, "userId").equals(settings.userId());
  }

  private ApiKeyView toApiKeyView(ApiKeyRecord record) {
    return new ApiKeyView(
        record.id(),
        record.domain(),
        record.ownerSource(),
        record.ownerUserId(),
        record.displayName(),
        record.status(),
        record.tokenPrefix(),
        stringList(record.scopes().get("values")),
        record.createdAt(),
        record.updatedAt(),
        record.expiresAt(),
        record.lastUsedAt());
  }

  private void validateRoleReferences(
      String roleId,
      List<String> privilegeIds,
      List<String> childRoleIds) {
    for (String privilegeId : privilegeIds) {
      if (securityDao.findPrivilege(privilegeId).isEmpty()) {
        throw new SecurityValidationException("Privilege not found: " + privilegeId);
      }
    }
    for (String childRoleId : childRoleIds) {
      if (roleId.equals(childRoleId)) {
        throw new SecurityValidationException("Role cannot contain itself: " + roleId);
      }
      if (securityDao.findRole(childRoleId).isEmpty()) {
        throw new SecurityValidationException("Role not found: " + childRoleId);
      }
      if (roleContains(childRoleId, roleId, new HashSet<>())) {
        throw new SecurityValidationException("Role cannot contain itself through child role: " + childRoleId);
      }
    }
  }

  private void validateUserRoleReferences(List<String> roleIds) {
    for (String roleId : roleIds) {
      if (securityDao.findRole(roleId).isEmpty()) {
        throw new SecurityValidationException("Role not found: " + roleId);
      }
    }
  }

  private boolean roleContains(String currentRoleId, String targetRoleId, Set<String> visited) {
    if (!visited.add(currentRoleId)) {
      return false;
    }
    for (String childRoleId : securityDao.listRoleChildIds(currentRoleId)) {
      if (targetRoleId.equals(childRoleId) || roleContains(childRoleId, targetRoleId, visited)) {
        return true;
      }
    }
    return false;
  }

  private Set<String> effectiveRoleIds(PermissionSubject subject) {
    Set<String> roles = new LinkedHashSet<>();
    if (subject.groupIds() != null) {
      roles.addAll(subject.groupIds());
    }
    if (subject.userId() != null && !subject.userId().isBlank()) {
      roles.addAll(securityDao.listUserRoleIds(normalizeSource(subject.source()), subject.userId()));
    }
    ArrayDeque<String> queue = new ArrayDeque<>(roles);
    while (!queue.isEmpty()) {
      String roleId = queue.removeFirst();
      for (String child : securityDao.listRoleChildIds(roleId)) {
        if (roles.add(child)) {
          queue.addLast(child);
        }
      }
    }
    return roles;
  }

  private String repositoryPermissionString(RepositoryPermission permission) {
    String format = permission.format() == null
        ? "*"
        : ContentSelectorExpressionEvaluator.nexusFormat(permission.format().name());
    String repository = defaultString(permission.repository(), "*");
    PermissionAction action = permission.action() == null ? PermissionAction.READ : permission.action();
    String domain = action == PermissionAction.ADMIN ? "repository-admin" : "repository-view";
    return "nexus:" + domain + ":" + format + ":" + repository + ":" + action.nexusAction();
  }

  private String toPermission(SecurityPrivilegeRecord privilege) {
    Map<String, Object> properties = privilege.properties();
    String type = defaultString(privilege.type(), "wildcard");
    return switch (type) {
      case "wildcard" -> defaultString(asString(firstPresent(properties, "pattern", "permission", "wildcard")), "nexus:*");
      case "application" -> "nexus:"
          + defaultString(asString(firstPresent(properties, "domain", "application")), "*")
          + ":" + actions(properties);
      case "repository-view" -> "nexus:repository-view:"
          + defaultString(formatName(properties), "*")
          + ":" + defaultString(repositoryName(properties), "*")
          + ":" + actions(properties);
      case "repository-admin" -> "nexus:repository-admin:"
          + defaultString(formatName(properties), "*")
          + ":" + defaultString(repositoryName(properties), "*")
          + ":" + actions(properties);
      case "repository-content-selector" -> contentSelectorPermission(properties);
      default -> defaultString(asString(firstPresent(properties, "permission", "pattern")), type + ":*");
    };
  }

  private String contentSelectorPermission(Map<String, Object> properties) {
    String selector = defaultString(contentSelectorName(properties), "*");
    RepositorySelectorParts repositorySelector = repositorySelector(properties);
    String format = repositorySelector.format();
    String repository = repositorySelector.repository();
    return "nexus:repository-content-selector:" + selector + ":" + format + ":" + repository + ":" + actions(properties);
  }

  private boolean repositoryContentSelectorAllows(
      SecurityPrivilegeRecord privilege,
      RepositoryPermission requested,
      Map<String, SecurityRepositoryTargetRecord> repositoryTargets) {
    if (!"repository-content-selector".equals(privilege.type())) {
      return false;
    }
    Map<String, Object> properties = privilege.properties();
    String selector = contentSelectorName(properties);
    if (selector == null) {
      return false;
    }
    SecurityRepositoryTargetRecord contentSelector = repositoryTargets == null ? null : repositoryTargets.get(selector);
    if (contentSelector == null) {
      return false;
    }
    String requestedFormat = requested.format() == null
        ? "*"
        : ContentSelectorExpressionEvaluator.nexusFormat(requested.format().name());
    if (!formatPartMatches(contentSelector.format(), requestedFormat)) {
      return false;
    }
    RepositorySelectorParts repositorySelector = repositorySelector(properties);
    if (!formatPartMatches(repositorySelector.format(), requestedFormat)) {
      return false;
    }
    if (!partMatches(repositorySelector.repository(), defaultString(requested.repository(), "*"))) {
      return false;
    }
    PermissionAction action = requested.action() == null ? PermissionAction.READ : requested.action();
    if (!partMatches(actions(properties), action.nexusAction())) {
      return false;
    }
    return ContentSelectorExpressionEvaluator.matches(
        contentSelector.contentExpression(),
        requested.repository(),
        requestedFormat,
        defaultString(requested.pathPattern(), ""));
  }

  private void invalidateAuthorizationCacheAfterCommit() {
    if (authorizationCache != null) {
      authorizationCache.invalidateAllAfterCommit();
    }
    if (securityCatalogCache != null) {
      securityCatalogCache.refreshAfterCommit();
    }
    evictAuthCachesAfterCommit();
  }

  private Optional<SecurityCatalog> currentSecurityCatalog() {
    if (securityCatalogCache == null) {
      return Optional.empty();
    }
    return securityCatalogCache.current();
  }

  /**
   * Drop every cached {@link AuthenticatedSubject}. Called after security mutations that could
   * change a cached subject's identity, password hash, role list, or privilege grants: user/role/
   * privilege changes, content-selector changes, and api-key revocation or reset. Without this, a
   * revoked, downgraded, or password-rotated subject keeps authenticating until the cache TTL elapses.
   *
   * <p>Bulk eviction is used because cache entries are keyed by presented secret material: api-key
   * auth uses sha256(presentedToken), and Basic auth uses HMAC(username + password). Building a
   * reverse index is more complexity than warranted for these comparatively infrequent admin
   * operations.
   */
  private void evictAuthCachesAfterCommit() {
    if (apiKeyAuthCache == null && basicAuthCache == null) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      evictAuthCaches();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        evictAuthCaches();
      }
    });
  }

  private void evictAuthCaches() {
    if (apiKeyAuthCache != null) {
      apiKeyAuthCache.evictAll();
    }
    if (basicAuthCache != null) {
      basicAuthCache.evictAll();
    }
  }

  private static boolean wildcardMatches(String granted, String requested) {
    String[] grantedParts = granted.split(":");
    String[] requestedParts = requested.split(":");
    int max = Math.max(grantedParts.length, requestedParts.length);
    for (int i = 0; i < max; i++) {
      String grantedPart = i < grantedParts.length ? grantedParts[i] : "*";
      String requestedPart = i < requestedParts.length ? requestedParts[i] : "*";
      boolean matches;
      if (isFormatSegment(grantedParts, requestedParts, i)) {
        matches = formatPartMatches(grantedPart, requestedPart);
      } else if (isActionSegment(grantedParts, requestedParts, i)) {
        matches = actionPartMatches(grantedPart, requestedPart);
      } else {
        matches = partMatches(grantedPart, requestedPart);
      }
      if (!matches) {
        return false;
      }
    }
    return true;
  }

  private static boolean isFormatSegment(String[] grantedParts, String[] requestedParts, int index) {
    String grantedDomain = grantedParts.length > 1 ? grantedParts[1] : "";
    String requestedDomain = requestedParts.length > 1 ? requestedParts[1] : "";
    String domain = grantedDomain.equals(requestedDomain) ? grantedDomain : requestedDomain;
    return switch (domain) {
      case "repository-view", "repository-admin" -> index == 2;
      case "repository-content-selector" -> index == 3;
      default -> false;
    };
  }

  private static boolean isActionSegment(String[] grantedParts, String[] requestedParts, int index) {
    String grantedDomain = grantedParts.length > 1 ? grantedParts[1] : "";
    String requestedDomain = requestedParts.length > 1 ? requestedParts[1] : "";
    String domain = grantedDomain.equals(requestedDomain) ? grantedDomain : requestedDomain;
    return switch (domain) {
      case "repository-view", "repository-admin" -> index == 4;
      case "repository-content-selector" -> index == 5;
      default -> index == 2 && "nexus".equals(grantedParts.length > 0 ? grantedParts[0] : "")
          && "nexus".equals(requestedParts.length > 0 ? requestedParts[0] : "");
    };
  }

  private static boolean partMatches(String grantedPart, String requestedPart) {
    String requested = defaultString(requestedPart, "*").toLowerCase(Locale.ROOT);
    for (String option : defaultString(grantedPart, "*").split(",")) {
      String normalized = option.trim().toLowerCase(Locale.ROOT);
      if ("*".equals(normalized) || normalized.equals(requested)) {
        return true;
      }
    }
    return false;
  }

  private static boolean formatPartMatches(String grantedPart, String requestedPart) {
    String requested = ContentSelectorExpressionEvaluator.nexusFormat(defaultString(requestedPart, "*"));
    for (String option : defaultString(grantedPart, "*").split(",")) {
      String normalized = ContentSelectorExpressionEvaluator.nexusFormat(option);
      if ("*".equals(normalized) || normalized.equals(requested)) {
        return true;
      }
    }
    return false;
  }

  private static boolean actionPartMatches(String grantedPart, String requestedPart) {
    String requested = normalizeActionPart(requestedPart);
    boolean requestedWildcard = "*".equals(defaultString(requestedPart, "*").trim());
    for (String option : defaultString(grantedPart, "*").split(",")) {
      String normalized = normalizeActionPart(option);
      if (requestedWildcard) {
        if ("*".equals(option.trim())) {
          return true;
        }
        continue;
      }
      if ("*".equals(normalized) || normalized.equals(requested)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeActionPart(String action) {
    String normalized = defaultString(action, "*").trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "all" -> "*";
      case "create" -> "add";
      case "update" -> "edit";
      default -> normalized;
    };
  }

  private static boolean globMatches(String pattern, String value) {
    StringBuilder regex = new StringBuilder();
    for (char c : defaultString(pattern, "*").toCharArray()) {
      if (c == '*') {
        regex.append(".*");
      } else if (".[]{}()+-^$?|\\".indexOf(c) >= 0) {
        regex.append('\\').append(c);
      } else {
        regex.append(c);
      }
    }
    return defaultString(value, "").matches(regex.toString());
  }

  private static String stripLeadingSlashes(String value) {
    String result = defaultString(value, "");
    while (result.startsWith("/")) {
      result = result.substring(1);
    }
    return result;
  }

  private String resolvePasswordHash(UserCommand command) {
    if (command == null) {
      return null;
    }
    if (command.passwordHash() != null && !command.passwordHash().isBlank()) {
      return command.passwordHash();
    }
    if (command.password() == null || command.password().isBlank()) {
      return null;
    }
    return SecurityHashing.hashPassword(command.password());
  }

  private static String actions(Map<String, Object> properties) {
    return defaultString(asString(firstPresent(properties, "actions", "action")), "*");
  }

  private static RepositorySelectorParts repositorySelector(Map<String, Object> properties) {
    String format = defaultString(formatName(properties), "*");
    String repository = defaultString(repositoryName(properties), "*");
    if (repository.startsWith("*-")) {
      format = repository.substring(2);
      repository = "*";
    } else if (repository.contains("/") && ("*".equals(format) || format.isBlank())) {
      String[] parts = repository.split("/", 2);
      format = parts[0];
      repository = parts[1];
    }
    return new RepositorySelectorParts(
        ContentSelectorExpressionEvaluator.nexusFormat(format),
        defaultString(repository, "*"));
  }

  private record RepositorySelectorParts(String format, String repository) {
  }

  private static Object firstPresent(Map<String, Object> properties, String... keys) {
    if (properties == null) {
      return null;
    }
    for (String key : keys) {
      Object value = properties.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String normalized = blankToNull(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private static <T> T firstNonNull(T first, T second) {
    return first == null ? second : first;
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

  private static String asString(Object value) {
    if (value instanceof Collection<?> values) {
      return String.join(",", values.stream().map(String::valueOf).toList());
    }
    return value == null ? null : String.valueOf(value);
  }

    private static Integer asInteger(Object value) {
      if (value instanceof Number number) {
        return number.intValue();
      }
    String text = blankToNull(asString(value));
    if (text == null) {
      return null;
    }
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException ignored) {
      return null;
      }
    }

    private static Boolean asBoolean(Object value) {
      if (value instanceof Boolean bool) {
        return bool;
      }
      if (value instanceof Number number) {
        return number.intValue() != 0;
      }
      String text = blankToNull(asString(value));
      if (text == null) {
        return null;
      }
      return Boolean.parseBoolean(text);
    }

    private static void putText(Map<String, Object> values, String key, String value) {
      String normalized = blankToNull(value);
      if (normalized == null) {
      values.remove(key);
      return;
    }
    values.put(key, normalized);
  }

  private static void putInteger(Map<String, Object> values, String key, Integer value) {
    if (value == null) {
      values.remove(key);
      return;
      }
      values.put(key, value);
    }

    private static void putObject(Map<String, Object> values, String key, Object value) {
      if (value != null) {
        values.put(key, value);
      }
    }

    private static void alias(Map<String, Object> values, String key, Object value) {
      String text = blankToNull(asString(value));
      if (text != null) {
        values.put(key, text);
      }
    }

    private static Map<String, Object> ldapAttributes(
        LdapSettingsCommand command,
        Map<String, Object> existing) {
      Map<String, Object> attributes = new LinkedHashMap<>();
      if (existing != null) {
        attributes.putAll(existing);
      }
      if (command.attributes() != null) {
        attributes.putAll(command.attributes());
      }
      attributes.put("source", normalizeSource(defaultString(command.source(), "LDAP")));
      attributes.put("nexusRealm", LDAP_REALM);
      attributes.put("ldapConfigured", true);
      putObject(attributes, "name", blankToNull(command.name()));
      putObject(attributes, "protocol", blankToNull(command.protocol()));
      putObject(attributes, "host", blankToNull(command.host()));
      putObject(attributes, "port", command.port());
      putObject(attributes, "useTrustStore", command.useTrustStore());
      putObject(attributes, "searchBase", blankToNull(command.searchBase()));
      putObject(attributes, "authScheme", blankToNull(command.authScheme()));
      putObject(attributes, "authRealm", blankToNull(command.authRealm()));
      putObject(attributes, "authUsername", blankToNull(command.authUsername()));
      putObject(attributes, "connectionTimeout", command.connectionTimeout());
      putObject(attributes, "connectionRetryDelay", command.connectionRetryDelay());
      putObject(attributes, "maxIncidentsCount", command.maxIncidentsCount());
      putObject(attributes, "userBaseDn", blankToNull(command.userBaseDn()));
      putObject(attributes, "userSubtree", command.userSubtree());
      putObject(attributes, "userObjectClass", blankToNull(command.userObjectClass()));
      putObject(attributes, "userLdapFilter", blankToNull(command.userLdapFilter()));
      putObject(attributes, "userIdAttribute", blankToNull(command.userIdAttribute()));
      putObject(attributes, "userRealNameAttribute", blankToNull(command.userRealNameAttribute()));
      putObject(attributes, "userMemberOfAttribute", blankToNull(command.userMemberOfAttribute()));
      putObject(attributes, "userEmailAddressAttribute", blankToNull(command.userEmailAddressAttribute()));
      putObject(attributes, "userPasswordAttribute", blankToNull(command.userPasswordAttribute()));
      putObject(attributes, "ldapGroupsAsRoles", command.ldapGroupsAsRoles());
      putObject(attributes, "groupType", blankToNull(command.groupType()));
      putObject(attributes, "groupBaseDn", blankToNull(command.groupBaseDn()));
      putObject(attributes, "groupSubtree", command.groupSubtree());
      putObject(attributes, "groupIdAttribute", blankToNull(command.groupIdAttribute()));
      putObject(attributes, "groupMemberAttribute", blankToNull(command.groupMemberAttribute()));
      putObject(attributes, "groupMemberFormat", blankToNull(command.groupMemberFormat()));
      putObject(attributes, "groupObjectClass", blankToNull(command.groupObjectClass()));

      String authPassword = blankToNull(command.authPassword());
      if (authPassword != null && !PASSWORD_PLACEHOLDER.equals(authPassword)) {
        attributes.put("authPassword", authPassword);
        attributes.put("managerPassword", authPassword);
        attributes.put("systemPassword", authPassword);
        attributes.put("bindPassword", authPassword);
      }

      boolean endpointChanged = command.protocol() != null
          || command.host() != null
          || command.port() != null
          || command.searchBase() != null;
      String url = blankToNull(command.url());
      if (url == null) {
        url = endpointChanged
            ? ldapUrl(attributes)
            : firstNonBlank(asString(attributes.get("url")), ldapUrl(attributes));
      }
      putText(attributes, "url", url);
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
      Integer timeoutSeconds = asInteger(attributes.get("connectionTimeout"));
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

    private static String ldapUrl(Map<String, Object> attributes) {
      String host = blankToNull(asString(attributes.get("host")));
      if (host == null) {
        return null;
      }
      String protocol = defaultString(asString(attributes.get("protocol")), "ldap");
      Integer port = asInteger(attributes.get("port"));
      String searchBase = blankToNull(asString(attributes.get("searchBase")));
      return protocol + "://" + host + (port == null ? "" : ":" + port) + (searchBase == null ? "" : "/" + searchBase);
    }

    private static Map<String, Object> ldapUrlParts(String url) {
      if (blankToNull(url) == null) {
        return Map.of();
      }
      try {
        java.net.URI uri = java.net.URI.create(url);
        Map<String, Object> parts = new LinkedHashMap<>();
        putObject(parts, "protocol", uri.getScheme());
        putObject(parts, "host", uri.getHost());
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
      if (!Boolean.TRUE.equals(asBoolean(attributes.get("ldapGroupsAsRoles")))) {
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

  private static Map<String, Object> copyMap(Map<String, Object> map) {
    return map == null ? Map.of() : new LinkedHashMap<>(map);
  }

  private static List<String> normalizeList(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(SecurityManagementService::blankToNull)
        .filter(value -> value != null)
        .distinct()
        .toList();
  }

  private static List<String> stringList(Object value) {
    if (value instanceof Collection<?> collection) {
      return collection.stream()
          .map(String::valueOf)
          .filter(item -> !item.isBlank())
          .toList();
    }
    if (value instanceof String text && !text.isBlank()) {
      return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }
    return List.of();
  }

  private static List<String> nexusRealmNames(SecurityRealmRecord record) {
    if ("local".equals(record.realmId())) {
      return List.of(NEXUS_AUTHENTICATING_REALM, NEXUS_AUTHORIZING_REALM);
    }
    return List.of(nexusRealmName(record));
  }

  private static String nexusRealmName(SecurityRealmRecord record) {
    Object configured = record.attributes() == null ? null : record.attributes().get("nexusRealm");
    String nexusRealm = configured == null ? null : blankToNull(String.valueOf(configured));
    if (nexusRealm != null) {
      return nexusRealm;
    }
    return switch (record.realmId()) {
      case "local" -> NEXUS_AUTHENTICATING_REALM;
      case "ldap" -> LDAP_REALM;
      case "oidc" -> OIDC_REALM;
      default -> record.realmId();
    };
  }

  private static String internalRealmIdForNexusRealm(String realmName) {
    String normalized = blankToNull(realmName);
    if (normalized == null) {
      return null;
    }
    if ("local".equalsIgnoreCase(normalized)
        || NEXUS_AUTHENTICATING_REALM.equals(normalized)
        || NEXUS_AUTHORIZING_REALM.equals(normalized)) {
      return "local";
    }
    if ("ldap".equalsIgnoreCase(normalized) || LDAP_REALM.equals(normalized)) {
      return "ldap";
    }
    if ("oidc".equalsIgnoreCase(normalized) || OIDC_REALM.equals(normalized)) {
      return "oidc";
    }
    return normalized.toLowerCase(Locale.ROOT);
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

  static String normalizeSource(String source) {
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

  private static void validateAnonymousRealmName(String realmName) {
    String realmId = internalRealmIdForNexusRealm(realmName);
    if (realmId == null || !SUPPORTED_REALMS.contains(realmId)) {
      throw new SecurityValidationException("Realm does not exist: " + realmName);
    }
  }

  private static LdapSettingsCommand emptyLdapSettingsCommand() {
    return new LdapSettingsCommand(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
