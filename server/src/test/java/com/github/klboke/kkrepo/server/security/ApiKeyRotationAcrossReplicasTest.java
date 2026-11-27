package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.TokenKind;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.server.conan.ConanAuthService;
import com.github.klboke.kkrepo.server.docker.DockerAuthService;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Two replicas with independent node-local auth caches share one database. A key reset on one
 * replica must invalidate bearers that the other issues from its stale cached credential.
 */
class ApiKeyRotationAcrossReplicasTest {
  private static final long CONAN_REPOSITORY = 42L;

  private final ProxiedHttpClientFactory transport = new ProxiedHttpClientFactory(60_000, 10_000);
  private final SharedSecurityDb db = new SharedSecurityDb();
  private final Map<String, DockerAuthTokenDao.TokenRecord> dockerRows = new ConcurrentHashMap<>();
  private final Map<String, ConanRegistryDao.AuthToken> conanRows = new ConcurrentHashMap<>();
  private final Node nodeA = new Node();
  private final Node nodeB = new Node();
  private final SecurityManagementService management = new SecurityManagementService(db);

  ApiKeyRotationAcrossReplicasTest() {
    db.users.put("Local/alice", new SecurityUserRecord(
        1L, "Local", "alice", null, null, "alice@example.com", null, "ACTIVE", null, Map.of()));
    // The reset transaction's eager cleanup reaches the shared bearer tables, as in production.
    management.configureEvents(event -> {
      if (event instanceof ApiKeyRotatedEvent rotated) {
        nodeA.docker.revokeTokensOf(rotated);
        nodeA.conan.revokeTokensOf(rotated);
      }
    });
  }

  @AfterEach
  void close() {
    transport.close();
  }

  @Test
  void userTokenResetInvalidatesBearersIssuedFromAStaleReplicaCache() {
    String oldToken = createKey(UserTokens.DOMAIN);
    MockHttpServletRequest oldCredential = userTokenRequest(oldToken);
    assertTrue(nodeB.auth.authenticate(oldCredential).isPresent());

    String newToken = reset(UserTokens.DOMAIN);
    MockHttpServletRequest newCredential = userTokenRequest(newToken);

    assertRotationEnforced(oldCredential, newCredential);
  }

  @Test
  void apiKeyResetInvalidatesBearersIssuedFromAStaleReplicaCache() {
    String oldToken = createKey("nexus");
    MockHttpServletRequest oldCredential = apiKeyRequest(oldToken);
    assertTrue(nodeB.auth.authenticate(oldCredential).isPresent());

    String newToken = reset("nexus");
    MockHttpServletRequest newCredential = apiKeyRequest(newToken);

    assertRotationEnforced(oldCredential, newCredential);
  }

  private void assertRotationEnforced(
      MockHttpServletRequest oldCredential, MockHttpServletRequest newCredential) {
    // Premise: nodeB still answers from its node-local cache, nodeA reads the reset row.
    assertTrue(nodeB.auth.authenticate(oldCredential).isPresent());
    assertTrue(nodeA.auth.authenticate(oldCredential).isEmpty());

    String staleDocker = nodeB.dockerBearer(oldCredential);
    String staleConan = nodeB.conanBearer(oldCredential);
    for (Node node : List.of(nodeA, nodeB)) {
      assertTrue(node.docker.authenticateBearer(staleDocker).isEmpty());
      assertTrue(node.conan.authenticate(conanRequest(staleConan), CONAN_REPOSITORY).isEmpty());
    }

    String freshDocker = nodeB.dockerBearer(newCredential);
    String freshConan = nodeB.conanBearer(newCredential);
    for (Node node : List.of(nodeA, nodeB)) {
      AuthenticatedSubject docker = node.docker.authenticateBearer(freshDocker).orElseThrow();
      assertEquals("alice", docker.userId());
      assertNotNull(docker.apiKeyGeneration());
      assertEquals("alice", node.conan.authenticate(conanRequest(freshConan), CONAN_REPOSITORY)
          .orElseThrow().userId());
    }
  }

  private String createKey(String domain) {
    return management.createApiKeyForOwner("Local", "alice",
        new ApiKeyCommand(domain, null, null, "ci", null, List.of(), null, null, null)).token();
  }

  private String reset(String domain) {
    long id = db.apiKeys.values().stream().filter(key -> key.domain().equals(domain))
        .findFirst().orElseThrow().id();
    var reset = management.resetApiKeyForOwner(id, "Local", "alice");
    assertEquals(id, reset.apiKey().id());
    return reset.token();
  }

  private static MockHttpServletRequest userTokenRequest(String token) {
    String userCode = UserTokens.userCode(token);
    return request("Authorization", "Basic " + Base64.getEncoder().encodeToString(
        (userCode + ":" + token.substring(userCode.length() + 1)).getBytes(StandardCharsets.UTF_8)));
  }

  private static MockHttpServletRequest apiKeyRequest(String token) {
    return request("X-Nexus-Plus-Token", token);
  }

  private static MockHttpServletRequest conanRequest(String bearer) {
    return request("Authorization", "Bearer " + bearer);
  }

