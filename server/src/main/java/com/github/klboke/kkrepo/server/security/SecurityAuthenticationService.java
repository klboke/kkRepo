package com.github.klboke.kkrepo.server.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityAuthenticationService {
  private static final String DEFAULT_SOURCE = "Local";
  private static final String DEFAULT_ANONYMOUS_USER_ID = "anonymous";
  private static final String DEFAULT_ANONYMOUS_REALM_NAME = "NexusAuthorizingRealm";
  private static final String DEFAULT_AUTHENTICATED_ROLE_ID = "nx-anonymous";
  static final String BASIC_AUTH_SUPPRESSED_ATTRIBUTE =
      SecurityAuthenticationService.class.getName() + ".BASIC_AUTH_SUPPRESSED";
  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String STATUS_CHANGE_PASSWORD = "CHANGEPASSWORD";
  private static final String JWKS_CACHE_NAMESPACE = "oidc-jwks";
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  private final SecurityDao securityDao;
  private final String tokenHeader;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final OutboundRequestPolicy outboundPolicy;
  private final String defaultAuthenticatedRoleId;
  private final SharedCache sharedCache;
  private final ApiKeyAuthCache apiKeyAuthCache;
  private final BasicAuthCache basicAuthCache;
  private final SecurityCatalogCache securityCatalogCache;

  @Autowired
  public SecurityAuthenticationService(
      SecurityDao securityDao,
      ObjectMapper objectMapper,
      @Value("${kkrepo.security.token-header:X-Nexus-Plus-Token}") String tokenHeader,
      @Value("${kkrepo.security.default-authenticated-role-id:nx-anonymous}") String defaultAuthenticatedRoleId,
      SharedCache sharedCache,
      OutboundRequestPolicy outboundPolicy,
      ApiKeyAuthCache apiKeyAuthCache,
      BasicAuthCache basicAuthCache,
      SecurityCatalogCache securityCatalogCache) {
    this.securityDao = securityDao;
    this.objectMapper = objectMapper;
    this.tokenHeader = tokenHeader;
    this.defaultAuthenticatedRoleId = optionalText(defaultAuthenticatedRoleId);
    this.sharedCache = sharedCache;
    this.outboundPolicy = outboundPolicy;
    this.apiKeyAuthCache = apiKeyAuthCache;
    this.basicAuthCache = basicAuthCache;
    this.securityCatalogCache = securityCatalogCache;
    this.httpClient = HttpClient.newHttpClient();
  }

  public SecurityAuthenticationService(
      SecurityDao securityDao,
      ObjectMapper objectMapper,
      @Value("${kkrepo.security.token-header:X-Nexus-Plus-Token}") String tokenHeader) {
    this(securityDao, objectMapper, tokenHeader, DEFAULT_AUTHENTICATED_ROLE_ID);
  }

  public SecurityAuthenticationService(
      SecurityDao securityDao,
      ObjectMapper objectMapper,
      String tokenHeader,
      String defaultAuthenticatedRoleId) {
    this(securityDao, objectMapper, tokenHeader, defaultAuthenticatedRoleId, null);
  }

  SecurityAuthenticationService(
      SecurityDao securityDao,
      ObjectMapper objectMapper,
      String tokenHeader,
      String defaultAuthenticatedRoleId,
      SecurityCatalogCache securityCatalogCache) {
    this.securityDao = securityDao;
    this.objectMapper = objectMapper;
    this.tokenHeader = tokenHeader;
    this.defaultAuthenticatedRoleId = optionalText(defaultAuthenticatedRoleId);
    this.sharedCache = null;
    this.outboundPolicy = OutboundRequestPolicy.allowPrivateForTests();
    this.apiKeyAuthCache = null;
    this.basicAuthCache = null;
    this.securityCatalogCache = securityCatalogCache;
    this.httpClient = HttpClient.newHttpClient();
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
    Optional<AuthenticatedSubject> apiKey = authenticateApiKey(request);
    if (apiKey.isPresent()) {
      return apiKey;
    }
    Optional<AuthenticatedSubject> oidc = authenticateOidc(request);
    if (oidc.isPresent()) {
      return oidc;
    }
    Optional<AuthenticatedSubject> session = authenticateSession(request);
    if (session.isPresent()) {
      return session;
    }
    if (basicAuthSuppressed(request) && !isBasicLoginRequest(request)) {
      return Optional.empty();
    }
    return basicCredentials(request).flatMap(credentials ->
        authenticateBasic(credentials.username(), credentials.password()));
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticateCargo(HttpServletRequest request) {
    Optional<AuthenticatedSubject> apiKey = authenticateCargoApiKey(request);
    if (apiKey.isPresent()) {
      return apiKey;
    }
    return authenticate(request);
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticatePub(HttpServletRequest request) {
    Optional<AuthenticatedSubject> apiKey = authenticatePubApiKey(request);
    if (apiKey.isPresent()) {
      return apiKey;
    }
    return authenticate(request);
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticateRubygems(HttpServletRequest request) {
    Optional<AuthenticatedSubject> apiKey = authenticateRubygemsApiKey(request);
    if (apiKey.isPresent()) {
      return apiKey;
    }
    return authenticate(request);
  }

  /**
   * Returns whether the request explicitly presented a credential understood by repository
   * authentication. An invalid explicit credential must never silently downgrade to anonymous
   * access, even when anonymous reads are enabled for the repository.
   */
  public boolean hasPresentedCredentials(HttpServletRequest request) {
    if (request == null) {
      return false;
    }
    String authorization = request.getHeader("Authorization");
    return (authorization != null && !authorization.isBlank())
        || headerValue(request, tokenHeader) != null
        || headerValue(request, "X-Nexus-Plus-Api-Key") != null
        || headerValue(request, "X-NuGet-ApiKey") != null;
  }

  /** Authenticates the single decoded credential segment used by Nexus Terraform repository URLs. */
  @Transactional
  public Optional<AuthenticatedSubject> authenticateTerraformUrlToken(String token) {
    if (token == null || token.isBlank()) return Optional.empty();
    Optional<AuthenticatedSubject> apiKey = apiKeyAuthCache == null
        ? resolveApiKey(ApiKeyTokenCandidate.fromPresentedToken(token))
        : apiKeyAuthCache.find("terraform:" + token,
            () -> resolveApiKey(ApiKeyTokenCandidate.fromPresentedToken(token)));
    if (apiKey.isPresent()) return apiKey;
    try {
      String decoded = new String(java.util.Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
      int colon = decoded.indexOf(':');
      if (colon > 0) return authenticateBasic(decoded.substring(0, colon), decoded.substring(colon + 1));
    } catch (IllegalArgumentException ignored) {
      // GenericToken/API-key URL tokens are not necessarily base64 Basic credentials.
    }
    return Optional.empty();
  }

  /**
   * Returns a credential from this request only when it can be replayed through the Terraform URL
   * token authentication path as the already-authenticated subject. Session and OIDC credentials
   * intentionally do not qualify because standalone archive downloads cannot replay them safely.
   */
  @Transactional
  public Optional<String> replayableTerraformUrlToken(
      HttpServletRequest request, AuthenticatedSubject authenticated) {
    if (request == null || authenticated == null) return Optional.empty();
    if (authenticated.apiKeyId() != null) {
      return boundedTerraformToken(apiKeyToken(request));
    }
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      return Optional.empty();
    }
    Optional<AuthenticatedSubject> basic = basicCredentials(request).flatMap(credentials ->
        authenticateBasic(credentials.username(), credentials.password()));
    if (basic.isEmpty()
        || !java.util.Objects.equals(basic.get().source(), authenticated.source())
        || !java.util.Objects.equals(basic.get().userId(), authenticated.userId())) {
      return Optional.empty();
    }
    return boundedTerraformToken(authorization.substring(6).trim());
  }

  private static Optional<String> boundedTerraformToken(String token) {
    if (token == null || token.isBlank() || token.length() > 4096
        || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
      return Optional.empty();
    }
    return Optional.of(token);
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticateCredentials(String username, String password) {
    if (username == null || username.isBlank() || password == null) {
      return Optional.empty();
    }
    return authenticateBasic(username, password);
  }

  @Transactional(readOnly = true)
  public Optional<AuthenticatedSubject> authenticateStoredSubject(
      String source, String userId, String realmId, Long apiKeyId) {
    if (source == null || source.isBlank() || userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    return securityDao.findUser(source, userId)
        .filter(this::activeUser)
        .map(user -> toSubject(user, realmId, null, apiKeyId));
  }

  public Optional<AuthenticatedSubject> authenticateAnonymous() {
    if (securityCatalogCache != null) {
      Optional<SecurityCatalogCache.SecurityCatalog> catalog = securityCatalogCache.current();
      if (catalog.isPresent()) {
        return authenticateAnonymous(catalog.get());
      }
    }
    return authenticateAnonymousFromDatabase();
  }

  private Optional<AuthenticatedSubject> authenticateAnonymous(
      SecurityCatalogCache.SecurityCatalog catalog) {
    SecurityAnonymousConfigRecord config = catalog.anonymousConfig();
    if (config == null || !config.enabled()) {
      return Optional.empty();
    }
    String realmName = defaultString(config.realmName(), DEFAULT_ANONYMOUS_REALM_NAME);
    String userId = defaultString(config.userId(), DEFAULT_ANONYMOUS_USER_ID);
    SecurityUserRecord user = catalog.anonymousUser();
    if (user == null || !userId.equals(user.userId()) || !activeUser(user)) {
      return Optional.empty();
    }
    return Optional.of(toSubject(
        user,
        realmName,
        null,
        null,
        false,
        catalog.userRoleIds(DEFAULT_SOURCE, userId)));
  }

  private Optional<AuthenticatedSubject> authenticateAnonymousFromDatabase() {
    return securityDao.findAnonymousConfig()
        .filter(SecurityAnonymousConfigRecord::enabled)
        .flatMap(config -> {
          String realmName = defaultString(config.realmName(), DEFAULT_ANONYMOUS_REALM_NAME);
          String userId = defaultString(config.userId(), DEFAULT_ANONYMOUS_USER_ID);
          return securityDao.findUser(DEFAULT_SOURCE, userId)
              .filter(this::activeUser)
              .map(user -> toSubject(user, realmName, null, null, false));
        });
  }

  @Transactional(readOnly = true)
  public Optional<SecurityRealmRecord> activeOidcRealm() {
    return activeRealms().stream()
        .filter(realm -> isRealm(realm, "oidc"))
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<SecurityRealmRecord> activeLdapRealm() {
    return activeRealms().stream()
        .filter(realm -> isRealm(realm, "ldap"))
        .findFirst();
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticateOidcToken(String token) {
    if (token == null || token.split("\\.", -1).length != 3) {
      return Optional.empty();
    }
    for (SecurityRealmRecord realm : activeRealms()) {
      if (!isRealm(realm, "oidc")) {
        continue;
      }
      Optional<OidcProfile> profile = validateOidcToken(realm, token);
      if (profile.isPresent()) {
        return Optional.of(oidcSubject(realm, profile.get()));
      }
    }
    return Optional.empty();
  }

  public void storeSessionSubject(HttpServletRequest request, AuthenticatedSubject subject) {
    HttpSession session = request.getSession(true);
    session.removeAttribute(BASIC_AUTH_SUPPRESSED_ATTRIBUTE);
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principalName(subject));
    session.setAttribute(AuthenticatedSubject.SESSION_ATTRIBUTE, newSessionSubject(subject));
  }

  public void clearSessionSubject(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    request.getSession(true).setAttribute(BASIC_AUTH_SUPPRESSED_ATTRIBUTE, Boolean.TRUE);
  }

  private boolean basicAuthSuppressed(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    return session != null && Boolean.TRUE.equals(session.getAttribute(BASIC_AUTH_SUPPRESSED_ATTRIBUTE));
  }

  private boolean isBasicLoginRequest(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri != null && uri.startsWith(contextPath)) {
      uri = uri.substring(contextPath.length());
    }
    return "/internal/security/basic/login".equals(uri);
  }

  private Optional<AuthenticatedSubject> authenticateApiKey(HttpServletRequest request) {
    String token = apiKeyToken(request);
    if (token == null) {
      return Optional.empty();
    }
    if (apiKeyAuthCache == null) {
      return resolveApiKey(ApiKeyTokenCandidate.fromPresentedToken(token));
    }
    return apiKeyAuthCache.find(token, () -> resolveApiKey(ApiKeyTokenCandidate.fromPresentedToken(token)));
  }

  private Optional<AuthenticatedSubject> authenticateCargoApiKey(HttpServletRequest request) {
    for (String token : cargoApiKeyTokens(request)) {
      Optional<AuthenticatedSubject> resolved = apiKeyAuthCache == null
          ? resolveApiKey(ApiKeyTokenCandidate.fromPresentedCargoToken(token))
          : apiKeyAuthCache.find("cargo:" + token,
              () -> resolveApiKey(ApiKeyTokenCandidate.fromPresentedCargoToken(token)));
      if (resolved.isPresent()) {
        return resolved;
      }
    }
    return Optional.empty();
  }

  private Optional<AuthenticatedSubject> authenticatePubApiKey(HttpServletRequest request) {
    String token = bearerToken(request);
    if (token == null) {
      return Optional.empty();
    }
    return apiKeyAuthCache == null
        ? resolveApiKey(ApiKeyTokenCandidate.fromPresentedPubToken(token))
        : apiKeyAuthCache.find("pub:" + token,
            () -> resolveApiKey(ApiKeyTokenCandidate.fromPresentedPubToken(token)));
  }

  private Optional<AuthenticatedSubject> authenticateRubygemsApiKey(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization == null || authorization.isBlank()
        || authorization.regionMatches(true, 0, "Basic ", 0, 6)
        || authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String token = authorization.trim();
    return apiKeyAuthCache == null
        ? resolveApiKey(ApiKeyTokenCandidate.fromPresentedRubygemsToken(token))
        : apiKeyAuthCache.find("rubygems:" + token,
            () -> resolveApiKey(ApiKeyTokenCandidate.fromPresentedRubygemsToken(token)));
  }

  private Optional<AuthenticatedSubject> resolveApiKey(List<ApiKeyTokenCandidate> candidates) {
    Optional<ApiKeyRecord> match = findApiKey(candidates);
    if (match.isEmpty() || !activeApiKey(match.get())) {
      return Optional.empty();
    }
    ApiKeyRecord apiKey = match.get();
    Optional<SecurityUserRecord> owner = securityDao.findUser(apiKey.ownerSource(), apiKey.ownerUserId())
        .filter(this::activeUser);
    if (owner.isEmpty()) {
      securityDao.deleteApiKeysForOwner(apiKey.ownerSource(), apiKey.ownerUserId());
      return Optional.empty();
    }
    securityDao.markApiKeyUsed(apiKey.id(), LocalDateTime.now());
    return Optional.of(toSubject(owner.get(), "api-key", null, apiKey.id()));
  }

  private Optional<ApiKeyRecord> findApiKey(List<ApiKeyTokenCandidate> candidates) {
    for (ApiKeyTokenCandidate candidate : candidates) {
      String hash = SecurityHashing.sha256(candidate.tokenMaterial());
      Optional<ApiKeyRecord> match = candidate.domainScoped()
          ? securityDao.findApiKeyByDomainAndHash(candidate.domain(), hash)
          : securityDao.findApiKeyByHash(hash);
      if (match.isPresent()) {
        return match;
      }
    }
    return Optional.empty();
  }

  private Optional<AuthenticatedSubject> authenticateOidc(HttpServletRequest request) {
    String token = bearerToken(request);
    return authenticateOidcToken(token);
  }

  private Optional<AuthenticatedSubject> authenticateSession(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    Object value = session.getAttribute(AuthenticatedSubject.SESSION_ATTRIBUTE);
    SessionSubject subject = sessionSubject(value);
    if (subject == null || subject.source() == null || subject.userId() == null || subject.userId().isBlank()) {
      return Optional.empty();
    }
    Optional<SecurityUserRecord> user = securityDao.findUser(subject.source(), subject.userId())
        .filter(this::activeUser);
    if (user.isEmpty()) {
      session.removeAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
      session.removeAttribute(AuthenticatedSubject.SESSION_ATTRIBUTE);
      return Optional.empty();
    }
    Set<String> roleIds = new LinkedHashSet<>();
    if (subject.externalRoleIds() != null) {
      roleIds.addAll(subject.externalRoleIds());
    }
    roleIds.addAll(securityDao.listUserRoleIds(user.get().id()));
    addDefaultAuthenticatedRole(roleIds);
    PermissionSubject permissionSubject = new PermissionSubject(
        user.get().source(),
        user.get().userId(),
        roleIds,
        null);
    return Optional.of(new AuthenticatedSubject(
        user.get().source(),
        user.get().userId(),
        subject.realmId(),
        null,
        permissionSubject));
  }

  private SessionSubject sessionSubject(Object value) {
    if (value instanceof SessionSubject subject) {
      return subject;
    }
    if (value instanceof AuthenticatedSubject legacy) {
      return SessionSubject.from(legacy);
    }
    return null;
  }

  private SessionSubject newSessionSubject(AuthenticatedSubject subject) {
    Set<String> externalRoleIds = new LinkedHashSet<>();
    if (subject.permissionSubject() != null && subject.permissionSubject().groupIds() != null) {
      externalRoleIds.addAll(subject.permissionSubject().groupIds());
    }
    if (subject.source() != null && subject.userId() != null && !subject.userId().isBlank()) {
      externalRoleIds.removeAll(securityDao.listUserRoleIds(subject.source(), subject.userId()));
    }
    if (defaultAuthenticatedRoleId != null) {
      externalRoleIds.remove(defaultAuthenticatedRoleId);
    }
    return new SessionSubject(subject.source(), subject.userId(), subject.realmId(), externalRoleIds);
  }

  private String principalName(AuthenticatedSubject subject) {
    return defaultString(subject.source(), DEFAULT_SOURCE) + "/" + subject.userId();
  }

  record SessionSubject(
      String source,
      String userId,
      String realmId,
      Set<String> externalRoleIds) implements Serializable {
    static SessionSubject from(AuthenticatedSubject subject) {
      Set<String> externalRoleIds = Set.of();
      if (subject.permissionSubject() != null && subject.permissionSubject().groupIds() != null) {
        externalRoleIds = new LinkedHashSet<>(subject.permissionSubject().groupIds());
      }
      return new SessionSubject(subject.source(), subject.userId(), subject.realmId(), externalRoleIds);
    }
  }

  private AuthenticatedSubject oidcSubject(SecurityRealmRecord realm, OidcProfile profile) {
    String source = sourceForRealm(realm);
    SecurityUserRecord user = upsertExternalUser(
        source,
        profile.userId(),
        profile.firstName(),
        profile.lastName(),
        profile.email(),
        profile.externalId(),
        Map.of("claims", profile.claims(), "groups", profile.roles()));
    return toSubject(user, realm.realmId(), profile.roles(), null);
  }

  private Optional<AuthenticatedSubject> authenticateBasic(String username, String password) {
    if (basicAuthCache == null) {
      return authenticateBasicUncached(username, password);
    }
    return basicAuthCache.find(username, password, () -> authenticateBasicUncached(username, password));
  }

  private Optional<AuthenticatedSubject> authenticateBasicUncached(String username, String password) {
    LoginName login = LoginName.parse(username);
    if (DEFAULT_ANONYMOUS_USER_ID.equals(login.userId())) {
      return Optional.empty();
    }
    for (SecurityRealmRecord realm : activeRealms()) {
      if (login.source() != null && !login.source().equals(sourceForRealm(realm))) {
        continue;
      }
      Optional<AuthenticatedSubject> authenticated = Optional.empty();
      if (isRealm(realm, "local")) {
        authenticated = authenticateLocal(realm, login, password);
      } else if (isRealm(realm, "ldap")) {
        authenticated = authenticateLdap(realm, login, password);
      }
      if (authenticated.isPresent()) {
        return authenticated;
      }
      if (login.source() != null && (isRealm(realm, "local") || isRealm(realm, "ldap"))) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private Optional<AuthenticatedSubject> authenticateLocal(
      SecurityRealmRecord realm,
      LoginName login,
      String password) {
    String source = sourceForRealm(realm);
    Optional<SecurityUserRecord> user = securityDao.findUser(source, login.userId()).filter(this::activeUser);
    if (user.isPresent() && SecurityHashing.verifyPassword(user.get().passwordHash(), password)) {
      if (SecurityHashing.needsRehash(user.get().passwordHash())) {
        securityDao.updatePasswordHash(source, login.userId(), SecurityHashing.hashPassword(password));
      }
      return Optional.of(toSubject(user.get(), realm.realmId(), null, null));
    }
    return Optional.empty();
  }

  private Optional<AuthenticatedSubject> authenticateLdap(
      SecurityRealmRecord realm,
      LoginName login,
      String password) {
    if (password == null || password.isBlank()) {
      return Optional.empty();
    }
    Map<String, Object> config = attributes(realm);
    String source = sourceForRealm(realm);
    String url = required(config, "url", "providerUrl");
    int timeoutMs = intConfig(config, 5000, "timeoutMs", "connectTimeoutMs");
    InitialLdapContext lookupContext = null;
    InitialLdapContext userContext = null;
    try {
      lookupContext = lookupContext(url, config, timeoutMs);
      LdapProfile profile = resolveLdapProfile(lookupContext, config, login.userId());
      userContext = ldapContext(url, profile.dn(), password, timeoutMs);
      Set<String> externalRoles = ldapGroupRoles(lookupContext, config, login.userId(), profile.dn());
      SecurityUserRecord user = upsertExternalUser(
          source,
          login.userId(),
          profile.firstName(),
          profile.lastName(),
          profile.email(),
          profile.externalId(),
          Map.of("dn", profile.dn(), "groups", externalRoles));
      return Optional.of(toSubject(user, realm.realmId(), externalRoles, null));
    } catch (AuthenticationException e) {
      return Optional.empty();
    } catch (NamingException e) {
      throw new SecurityValidationException("LDAP authentication failed for realm " + realm.realmId() + ": " + e.getMessage(), e);
    } finally {
      closeQuietly(userContext);
      closeQuietly(lookupContext);
    }
  }

  private LdapProfile resolveLdapProfile(
      InitialLdapContext context,
      Map<String, Object> config,
      String userId) throws NamingException {
    String template = stringConfig(config, "userDnTemplate", "userDnPattern");
    if (template != null) {
      return new LdapProfile(template.replace("{0}", userId).replace("{username}", userId), userId, null, null, null, userId);
    }
    String searchBase = required(config, "userSearchBase", "searchBase");
    String searchFilter = defaultString(
        stringConfig(config, "userSearchFilter", "searchFilter"),
        "(uid={0})").replace("{0}", escapeLdapFilter(userId)).replace("{username}", escapeLdapFilter(userId));
    SearchControls controls = new SearchControls();
    controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
    String firstNameAttribute = attributeName(config, "givenName", "firstNameAttribute", "givenNameAttribute");
    String lastNameAttribute = attributeName(config, "sn", "lastNameAttribute", "snAttribute");
    String emailAttribute = attributeName(config, "mail", "emailAttribute", "mailAttribute");
    String externalIdAttribute = attributeName(config, "uid", "externalIdAttribute", "uidAttribute");
    controls.setReturningAttributes(new String[] {
        firstNameAttribute,
        lastNameAttribute,
        emailAttribute,
        externalIdAttribute
    });
    NamingEnumeration<SearchResult> results = context.search(searchBase, searchFilter, controls);
    try {
      if (!results.hasMore()) {
        throw new AuthenticationException("LDAP user not found: " + userId);
      }
      SearchResult result = results.next();
      Attributes attrs = result.getAttributes();
      return new LdapProfile(
          result.getNameInNamespace(),
          userId,
          attribute(attrs, firstNameAttribute),
          attribute(attrs, lastNameAttribute),
          attribute(attrs, emailAttribute),
          defaultString(attribute(attrs, externalIdAttribute), userId));
    } finally {
      results.close();
    }
  }

  private Set<String> ldapGroupRoles(
      InitialLdapContext context,
      Map<String, Object> config,
      String userId,
      String userDn) throws NamingException {
    String groupBase = stringConfig(config, "groupSearchBase");
    if (groupBase == null) {
      return Set.of();
    }
    String groupFilter = defaultString(stringConfig(config, "groupSearchFilter"), "(member={1})")
        .replace("{0}", escapeLdapFilter(userId))
        .replace("{username}", escapeLdapFilter(userId))
        .replace("{1}", escapeLdapFilter(userDn))
        .replace("{userDn}", escapeLdapFilter(userDn));
    String groupNameAttribute = defaultString(stringConfig(config, "groupNameAttribute"), "cn");
    SearchControls controls = new SearchControls();
    controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
    controls.setReturningAttributes(new String[] {groupNameAttribute});
    NamingEnumeration<SearchResult> results = context.search(groupBase, groupFilter, controls);
    try {
      Set<String> roles = new LinkedHashSet<>();
      while (results.hasMore()) {
        SearchResult result = results.next();
        Attribute attribute = result.getAttributes().get(groupNameAttribute);
        if (attribute == null) {
          continue;
        }
        for (int i = 0; i < attribute.size(); i++) {
          Object value = attribute.get(i);
          if (value != null && !String.valueOf(value).isBlank()) {
            roles.add(String.valueOf(value).trim());
          }
        }
      }
      return roles;
    } finally {
      results.close();
    }
  }

  private InitialLdapContext lookupContext(
      String url,
      Map<String, Object> config,
      int timeoutMs) throws NamingException {
    String managerDn = stringConfig(config, "managerDn", "systemUsername", "bindDn");
    String managerPassword = stringConfig(config, "managerPassword", "systemPassword", "bindPassword");
    return ldapContext(url, managerDn, managerPassword, timeoutMs);
  }

  private InitialLdapContext ldapContext(
      String url,
      String principal,
      String credential,
      int timeoutMs) throws NamingException {
    Hashtable<String, String> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
    env.put(Context.PROVIDER_URL, url);
    env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(timeoutMs));
    env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(timeoutMs));
    if (principal == null || principal.isBlank()) {
      env.put(Context.SECURITY_AUTHENTICATION, "none");
    } else {
      env.put(Context.SECURITY_AUTHENTICATION, "simple");
      env.put(Context.SECURITY_PRINCIPAL, principal);
      env.put(Context.SECURITY_CREDENTIALS, credential == null ? "" : credential);
    }
    return new InitialLdapContext(env, null);
  }

  private Optional<OidcProfile> validateOidcToken(SecurityRealmRecord realm, String token) {
    try {
      String[] parts = token.split("\\.", -1);
      Map<String, Object> header = readJwtJson(parts[0]);
      Map<String, Object> claims = readJwtJson(parts[1]);
      String alg = asString(header.get("alg"));
      if (alg == null || "none".equalsIgnoreCase(alg)) {
        return Optional.empty();
      }
      Map<String, Object> config = attributes(realm);
      RSAPublicKey key = oidcSigningKey(config, asString(header.get("kid")));
      if (!verifyJwt(parts, alg, key)) {
        return Optional.empty();
      }
      if (!validClaims(config, claims)) {
        return Optional.empty();
      }
      String userId = claimString(claims, defaultString(stringConfig(config, "userIdClaim", "usernameClaim"), "preferred_username"));
      if (userId == null) {
        userId = claimString(claims, "sub");
      }
      if (userId == null) {
        return Optional.empty();
      }
      Set<String> roles = new LinkedHashSet<>();
      roles.addAll(claimStrings(claims.get(defaultString(stringConfig(config, "groupsClaim"), "groups"))));
      roles.addAll(claimStrings(claims.get(defaultString(stringConfig(config, "rolesClaim"), "roles"))));
      Object realmAccess = claims.get("realm_access");
      if (realmAccess instanceof Map<?, ?> realmAccessMap) {
        roles.addAll(claimStrings(realmAccessMap.get("roles")));
      }
      return Optional.of(new OidcProfile(
          userId,
          claimString(claims, defaultString(stringConfig(config, "firstNameClaim"), "given_name")),
          claimString(claims, defaultString(stringConfig(config, "lastNameClaim"), "family_name")),
          claimString(claims, defaultString(stringConfig(config, "emailClaim"), "email")),
          claimString(claims, "sub"),
          roles,
          claims));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private Map<String, Object> readJwtJson(String base64Url) throws java.io.IOException {
    byte[] decoded = Base64.getUrlDecoder().decode(base64Url);
    return objectMapper.readValue(decoded, JSON_MAP);
  }

  private boolean verifyJwt(String[] parts, String alg, RSAPublicKey key) throws Exception {
    Signature verifier = Signature.getInstance(signatureAlgorithm(alg));
    verifier.initVerify(key);
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
  }

  private String signatureAlgorithm(String alg) {
    return switch (alg) {
      case "RS256" -> "SHA256withRSA";
      case "RS384" -> "SHA384withRSA";
      case "RS512" -> "SHA512withRSA";
      default -> throw new SecurityValidationException("Unsupported OIDC JWT alg: " + alg);
    };
  }

  @SuppressWarnings("unchecked")
  private RSAPublicKey oidcSigningKey(Map<String, Object> config, String kid) throws Exception {
    String jwksUri = required(config, "jwksUri", "jwks_url", "jwkSetUri");
    long cacheSeconds = intConfig(config, 300, "jwksCacheSeconds");
    Map<String, Object> jwks = sharedCache == null
        ? null
        : sharedCache.getJson(JWKS_CACHE_NAMESPACE, jwksUri, JSON_MAP).orElse(null);
    if (jwks == null) {
      HttpRequest request = HttpRequest.newBuilder(
          outboundPolicy.validateHttpUri(jwksUri, "OIDC JWKS")).GET().build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new SecurityValidationException("JWKS endpoint returned " + response.statusCode());
      }
      jwks = objectMapper.readValue(response.body(), JSON_MAP);
      if (sharedCache != null) {
        sharedCache.putJson(JWKS_CACHE_NAMESPACE, jwksUri, jwks, java.time.Duration.ofSeconds(cacheSeconds));
      }
    }
    Object keysValue = jwks.get("keys");
    if (!(keysValue instanceof Collection<?> keys)) {
      throw new SecurityValidationException("JWKS does not contain keys");
    }
    for (Object value : keys) {
      if (!(value instanceof Map<?, ?> rawKey)) {
        continue;
      }
      Map<String, Object> key = (Map<String, Object>) rawKey;
      if (!"RSA".equals(asString(key.get("kty")))) {
        continue;
      }
      String keyId = asString(key.get("kid"));
      if (kid != null && !kid.equals(keyId)) {
        continue;
      }
      BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(asString(key.get("n"))));
      BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(asString(key.get("e"))));
      return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }
    throw new SecurityValidationException("OIDC signing key not found for kid: " + kid);
  }

  private boolean validClaims(Map<String, Object> config, Map<String, Object> claims) {
    long now = Instant.now().getEpochSecond();
    long skew = intConfig(config, 60, "clockSkewSeconds");
    Long exp = asLong(claims.get("exp"));
    if (exp == null || exp + skew < now) {
      return false;
    }
    Long nbf = asLong(claims.get("nbf"));
    if (nbf != null && nbf - skew > now) {
      return false;
    }
    String issuer = stringConfig(config, "issuer", "issuerUri");
    if (issuer != null && !issuer.equals(asString(claims.get("iss")))) {
      return false;
    }
    Set<String> acceptedAudiences = new LinkedHashSet<>();
    acceptedAudiences.addAll(configStrings(config.get("audiences")));
    acceptedAudiences.addAll(configStrings(config.get("allowedAudiences")));
    String audience = stringConfig(config, "audience", "clientId");
    if (audience != null) {
      acceptedAudiences.add(audience);
    }
    if (!acceptedAudiences.isEmpty()) {
      Set<String> tokenAudiences = claimStrings(claims.get("aud"));
      if (tokenAudiences.stream().noneMatch(acceptedAudiences::contains)) {
        return false;
      }
    }
    return true;
  }

  private AuthenticatedSubject toSubject(
      SecurityUserRecord user,
      String realmId,
      Set<String> externalRoleIds,
      Long apiKeyId) {
    return toSubject(user, realmId, externalRoleIds, apiKeyId, true);
  }

  private AuthenticatedSubject toSubject(
      SecurityUserRecord user,
      String realmId,
      Set<String> externalRoleIds,
      Long apiKeyId,
      boolean grantDefaultAuthenticatedRole) {
    return toSubject(
        user,
        realmId,
        externalRoleIds,
        apiKeyId,
        grantDefaultAuthenticatedRole,
        securityDao.listUserRoleIds(user.id()));
  }

  private AuthenticatedSubject toSubject(
      SecurityUserRecord user,
      String realmId,
      Set<String> externalRoleIds,
      Long apiKeyId,
      boolean grantDefaultAuthenticatedRole,
      Collection<String> assignedRoleIds) {
    Set<String> roleIds = new LinkedHashSet<>();
    if (externalRoleIds != null) {
      roleIds.addAll(externalRoleIds);
    }
    if (assignedRoleIds != null) {
      roleIds.addAll(assignedRoleIds);
    }
    if (grantDefaultAuthenticatedRole) {
      addDefaultAuthenticatedRole(roleIds);
    }
    PermissionSubject permissionSubject = new PermissionSubject(
        user.source(),
        user.userId(),
        roleIds,
        apiKeyId == null ? null : String.valueOf(apiKeyId));
    return new AuthenticatedSubject(user.source(), user.userId(), realmId, apiKeyId, permissionSubject);
  }

  private void addDefaultAuthenticatedRole(Set<String> roleIds) {
    if (defaultAuthenticatedRoleId != null) {
      roleIds.add(defaultAuthenticatedRoleId);
    }
  }

  private SecurityUserRecord upsertExternalUser(
      String source,
      String userId,
      String firstName,
      String lastName,
      String email,
      String externalId,
      Map<String, Object> attributes) {
    SecurityUserRecord existing = securityDao.findUser(source, userId).orElse(null);
    Map<String, Object> mergedAttributes = new LinkedHashMap<>();
    if (existing != null && existing.attributes() != null) {
      mergedAttributes.putAll(existing.attributes());
    }
    if (attributes != null) {
      mergedAttributes.putAll(attributes);
    }
    SecurityUserRecord record = new SecurityUserRecord(
        existing == null ? null : existing.id(),
        source,
        userId,
        defaultString(firstName, existing == null ? null : existing.firstName()),
        defaultString(lastName, existing == null ? null : existing.lastName()),
        defaultString(email, existing == null ? null : existing.email()),
        existing == null ? null : existing.passwordHash(),
        STATUS_ACTIVE,
        defaultString(externalId, existing == null ? userId : existing.externalId()),
        mergedAttributes);
    if (existing == null) {
      long id = securityDao.insertUser(record);
      return securityDao.findUser(source, userId).orElseThrow();
    }
    securityDao.updateUser(record);
    return securityDao.findUser(source, userId).orElseThrow();
  }

  private List<SecurityRealmRecord> activeRealms() {
    return securityDao.listRealms().stream()
        .filter(SecurityRealmRecord::enabled)
        .sorted((left, right) -> Integer.compare(left.priority(), right.priority()))
        .toList();
  }

  private boolean isRealm(SecurityRealmRecord realm, String expected) {
    return expected.equalsIgnoreCase(realm.realmId()) || expected.equalsIgnoreCase(realm.type());
  }

  private String sourceForRealm(SecurityRealmRecord realm) {
    Object source = realm.attributes() == null ? null : realm.attributes().get("source");
    if (source == null || String.valueOf(source).isBlank()) {
      if (isRealm(realm, "local")) {
        return DEFAULT_SOURCE;
      }
      if (isRealm(realm, "ldap")) {
        return "LDAP";
      }
      if (isRealm(realm, "oidc")) {
        return "OIDC";
      }
      return normalizeSource(realm.realmId());
    }
    return normalizeSource(String.valueOf(source));
  }

  private static String normalizeSource(String source) {
    String normalized = source == null ? null : source.trim();
    if (normalized == null || normalized.isBlank()) {
      return DEFAULT_SOURCE;
    }
    String lower = normalized.toLowerCase(Locale.ROOT);
    if (lower.equals("nexus")
        || lower.equals("local")
        || lower.equals("default")
        || lower.equals("nexusauthenticatingrealm")
        || lower.equals("nexusauthorizingrealm")) {
      return DEFAULT_SOURCE;
    }
    if (lower.equals("ldap") || lower.equals("ldaprealm")) {
      return "LDAP";
    }
    if (lower.equals("oidc") || lower.equals("oidcrealm")) {
      return "OIDC";
    }
    return normalized;
  }

  private Optional<BasicCredentials> basicCredentials(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      return Optional.empty();
    }
    try {
      String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
      int separator = decoded.indexOf(':');
      if (separator < 0) {
        return Optional.empty();
      }
      String username = decoded.substring(0, separator);
      String password = decoded.substring(separator + 1);
      if (username.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new BasicCredentials(username, password));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private String apiKeyToken(HttpServletRequest request) {
    String fromConfiguredHeader = headerValue(request, tokenHeader);
    if (fromConfiguredHeader != null) {
      return fromConfiguredHeader;
    }
    String fromKkRepoApiKey = headerValue(request, "X-Nexus-Plus-Api-Key");
    if (fromKkRepoApiKey != null) {
      return fromKkRepoApiKey;
    }
    String fromNuGetApiKey = headerValue(request, "X-NuGet-ApiKey");
    if (fromNuGetApiKey != null) {
      return domainScopedApiKey("NuGetApiKey", fromNuGetApiKey);
    }
    String bearer = bearerToken(request);
    if (bearer != null) {
      return bearer;
    }
    return null;
  }

  private List<String> cargoApiKeyTokens(HttpServletRequest request) {
    List<String> tokens = new ArrayList<>();
    addToken(tokens, headerValue(request, tokenHeader));
    addToken(tokens, headerValue(request, "X-Nexus-Plus-Api-Key"));
    addToken(tokens, bearerToken(request));
    String authorization = request.getHeader("Authorization");
    if (authorization != null && !authorization.isBlank()) {
      String trimmed = authorization.trim();
      addToken(tokens, trimmed);
      if (trimmed.regionMatches(true, 0, "Basic ", 0, 6)) {
        addToken(tokens, trimmed.substring(6).trim());
      } else if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
        addToken(tokens, trimmed.substring(7).trim());
      }
    }
    return List.copyOf(tokens);
  }

  private static void addToken(List<String> tokens, String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    String normalized = token.trim();
    if (!tokens.contains(normalized)) {
      tokens.add(normalized);
    }
  }

  private static String domainScopedApiKey(String domain, String token) {
    if (token.regionMatches(true, 0, domain + ".", 0, domain.length() + 1)) {
      return token;
    }
    return domain + "." + token;
  }

  private String bearerToken(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      String token = authorization.substring(7).trim();
      return token.isBlank() ? null : token;
    }
    return null;
  }

  private static String headerValue(HttpServletRequest request, String headerName) {
    if (headerName == null || headerName.isBlank()) {
      return null;
    }
    String value = request.getHeader(headerName);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private boolean activeUser(SecurityUserRecord user) {
    if (user.status() == null) {
      return true;
    }
    String status = user.status().trim().toUpperCase(Locale.ROOT);
    return STATUS_ACTIVE.equals(status) || STATUS_CHANGE_PASSWORD.equals(status);
  }

  private boolean activeApiKey(ApiKeyRecord apiKey) {
    if (apiKey.status() != null && !STATUS_ACTIVE.equalsIgnoreCase(apiKey.status())) {
      return false;
    }
    return apiKey.expiresAt() == null || apiKey.expiresAt().isAfter(LocalDateTime.now());
  }

  private Map<String, Object> attributes(SecurityRealmRecord realm) {
    return realm.attributes() == null ? Map.of() : realm.attributes();
  }

  private static String required(Map<String, Object> config, String... keys) {
    String value = stringConfig(config, keys);
    if (value == null) {
      throw new SecurityValidationException("Missing realm attribute: " + String.join("/", keys));
    }
    return value;
  }

  private static String stringConfig(Map<String, Object> config, String... keys) {
    for (String key : keys) {
      Object value = config.get(key);
      if (value != null && !String.valueOf(value).isBlank()) {
        return String.valueOf(value).trim();
      }
    }
    return null;
  }

  private static String attributeName(Map<String, Object> config, String fallback, String... keys) {
    return defaultString(stringConfig(config, keys), fallback);
  }

  private static int intConfig(Map<String, Object> config, int fallback, String... keys) {
    String value = stringConfig(config, keys);
    if (value == null) {
      return fallback;
    }
    return Integer.parseInt(value);
  }

  private static String attribute(Attributes attributes, String name) throws NamingException {
    if (name == null) {
      return null;
    }
    Attribute attribute = attributes.get(name);
    if (attribute == null) {
      return null;
    }
    Object value = attribute.get();
    return value == null ? null : String.valueOf(value);
  }

  private static String escapeLdapFilter(String value) {
    StringBuilder escaped = new StringBuilder();
    for (char c : value.toCharArray()) {
      switch (c) {
        case '\\' -> escaped.append("\\5c");
        case '*' -> escaped.append("\\2a");
        case '(' -> escaped.append("\\28");
        case ')' -> escaped.append("\\29");
        case '\u0000' -> escaped.append("\\00");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static void closeQuietly(InitialLdapContext context) {
    if (context == null) {
      return;
    }
    try {
      context.close();
    } catch (NamingException ignored) {
      // best effort cleanup
    }
  }

  private static Long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      return Long.parseLong(text);
    }
    return null;
  }

  private static String claimString(Map<String, Object> claims, String name) {
    return asString(claims.get(name));
  }

  private static String asString(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().findFirst().map(String::valueOf).orElse(null);
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }

  private static Set<String> claimStrings(Object value) {
    return configStrings(value);
  }

  private static Set<String> configStrings(Object value) {
    if (value == null) {
      return Set.of();
    }
    Set<String> values = new LinkedHashSet<>();
    if (value instanceof Collection<?> collection) {
      for (Object item : collection) {
        if (item != null && !String.valueOf(item).isBlank()) {
          values.add(String.valueOf(item).trim());
        }
      }
      return values;
    }
    for (String item : String.valueOf(value).split(",")) {
      if (!item.isBlank()) {
        values.add(item.trim());
      }
    }
    return values;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String optionalText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record BasicCredentials(String username, String password) {
  }

  private record LoginName(String source, String userId) {
    private static LoginName parse(String value) {
      int separator = value.indexOf('/');
      if (separator > 0 && separator < value.length() - 1) {
        return new LoginName(normalizeSource(value.substring(0, separator)), value.substring(separator + 1));
      }
      return new LoginName(null, value);
    }
  }

  private record LdapProfile(
      String dn,
      String userId,
      String firstName,
      String lastName,
      String email,
      String externalId) {
  }

  private record OidcProfile(
      String userId,
      String firstName,
      String lastName,
      String email,
      String externalId,
      Set<String> roles,
      Map<String, Object> claims) {
  }

}
