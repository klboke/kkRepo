package com.github.klboke.kkrepo.server.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory.ProxiedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/security")
public class OidcLoginController {
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };
  private static final String OIDC_STATE_ATTRIBUTE = OidcLoginController.class.getName() + ".STATE";
  private static final String OIDC_RETURN_TO_ATTRIBUTE = OidcLoginController.class.getName() + ".RETURN_TO";
  private static final String DEFAULT_SCOPES = "openid profile email";
  private static final String DEFAULT_RETURN_TO = "/browse/#browse/welcome";
  private static final String DISCOVERY_CACHE_NAMESPACE = "oidc-discovery";
  private static final long OIDC_HTTP_TIMEOUT_MILLIS = 30_000L;

  private final SecurityAuthenticationService authenticationService;
  private final ObjectMapper objectMapper;
  private final OutboundRequestPolicy outboundPolicy;
  private final ProxiedHttpClientFactory transportFactory;
  private final String externalBaseUrl;
  private final SharedCache sharedCache;
  private final SecureRandom secureRandom = new SecureRandom();

  @Autowired
  public OidcLoginController(
      SecurityAuthenticationService authenticationService,
      ObjectMapper objectMapper,
      OutboundRequestPolicy outboundPolicy,
      ProxiedHttpClientFactory transportFactory,
      SharedCache sharedCache,
      @Value("${kkrepo.security.external-base-url:}") String externalBaseUrl) {
    this.authenticationService = authenticationService;
    this.objectMapper = objectMapper;
    this.outboundPolicy = outboundPolicy;
    this.transportFactory = java.util.Objects.requireNonNull(
        transportFactory, "transportFactory");
    this.sharedCache = sharedCache;
    this.externalBaseUrl = asString(externalBaseUrl);
  }

  @GetMapping("/login/options")
  public LoginOptions loginOptions() {
    return new LoginOptions(
        authenticationService.activeOidcRealm().isPresent(),
        authenticationService.activeLdapRealm().isPresent());
  }

  @PostMapping("/login")
  public LoginResult passwordLogin(
      HttpServletRequest request,
      @RequestBody LoginCommand command) {
    if (command == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication failed");
    }
    AuthenticatedSubject authenticated = authenticationService
        .authenticateCredentials(command.username(), command.password())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication failed"));
    authenticationService.storeSessionSubject(request, authenticated);
    return new LoginResult(safeReturnTo(command.returnTo()));
  }

  @GetMapping("/basic/login")
  public void basicLogin(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(name = "returnTo", required = false) String returnTo) throws IOException {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (!(subject instanceof AuthenticatedSubject authenticated)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    authenticationService.storeSessionSubject(request, authenticated);
    response.sendRedirect(safeReturnTo(returnTo));
  }

  @GetMapping("/oidc/login")
  public void login(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(name = "returnTo", required = false) String returnTo) throws IOException {
    SecurityRealmRecord realm = activeOidcRealm();
    Map<String, Object> config = attributes(realm);
    String clientId = required(config, "clientId", "audience");
    String redirectUri = redirectUri(config);
    String state = randomToken();
    HttpSession session = request.getSession(true);
    session.setAttribute(OIDC_STATE_ATTRIBUTE, state);
    session.setAttribute(OIDC_RETURN_TO_ATTRIBUTE, safeReturnTo(returnTo));

    Map<String, String> params = new LinkedHashMap<>();
    params.put("response_type", "code");
    params.put("client_id", clientId);
    params.put("redirect_uri", redirectUri);
    params.put("scope", defaultString(stringConfig(config, "scopes", "scope"), DEFAULT_SCOPES));
    params.put("state", state);
    OidcEndpoint authorizationEndpoint = browserEndpoint(
        config,
        "OIDC authorization endpoint",
        "authorizationEndpoint",
        "authorizationEndpointUri",
        "authorization_endpoint");
    sendRedirectToOidcEndpoint(response, authorizationEndpoint, form(params));
  }

  @GetMapping("/oidc/callback")
  public void callback(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "id_token", required = false) String idToken,
      @RequestParam(name = "access_token", required = false) String accessToken,
      @RequestParam(name = "error", required = false) String error,
      @RequestParam(name = "error_description", required = false) String errorDescription) throws IOException {
    if (error != null && !error.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED,
          errorDescription == null || errorDescription.isBlank() ? error : error + ": " + errorDescription);
    }
    HttpSession session = request.getSession(false);
    if (session == null || state == null || !state.equals(session.getAttribute(OIDC_STATE_ATTRIBUTE))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC state is invalid or expired");
    }

    SecurityRealmRecord realm = activeOidcRealm();
    Map<String, Object> config = attributes(realm);
    String token = firstNonBlank(idToken, accessToken);
    if (token == null && code != null && !code.isBlank()) {
      Map<String, Object> tokenResponse = exchangeCode(config, code, redirectUri(config));
      token = firstNonBlank(asString(tokenResponse.get("id_token")), asString(tokenResponse.get("access_token")));
    }
    if (token == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC callback did not include a token");
    }
    Optional<AuthenticatedSubject> authenticated = authenticationService.authenticateOidcToken(token);
    if (authenticated.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC token validation failed");
    }
    authenticationService.storeSessionSubject(request, authenticated.get());
    session.removeAttribute(OIDC_STATE_ATTRIBUTE);
    String returnTo = safeReturnTo(asString(session.getAttribute(OIDC_RETURN_TO_ATTRIBUTE)));
    session.removeAttribute(OIDC_RETURN_TO_ATTRIBUTE);
    response.sendRedirect(returnTo);
  }

  @GetMapping("/logout")
  public void logout(
      HttpServletRequest request,
      HttpServletResponse response,
      @RequestParam(name = "returnTo", required = false) String returnTo) throws IOException {
    authenticationService.clearSessionSubject(request);
    response.sendRedirect(safeReturnTo(returnTo));
  }

  private SecurityRealmRecord activeOidcRealm() {
    return authenticationService.activeOidcRealm()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OIDC realm is not enabled"));
  }

  private Map<String, Object> exchangeCode(
      Map<String, Object> config,
      String code,
      String redirectUri) {
    String clientId = required(config, "clientId", "audience");
    OutboundRequestPolicy.ResolvedHttpTarget tokenEndpoint = resolvedEndpoint(
        config,
        "OIDC token endpoint",
        "tokenEndpoint",
        "tokenEndpointUri",
        "token_endpoint");
    Map<String, String> body = new LinkedHashMap<>();
    body.put("grant_type", "authorization_code");
    body.put("code", code);
    body.put("redirect_uri", redirectUri);
    body.put("client_id", clientId);
    String clientSecret = stringConfig(config, "clientSecret", "client_secret");
    if (clientSecret != null) {
      body.put("client_secret", clientSecret);
    }
    try (ProxiedResponse tokenResponse = transportFactory.execute(
        "security:oidc-token",
        null,
        "POST",
        tokenEndpoint,
        Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE),
        form(body).getBytes(StandardCharsets.UTF_8),
        OIDC_HTTP_TIMEOUT_MILLIS)) {
      if (tokenResponse.status() < 200 || tokenResponse.status() >= 300) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "OIDC token endpoint returned " + tokenResponse.status());
      }
      return objectMapper.readValue(tokenResponse.body(), JSON_MAP);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC token exchange failed", e);
    }
  }

  private OidcEndpoint browserEndpoint(
      Map<String, Object> config, String purpose, String... keys) {
    URI uri = outboundPolicy.validateHttpUri(endpointUrl(config, keys), purpose);
    validateEndpointFragment(uri, purpose);
    return new OidcEndpoint(uri, uri.getHost());
  }

  private OutboundRequestPolicy.ResolvedHttpTarget resolvedEndpoint(
      Map<String, Object> config, String purpose, String... keys) {
    OutboundRequestPolicy.ResolvedHttpTarget target =
        outboundPolicy.resolveHttpTarget(endpointUrl(config, keys), purpose);
    validateEndpointFragment(target.uri(), purpose);
    return target;
  }

  private String endpointUrl(Map<String, Object> config, String... keys) {
    String direct = stringConfig(config, keys);
    if (direct != null) {
      return direct;
    }
    Map<String, Object> discovery = discovery(config);
    for (String key : keys) {
      String discovered = asString(discovery.get(key));
      if (discovered != null) {
        return discovered;
      }
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OIDC endpoint is not configured: " + String.join("/", keys));
  }

  private void validateEndpointFragment(URI uri, String purpose) {
    if (uri.getRawFragment() != null) {
      throw new SecurityValidationException(purpose + " URL must not include a fragment");
    }
  }

  private Map<String, Object> discovery(Map<String, Object> config) {
    String uri = stringConfig(config, "discoveryUri", "metadataUrl", "wellKnownUrl");
    if (uri == null) {
      String issuer = required(config, "issuerUri", "issuer");
      uri = stripTrailingSlash(issuer) + "/.well-known/openid-configuration";
    }
    if (sharedCache != null) {
      Optional<Map<String, Object>> cached = sharedCache.getJson(DISCOVERY_CACHE_NAMESPACE, uri, JSON_MAP);
      if (cached.isPresent()) {
        Map<String, Object> document = cached.get();
        validateDiscoveryDocument(config, document);
        return document;
      }
    }
    OutboundRequestPolicy.ResolvedHttpTarget target =
        outboundPolicy.resolveHttpTarget(uri, "OIDC discovery");
    try (ProxiedResponse response = transportFactory.execute(
        "security:oidc-discovery",
        null,
        "GET",
        target,
        Map.of(),
        OIDC_HTTP_TIMEOUT_MILLIS)) {
      if (response.status() < 200 || response.status() >= 300) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "OIDC discovery endpoint returned " + response.status());
      }
      Map<String, Object> document = objectMapper.readValue(response.body(), JSON_MAP);
      validateDiscoveryDocument(config, document);
      if (sharedCache != null) {
        sharedCache.putJson(DISCOVERY_CACHE_NAMESPACE, uri, document, java.time.Duration.ofSeconds(300));
      }
      return document;
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OIDC discovery failed", e);
    }
  }

  private void validateDiscoveryDocument(Map<String, Object> config, Map<String, Object> document) {
    String configuredIssuer = stringConfig(config, "issuerUri", "issuer");
    if (configuredIssuer == null) {
      return;
    }
    String discoveredIssuer = asString(document.get("issuer"));
    String expectedIssuer = stripTrailingSlash(configuredIssuer);
    if (!expectedIssuer.equals(discoveredIssuer)) {
      throw new SecurityValidationException("OIDC discovery issuer must match configured issuer");
    }
  }

  private void sendRedirectToOidcEndpoint(
      HttpServletResponse response,
      OidcEndpoint endpoint,
      String query) throws IOException {
    URI uri = endpoint.uri();
    String authorizedHost = endpoint.authorizedRedirectHost();
    if (!authorizedHost.equals(uri.getHost())) {
      throw new SecurityValidationException("OIDC authorization endpoint host changed after validation");
    }
    response.sendRedirect(appendQuery(uri, query));
  }

  private String redirectUri(Map<String, Object> config) {
    String configured = stringConfig(config, "redirectUri", "redirect_uri");
    if (configured != null) {
      return configured;
    }
    if (externalBaseUrl != null) {
      return stripTrailingSlash(externalBaseUrl) + "/internal/security/oidc/callback";
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "OIDC redirectUri or kkrepo.security.external-base-url must be configured");
  }

  private String randomToken() {
    byte[] bytes = new byte[24];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String safeReturnTo(String returnTo) {
    String value = asString(returnTo);
    if (value == null || !value.startsWith("/") || value.startsWith("//")) {
      return DEFAULT_RETURN_TO;
    }
    return value;
  }

  private static Map<String, Object> attributes(SecurityRealmRecord realm) {
    return realm.attributes() == null ? Map.of() : realm.attributes();
  }

  private static String required(Map<String, Object> config, String... keys) {
    String value = stringConfig(config, keys);
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing OIDC realm attribute: " + String.join("/", keys));
    }
    return value;
  }

  private static String stringConfig(Map<String, Object> config, String... keys) {
    for (String key : keys) {
      String value = asString(config.get(key));
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String form(Map<String, String> values) {
    StringBuilder builder = new StringBuilder();
    values.forEach((key, value) -> {
      if (value == null) {
        return;
      }
      if (!builder.isEmpty()) {
        builder.append('&');
      }
      builder.append(urlEncode(key)).append('=').append(urlEncode(value));
    });
    return builder.toString();
  }

  private static String appendQuery(URI uri, String query) {
    String raw = uri.toString();
    return raw + (uri.getRawQuery() == null ? "?" : "&") + query;
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String stripTrailingSlash(String value) {
    String current = value;
    while (current.endsWith("/")) {
      current = current.substring(0, current.length() - 1);
    }
    return current;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      String normalized = asString(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String asString(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }

  public record LoginOptions(boolean oidcEnabled, boolean ldapEnabled) {
  }

  public record LoginCommand(String username, String password, String returnTo) {
  }

  public record LoginResult(String returnTo) {
  }

  private record OidcEndpoint(URI uri, String authorizedRedirectHost) {
  }
}
