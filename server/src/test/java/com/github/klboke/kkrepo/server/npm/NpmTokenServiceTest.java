package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.CreatedApiKeyView;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NpmTokenServiceTest {

  @Test
  void loginCreatesNpmTokenForAuthenticatedCredentials() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("alice"));
    StubSecurityManagementService security = new StubSecurityManagementService();
    NpmTokenService service = new NpmTokenService(authentication, security, new ObjectMapper());

    MavenResponse response = service.login(body("""
        {"name":"alice","password":"secret"}
        """));
    Map<?, ?> payload = new ObjectMapper().readValue(response.body().readAllBytes(), Map.class);

    assertEquals(201, response.status());
    assertEquals("true", payload.get("ok"));
    assertEquals("NpmToken.generated-token", payload.get("token"));
    assertEquals("NpmToken", security.createdDomain);
    assertEquals("Local", security.createdOwnerSource);
    assertEquals("alice", security.createdOwnerUserId);
  }

  @Test
  void loginRejectsBadCredentials() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(null);
    StubSecurityManagementService security = new StubSecurityManagementService();
    NpmTokenService service = new NpmTokenService(authentication, security, new ObjectMapper());

    MavenResponse response = service.login(body("""
        {"name":"alice","password":"wrong"}
        """));
    Map<?, ?> payload = new ObjectMapper().readValue(response.body().readAllBytes(), Map.class);

    assertEquals(401, response.status());
    assertEquals("Bad username or password", payload.get("error"));
    assertEquals(null, security.createdDomain);
  }

  @Test
  void logoutDeletesCurrentUsersNpmToken() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("alice"));
    StubSecurityManagementService security = new StubSecurityManagementService();
    security.deleteResult = true;
    NpmTokenService service = new NpmTokenService(authentication, security, new ObjectMapper());

    MavenResponse response = service.logout(null);
    Map<?, ?> payload = new ObjectMapper().readValue(response.body().readAllBytes(), Map.class);

    assertEquals(200, response.status());
    assertEquals("true", payload.get("ok"));
    assertEquals("NpmToken", security.deletedDomain);
    assertEquals("Local", security.deletedOwnerSource);
    assertEquals("alice", security.deletedOwnerUserId);
  }

  @Test
  void encodedNpmTokenPathsAreRecognized() {
    assertEquals(true, NpmTokenService.isLoginPath("-/user/org.couchdb.user%3Aalice"));
    assertEquals(true, NpmTokenService.isLogoutPath("-/user/token/NpmToken.generated-token"));
  }

  private static ByteArrayInputStream body(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "Local",
        userId,
        "local",
        null,
        new PermissionSubject("Local", userId, Set.of(), null));
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final AuthenticatedSubject subject;

    private StubAuthenticationService(AuthenticatedSubject subject) {
      super(new SecurityDaoAdapter(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateCredentials(String username, String password) {
      return Optional.ofNullable(subject);
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      return Optional.ofNullable(subject);
    }
  }

  private static class StubSecurityManagementService extends SecurityManagementService {
    private String createdDomain;
    private String createdOwnerSource;
    private String createdOwnerUserId;
    private String deletedDomain;
    private String deletedOwnerSource;
    private String deletedOwnerUserId;
    private boolean deleteResult;

    private StubSecurityManagementService() {
      super(new SecurityDaoAdapter(null, null));
    }

    @Override
    public CreatedApiKeyView createApiKeyForOwner(String ownerSource, String ownerUserId, ApiKeyCommand command) {
      createdDomain = command.domain();
      createdOwnerSource = ownerSource;
      createdOwnerUserId = ownerUserId;
      ApiKeyView view = new ApiKeyView(
          1L,
          command.domain(),
          ownerSource,
          ownerUserId,
          command.displayName(),
          "ACTIVE",
          "NpmToken.gen",
          List.of(),
          null,
          null,
          null,
          null);
      return new CreatedApiKeyView(view, "NpmToken.generated-token");
    }

    @Override
    public boolean deleteApiKeyForOwner(String domain, String ownerSource, String ownerUserId) {
      deletedDomain = domain;
      deletedOwnerSource = ownerSource;
      deletedOwnerUserId = ownerUserId;
      return deleteResult;
    }
  }
}
