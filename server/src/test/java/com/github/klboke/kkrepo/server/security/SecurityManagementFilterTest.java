package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;

class SecurityManagementFilterTest {

  @Test
  void managementFilterRunsAfterSpringSessionRepositoryFilter() {
    Order order = SecurityManagementFilter.class.getAnnotation(Order.class);

    assertEquals(SecurityManagementFilter.FILTER_ORDER, order.value());
    assertTrue(order.value() > SessionRepositoryFilter.DEFAULT_ORDER);
  }

  @Test
  void currentApiKeysRequireAuthenticationOnly() throws Exception {
    AuthenticatedSubject subject = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request("GET", "/service/rest/v1/security/api-keys/current");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(0, response.status);
  }

  @Test
  void internalUiPermissionsRequireAuthenticationOnly() throws Exception {
    AuthenticatedSubject subject = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request("GET", "/service/rest/internal/ui/security/permissions");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(0, response.status);
  }

  @Test
  void internalPermissionsRequireAuthenticationOnly() throws Exception {
    AuthenticatedSubject subject = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security, false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request("GET", "/internal/security/permissions");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(0, response.status);
  }

  @Test
  void internalSessionRequiresAuthenticationOnly() throws Exception {
    AuthenticatedSubject subject = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request("GET", "/internal/security/session");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(0, response.status);
  }

  @Test
  void currentUserAccountRestRequiresAuthenticationOnly() throws Exception {
    AuthenticatedSubject subject = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request("PUT", "/service/rest/internal/ui/user");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(0, response.status);
  }

  @Test
  void currentUserAccountPasswordRequiresChangePasswordPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("PUT", "/service/rest/internal/ui/user/admin/password"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:userschangepw:create", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void basicBrowserLoginRequiresAuthenticationOnly() throws Exception {
    AuthenticatedSubject subject = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request("GET", "/internal/security/basic/login");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(0, response.status);
  }

  @Test
  void raptureSessionCreateBypassesManagementFilterLikeNexusSessionServlet() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request("POST", "/service/rapture/session");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(0, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void raptureSessionDeleteBypassesAuthenticationLikeNexusLogout() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("DELETE", "/service/rapture/session"),
        response.proxy(),
        chain);

