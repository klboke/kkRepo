package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OidcLoginControllerTest {
  private final ProxiedHttpClientFactory transportFactory =
      new ProxiedHttpClientFactory(60_000, 10_000);

  @AfterEach
  void closeTransportFactory() {
    transportFactory.close();
  }

  @Test
  void loginRedirectsToConfiguredAuthorizationEndpointAndStoresReturnState() throws Exception {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
        "clientId", "kkrepo",
        "issuerUri", "https://localhost",
        "authorizationEndpoint", "https://localhost/oauth2/authorize",
        "redirectUri", "http://nexus.example.com/internal/security/oidc/callback",
        "scopes", "openid profile email groups")));
    OidcLoginController controller = controller(authentication);

    controller.login(request(session), response.proxy(), "/browse/");

    URI redirect = URI.create(response.redirect);
    Map<String, String> query = query(redirect);
    assertEquals("https", redirect.getScheme());
    assertEquals("localhost", redirect.getHost());
    assertEquals("/oauth2/authorize", redirect.getPath());
    assertEquals("code", query.get("response_type"));
    assertEquals("kkrepo", query.get("client_id"));
    assertEquals("http://nexus.example.com/internal/security/oidc/callback", query.get("redirect_uri"));
    assertEquals("openid profile email groups", query.get("scope"));
    assertNotNull(query.get("state"));
  }

  @Test
  void loginRejectsUnsafeAuthorizationEndpoint() {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
        "clientId", "kkrepo",
        "issuerUri", "https://localhost",
        "authorizationEndpoint", "http://127.0.0.1/oauth2/authorize",
        "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
    OidcLoginController controller = controller(
        authentication, new OutboundRequestPolicy(false, ""), "");

    SecurityValidationException error = assertThrows(
        SecurityValidationException.class,
        () -> controller.login(request(session), response.proxy(), "/browse/"));

    assertTrue(error.getMessage().contains("OIDC authorization endpoint URL resolves to a private or local address"));
  }

  @Test
  void loginAllowsConfiguredAuthorizationEndpointOnDifferentHostWhenOutboundPolicyAllowsIt() throws Exception {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
        "clientId", "kkrepo",
        "issuerUri", "https://issuer.example.com",
        "authorizationEndpoint", "https://login.example.net/oauth2/authorize",
        "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
    OidcLoginController controller = controller(
        authentication,
        new OutboundRequestPolicy(false, "issuer.example.com,login.example.net"),
        "");

    controller.login(request(session), response.proxy(), "/browse/");

    URI redirect = URI.create(response.redirect);
    assertEquals("login.example.net", redirect.getHost());
    assertEquals("/oauth2/authorize", redirect.getPath());
  }

  @Test
  void loginBuildsRedirectUriFromConfiguredExternalBaseUrl() throws Exception {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
        "clientId", "kkrepo",
        "issuerUri", "https://localhost",
        "authorizationEndpoint", "https://localhost/oauth2/authorize")));
    OidcLoginController controller = controller(
        authentication,
        OutboundRequestPolicy.allowPrivateForTests(),
        "https://nexus.example.com/");

    controller.login(request(session), response.proxy(), "/browse/");

    URI redirect = URI.create(response.redirect);
    assertEquals(
        "https://nexus.example.com/internal/security/oidc/callback",
        query(redirect).get("redirect_uri"));
  }

  @Test
  void loginRequiresConfiguredRedirectUriOrExternalBaseUrl() {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
        "clientId", "kkrepo",
        "issuerUri", "https://localhost",
        "authorizationEndpoint", "https://localhost/oauth2/authorize")));
    OidcLoginController controller = controller(authentication);

    ResponseStatusException error = assertThrows(
        ResponseStatusException.class,
        () -> controller.login(request(session), response.proxy(), "/browse/"));

    assertTrue(error.getStatusCode().isSameCodeAs(org.springframework.http.HttpStatus.BAD_REQUEST));
    assertEquals(
        "OIDC redirectUri or kkrepo.security.external-base-url must be configured",
        error.getReason());
  }

  @Test
  void loginAllowsDiscoveredAuthorizationEndpointOnDifferentHostThanIssuer() throws Exception {
    try (TestOidcDiscovery discovery = oidcDiscoveryServer("127.0.0.1", "localhost", false)) {
      SessionState session = new SessionState();
      ResponseState response = new ResponseState();
      StubAuthenticationService authentication = new StubAuthenticationService();
      authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
          "clientId", "kkrepo",
          "issuerUri", discovery.issuer(),
          "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
      OidcLoginController controller = controller(authentication);

      controller.login(request(session), response.proxy(), "/browse/");

      URI redirect = URI.create(response.redirect);
      assertEquals("localhost", redirect.getHost());
      assertEquals("/oauth2/authorize", redirect.getPath());
    }
  }

  @Test
  void discoveryConnectsToThePolicyResolvedAddressWithoutASecondDnsLookup() throws Exception {
    try (TestOidcDiscovery discovery =
        oidcDiscoveryServer("issuer.rebind.invalid", "login.rebind.invalid", false)) {
      SessionState session = new SessionState();
      ResponseState response = new ResponseState();
      StubAuthenticationService authentication = new StubAuthenticationService();
      authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
          "clientId", "kkrepo",
          "issuerUri", discovery.issuer(),
          "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
      OutboundRequestPolicy pinnedPolicy = new OutboundRequestPolicy(
          true,
          "",
          host -> new InetAddress[] {InetAddress.getByAddress(
              host, new byte[] {127, 0, 0, 1})});
      OidcLoginController controller = controller(authentication, pinnedPolicy, "");

      controller.login(request(session), response.proxy(), "/browse/");

      assertEquals("login.rebind.invalid", URI.create(response.redirect).getHost());
    }
  }

  @Test
  void discoveryRejectsIssuerMismatch() throws Exception {
    try (TestOidcDiscovery discovery = oidcDiscoveryServer("127.0.0.1", "localhost", true)) {
      SessionState session = new SessionState();
      ResponseState response = new ResponseState();
      StubAuthenticationService authentication = new StubAuthenticationService();
      authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
          "clientId", "kkrepo",
          "issuerUri", discovery.issuer(),
          "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
      OidcLoginController controller = controller(authentication);

      SecurityValidationException error = assertThrows(
          SecurityValidationException.class,
          () -> controller.login(request(session), response.proxy(), "/browse/"));

      assertEquals("OIDC discovery issuer must match configured issuer", error.getMessage());
    }
  }

  @Test
  void discoveryRejectsNonSuccessfulResponse() throws Exception {
    HttpServer discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    discovery.createContext("/.well-known/openid-configuration", exchange -> {
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
    });
    discovery.start();
    try {
      SessionState session = new SessionState();
      StubAuthenticationService authentication = new StubAuthenticationService();
      authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
          "clientId", "kkrepo",
          "issuerUri", "http://127.0.0.1:" + discovery.getAddress().getPort(),
          "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
      OidcLoginController controller = controller(authentication);

      ResponseStatusException error = assertThrows(
          ResponseStatusException.class,
          () -> controller.login(
              request(session), new ResponseState().proxy(), "/browse/"));

      assertTrue(error.getStatusCode().isSameCodeAs(org.springframework.http.HttpStatus.BAD_REQUEST));
      assertEquals("OIDC discovery endpoint returned 503", error.getReason());
    } finally {
      discovery.stop(0);
    }
  }

  @Test
  void basicLoginStoresAuthenticatedSubjectInSessionAndRedirectsBack() throws Exception {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new PermissionSubject("Local", "admin", Set.of("nx-admin"), null));
    OidcLoginController controller = controller(new StubAuthenticationService());

    controller.basicLogin(request(session, Map.of(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject)), response.proxy(), "/admin/#security-users");

    assertEquals(
        new SecurityAuthenticationService.SessionSubject("Local", "admin", "local", Set.of()),
        session.attributes.get(AuthenticatedSubject.SESSION_ATTRIBUTE));
    assertEquals("/admin/#security-users", response.redirect);
  }

  @Test
  void passwordLoginStoresAuthenticatedSubjectAndReturnsSafeRedirect() {
    SessionState session = new SessionState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new PermissionSubject("Local", "admin", Set.of("nx-admin"), null));
    authentication.credentialSubject = Optional.of(subject);
    OidcLoginController controller = controller(authentication);

    OidcLoginController.LoginResult result = controller.passwordLogin(
        request(session),
        new OidcLoginController.LoginCommand("admin", "admin123", "/admin/#security-users"));

    assertEquals("admin", authentication.presentedUsername);
    assertEquals("admin123", authentication.presentedPassword);
    assertEquals(
        new SecurityAuthenticationService.SessionSubject("Local", "admin", "local", Set.of()),
        session.attributes.get(AuthenticatedSubject.SESSION_ATTRIBUTE));
    assertEquals("/admin/#security-users", result.returnTo());
  }

  @Test
  void loginOptionsExposeActiveExternalRealms() {
    StubAuthenticationService authentication = new StubAuthenticationService();
    OidcLoginController controller = controller(authentication);

    assertEquals(false, controller.loginOptions().oidcEnabled());
    assertEquals(false, controller.loginOptions().ldapEnabled());

    authentication.oidcRealm = Optional.of(oidcRealm(Map.of("clientId", "kkrepo")));

    assertEquals(true, controller.loginOptions().oidcEnabled());
    assertEquals(false, controller.loginOptions().ldapEnabled());

    authentication.ldapRealm = Optional.of(new SecurityRealmRecord(
        2L,
        "ldap",
        "LDAP",
        "LDAP",
        true,
        10,
        Map.of("source", "LDAP")));

    assertEquals(true, controller.loginOptions().oidcEnabled());
    assertEquals(true, controller.loginOptions().ldapEnabled());
  }

  @Test
  void logoutClearsSessionAndSuppressesCachedBasicCredentials() throws Exception {
    SessionState session = new SessionState();
    session.attributes.put(AuthenticatedSubject.SESSION_ATTRIBUTE, new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new PermissionSubject("Local", "admin", Set.of("nx-admin"), null)));
    session.exists = true;
    ResponseState response = new ResponseState();
    OidcLoginController controller = controller(new StubAuthenticationService());

    controller.logout(request(session), response.proxy(), null);

    assertEquals(Boolean.TRUE, session.attributes.get(SecurityAuthenticationService.BASIC_AUTH_SUPPRESSED_ATTRIBUTE));
    assertEquals("/browse/#browse/welcome", response.redirect);
  }

  @Test
  void callbackValidatesTokenStoresSessionAndRedirectsBack() throws Exception {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "OIDC",
        "alice",
        "oidc",
        null,
        new PermissionSubject("OIDC", "alice", Set.of("nx-admin"), null));
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
        "clientId", "kkrepo",
        "issuerUri", "https://localhost",
        "authorizationEndpoint", "https://localhost/oauth2/authorize",
        "redirectUri", "http://nexus.example.com/callback")));
    authentication.subject = Optional.of(subject);
    OidcLoginController controller = controller(authentication);
    controller.login(request(session), response.proxy(), "/browse/");
    String state = query(URI.create(response.redirect)).get("state");

    ResponseState callbackResponse = new ResponseState();
    controller.callback(
        request(session),
        callbackResponse.proxy(),
        state,
        null,
        "header.claims.signature",
        null,
        null,
        null);

    assertEquals("header.claims.signature", authentication.presentedToken);
    assertEquals(
        new SecurityAuthenticationService.SessionSubject("OIDC", "alice", "oidc", Set.of()),
        session.attributes.get(AuthenticatedSubject.SESSION_ATTRIBUTE));
    assertEquals("/browse/", callbackResponse.redirect);
  }

  @Test
  void callbackPostsTokenRequestToThePolicyResolvedAddress() throws Exception {
    AtomicReference<String> requestMethod = new AtomicReference<>();
    AtomicReference<String> requestContentType = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    tokenServer.createContext("/oauth2/token", exchange -> {
      requestMethod.set(exchange.getRequestMethod());
      requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = "{\"id_token\":\"header.claims.signature\"}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    tokenServer.start();
    try {
      int port = tokenServer.getAddress().getPort();
      SessionState session = new SessionState();
      ResponseState loginResponse = new ResponseState();
      StubAuthenticationService authentication = new StubAuthenticationService();
      authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
          "clientId", "kkrepo",
          "clientSecret", "top-secret",
          "issuerUri", "http://issuer.rebind.invalid:" + port,
          "authorizationEndpoint", "http://login.rebind.invalid:" + port + "/oauth2/authorize",
          "tokenEndpoint", "http://token.rebind.invalid:" + port + "/oauth2/token",
          "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
      authentication.subject = Optional.of(new AuthenticatedSubject(
          "OIDC",
          "alice",
          "oidc",
          null,
          new PermissionSubject("OIDC", "alice", Set.of("nx-admin"), null)));
      OutboundRequestPolicy pinnedPolicy = new OutboundRequestPolicy(
          true,
          "",
          host -> new InetAddress[] {InetAddress.getByAddress(
              host, new byte[] {127, 0, 0, 1})});
      OidcLoginController controller = controller(authentication, pinnedPolicy, "");
      controller.login(request(session), loginResponse.proxy(), "/browse/");
      String state = query(URI.create(loginResponse.redirect)).get("state");

      ResponseState callbackResponse = new ResponseState();
      controller.callback(
          request(session),
          callbackResponse.proxy(),
          state,
          "one-time-code",
          null,
          null,
          null,
          null);

      assertEquals("POST", requestMethod.get());
      assertEquals("application/x-www-form-urlencoded", requestContentType.get());
      Map<String, String> form = query(URI.create("http://localhost/?" + requestBody.get()));
      assertEquals("authorization_code", form.get("grant_type"));
      assertEquals("one-time-code", form.get("code"));
      assertEquals("top-secret", form.get("client_secret"));
      assertEquals("header.claims.signature", authentication.presentedToken);
      assertEquals("/browse/", callbackResponse.redirect);
    } finally {
      tokenServer.stop(0);
    }
  }

  @Test
  void callbackRejectsMissingOrMismatchedState() {
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of("clientId", "kkrepo")));
    OidcLoginController controller = controller(authentication);

    ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
        controller.callback(
            request(new SessionState()),
            new ResponseState().proxy(),
            "wrong",
            null,
            "header.claims.signature",
            null,
            null,
            null));

    assertTrue(error.getStatusCode().isSameCodeAs(org.springframework.http.HttpStatus.UNAUTHORIZED));
  }

  private OidcLoginController controller(SecurityAuthenticationService authentication) {
    return controller(authentication, OutboundRequestPolicy.allowPrivateForTests(), "");
  }

  private OidcLoginController controller(
      SecurityAuthenticationService authentication,
      OutboundRequestPolicy outboundPolicy,
      String externalBaseUrl) {
    return new OidcLoginController(
        authentication,
        new ObjectMapper(),
        outboundPolicy,
        transportFactory,
        null,
        externalBaseUrl);
  }

  private static SecurityRealmRecord oidcRealm(Map<String, Object> attributes) {
    return new SecurityRealmRecord(1L, "oidc", "OIDC", "OIDC", true, 0, attributes);
  }

  private static HttpServletRequest request(SessionState session) {
    return request(session, Map.of());
  }

  private static HttpServletRequest request(SessionState session, Map<String, Object> requestAttributes) {
    return (HttpServletRequest) Proxy.newProxyInstance(
        OidcLoginControllerTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, invoked, args) -> switch (invoked.getName()) {
          case "getAttribute" -> requestAttributes.get(String.valueOf(args[0]));
          case "getSession" -> {
            if (args == null || args.length == 0 || Boolean.TRUE.equals(args[0])) {
              yield session.proxy();
            }
            yield session.exists ? session.proxy() : null;
          }
          case "getScheme" -> "http";
          case "getServerName" -> "127.0.0.1";
          case "getServerPort" -> 18090;
          case "getContextPath" -> "";
          case "getHeader" -> null;
          case "getDispatcherType" -> DispatcherType.REQUEST;
          case "toString" -> "OidcLoginRequest";
          default -> primitiveDefault(invoked.getReturnType());
        });
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> values = new LinkedHashMap<>();
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return values;
    }
    for (String part : query.split("&")) {
      int separator = part.indexOf('=');
      if (separator < 0) {
        values.put(decode(part), "");
      } else {
        values.put(decode(part.substring(0, separator)), decode(part.substring(separator + 1)));
      }
    }
    return values;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static TestOidcDiscovery oidcDiscoveryServer(
      String issuerHost,
      String authorizationHost,
      boolean issuerMismatch) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();
    String issuer = "http://" + issuerHost + ":" + port;
    String authorizationEndpoint = "http://" + authorizationHost + ":" + port + "/oauth2/authorize";
    String tokenEndpoint = "http://" + authorizationHost + ":" + port + "/oauth2/token";
    String documentIssuer = issuerMismatch ? "http://mismatch.example.com" : issuer;
    String body = new ObjectMapper().writeValueAsString(Map.of(
        "issuer", documentIssuer,
        "authorization_endpoint", authorizationEndpoint,
        "token_endpoint", tokenEndpoint));
    server.createContext("/.well-known/openid-configuration", exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return new TestOidcDiscovery(server, issuer);
  }

  private static Object primitiveDefault(Class<?> type) {
    if (boolean.class.equals(type)) {
      return false;
    }
    if (int.class.equals(type) || long.class.equals(type) || short.class.equals(type) || byte.class.equals(type)) {
      return 0;
    }
    if (char.class.equals(type)) {
      return '\0';
    }
    return null;
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private Optional<SecurityRealmRecord> oidcRealm = Optional.empty();
    private Optional<SecurityRealmRecord> ldapRealm = Optional.empty();
    private Optional<AuthenticatedSubject> subject = Optional.empty();
    private Optional<AuthenticatedSubject> credentialSubject = Optional.empty();
    private String presentedToken;
    private String presentedUsername;
    private String presentedPassword;

    private StubAuthenticationService() {
      super(
          new SessionRoleDao(),
          new ObjectMapper(),
          "X-Nexus-Plus-Token",
          "nx-anonymous",
          null);
    }

    @Override
    public Optional<SecurityRealmRecord> activeOidcRealm() {
      return oidcRealm;
    }

    @Override
    public Optional<SecurityRealmRecord> activeLdapRealm() {
      return ldapRealm;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateOidcToken(String token) {
      presentedToken = token;
      return subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateCredentials(String username, String password) {
      presentedUsername = username;
      presentedPassword = password;
      return credentialSubject;
    }
  }

  private static class SessionRoleDao extends SecurityDaoAdapter {
    private SessionRoleDao() {
      super(null, null);
    }

    @Override
    public List<String> listUserRoleIds(String source, String userId) {
      return List.of("nx-admin");
    }
  }

  private record TestOidcDiscovery(HttpServer server, String issuer) implements AutoCloseable {
    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static class SessionState {
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private boolean exists;

    private HttpSession proxy() {
      exists = true;
      return (HttpSession) Proxy.newProxyInstance(
          OidcLoginControllerTest.class.getClassLoader(),
          new Class<?>[] {HttpSession.class},
          (proxy, invoked, args) -> switch (invoked.getName()) {
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
              exists = false;
              yield null;
            }
            case "toString" -> "SessionState";
            default -> primitiveDefault(invoked.getReturnType());
          });
    }
  }

  private static class ResponseState {
    private String redirect;

    private HttpServletResponse proxy() {
      return (HttpServletResponse) Proxy.newProxyInstance(
          OidcLoginControllerTest.class.getClassLoader(),
          new Class<?>[] {HttpServletResponse.class},
          (proxy, invoked, args) -> {
            if ("sendRedirect".equals(invoked.getName())) {
              redirect = String.valueOf(args[0]);
              return null;
            }
            if ("toString".equals(invoked.getName())) {
              return "ResponseState";
            }
            return primitiveDefault(invoked.getReturnType());
          });
    }
  }
}
