package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.CreatedApiKeyView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
public class NpmTokenService {
  private static final String NPM_TOKEN_DOMAIN = "NpmToken";
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;
  private final ObjectMapper objectMapper;

  public NpmTokenService(
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService,
      ObjectMapper objectMapper) {
    this.authenticationService = authenticationService;
    this.securityService = securityService;
    this.objectMapper = objectMapper;
  }

  public MavenResponse login(InputStream body) {
    if (body == null) {
      return npmError(400, "Missing body");
    }
    Map<String, Object> request;
    try {
      request = objectMapper.readValue(body, JSON_MAP);
    } catch (IOException e) {
      return npmError(400, "Invalid JSON body");
    }
    String username = text(request.get("name"));
    String password = text(request.get("password"));
    if (username == null || password == null) {
      return npmError(400, "Missing name or password");
    }

    Optional<AuthenticatedSubject> authenticated = authenticationService.authenticateCredentials(username, password);
    if (authenticated.isEmpty()) {
      return npmError(401, "Bad username or password")
          .withHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"kkrepo\"");
    }

    AuthenticatedSubject subject = authenticated.get();
    CreatedApiKeyView created = securityService.createApiKeyForOwner(
        subject.source(),
        subject.userId(),
        new ApiKeyCommand(
            NPM_TOKEN_DOMAIN,
            null,
            null,
            "npm login",
            null,
            List.of(),
            null,
            null,
            null));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", Boolean.TRUE.toString());
    response.put("rev", "_we_dont_use_revs_any_more");
    response.put("id", "org.couchdb.user:undefined");
    response.put("token", created.token());
    return npmJson(response, 201);
  }

  public MavenResponse logout(HttpServletRequest request) {
    Optional<AuthenticatedSubject> authenticated = authenticationService.authenticate(request);
    if (authenticated.isEmpty()) {
      return npmError(401, "Authentication required")
          .withHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"kkrepo\"");
    }
    AuthenticatedSubject subject = authenticated.get();
    boolean deleted = securityService.deleteApiKeyForOwner(NPM_TOKEN_DOMAIN, subject.source(), subject.userId());
    if (!deleted) {
      return npmError(404, "Token not found");
    }
    return npmJson(Map.of("ok", Boolean.TRUE.toString()), 200);
  }

  public static boolean isLoginPath(String rawPath) {
    return normalizedPath(rawPath).startsWith("-/user/org.couchdb.user:");
  }

  public static boolean isWebLoginPath(String rawPath) {
    return "-/v1/login".equals(normalizedPath(rawPath));
  }

  public static boolean isLogoutPath(String rawPath) {
    return normalizedPath(rawPath).startsWith("-/user/token/");
  }

  public static boolean isTokenPath(String rawPath) {
    return isLoginPath(rawPath) || isWebLoginPath(rawPath) || isLogoutPath(rawPath);
  }

  private MavenResponse npmError(int status, String message) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("error", message);
    response.put("reason", message);
    return npmJson(response, status);
  }

  private MavenResponse npmJson(Object value, int status) {
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize npm JSON", e);
    }
    return MavenResponse.ok(
            new ByteArrayInputStream(bytes),
            bytes.length,
            "application/json",
            null,
            null)
        .withStatus(status);
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static String normalizedPath(String rawPath) {
    String path;
    try {
      // Only classify the endpoint here. The username/token suffix is opaque and may include
      // encoded separators; authentication validates the actual credentials separately.
      path = UriPathDecoder.decodeComponent(rawPath == null ? "" : rawPath);
    } catch (IllegalArgumentException e) {
      return ""; // Malformed paths must never be recognized as an authentication endpoint.
    }
    while (path.startsWith("/")) {
      path = path.substring(1);
    }
    return path;
  }

}
