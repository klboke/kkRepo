package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import com.sun.net.httpserver.HttpServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;

class SecurityAuthenticationServiceTest {

  private static final String NEXUS_SHIRO1_ADMIN123 = "$shiro1$SHA-512$1024$zjU1u+Zg9UNwuB+HEawvtA==$"
      + "IzF/OWzJxrqvB5FCe/2+UcZhhZYM2pTu0TEz7Ybnk65AbbEdUk9ntdtBzkN8P3gZby2qz6MHKqAe8Cjai9c4Gg==";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final ProxiedHttpClientFactory transportFactory =
      new ProxiedHttpClientFactory(60_000, 10_000);

  @AfterEach
  void closeTransportFactory() {
    transportFactory.close();
  }

  @Test
  void exposesOnlyReplayableTerraformCredentialsForTheAuthenticatedSubject() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    SecurityAuthenticationService service = service(dao);
    String basic = basic("admin", "admin123");
    AuthenticatedSubject local = new AuthenticatedSubject(
        "Local", "admin", "local", null,
        new com.github.klboke.kkrepo.auth.PermissionSubject("Local", "admin", Set.of(), null));
    AuthenticatedSubject apiKey = new AuthenticatedSubject(
        "Local", "admin", "api-key", 10L, local.permissionSubject());

