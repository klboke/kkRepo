package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.mysql.MySQLContainer;

/**
 * API key domains follow one identity rule in storage, management and authentication, although
 * MySQL compares the domain column case- and accent-insensitively.
 */
class SecurityManagementServiceMySqlTest {
  @TempDir Path directory;

  @Test
  void keyAuthenticatedAdminCannotReachAUserTokenThroughAnotherSpellingOfItsDomain() {
    try (var mysql = new MySQLContainer("mysql:8.0")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")
        .withTmpFs(Map.of("/var/lib/mysql", "rw"))) {
      mysql.start();
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("server.port", "0");
      properties.put("management.server.port", "0");
      properties.put("spring.datasource.url", mysql.getJdbcUrl()
          + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC");
      properties.put("spring.datasource.username", "kkrepo");
      properties.put("spring.datasource.password", "kkrepo");
      properties.put("kkrepo.database.type", "mysql");
      properties.put("kkrepo.storage.file.base-dir", directory.toString());
      properties.put("kkrepo.security.encryption.credential-secret", "mysql-domain-credential-secret");
      properties.put("kkrepo.security.encryption.api-key-payload-secret", "mysql-domain-api-key-secret");
      properties.put("kkrepo.catalog-cache.refresh-interval-ms", "3600000");
      properties.put("kkrepo.catalog-cache.initial-delay-ms", "3600000");
      properties.put("kkrepo.catalog-cache.jdbc.initial-delay-ms", "3600000");
      properties.put("kkrepo.blob-gc.enabled", "false");
      properties.put("kkrepo.repository-index-rebuild.enabled", "false");
      properties.put("kkrepo.maven.metadata-rebuild.enabled", "false");
      properties.put("kkrepo.storage.file.temp-cleanup-enabled", "false");
      try (var context = new SpringApplicationBuilder(KkRepoApplication.class).profiles("test")
          .run(properties.entrySet().stream()
              .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))) {
        verify(context.getBean(SecurityManagementService.class), context.getBean(SecurityDao.class),
            context.getBean(SecurityAuthenticationService.class));
      }
    }
  }

  private static void verify(
      SecurityManagementService service, SecurityDao dao, SecurityAuthenticationService authentication) {
    for (String user : List.of("carol", "dave", "erin")) {
      dao.insertUser(new SecurityUserRecord(null, "OIDC", user, null, null, user + "@example.com", null,
          "ACTIVE", user, Map.of()));
    }
    String chosenPair = "ABCDEFGHJK:" + "a".repeat(43);
    String chosenHash = SecurityHashing.sha256(chosenPair);

    // Empty slot: a spelling the database would match as "UserToken" is refused before anything is
    // stored, for key-authenticated and signed-in callers alike, so no hidden user token can exist.
    for (String variant : List.of("UsérToken", "ＵｓｅｒＴｏｋｅｎ")) {
      for (String importedHash : new String[] {null, chosenHash}) {
        ApiKeyCommand command = new ApiKeyCommand(
            variant, "OIDC", "dave", null, null, List.of(), null, null, importedHash);
        assertThrows(SecurityValidationException.class,
            () -> asKeyAuthenticatedAdmin(() -> service.createApiKey(command)), variant);
        assertThrows(SecurityValidationException.class, () -> service.createApiKey(command), variant);
      }
    }
    assertTrue(dao.listApiKeysForOwner("OIDC", "dave").isEmpty());
    assertTrue(authentication.authenticate(basic(chosenPair)).isEmpty());

    // Existing user token: the same spellings cannot reveal or replace it either.
    var token = service.createApiKeyForOwner("OIDC", "carol",
        new ApiKeyCommand(UserTokens.DOMAIN, null, null, "docker", null, List.of(), null, null, null));
    String hash = dao.findApiKey(UserTokens.DOMAIN, "OIDC", "carol").orElseThrow().apiKeyHash();
    for (String variant : List.of("UsérToken", "ＵｓｅｒＴｏｋｅｎ")) {
      // The database itself resolves the variant to the stored user token row.
      assertEquals(UserTokens.DOMAIN, dao.findApiKey(variant, "OIDC", "carol").orElseThrow().domain(), variant);
      for (String importedHash : new String[] {null, chosenHash}) {
        ApiKeyCommand command = new ApiKeyCommand(
            variant, "OIDC", "carol", null, null, List.of(), null, null, importedHash);
        assertThrows(SecurityValidationException.class,
            () -> asKeyAuthenticatedAdmin(() -> service.createApiKey(command)), variant);
      }
    }
    // An ASCII case variant is the same identity, and administrators never obtain another user's token.
    ApiKeyCommand caseVariant =
        new ApiKeyCommand("usertoken", "OIDC", "carol", null, null, List.of(), null, null, null);
    var error = assertThrows(ResponseStatusException.class,
        () -> asKeyAuthenticatedAdmin(() -> service.createApiKey(caseVariant)));
    assertEquals(403, error.getStatusCode().value());
    assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.createApiKey(caseVariant))
        .getStatusCode().value());
    assertEquals(hash, dao.findApiKey(UserTokens.DOMAIN, "OIDC", "carol").orElseThrow().apiKeyHash());
    assertEquals("carol", authentication.authenticate(basic(token.token())).orElseThrow().userId());

    // A row written under another spelling before this rule (directly in the database) is never
    // accepted as a user token, although the database matches its domain.
    dao.upsertApiKey(new ApiKeyRecord(null, "UsérToken", "OIDC", "erin", "legacy", "ACTIVE", chosenHash,
        "ABCDEFGHJK", Map.of("values", List.of()), "", null, null, null, null));
    assertEquals("UsérToken", dao.findApiKey(UserTokens.DOMAIN, "OIDC", "erin").orElseThrow().domain());
    assertTrue(authentication.authenticate(basic(chosenPair)).isEmpty());
  }

  private static MockHttpServletRequest basic(String pair) {
    var request = new MockHttpServletRequest("GET", "/repository/docker-hosted/v2/");
    request.addHeader("Authorization",
        "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    return request;
  }

  private static void asKeyAuthenticatedAdmin(Runnable action) {
    var request = new MockHttpServletRequest();
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, new AuthenticatedSubject(
        "Local", "admin", "api-key", 17L, new PermissionSubject("Local", "admin", Set.of("nx-admin"), null)));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      action.run();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }
}
