package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRealmRecord;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OidcLoginControllerTest {

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
    OidcLoginController controller = new OidcLoginController(authentication, new ObjectMapper());

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
    OidcLoginController controller = new OidcLoginController(
        authentication,
        new ObjectMapper(),
        new OutboundRequestPolicy(false, ""),
        new ForwardedHeaderPolicy(""),
        null,
        "");

    SecurityValidationException error = assertThrows(
        SecurityValidationException.class,
        () -> controller.login(request(session), response.proxy(), "/browse/"));

    assertTrue(error.getMessage().contains("OIDC authorization endpoint URL resolves to a private or local address"));
  }

  @Test
  void loginRejectsAuthorizationEndpointOnDifferentHostThanIssuer() {
    SessionState session = new SessionState();
    ResponseState response = new ResponseState();
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of(
        "clientId", "kkrepo",
        "issuerUri", "https://issuer.example.com",
        "authorizationEndpoint", "https://login.example.net/oauth2/authorize",
        "redirectUri", "http://nexus.example.com/internal/security/oidc/callback")));
    OidcLoginController controller = new OidcLoginController(
        authentication,
        new ObjectMapper(),
        new OutboundRequestPolicy(false, "issuer.example.com,login.example.net"),
        new ForwardedHeaderPolicy(""),
        null,
        "");

    SecurityValidationException error = assertThrows(
        SecurityValidationException.class,
        () -> controller.login(request(session), response.proxy(), "/browse/"));

    assertEquals(
        "OIDC authorization endpoint host must match configured issuer/discovery host",
        error.getMessage());
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
    OidcLoginController controller = new OidcLoginController(new StubAuthenticationService(), new ObjectMapper());

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
    OidcLoginController controller = new OidcLoginController(authentication, new ObjectMapper());

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
    OidcLoginController controller = new OidcLoginController(authentication, new ObjectMapper());

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
    OidcLoginController controller = new OidcLoginController(new StubAuthenticationService(), new ObjectMapper());

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
    OidcLoginController controller = new OidcLoginController(authentication, new ObjectMapper());
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
  void callbackRejectsMissingOrMismatchedState() {
    StubAuthenticationService authentication = new StubAuthenticationService();
    authentication.oidcRealm = Optional.of(oidcRealm(Map.of("clientId", "kkrepo")));
    OidcLoginController controller = new OidcLoginController(authentication, new ObjectMapper());

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
      super(new SessionRoleDao(), new ObjectMapper(), "X-Nexus-Plus-Token");
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

  private static class SessionRoleDao extends SecurityDao {
    private SessionRoleDao() {
      super(null, null);
    }

    @Override
    public List<String> listUserRoleIds(String source, String userId) {
      return List.of("nx-admin");
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