    assertEquals(basic.substring(6), service.replayableTerraformUrlToken(
        request(Map.of("Authorization", basic)), local).orElseThrow());
    AuthenticatedSubject anotherUser = new AuthenticatedSubject(
        "Local", "other", "local", null,
        new com.github.klboke.kkrepo.auth.PermissionSubject("Local", "other", Set.of(), null));
    assertTrue(service.replayableTerraformUrlToken(
        request(Map.of("Authorization", basic)), anotherUser).isEmpty());
    assertEquals("GenericToken.secret", service.replayableTerraformUrlToken(
        request(Map.of("X-Nexus-Plus-Token", "GenericToken.secret")), apiKey).orElseThrow());
    assertTrue(service.replayableTerraformUrlToken(request(Map.of()), local).isEmpty());
  }

  @Test
  void credentialFreeRequestDoesNotOpenAuthenticationTransaction() {
    FakeSecurityDao dao = new FakeSecurityDao();
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    SecurityAuthenticationService service = new SecurityAuthenticationService(
        dao,
        new ObjectMapper(),
        "X-Nexus-Plus-Token",
        "nx-anonymous",
        null,
        OutboundRequestPolicy.allowPrivateForTests(),
        transportFactory,
        null,
        null,
        null,
        transactions);

    assertTrue(service.authenticate(request(Map.of())).isEmpty());
    verifyNoInteractions(transactions);
  }

  @Test
  void authenticatesLocalUserWithNexusPasswordHashAndAssignedRoles() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "nx-admin");
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "Authorization", basic("admin", "admin123"))));

    assertTrue(authenticated.isPresent());
    assertEquals("Local", authenticated.get().source());
    assertEquals("admin", authenticated.get().userId());
    assertEquals("local", authenticated.get().realmId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("nx-admin"));
    assertTrue(dao.findUser("Local", "admin").orElseThrow().passwordHash()
        .startsWith("{PBKDF2WithHmacSHA256}"));
  }

  @Test
  void basicAuthenticationRejectsAnonymousUserLikeNexus() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "anonymous", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "nx-anonymous");
    SecurityAuthenticationService service = service(dao);

    assertFalse(service.authenticate(request(Map.of(
        "Authorization", basic("anonymous", "admin123")))).isPresent());
    assertFalse(service.authenticate(request(Map.of(
        "Authorization", basic("Nexus/anonymous", "admin123")))).isPresent());
  }

  @Test
  void ansibleGalaxyAcceptsCurrentBearerAndAnsible29TokenCredentials() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(
        1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    SecurityAuthenticationService service = service(dao);

    String valid = Base64.getEncoder()
        .encodeToString("admin:admin123".getBytes(StandardCharsets.UTF_8));
    for (String scheme : List.of("Bearer ", "Token ")) {
      Optional<AuthenticatedSubject> authenticated = service.authenticateAnsibleGalaxy(
          request(Map.of("Authorization", scheme + valid)));
      assertTrue(authenticated.isPresent(), scheme);
      assertEquals("admin", authenticated.get().userId());
    }
    for (String decoded : List.of("admin:", ":admin123", "admin:\nadmin123", "admin\0:admin123")) {
      String encoded = Base64.getEncoder().encodeToString(decoded.getBytes(StandardCharsets.UTF_8));
      assertTrue(service.authenticateAnsibleGalaxy(
          request(Map.of("Authorization", "Bearer " + encoded))).isEmpty(), decoded);
    }
    assertTrue(service.authenticateAnsibleGalaxy(
        request(Map.of("Authorization", "Bearer not-base64***"))).isEmpty());
  }

  @Test
  void localBasicAuthenticationUsesRealmPriority() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "secondary-local", "LOCAL", "Secondary local", true, 0, Map.of("source", "secondary")));
    dao.realm(new SecurityRealmRecord(2L, "local", "LOCAL", "Local", true, 10, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    dao.user(user(2L, "secondary", "admin", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "default-role");
    dao.roles(2L, "secondary-role");
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "Authorization", basic("admin", "admin123"))));

    assertTrue(authenticated.isPresent());
    assertEquals("secondary", authenticated.get().source());
    assertEquals("secondary-local", authenticated.get().realmId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("secondary-role"));
  }

  @Test
  void basicAuthenticationUsesLdapRealmPriorityWithRealBindSearchAndGroupMapping() throws Exception {
    InMemoryDirectoryServer ldap = ldapServer();
    ldap.startListening();
    try {
      FakeSecurityDao dao = new FakeSecurityDao();
      dao.realm(new SecurityRealmRecord(
          1L,
          "ldap",
          "LDAP",
          "Corporate LDAP",
          true,
          0,
          Map.of(
              "source", "LDAP",
              "url", "ldap://127.0.0.1:" + ldap.getListenPort(),
              "managerDn", "cn=Manager,dc=example,dc=com",
              "managerPassword", "manager-secret",
              "userSearchBase", "ou=people,dc=example,dc=com",
              "userSearchFilter", "(uid={0})",
              "groupSearchBase", "ou=groups,dc=example,dc=com",
              "groupSearchFilter", "(member={1})",
              "groupNameAttribute", "cn")));
      dao.realm(new SecurityRealmRecord(2L, "local", "LOCAL", "Local", true, 10, Map.of("source", "Local")));
      dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
      dao.roles(1L, "local-role");
      SecurityAuthenticationService service = service(dao);

      Optional<AuthenticatedSubject> ldapSubject = service.authenticate(request(Map.of(
          "Authorization", basic("alice", "ldap-secret"))));
      Optional<AuthenticatedSubject> localFallback = service.authenticate(request(Map.of(
          "Authorization", basic("alice", "admin123"))));

      assertTrue(ldapSubject.isPresent());
      assertEquals("LDAP", ldapSubject.get().source());
      assertEquals("alice", ldapSubject.get().userId());
      assertEquals("ldap", ldapSubject.get().realmId());
      assertTrue(ldapSubject.get().permissionSubject().groupIds().contains("nx-ldap-team"));
      assertEquals("Alice", dao.findUser("LDAP", "alice").orElseThrow().firstName());
      assertTrue(localFallback.isPresent());
      assertEquals("Local", localFallback.get().source());
      assertTrue(localFallback.get().permissionSubject().groupIds().contains("local-role"));
    } finally {
      ldap.shutDown(true);
    }
  }

  @Test
  void basicAuthenticationNormalizesNexusLocalSourceAliases() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "NexusAuthenticatingRealm")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "nx-admin");
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> nexusSource = service.authenticate(request(Map.of(
        "Authorization", basic("Nexus/admin", "admin123"))));
    Optional<AuthenticatedSubject> localSource = service.authenticate(request(Map.of(
        "Authorization", basic("local/admin", "admin123"))));

    assertTrue(nexusSource.isPresent());
    assertEquals("Local", nexusSource.get().source());
    assertTrue(localSource.isPresent());
    assertEquals("Local", localSource.get().source());
  }

  @Test
  void authenticatesDomainScopedApiKeyBeforeBasicCredentials() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    dao.user(user(2L, "Local", "bob", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "api-role");
    dao.roles(2L, "basic-role");
    dao.apiKey(new ApiKeyRecord(
        10L,
        "nexus",
        "Local",
        "alice",
        "Alice API key",
        "ACTIVE",
        SecurityHashing.sha256("api-secret"),
        "nexus.api-se",
        Map.of("values", List.of()),
        "{}",
        null,
        null,
        LocalDateTime.now().plusDays(1),
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "X-Nexus-Plus-Token", "nexus.api-secret",
        "Authorization", basic("bob", "admin123"))));

    assertTrue(authenticated.isPresent());
    assertEquals("alice", authenticated.get().userId());
    assertEquals("api-key", authenticated.get().realmId());
    assertEquals(10L, authenticated.get().apiKeyId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("api-role"));
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("nx-anonymous"));
    assertEquals(10L, dao.lastUsedApiKeyId);
  }

  @Test
  void authenticatesGenericTokenWithDomainPrefixedBearerToken() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "ci-bot", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "repo-ci");
    dao.apiKey(new ApiKeyRecord(
        20L,
        "GenericToken",
        "Local",
        "ci-bot",
        "CI token",
        "ACTIVE",
        SecurityHashing.sha256("generic-secret"),
        "GenericToken",
        Map.of("values", List.of("read", "write")),
        "{}",
        null,
        null,
        LocalDateTime.now().plusDays(1),
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "Authorization", "Bearer GenericToken.generic-secret")));

    assertTrue(authenticated.isPresent());
    assertEquals("ci-bot", authenticated.get().userId());
    assertEquals("api-key", authenticated.get().realmId());
    assertEquals(20L, authenticated.get().apiKeyId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("repo-ci"));
    assertEquals(20L, dao.lastUsedApiKeyId);

    Optional<AuthenticatedSubject> ansible29 = service.authenticateAnsibleGalaxy(request(Map.of(
        "Authorization", "Token GenericToken.generic-secret")));
    assertTrue(ansible29.isPresent());
    assertEquals("ci-bot", ansible29.get().userId());
    assertEquals(20L, ansible29.get().apiKeyId());
  }

  @Test
  void authenticatedUsersReceiveConfiguredDefaultRoleWithoutPersistedUserRole() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    SecurityAuthenticationService service = service(dao, "nx-anonymous");

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "Authorization", basic("alice", "admin123"))));

    assertTrue(authenticated.isPresent());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("nx-anonymous"));
    assertEquals(List.of(), dao.listUserRoleIds(1L));
  }

  @Test
  void authenticatesMigratedNexusNpmApiKeyWithDomainTokenAndBareBearerToken() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "npm-publisher");
    dao.apiKey(new ApiKeyRecord(
        10L,
        "NpmToken",
        "Local",
        "alice",
        "Migrated npm token",
        "ACTIVE",
        SecurityHashing.sha256("raw-secret"),
        "NpmToken.raw",
        Map.of("source", "nexus-orient", "values", List.of()),
        "{migrated-nexus-raw-sha256}",
        null,
        null,
        null,
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> domainToken = service.authenticate(request(Map.of(
        "Authorization", "Bearer NpmToken.raw-secret")));
    Optional<AuthenticatedSubject> bareToken = service.authenticate(request(Map.of(
        "Authorization", "Bearer raw-secret")));

    assertTrue(domainToken.isPresent());
    assertEquals("alice", domainToken.get().userId());
    assertEquals("api-key", domainToken.get().realmId());
    assertTrue(domainToken.get().permissionSubject().groupIds().contains("npm-publisher"));
    assertTrue(bareToken.isPresent());
    assertEquals("alice", bareToken.get().userId());
    assertEquals("api-key", bareToken.get().realmId());
  }

  @Test
  void authenticatesNuGetApiKeyHeader() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "nuget-publisher");
    dao.apiKey(new ApiKeyRecord(
        10L,
        "NuGetApiKey",
        "Local",
        "alice",
        "NuGet API key",
        "ACTIVE",
        SecurityHashing.sha256("nuget-secret"),
        "NuGetApiKey.",
        Map.of("values", List.of()),
        "{}",
        null,
        null,
        null,
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "X-NuGet-ApiKey", "nuget-secret")));

    assertTrue(authenticated.isPresent());
    assertEquals("alice", authenticated.get().userId());
    assertEquals("api-key", authenticated.get().realmId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("nuget-publisher"));
  }

  @Test
  void cargoAuthenticationAcceptsRawAuthorizationTokenWithoutChangingNormalAuth() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "cargo-publisher");
    dao.apiKey(new ApiKeyRecord(
        11L,
        "CargoToken",
        "Local",
        "alice",
        "Migrated Cargo token",
        "ACTIVE",
        SecurityHashing.sha256("cargo-secret"),
        "CargoToken.raw",
        Map.of("source", "nexus-postgres", "values", List.of()),
        "{migrated-nexus-cargo-raw-sha256}",
        null,
        null,
        null,
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticateCargo(request(Map.of(
        "Authorization", "cargo-secret")));
    Optional<AuthenticatedSubject> normalAuthenticated = service.authenticate(request(Map.of(
        "Authorization", "cargo-secret")));

    assertTrue(authenticated.isPresent());
    assertEquals("alice", authenticated.get().userId());
    assertEquals("api-key", authenticated.get().realmId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("cargo-publisher"));
    assertTrue(normalAuthenticated.isEmpty());
    assertEquals(11L, dao.lastUsedApiKeyId);
  }

  @Test
  void rubygemsAuthenticationAcceptsRawAuthorizationTokenWithoutChangingNormalAuth() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "rubygems-publisher");
    dao.apiKey(new ApiKeyRecord(
        13L,
        "RubyGemsApiKey",
        "Local",
        "alice",
        "RubyGems API key",
        "ACTIVE",
        SecurityHashing.sha256("rubygems-secret"),
        "RubyGemsApi",
        Map.of("values", List.of()),
        "{}",
        null,
        null,
        null,
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticateRubygems(request(Map.of(
        "Authorization", "rubygems-secret")));
    Optional<AuthenticatedSubject> normalAuthenticated = service.authenticate(request(Map.of(
        "Authorization", "rubygems-secret")));

    assertTrue(authenticated.isPresent());
    assertEquals("alice", authenticated.get().userId());
    assertEquals("api-key", authenticated.get().realmId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("rubygems-publisher"));
    assertTrue(normalAuthenticated.isEmpty());
    assertEquals(13L, dao.lastUsedApiKeyId);
  }

  @Test
  void cargoAuthenticationPrefersCargoDomainWhenBareTokenCollides() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "npm-user", NEXUS_SHIRO1_ADMIN123));
    dao.user(user(2L, "Local", "cargo-user", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "npm-publisher");
    dao.roles(2L, "cargo-publisher");
    String hash = SecurityHashing.sha256("shared-secret");
    dao.apiKey(new ApiKeyRecord(
        21L,
        "NpmToken",
        "Local",
        "npm-user",
        "NPM token",
        "ACTIVE",
        hash,
        "NpmToken.raw",
        Map.of(),
        "{}",
        null,
        null,
        null,
        null));
    dao.apiKey(new ApiKeyRecord(
        22L,
        "CargoToken",
        "Local",
        "cargo-user",
        "Cargo token",
        "ACTIVE",
        hash,
        "CargoToken.raw",
        Map.of(),
        "{}",
        null,
        null,
        null,
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> cargoAuthenticated = service.authenticateCargo(request(Map.of(
        "Authorization", "shared-secret")));
    Optional<AuthenticatedSubject> normalAuthenticated = service.authenticate(request(Map.of(
        "Authorization", "Bearer shared-secret")));

    assertTrue(cargoAuthenticated.isPresent());
    assertEquals("cargo-user", cargoAuthenticated.get().userId());
    assertTrue(normalAuthenticated.isPresent());
    assertEquals("npm-user", normalAuthenticated.get().userId());
  }

  @Test
  void cargoAuthenticationAcceptsCompleteBasicAuthorizationHeaderAsToken() {
    String cargoHeaderToken = "Basic " + Base64.getEncoder()
        .encodeToString("nexus-user-token".getBytes(StandardCharsets.UTF_8));
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "cargo-publisher");
    dao.apiKey(new ApiKeyRecord(
        12L,
        "CargoToken",
        "Local",
        "alice",
        "Migrated Cargo Basic user token",
        "ACTIVE",
        SecurityHashing.sha256(cargoHeaderToken),
        "Basic token",
        Map.of("source", "nexus-3.77-user-token", "values", List.of()),
        "{migrated-nexus-cargo-basic-sha256}",
        null,
        null,
        null,
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> cargoAuthenticated = service.authenticateCargo(request(Map.of(
        "Authorization", cargoHeaderToken)));
    Optional<AuthenticatedSubject> normalAuthenticated = service.authenticate(request(Map.of(
        "Authorization", cargoHeaderToken)));

    assertTrue(cargoAuthenticated.isPresent());
    assertEquals("alice", cargoAuthenticated.get().userId());
    assertEquals("api-key", cargoAuthenticated.get().realmId());
    assertTrue(cargoAuthenticated.get().permissionSubject().groupIds().contains("cargo-publisher"));
    assertFalse(normalAuthenticated.isPresent());
  }

  @Test
  void cargoAuthenticationFallsBackToNormalBasicCredentials() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "nx-admin");
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticateCargo(request(Map.of(
        "Authorization", basic("admin", "admin123"))));

    assertTrue(authenticated.isPresent());
    assertEquals("admin", authenticated.get().userId());
    assertEquals("local", authenticated.get().realmId());
  }

  @Test
  void staleApiKeyOwnerDeletesOwnerApiKeys() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.apiKey(new ApiKeyRecord(
        10L,
        "nexus",
        "Local",
        "missing",
        "stale key",
        "ACTIVE",
        SecurityHashing.sha256("api-secret"),
        "nexus.api-se",
        Map.of("values", List.of()),
        "{}",
        null,
        null,
        LocalDateTime.now().plusDays(1),
        null));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "X-Nexus-Plus-Token", "nexus.api-secret")));

    assertFalse(authenticated.isPresent());
    assertEquals(1, dao.deletedApiKeysForOwner);
    assertFalse(dao.findApiKeyByDomainAndHash("nexus", SecurityHashing.sha256("api-secret")).isPresent());
  }

  @Test
  void invalidOidcBearerTokenDoesNotAbortAuthenticationFlow() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "oidc", "OIDC", "OIDC", true, 0, Map.of("source", "OIDC")));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
        "Authorization", "Bearer " + jwt(
            "{\"alg\":\"RS256\",\"kid\":\"missing\"}",
            "{\"sub\":\"alice\",\"exp\":4102444800}",
            "signature"))));

    assertFalse(authenticated.isPresent());
  }

  @Test
  void rejectsOidcBearerTokenWhenJwksEndpointIsNotSuccessful() throws Exception {
    HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext("/jwks", exchange -> {
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
    });
    jwks.start();
    try {
      FakeSecurityDao dao = new FakeSecurityDao();
      dao.realm(new SecurityRealmRecord(
          1L,
          "oidc",
          "OIDC",
          "OIDC",
          true,
          0,
          Map.of(
              "source", "OIDC",
              "jwksUri", "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks")));
      SecurityAuthenticationService service = service(dao);

      Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
          "Authorization", "Bearer " + jwt(
              "{\"alg\":\"RS256\",\"kid\":\"missing\"}",
              "{\"sub\":\"alice\",\"exp\":4102444800}",
              "signature"))));

      assertFalse(authenticated.isPresent());
    } finally {
      jwks.stop(0);
    }
  }

  @Test
  void authenticatesOidcBearerJwtAgainstLiveJwksAndMapsClaimsToExternalRoles() throws Exception {
    KeyPair keyPair = rsaKeyPair();
    String kid = "test-key";
    HttpServer jwks = jwksServer(jwksJson(keyPair, kid));
    jwks.start();
    try {
      FakeSecurityDao dao = new FakeSecurityDao();
      dao.realm(new SecurityRealmRecord(
          1L,
          "oidc",
          "OIDC",
          "OIDC",
          true,
          0,
          Map.of(
              "source", "OIDC",
              "issuer", "https://issuer.example.com",
              "audience", "kkrepo",
              "jwksUri", "http://jwks.rebind.invalid:" + jwks.getAddress().getPort() + "/jwks",
              "userIdClaim", "preferred_username",
              "groupsClaim", "groups",
              "rolesClaim", "roles")));
      OutboundRequestPolicy pinnedPolicy = new OutboundRequestPolicy(
          true,
          "",
          host -> new InetAddress[] {InetAddress.getByAddress(
              host, new byte[] {127, 0, 0, 1})});
      SecurityAuthenticationService service = service(dao, "nx-anonymous", pinnedPolicy);
      String token = signedJwt(
          keyPair,
          kid,
          Map.of(
              "iss", "https://issuer.example.com",
              "aud", List.of("kkrepo"),
              "sub", "subject-123",
              "preferred_username", "alice",
              "given_name", "Alice",
              "family_name", "OIDC",
              "email", "alice@example.com",
              "groups", List.of("nx-oidc-team"),
              "roles", List.of("nx-deploy"),
              "exp", Instant.now().plusSeconds(300).getEpochSecond()));

      Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
          "Authorization", "Bearer " + token)));

      assertTrue(authenticated.isPresent());
      assertEquals("OIDC", authenticated.get().source());
      assertEquals("alice", authenticated.get().userId());
      assertEquals("oidc", authenticated.get().realmId());
      assertTrue(authenticated.get().permissionSubject().groupIds().contains("nx-oidc-team"));
      assertTrue(authenticated.get().permissionSubject().groupIds().contains("nx-deploy"));
      assertTrue(authenticated.get().permissionSubject().groupIds().contains("nx-anonymous"));
      SecurityUserRecord stored = dao.findUser("OIDC", "alice").orElseThrow();
      assertEquals("Alice", stored.firstName());
      assertEquals("OIDC", stored.lastName());
      assertEquals("subject-123", stored.externalId());
    } finally {
      jwks.stop(0);
    }
  }

  @Test
  void authenticatesEs256OidcBearerJwtAgainstMatchingEcJwkAlongsideRsaKey() throws Exception {
    KeyPair rsaKeyPair = rsaKeyPair();
    KeyPair ecKeyPair = ecKeyPair("secp256r1");
    String ecKid = "authelia-es256";
    HttpServer jwks = jwksServer(mixedRsaAndEcJwksJson(
        rsaKeyPair, "authelia-rsa256", ecKeyPair, ecKid));
    jwks.start();
    try {
      FakeSecurityDao dao = new FakeSecurityDao();
      dao.realm(new SecurityRealmRecord(
          1L,
          "oidc",
          "OIDC",
          "OIDC",
          true,
          0,
          Map.of(
              "source", "OIDC",
              "issuer", "https://issuer.example.com",
              "audience", "kkrepo",
              "jwksUri", "http://jwks.rebind.invalid:" + jwks.getAddress().getPort() + "/jwks",
              "userIdClaim", "preferred_username",
              "groupsClaim", "groups")));
      OutboundRequestPolicy pinnedPolicy = new OutboundRequestPolicy(
          true,
          "",
          host -> new InetAddress[] {InetAddress.getByAddress(
              host, new byte[] {127, 0, 0, 1})});
      SecurityAuthenticationService service = service(dao, "nx-anonymous", pinnedPolicy);
      String token = signedJwt(
          ecKeyPair,
          ecKid,
          "ES256",
          "SHA256withECDSAinP1363Format",
          Map.of(
              "iss", "https://issuer.example.com",
              "aud", List.of("kkrepo"),
              "sub", "subject-es256",
              "preferred_username", "alice-es256",
              "groups", List.of("admins"),
              "exp", Instant.now().plusSeconds(300).getEpochSecond()));

      Optional<AuthenticatedSubject> authenticated = service.authenticate(request(Map.of(
          "Authorization", "Bearer " + token)));

      assertTrue(authenticated.isPresent());
      assertEquals("alice-es256", authenticated.get().userId());
      assertTrue(authenticated.get().permissionSubject().groupIds().contains("admins"));
      assertEquals(
          "subject-es256",
          dao.findUser("OIDC", "alice-es256").orElseThrow().externalId());

      String derEncodedSignatureToken = signedJwt(
          ecKeyPair,
          ecKid,
          "ES256",
          "SHA256withECDSA",
          Map.of(
              "iss", "https://issuer.example.com",
              "aud", List.of("kkrepo"),
              "sub", "subject-invalid-signature-encoding",
              "preferred_username", "invalid-signature-encoding",
              "exp", Instant.now().plusSeconds(300).getEpochSecond()));
      assertFalse(service.authenticate(request(Map.of(
          "Authorization", "Bearer " + derEncodedSignatureToken))).isPresent());
    } finally {
      jwks.stop(0);
    }
  }

  @Test
  void logsOidcRejectionReasonsForUnsignedInvalidClaimsMissingUserAndMalformedTokens()
      throws Exception {
    KeyPair keyPair = rsaKeyPair();
    String kid = "validation-key";
    HttpServer jwks = jwksServer(jwksJson(keyPair, kid));
    jwks.start();
    try {
      FakeSecurityDao dao = new FakeSecurityDao();
      dao.realm(new SecurityRealmRecord(
          1L,
          "oidc",
          "OIDC",
          "OIDC",
          true,
          0,
          Map.of(
              "source", "OIDC",
              "issuer", "https://issuer.example.com",
              "audience", "kkrepo",
              "jwksUri", "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks")));
      SecurityAuthenticationService service = service(dao);
      String unsigned = jwt(
          "{\"alg\":\"none\",\"kid\":\"unsigned-key\"}",
          "{\"sub\":\"sensitive-unsigned-user\"}",
          "sensitive-unsigned-signature");
      String expired = signedJwt(
          keyPair,
          kid,
          Map.of(
              "iss", "https://issuer.example.com",
              "aud", List.of("kkrepo"),
              "sub", "expired-user",
              "exp", 1));
      String missingUser = signedJwt(
          keyPair,
          kid,
          Map.of(
              "iss", "https://issuer.example.com",
              "aud", List.of("kkrepo"),
              "exp", Instant.now().plusSeconds(300).getEpochSecond()));
      LogCapture capture = attachAuthenticationAppender();

      try {
        assertFalse(service.authenticate(request(Map.of(
            "Authorization", "Bearer " + unsigned))).isPresent());
        assertFalse(service.authenticate(request(Map.of(
            "Authorization", "Bearer " + expired))).isPresent());
        assertFalse(service.authenticate(request(Map.of(
            "Authorization", "Bearer " + missingUser))).isPresent());
        assertFalse(service.authenticate(request(Map.of(
            "Authorization", "Bearer not-a-jwt.payload.signature"))).isPresent());
      } finally {
        detachAuthenticationAppender(capture);
      }

      List<String> messages = capture.appender().list.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .toList();
      assertEquals(4, messages.size());
      assertTrue(messages.stream().anyMatch(message ->
          message.contains("reason=missing or unsigned algorithm")));
      assertTrue(messages.stream().anyMatch(message ->
          message.contains("reason=claims validation failed")));
      assertTrue(messages.stream().anyMatch(message ->
          message.contains("reason=user identifier claim is missing")));
      assertTrue(messages.stream().anyMatch(message ->
          message.contains("OIDC token validation failed")
              && message.contains("cause=")));
      assertFalse(String.join("\n", messages).contains("sensitive-unsigned-user"));
      assertFalse(String.join("\n", messages).contains("sensitive-unsigned-signature"));
    } finally {
      jwks.stop(0);
    }
  }

  @Test
  void rejectsMatchingEcJwkWhenRequiredCoordinateIsMissing() throws Exception {
    String kid = "broken-ec";
    String body = OBJECT_MAPPER.writeValueAsString(Map.of(
        "keys",
        List.of(Map.of(
            "kty", "EC",
            "kid", kid,
            "alg", "ES256",
            "use", "sig",
            "crv", "P-256",
            "y", "AQ"))));
    HttpServer jwks = jwksServer(body);
    jwks.start();
    try {
      FakeSecurityDao dao = new FakeSecurityDao();
      dao.realm(new SecurityRealmRecord(
          1L,
          "oidc",
          "OIDC",
          "OIDC",
          true,
          0,
          Map.of(
              "source", "OIDC",
              "jwksUri", "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks")));
      SecurityAuthenticationService service = service(dao);
      String token = jwt(
          "{\"alg\":\"ES256\",\"kid\":\"broken-ec\"}",
          "{\"sub\":\"alice\",\"exp\":4102444800}",
          "signature");

      assertFalse(service.authenticate(request(Map.of(
          "Authorization", "Bearer " + token))).isPresent());
    } finally {
      jwks.stop(0);
    }
  }

  @Test
  void logsUnsupportedOidcAlgorithmWithoutTokenOrClaims() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(
        1L, "oidc", "OIDC", "OIDC", true, 0, Map.of("source", "OIDC")));
    SecurityAuthenticationService service = service(dao);
    String token = jwt(
        "{\"alg\":\"HS256\",\"kid\":\"unsupported-key\"}",
        "{\"sub\":\"sensitive-user\",\"exp\":4102444800}",
        "sensitive-signature");
    LogCapture capture = attachAuthenticationAppender();

    try {
      assertFalse(service.authenticate(request(Map.of(
          "Authorization", "Bearer " + token))).isPresent());
    } finally {
      detachAuthenticationAppender(capture);
    }

    assertEquals(1, capture.appender().list.size());
    assertEquals(Level.DEBUG, capture.appender().list.get(0).getLevel());
    String message = capture.appender().list.get(0).getFormattedMessage();
    assertTrue(message.contains("alg=HS256"));
    assertTrue(message.contains("kid=unsupported-key"));
    assertTrue(message.contains("Unsupported OIDC JWT alg: HS256"));
    assertFalse(message.contains("sensitive-user"));
    assertFalse(message.contains("sensitive-signature"));
  }

  @Test
  void authenticatesAnonymousUserThroughConfiguredRealmAndAssignedRoles() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.anonymous(new SecurityAnonymousConfigRecord(
        true,
        "Local",
        "anonymous",
        "NexusAuthorizingRealm"));
    dao.user(user(3L, "Local", "anonymous", null));
    dao.roles(3L, "nx-anonymous");
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticateAnonymous();

    assertTrue(authenticated.isPresent());
    assertEquals("Local", authenticated.get().source());
    assertEquals("anonymous", authenticated.get().userId());
    assertEquals("NexusAuthorizingRealm", authenticated.get().realmId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("nx-anonymous"));
  }

  @Test
  void anonymousUsersDoNotReceiveAuthenticatedDefaultRole() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.anonymous(new SecurityAnonymousConfigRecord(
        true,
        "Local",
        "anonymous",
        "NexusAuthorizingRealm"));
    dao.user(user(3L, "Local", "anonymous", null));
    SecurityAuthenticationService service = service(dao, "authenticated-default");

    Optional<AuthenticatedSubject> authenticated = service.authenticateAnonymous();

    assertTrue(authenticated.isPresent());
    assertFalse(authenticated.get().permissionSubject().groupIds().contains("authenticated-default"));
  }

  @Test
  void anonymousAuthenticationAlwaysUsesLocalSource() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.anonymous(new SecurityAnonymousConfigRecord(
        true,
        "LDAP",
        "anonymous",
        "LdapRealm"));
    dao.user(user(3L, "Local", "anonymous", null));
    dao.roles(3L, "nx-anonymous");
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticateAnonymous();

    assertTrue(authenticated.isPresent());
    assertEquals("Local", authenticated.get().source());
    assertEquals("anonymous", authenticated.get().userId());
  }

  @Test
  void authenticatesBrowserSessionSubjectBeforeBasicCredentials() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "alice", NEXUS_SHIRO1_ADMIN123));
    dao.user(user(2L, "Local", "bob", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "session-role");
    dao.roles(2L, "basic-role");
    SecurityAuthenticationService service = service(dao);
    SecurityAuthenticationService.SessionSubject sessionSubject = new SecurityAuthenticationService.SessionSubject(
        "Local",
        "alice",
        "oidc",
        Set.of("oidc-team"));

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(
        Map.of("Authorization", basic("bob", "admin123")),
        Map.of(AuthenticatedSubject.SESSION_ATTRIBUTE, sessionSubject)));

    assertTrue(authenticated.isPresent());
    assertEquals("alice", authenticated.get().userId());
    assertEquals("oidc", authenticated.get().realmId());
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("oidc-team"));
    assertTrue(authenticated.get().permissionSubject().groupIds().contains("session-role"));
  }

  @Test
  void storeSessionSubjectWritesLightweightPrincipalIndexForSessionRepository() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    dao.roles(1L, "nx-admin");
    SecurityAuthenticationService service = service(dao);
    Map<String, Object> sessionAttributes = new LinkedHashMap<>();
    sessionAttributes.put(SecurityAuthenticationService.BASIC_AUTH_SUPPRESSED_ATTRIBUTE, Boolean.TRUE);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new com.github.klboke.kkrepo.auth.PermissionSubject("Local", "admin", Set.of("nx-admin"), null));

    service.storeSessionSubject(request(Map.of(), sessionAttributes), subject);

    assertEquals(
        new SecurityAuthenticationService.SessionSubject("Local", "admin", "local", Set.of()),
        sessionAttributes.get(AuthenticatedSubject.SESSION_ATTRIBUTE));
    assertEquals(
        "Local/admin",
        sessionAttributes.get(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME));
    assertFalse(sessionAttributes.containsKey(SecurityAuthenticationService.BASIC_AUTH_SUPPRESSED_ATTRIBUTE));
  }

  @Test
  void explicitLogoutSuppressesCachedBasicCredentialsForSessionChecks() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(
        Map.of("Authorization", basic("admin", "admin123")),
        Map.of(SecurityAuthenticationService.BASIC_AUTH_SUPPRESSED_ATTRIBUTE, Boolean.TRUE),
        "/internal/security/session"));

    assertFalse(authenticated.isPresent());
  }

  @Test
  void explicitBasicLoginStillAcceptsCachedBasicCredentialsAfterLogout() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.user(user(1L, "Local", "admin", NEXUS_SHIRO1_ADMIN123));
    SecurityAuthenticationService service = service(dao);

    Optional<AuthenticatedSubject> authenticated = service.authenticate(request(
        Map.of("Authorization", basic("admin", "admin123")),
        Map.of(SecurityAuthenticationService.BASIC_AUTH_SUPPRESSED_ATTRIBUTE, Boolean.TRUE),
        "/internal/security/basic/login"));

    assertTrue(authenticated.isPresent());
    assertEquals("admin", authenticated.get().userId());
  }

  @Test
  void anonymousAuthenticationHonorsDisabledConfig() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.anonymous(new SecurityAnonymousConfigRecord(
        false,
        "Local",
        "anonymous",
        "NexusAuthorizingRealm"));
    dao.user(user(3L, "Local", "anonymous", null));
    SecurityAuthenticationService service = service(dao);

    assertFalse(service.authenticateAnonymous().isPresent());
  }

  @Test
  void anonymousAuthenticationIsDisabledWhenConfigIsMissing() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(user(3L, "Local", "anonymous", null));
    dao.roles(3L, "nx-anonymous");
    SecurityAuthenticationService service = service(dao);

    assertFalse(service.authenticateAnonymous().isPresent());
  }

  @Test
  void nonLocalRealmsInferDistinctSourceWhenSourceAttributeIsMissing() throws Exception {
    SecurityAuthenticationService service = service(new FakeSecurityDao());
    Method sourceForRealm = SecurityAuthenticationService.class
        .getDeclaredMethod("sourceForRealm", SecurityRealmRecord.class);
    sourceForRealm.setAccessible(true);

    assertEquals("Local", sourceForRealm.invoke(service,
        new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of())));
    assertEquals("LDAP", sourceForRealm.invoke(service,
        new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", true, 10, Map.of())));
    assertEquals("OIDC", sourceForRealm.invoke(service,
        new SecurityRealmRecord(3L, "oidc", "OIDC", "OIDC", true, 20, Map.of())));
  }

  private SecurityAuthenticationService service(FakeSecurityDao dao) {
    return service(dao, "nx-anonymous");
  }

  private SecurityAuthenticationService service(
      FakeSecurityDao dao, String defaultAuthenticatedRoleId) {
    return service(
        dao, defaultAuthenticatedRoleId, OutboundRequestPolicy.allowPrivateForTests());
  }

  private SecurityAuthenticationService service(
      FakeSecurityDao dao,
      String defaultAuthenticatedRoleId,
      OutboundRequestPolicy outboundPolicy) {
    return new SecurityAuthenticationService(
        dao,
        new ObjectMapper(),
        "X-Nexus-Plus-Token",
        defaultAuthenticatedRoleId,
        null,
        outboundPolicy,
        transportFactory,
        null,
        null,
        null);
  }

  private static SecurityUserRecord user(Long id, String source, String userId, String passwordHash) {
    return new SecurityUserRecord(
        id,
        source,
        userId,
        null,
        null,
        userId + "@example.com",
        passwordHash,
        "ACTIVE",
        userId,
        Map.of());
  }

  private static String basic(String username, String password) {
    String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    return "Basic " + token;
  }

  private static String jwt(String header, String payload, String signature) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(signature.getBytes(StandardCharsets.UTF_8));
  }

  private static InMemoryDirectoryServer ldapServer() throws Exception {
    InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
    config.addAdditionalBindCredentials("cn=Manager,dc=example,dc=com", "manager-secret");
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig(
        "ldap",
        InetAddress.getByName("127.0.0.1"),
        0,
        null));
    InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
    server.add(
        "dn: dc=example,dc=com",
        "objectClass: top",
        "objectClass: domain",
        "dc: example");
    server.add(
        "dn: ou=people,dc=example,dc=com",
        "objectClass: top",
        "objectClass: organizationalUnit",
        "ou: people");
    server.add(
        "dn: ou=groups,dc=example,dc=com",
        "objectClass: top",
        "objectClass: organizationalUnit",
        "ou: groups");
    server.add(
        "dn: uid=alice,ou=people,dc=example,dc=com",
        "objectClass: top",
        "objectClass: person",
        "objectClass: organizationalPerson",
        "objectClass: inetOrgPerson",
        "uid: alice",
        "cn: Alice LDAP",
        "sn: LDAP",
        "givenName: Alice",
        "mail: alice@example.com",
        "userPassword: ldap-secret");
    server.add(
        "dn: cn=nx-ldap-team,ou=groups,dc=example,dc=com",
        "objectClass: top",
        "objectClass: groupOfNames",
        "cn: nx-ldap-team",
        "member: uid=alice,ou=people,dc=example,dc=com");
    return server;
  }

  private static KeyPair rsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static KeyPair ecKeyPair(String curve) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec(curve));
    return generator.generateKeyPair();
  }

  private static HttpServer jwksServer(String jwksJson) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/jwks", exchange -> {
      byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    return server;
  }

  private static String jwksJson(KeyPair keyPair, String kid) throws Exception {
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    return OBJECT_MAPPER.writeValueAsString(Map.of(
        "keys",
        List.of(Map.of(
            "kty", "RSA",
            "kid", kid,
            "alg", "RS256",
            "use", "sig",
            "n", base64UrlUnsigned(publicKey.getModulus()),
            "e", base64UrlUnsigned(publicKey.getPublicExponent())))));
  }

  private static String mixedRsaAndEcJwksJson(
      KeyPair rsaKeyPair, String rsaKid, KeyPair ecKeyPair, String ecKid) throws Exception {
    RSAPublicKey rsaPublicKey = (RSAPublicKey) rsaKeyPair.getPublic();
    ECPublicKey ecPublicKey = (ECPublicKey) ecKeyPair.getPublic();
    return OBJECT_MAPPER.writeValueAsString(Map.of(
        "keys",
        List.of(
            Map.of(
                "kty", "RSA",
                "kid", rsaKid,
                "alg", "RS256",
                "use", "sig",
                "n", base64UrlUnsigned(rsaPublicKey.getModulus()),
                "e", base64UrlUnsigned(rsaPublicKey.getPublicExponent())),
            Map.of(
                "kty", "EC",
                "kid", ecKid,
                "alg", "RS256"),
            Map.of(
                "kty", "EC",
                "kid", ecKid,
                "alg", "ES256",
                "use", "enc"),
            Map.of(
                "kty", "EC",
                "kid", ecKid,
                "alg", "ES256",
                "use", "sig",
                "crv", "P-384"),
            Map.of(
                "kty", "EC",
                "kid", ecKid,
                "alg", "ES256",
                "use", "sig",
                "crv", "P-256",
                "x", base64UrlUnsigned(ecPublicKey.getW().getAffineX(), 32),
                "y", base64UrlUnsigned(ecPublicKey.getW().getAffineY(), 32)))));
  }

  private static String signedJwt(KeyPair keyPair, String kid, Map<String, Object> claims) throws Exception {
    return signedJwt(keyPair, kid, "RS256", "SHA256withRSA", claims);
  }

  private static String signedJwt(
      KeyPair keyPair,
      String kid,
      String algorithm,
      String signatureAlgorithm,
      Map<String, Object> claims) throws Exception {
    String header = base64UrlJson(Map.of("alg", algorithm, "kid", kid, "typ", "JWT"));
    String payload = base64UrlJson(claims);
    Signature signature = Signature.getInstance(signatureAlgorithm);
    signature.initSign(keyPair.getPrivate());
    signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
    return header + "." + payload + "." + base64Url(signature.sign());
  }

  private static String base64UrlJson(Map<String, Object> value) throws Exception {
    return base64Url(OBJECT_MAPPER.writeValueAsBytes(value));
  }

  private static String base64UrlUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return base64Url(bytes);
  }

  private static String base64UrlUnsigned(BigInteger value, int length) {
    byte[] bytes = value.toByteArray();
    byte[] fixed = new byte[length];
    int sourceOffset = Math.max(0, bytes.length - length);
    int copied = Math.min(bytes.length, length);
    System.arraycopy(bytes, sourceOffset, fixed, length - copied, copied);
    return base64Url(fixed);
  }

  private static LogCapture attachAuthenticationAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(SecurityAuthenticationService.class);
    Level priorLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return new LogCapture(logger, priorLevel, appender);
  }

  private static void detachAuthenticationAppender(LogCapture capture) {
    capture.logger().detachAppender(capture.appender());
    capture.logger().setLevel(capture.priorLevel());
  }

  private record LogCapture(Logger logger, Level priorLevel, ListAppender<ILoggingEvent> appender) {}

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static HttpServletRequest request(Map<String, String> headers) {
    return request(headers, null);
  }

  private static HttpServletRequest request(Map<String, String> headers, Map<String, Object> sessionAttributes) {
    return request(headers, sessionAttributes, "/repository/maven-public/");
  }

  private static HttpServletRequest request(
      Map<String, String> headers,
      Map<String, Object> sessionAttributes,
      String requestUri) {
    Map<String, String> normalized = new LinkedHashMap<>();
    headers.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
    return (HttpServletRequest) Proxy.newProxyInstance(
        SecurityAuthenticationServiceTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, method, args) -> {
          if ("getHeader".equals(method.getName()) && args != null && args.length == 1) {
            return normalized.get(String.valueOf(args[0]).toLowerCase(Locale.ROOT));
          }
          if ("getSession".equals(method.getName())) {
            if (sessionAttributes == null) {
              return null;
            }
            if (args != null && args.length == 1 && !Boolean.TRUE.equals(args[0])) {
              return session(sessionAttributes);
            }
            return session(sessionAttributes);
          }
          if ("getRequestURI".equals(method.getName())) {
            return requestUri;
          }
          if ("getContextPath".equals(method.getName())) {
            return "";
          }
          if ("toString".equals(method.getName())) {
            return "FakeHttpServletRequest" + normalized.keySet();
          }
          if (method.getReturnType().isPrimitive()) {
            return primitiveDefault(method.getReturnType());
          }
          return null;
        });
  }

  private static jakarta.servlet.http.HttpSession session(Map<String, Object> attributes) {
    return (jakarta.servlet.http.HttpSession) Proxy.newProxyInstance(
        SecurityAuthenticationServiceTest.class.getClassLoader(),
        new Class<?>[] {jakarta.servlet.http.HttpSession.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getAttribute" -> attributes.get(String.valueOf(args[0]));
          case "setAttribute" -> {
            attributes.put(String.valueOf(args[0]), args[1]);
            yield null;
          }
          case "removeAttribute" -> {
            attributes.remove(String.valueOf(args[0]));
            yield null;
          }
          case "invalidate" -> {
            attributes.clear();
            yield null;
          }
          case "toString" -> "FakeHttpSession";
          default -> primitiveDefault(method.getReturnType());
        });
  }

  private static Object primitiveDefault(Class<?> type) {
    if (boolean.class.equals(type)) {
      return false;
    }
    if (char.class.equals(type)) {
      return '\0';
    }
    return 0;
  }

  private static class FakeSecurityDao extends SecurityDaoAdapter {
    private final List<SecurityRealmRecord> realms = new ArrayList<>();
    private final Map<String, SecurityUserRecord> users = new LinkedHashMap<>();
    private final Map<Long, List<String>> roles = new LinkedHashMap<>();
    private final Map<String, ApiKeyRecord> apiKeysByDomainAndHash = new LinkedHashMap<>();
    private final Map<String, ApiKeyRecord> apiKeysByHash = new LinkedHashMap<>();
    private SecurityAnonymousConfigRecord anonymousConfig;
    private long nextUserId = 1L;
    private Long lastUsedApiKeyId;
    private int deletedApiKeysForOwner;

    private FakeSecurityDao() {
      super(null, null);
    }

    private void realm(SecurityRealmRecord realm) {
      realms.add(realm);
    }

    private void user(SecurityUserRecord user) {
      users.put(user.source() + "/" + user.userId(), user);
      if (user.id() != null) {
        nextUserId = Math.max(nextUserId, user.id() + 1);
      }
    }

    private void roles(long userId, String... roleIds) {
      roles.put(userId, List.of(roleIds));
    }

    private void apiKey(ApiKeyRecord apiKey) {
      apiKeysByHash.put(apiKey.apiKeyHash(), apiKey);
      apiKeysByDomainAndHash.put(apiKey.domain() + "/" + apiKey.apiKeyHash(), apiKey);
    }

    private void anonymous(SecurityAnonymousConfigRecord config) {
      anonymousConfig = config;
    }

    @Override
    public List<SecurityRealmRecord> listRealms() {
      return realms;
    }

    @Override
    public Optional<SecurityUserRecord> findUser(String source, String userId) {
      return Optional.ofNullable(users.get(source + "/" + userId));
    }

    @Override
    public long insertUser(SecurityUserRecord record) {
      long id = record.id() == null ? nextUserId++ : record.id();
      user(new SecurityUserRecord(
          id,
          record.source(),
          record.userId(),
          record.firstName(),
          record.lastName(),
          record.email(),
          record.passwordHash(),
          record.status(),
          record.externalId(),
          record.attributes()));
      return id;
    }

    @Override
    public void updateUser(SecurityUserRecord record) {
      SecurityUserRecord existing = findUser(record.source(), record.userId()).orElse(null);
      Long id = record.id() == null && existing != null ? existing.id() : record.id();
      user(new SecurityUserRecord(
          id == null ? nextUserId++ : id,
          record.source(),
          record.userId(),
          record.firstName(),
          record.lastName(),
          record.email(),
          record.passwordHash(),
          record.status(),
          record.externalId(),
          record.attributes()));
    }

    @Override
    public void updatePasswordHash(String source, String userId, String passwordHash) {
      SecurityUserRecord existing = findUser(source, userId).orElseThrow();
      user(new SecurityUserRecord(
          existing.id(),
          existing.source(),
          existing.userId(),
          existing.firstName(),
          existing.lastName(),
          existing.email(),
          passwordHash,
          existing.status(),
          existing.externalId(),
          existing.attributes()));
    }

    @Override
    public List<String> listUserRoleIds(long userNumericId) {
      return roles.getOrDefault(userNumericId, List.of());
    }

    @Override
    public List<String> listUserRoleIds(String source, String userId) {
      return findUser(source, userId)
          .map(SecurityUserRecord::id)
          .map(this::listUserRoleIds)
          .orElse(List.of());
    }

    @Override
    public Optional<SecurityAnonymousConfigRecord> findAnonymousConfig() {
      return Optional.ofNullable(anonymousConfig);
    }

    @Override
    public Optional<ApiKeyRecord> findApiKeyByHash(String apiKeyHash) {
      return Optional.ofNullable(apiKeysByHash.get(apiKeyHash));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKeyByDomainAndHash(String domain, String apiKeyHash) {
      return Optional.ofNullable(apiKeysByDomainAndHash.get(domain + "/" + apiKeyHash));
    }

    @Override
    public void markApiKeyUsed(long id, LocalDateTime usedAt) {
      lastUsedApiKeyId = id;
    }

    @Override
    public int deleteApiKeysForOwner(String ownerSource, String ownerUserId) {
      List<ApiKeyRecord> matches = apiKeysByHash.values().stream()
          .filter(apiKey -> ownerSource.equals(apiKey.ownerSource()))
          .filter(apiKey -> ownerUserId.equals(apiKey.ownerUserId()))
          .toList();
      matches.forEach(apiKey -> {
        apiKeysByHash.remove(apiKey.apiKeyHash());
        apiKeysByDomainAndHash.remove(apiKey.domain() + "/" + apiKey.apiKeyHash());
      });
      deletedApiKeysForOwner += matches.size();
      return matches.size();
    }
  }
}
