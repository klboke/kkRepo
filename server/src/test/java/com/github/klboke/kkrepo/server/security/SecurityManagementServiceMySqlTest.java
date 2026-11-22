package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import java.nio.file.Path;
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

/** The user token guard must hold under MySQL's case- and accent-insensitive domain comparison. */
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
        verify(context.getBean(SecurityManagementService.class), context.getBean(SecurityDao.class));
      }
    }
  }

  private static void verify(SecurityManagementService service, SecurityDao dao) {
    var token = service.createApiKeyForOwner("OIDC", "carol",
        new ApiKeyCommand(UserTokens.DOMAIN, null, null, "docker", null, List.of(), null, null, null));
    String hash = dao.findApiKey(UserTokens.DOMAIN, "OIDC", "carol").orElseThrow().apiKeyHash();

    for (String variant : List.of("UsérToken", "ＵｓｅｒＴｏｋｅｎ")) {
      // The database resolves the variant to the stored user token row.
      assertEquals(UserTokens.DOMAIN, dao.findApiKey(variant, "OIDC", "carol").orElseThrow().domain(), variant);
      for (String importedHash : new String[] {null, SecurityHashing.sha256("ABCDEFGHJK:" + "a".repeat(43))}) {
        ApiKeyCommand command = new ApiKeyCommand(
            variant, "OIDC", "carol", null, null, List.of(), null, null, importedHash);
        var error = assertThrows(ResponseStatusException.class,
            () -> asKeyAuthenticatedAdmin(() -> service.createApiKey(command)), variant);
        assertEquals(403, error.getStatusCode().value(), variant);
      }
    }
    assertEquals(hash, dao.findApiKey(UserTokens.DOMAIN, "OIDC", "carol").orElseThrow().apiKeyHash());

    // In a signed-in session the variant resolves to the same token, presented as the Basic pair.
    assertEquals(token.token(), service.createApiKey(
        new ApiKeyCommand("UsérToken", "OIDC", "carol", null, null, List.of(), null, null, null)).token());
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