    assertEquals(0, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void currentApiKeysRejectUnauthenticatedRequestsWithoutBrowserBasicChallenge() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.allow());
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/internal/security/api-keys/current"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals(null, response.headers.get("WWW-Authenticate"));
  }

  @Test
  void internalSessionDoesNotTriggerBrowserBasicChallengeWhenUnauthenticated() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.allow());
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/security/session"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals(null, response.headers.get("WWW-Authenticate"));
  }

  @Test
  void internalUiRequestsDoNotTriggerBrowserBasicChallengeWhenUnauthenticated() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.allow());
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/blob-stores"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals(null, response.headers.get("WWW-Authenticate"));
  }

  @Test
  void internalUiBrowserNavigationsRedirectToWelcomeWhenUnauthenticated() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.allow());
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/blob-stores", Map.of("Sec-Fetch-Mode", "navigate")),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FOUND, response.status);
    assertEquals("/browse/?login=1#browse/welcome", response.redirect);
    assertEquals(null, response.headers.get("WWW-Authenticate"));
  }

  @Test
  void serviceRestSecurityStillTriggersBasicChallengeWhenUnauthenticated() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.allow());
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/service/rest/v1/security/roles"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals("Basic realm=\"kkrepo\"", response.headers.get("WWW-Authenticate"));
  }

  @Test
  void customLoginEndpointsBypassManagementFilter() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);

    ResponseState optionsResponse = new ResponseState();
    ChainState optionsChain = new ChainState();
    filter.doFilter(
        request("GET", "/internal/security/login/options"),
        optionsResponse.proxy(),
        optionsChain);

    ResponseState loginResponse = new ResponseState();
    ChainState loginChain = new ChainState();
    filter.doFilter(
        request("POST", "/internal/security/login"),
        loginResponse.proxy(),
        loginChain);

    assertEquals(0, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, optionsChain.calls);
    assertEquals(1, loginChain.calls);
    assertEquals(0, optionsResponse.status);
    assertEquals(0, loginResponse.status);
  }

  @Test
  void adminBootstrapEndpointsBypassManagementFilter() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);

    ResponseState statusResponse = new ResponseState();
    ChainState statusChain = new ChainState();
    filter.doFilter(
        request("GET", "/internal/security/bootstrap"),
        statusResponse.proxy(),
        statusChain);

    ResponseState createResponse = new ResponseState();
    ChainState createChain = new ChainState();
    filter.doFilter(
        request("POST", "/internal/security/bootstrap/admin"),
        createResponse.proxy(),
        createChain);

    assertEquals(0, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, statusChain.calls);
    assertEquals(1, createChain.calls);
    assertEquals(0, statusResponse.status);
    assertEquals(0, createResponse.status);
  }

  @Test
  void uiSettingsReadBypassesManagementFilterForEarlyLocalization() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/ui-settings"),
        response.proxy(),
        chain);

    assertEquals(0, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void uiSettingsUpdateRequiresSettingsUpdatePermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("PUT", "/internal/ui-settings"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:settings:update", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void adminApiKeysStillRequireApiKeyManagementPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/service/rest/v1/security/api-keys"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:apikey:read", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void serviceRestAnonymousReadRequiresSettingsReadPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/service/rest/v1/security/anonymous"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:settings:read", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void auditLogReadRequiresSettingsReadPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/security/audit-log"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:settings:read", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void serviceRestAnonymousUpdateRequiresSettingsUpdatePermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("PUT", "/service/rest/v1/security/anonymous"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:settings:update", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void internalUiUploadRequiresAuthenticationOnly() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/service/rest/internal/ui/upload/maven-releases"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void serviceRestComponentsUploadRequiresAuthenticationOnly() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/service/rest/v1/components"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void internalBlobStoreListRequiresBlobStoreReadPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/blob-stores"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:blobstores:read", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void internalBlobStoreCreateRequiresBlobStoreCreatePermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/internal/blob-stores"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:blobstores:create", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void internalBlobStoreCheckRequiresBlobStoreUpdatePermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("admin")));
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("missing permission"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/internal/blob-stores/7/check"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(1, security.decisions);
    assertEquals("nexus:blobstores:update", security.requestedPermission);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void oidcBrowserLoginEndpointsBypassManagementPermissions() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.deny("should not be checked"));
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/security/oidc/login"),
        response.proxy(),
        chain);

    assertEquals(0, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void unknownInternalPathsAreFailClosedToAuthenticatedSubjects() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingSecurityService security = new RecordingSecurityService(AccessDecision.allow());
    SecurityManagementFilter filter = new SecurityManagementFilter(authentication, security);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/internal/status"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.calls);
    assertEquals(0, security.decisions);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "Local",
        userId,
        "local",
        null,
        new PermissionSubject("Local", userId, Set.of(), null));
  }

  private static HttpServletRequest request(String method, String uri) {
    return request(method, uri, Map.of());
  }

  private static HttpServletRequest request(String method, String uri, Map<String, String> headers) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    return (HttpServletRequest) Proxy.newProxyInstance(
        SecurityManagementFilterTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, invoked, args) -> switch (invoked.getName()) {
          case "getMethod" -> method;
          case "getRequestURI" -> uri;
          case "getContextPath" -> "";
          case "getHeader" -> headers.get(String.valueOf(args[0]));
          case "getDispatcherType" -> DispatcherType.REQUEST;
          case "getAttribute" -> attributes.get(String.valueOf(args[0]));
          case "setAttribute" -> {
            attributes.put(String.valueOf(args[0]), args[1]);
            yield null;
          }
          case "removeAttribute" -> {
            attributes.remove(String.valueOf(args[0]));
            yield null;
          }
          case "toString" -> method + " " + uri;
          default -> primitiveDefault(invoked.getReturnType());
        });
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
    private final Optional<AuthenticatedSubject> subject;
    private int calls;

    private StubAuthenticationService(Optional<AuthenticatedSubject> subject) {
      super(new SecurityDao(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      calls++;
      return subject;
    }
  }

  private static class RecordingSecurityService extends SecurityManagementService {
    private final AccessDecision decision;
    private int decisions;
    private String requestedPermission;

    private RecordingSecurityService(AccessDecision decision) {
      super(new SecurityDao(null, null));
      this.decision = decision;
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, String requestedPermission) {
      decisions++;
      this.requestedPermission = requestedPermission;
      return decision;
    }
  }

  private static class ResponseState {
    private int status;
    private String message;
    private String redirect;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private HttpServletResponse proxy() {
      return (HttpServletResponse) Proxy.newProxyInstance(
          SecurityManagementFilterTest.class.getClassLoader(),
          new Class<?>[] {HttpServletResponse.class},
          (proxy, invoked, args) -> {
            if ("sendError".equals(invoked.getName())) {
              status = (Integer) args[0];
              message = args.length > 1 ? String.valueOf(args[1]) : null;
              return null;
            }
            if ("sendRedirect".equals(invoked.getName())) {
              status = HttpServletResponse.SC_FOUND;
              redirect = String.valueOf(args[0]);
              headers.put("Location", redirect);
              return null;
            }
            if ("setHeader".equals(invoked.getName())) {
              headers.put(String.valueOf(args[0]), String.valueOf(args[1]));
              return null;
            }
            if ("toString".equals(invoked.getName())) {
              return "ResponseState";
            }
            return primitiveDefault(invoked.getReturnType());
          });
    }
  }

  private static class ChainState implements FilterChain {
    private int calls;

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
      calls++;
    }
  }
}