  private static MockHttpServletRequest request(String header, String value) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/token");
    request.addHeader(header, value);
    return request;
  }

  /** One replica: its own node-local cache, the shared database and bearer tables. */
  private final class Node {
    final SecurityAuthenticationService auth = new SecurityAuthenticationService(
        db, new ObjectMapper(), "X-Nexus-Plus-Token", "nx-anonymous", null,
        OutboundRequestPolicy.allowPrivateForTests(), transport,
        new ApiKeyAuthCache(new InMemorySharedCache(), true, 60, 5), null, null);
    final DockerAuthService docker = new DockerAuthService(
        dockerTokens(), mock(DockerRegistryDao.class), auth, mock(AccessDecisionService.class), 900);
    final ConanAuthService conan = new ConanAuthService(conanTokens(), auth, 60);

    String dockerBearer(MockHttpServletRequest credential) {
      // The catalog scope is granted without a repository permission lookup.
      return docker.grant(credential, "registry", List.of("registry:catalog:*")).token();
    }

    String conanBearer(MockHttpServletRequest credential) {
      return conan.issue(CONAN_REPOSITORY, auth.authenticate(credential).orElseThrow());
    }
  }

  private DockerAuthTokenDao dockerTokens() {
    DockerAuthTokenDao tokens = mock(DockerAuthTokenDao.class);
    doAnswer(call -> {
      dockerRows.put(call.getArgument(0), new DockerAuthTokenDao.TokenRecord(
          call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
          call.getArgument(4), call.getArgument(5), TokenKind.USER,
          Map.of("scopes", call.getArgument(7)), call.getArgument(8)));
      return null;
    }).when(tokens).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    when(tokens.findValid(anyString(), any()))
        .thenAnswer(call -> Optional.ofNullable(dockerRows.get(call.<String>getArgument(0))));
    when(tokens.deleteByApiKeyId(anyLong())).thenAnswer(call -> {
      long id = call.getArgument(0);
      dockerRows.values().removeIf(row -> Long.valueOf(id).equals(row.subjectApiKeyId()));
      return 0;
    });
    return tokens;
  }

  private ConanRegistryDao conanTokens() {
    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    doAnswer(call -> {
      ConanRegistryDao.AuthToken row = call.getArgument(0);
      conanRows.put(row.tokenHash(), row);
      return null;
    }).when(registry).insertAuthToken(any());
    when(registry.findValidAuthToken(anyString(), anyLong(), any())).thenAnswer(call -> Optional
        .ofNullable(conanRows.get(call.<String>getArgument(0)))
        .filter(row -> row.repositoryId() == call.<Long>getArgument(1)));
    when(registry.deleteAuthTokensByApiKeyId(anyLong())).thenAnswer(call -> {
      long id = call.getArgument(0);
      conanRows.values().removeIf(row -> Long.valueOf(id).equals(row.apiKeyId()));
      return 0;
    });
    return registry;
  }

  /** The shared database: api_key rows keyed by (domain, owner) keep their id across resets. */
  private static final class SharedSecurityDb extends SecurityDaoAdapter {
    final Map<String, SecurityUserRecord> users = new LinkedHashMap<>();
    final Map<String, ApiKeyRecord> apiKeys = new LinkedHashMap<>();
    private long nextId = 30L;

    private static String owner(String domain, String source, String userId) {
      return domain + "/" + source + "/" + userId;
    }

    @Override
    public List<SecurityRealmRecord> listRealms() {
      return List.of();
    }

    @Override
    public Optional<SecurityUserRecord> findUser(String source, String userId) {
      return Optional.ofNullable(users.get(source + "/" + userId));
    }

    @Override
    public List<String> listUserRoleIds(long userNumericId) {
      return List.of();
    }

    @Override
    public boolean insertApiKeyIfAbsent(ApiKeyRecord record) {
      if (apiKeys.containsKey(owner(record.domain(), record.ownerSource(), record.ownerUserId()))) {
        return false;
      }
      upsertApiKey(record);
      return true;
    }

    @Override
    public void upsertApiKey(ApiKeyRecord record) {
      String key = owner(record.domain(), record.ownerSource(), record.ownerUserId());
      ApiKeyRecord existing = apiKeys.get(key);
      apiKeys.put(key, new ApiKeyRecord(existing == null ? nextId++ : existing.id(), record.domain(),
          record.ownerSource(), record.ownerUserId(), record.displayName(), record.status(),
          record.apiKeyHash(), record.tokenPrefix(), record.scopes(), record.encryptedPayload(),
          LocalDateTime.now(), LocalDateTime.now(), record.expiresAt(), null));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKey(long id) {
      return apiKeys.values().stream().filter(key -> key.id() == id).findFirst();
    }

    @Override
    public Optional<ApiKeyRecord> findApiKey(String domain, String ownerSource, String ownerUserId) {
      return Optional.ofNullable(apiKeys.get(owner(domain, ownerSource, ownerUserId)));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKeyForOwner(long id, String ownerSource, String ownerUserId) {
      return findApiKey(id).filter(key ->
          key.ownerSource().equals(ownerSource) && key.ownerUserId().equals(ownerUserId));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKeyByHash(String apiKeyHash) {
      return apiKeys.values().stream().filter(key -> key.apiKeyHash().equals(apiKeyHash)).findFirst();
    }

    @Override
    public Optional<ApiKeyRecord> findApiKeyByDomainAndHash(String domain, String apiKeyHash) {
      return findApiKeyByHash(apiKeyHash).filter(key -> key.domain().equals(domain));
    }

    @Override
    public void markApiKeyUsed(long id, LocalDateTime usedAt) {
    }
  }
}
