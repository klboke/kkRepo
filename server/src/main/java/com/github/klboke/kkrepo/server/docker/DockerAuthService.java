package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.DockerAuthTokenDao;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DockerAuthService {
  public static final String TOKEN_SUBJECT_ATTRIBUTE = DockerAuthService.class.getName() + ".TOKEN_SUBJECT";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final DockerAuthTokenDao tokenDao;
  private final SecurityAuthenticationService authenticationService;
  private final AccessDecisionService accessDecisionService;
  private final long tokenTtlSeconds;

  public DockerAuthService(
      DockerAuthTokenDao tokenDao,
      SecurityAuthenticationService authenticationService,
      AccessDecisionService accessDecisionService,
      @Value("${kkrepo.docker.token-ttl-seconds:900}") long tokenTtlSeconds) {
    this.tokenDao = tokenDao;
    this.authenticationService = authenticationService;
    this.accessDecisionService = accessDecisionService;
    this.tokenTtlSeconds = Math.max(60, tokenTtlSeconds);
  }

  @Transactional
  public TokenView grant(HttpServletRequest request, String service, List<String> scopes) {
    AuthenticatedSubject subject = authenticationService.authenticate(request)
        .or(authenticationService::authenticateAnonymous)
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.UNAUTHORIZED, "authentication required"));
    List<Scope> granted = new ArrayList<>();
    for (Scope scope : parseScopes(scopes, connectorRepositoryOrNull(request))) {
      Set<String> actions = new LinkedHashSet<>();
      for (String action : scope.actions()) {
        if (actionAllowed(subject.permissionSubject(), scope.repository(), scope.imageName(), action)) {
          actions.add(action);
        }
      }
      if (!actions.isEmpty()) {
        granted.add(new Scope(scope.repository(), scope.imageName(), List.copyOf(actions)));
      }
    }
    String token = randomToken();
    Instant expiresAt = Instant.now().plusSeconds(tokenTtlSeconds);
    tokenDao.insert(
        sha256(token),
        subject.source(),
        subject.userId(),
        subject.realmId(),
        subject.apiKeyId(),
        granted.stream().map(Scope::toMap).toList(),
        expiresAt);
    return new TokenView(token, token, tokenTtlSeconds, issuedAt(expiresAt));
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticateBearer(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return tokenDao.findValid(sha256(token), Instant.now())
        .flatMap(this::restoreSubject);
  }

  @Transactional
  public Optional<AuthenticatedSubject> authenticateBearer(String token, String repository, String imageName, String action) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Optional<DockerAuthTokenDao.TokenRecord> record = tokenDao.findValid(sha256(token), Instant.now());
    if (record.isEmpty()) {
      return Optional.empty();
    }
    DockerAuthTokenDao.TokenRecord row = record.get();
    if (!scopeAllows(row.scopes(), repository, imageName, action)) {
      throw new DockerProtocolException(DockerErrorCode.DENIED, "token scope does not allow " + action, 403);
    }
    return restoreSubject(row);
  }

  private Optional<AuthenticatedSubject> restoreSubject(DockerAuthTokenDao.TokenRecord row) {
    return authenticationService.authenticateStoredSubject(
        row.subjectSource(), row.subjectUserId(), row.subjectRealmId(), row.subjectApiKeyId());
  }

  public String challenge(String realm, String service) {
    return "Bearer realm=\"" + realm + "\",service=\"" + service + "\"";
  }

  public String registryCatalogChallenge(String realm, String service) {
    return "Bearer realm=\"" + realm + "\",service=\"" + service + "\",scope=\"registry:catalog:*\"";
  }

  public String challenge(String realm, String service, String repository, String imageName, String... actions) {
    StringBuilder scope = new StringBuilder("repository:");
    scope.append(repository);
    if (imageName != null && !imageName.isBlank()) {
      scope.append('/').append(imageName);
    }
    scope.append(':').append(String.join(",", actions));
    return "Bearer realm=\"" + realm + "\",service=\"" + service + "\",scope=\"" + scope + "\"";
  }

  static boolean scopeAllows(Map<String, Object> stored, String repository, String imageName, String action) {
    Object scopes = stored.get("scopes");
    if (!(scopes instanceof List<?> list)) {
      return false;
    }
    if ("catalog".equals(action)) {
      return list.stream().anyMatch(DockerAuthService::isCatalogScope);
    }
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        continue;
      }
      if (!repository.equals(map.get("repository"))) {
        continue;
      }
      String tokenImage = map.get("imageName") == null ? "" : map.get("imageName").toString();
      if (!tokenImage.isBlank() && !tokenImage.equals(imageName == null ? "" : imageName)) {
        continue;
      }
      Object rawActions = map.get("actions");
      if (rawActions instanceof List<?> actions && actions.stream().anyMatch(value -> action.equals(value.toString()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCatalogScope(Object item) {
    if (!(item instanceof Map<?, ?> map)) {
      return false;
    }
    String repository = map.get("repository") == null ? "" : map.get("repository").toString();
    String imageName = map.get("imageName") == null ? "" : map.get("imageName").toString();
    if (!repository.isBlank() || !imageName.isBlank()) {
      return false;
    }
    Object rawActions = map.get("actions");
    return rawActions instanceof List<?> actions
        && actions.stream().anyMatch(value -> "catalog".equals(value.toString()));
  }

  static List<Scope> parseScopes(List<String> rawScopes) {
    return parseScopes(rawScopes, null);
  }

  static List<Scope> parseScopes(List<String> rawScopes, String connectorRepository) {
    if (rawScopes == null || rawScopes.isEmpty()) {
      return List.of();
    }
    List<Scope> scopes = new ArrayList<>();
    for (String raw : rawScopes) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      if ("registry:catalog:*".equals(raw)) {
        scopes.add(new Scope("", "", List.of("catalog")));
        continue;
      }
      if (!raw.startsWith("repository:")) {
        continue;
      }
      String rest = raw.substring("repository:".length());
      int colon = rest.lastIndexOf(':');
      if (colon <= 0 || colon == rest.length() - 1) {
        continue;
      }
      String name = rest.substring(0, colon);
      String repository;
      String imageName;
      if (connectorRepository != null && !connectorRepository.isBlank()) {
        repository = connectorRepository;
        imageName = connectorImageName(connectorRepository, name);
      } else {
        String[] nameParts = name.split("/", 2);
        repository = nameParts[0];
        imageName = nameParts.length > 1 ? nameParts[1] : "";
      }
      List<String> actions = java.util.Arrays.stream(rest.substring(colon + 1).split(","))
          .map(item -> item.trim().toLowerCase(Locale.ROOT))
          .filter(item -> !item.isBlank())
          .distinct()
          .toList();
      scopes.add(new Scope(repository, imageName, actions));
    }
    return scopes;
  }

  private static String connectorImageName(String connectorRepository, String scopeName) {
    if (scopeName == null || scopeName.isBlank() || scopeName.equals(connectorRepository)) {
      return "";
    }
    String prefix = connectorRepository + "/";
    return scopeName.startsWith(prefix) ? scopeName.substring(prefix.length()) : scopeName;
  }

  private static String connectorRepositoryOrNull(HttpServletRequest request) {
    Object repository = request.getAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE);
    if (repository instanceof String value && !value.isBlank()) {
      return value;
    }
    return null;
  }

  private boolean actionAllowed(PermissionSubject subject, String repository, String imageName, String action) {
    List<PermissionAction> permissions = permissionsFor(action);
    if (permissions.isEmpty()) {
      return false;
    }
    for (PermissionAction permission : permissions) {
      if (accessDecisionService.decide(
          subject,
          new RepositoryPermission(repository, RepositoryFormat.DOCKER, imageName, permission))
          .allowed()) {
        return true;
      }
    }
    return false;
  }

  private static List<PermissionAction> permissionsFor(String action) {
    return switch (action) {
      case "pull" -> List.of(PermissionAction.READ);
      case "push" -> List.of(PermissionAction.ADD, PermissionAction.EDIT);
      case "delete" -> List.of(PermissionAction.DELETE);
      case "catalog" -> List.of(PermissionAction.ADMIN);
      default -> List.of();
    };
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String issuedAt(Instant expiresAt) {
    return expiresAt.minusSeconds(1).toString();
  }

  public record TokenView(String token, String access_token, long expires_in, String issued_at) {
  }

  public record Scope(String repository, String imageName, List<String> actions) {
    Map<String, Object> toMap() {
      return Map.of(
          "repository", repository,
          "imageName", imageName == null ? "" : imageName,
          "actions", actions);
    }
  }
}
