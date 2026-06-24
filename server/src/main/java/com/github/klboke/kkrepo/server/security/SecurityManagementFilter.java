package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.AccessDecision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(SecurityManagementFilter.FILTER_ORDER)
public class SecurityManagementFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 10;
  static final String REQUESTED_PERMISSION_ATTRIBUTE =
      SecurityManagementFilter.class.getName() + ".REQUESTED_PERMISSION";
  private static final String AUTHENTICATED_ONLY = "__authenticated__";
  private static final String AUTH_REQUIRED_WELCOME = "/browse/?login=1#browse/welcome";

  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;

  public SecurityManagementFilter(
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.authenticationService = authenticationService;
    this.securityService = securityService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Optional<String> permission = permissionFor(request);
    if (permission.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }
    request.setAttribute(REQUESTED_PERMISSION_ATTRIBUTE, permission.get());

    Optional<AuthenticatedSubject> authenticated = authenticationService.authenticate(request);
    if (authenticated.isEmpty()) {
      challenge(request, response);
      return;
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated.get());

    if (AUTHENTICATED_ONLY.equals(permission.get())) {
      filterChain.doFilter(request, response);
      return;
    }

    AccessDecision decision = securityService.decide(authenticated.get().permissionSubject(), permission.get());
    if (!decision.allowed()) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, decision.reason());
      return;
    }
    filterChain.doFilter(request, response);
  }

  private Optional<String> permissionFor(HttpServletRequest request) {
    String method = request.getMethod().toUpperCase();
    String uri = stripContextPath(request);
    if (uri.startsWith("/service/rest/v1/security/")) {
      return serviceRestPermission(method, uri);
    }
    if (uri.equals("/service/rest/v1/components") && "POST".equals(method)) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (uri.equals("/service/rapture/session")) {
      return Optional.empty();
    }
    if (uri.equals("/service/rest/internal/ui/anonymous-settings")) {
      return Optional.of("GET".equals(method) ? "nexus:settings:read" : "nexus:settings:update");
    }
    if (uri.startsWith("/service/rest/internal/ui/upload/")) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (uri.equals("/service/rest/internal/ui/user")) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (uri.startsWith("/service/rest/internal/ui/user/")) {
      if (uri.endsWith("/password") && "PUT".equals(method)) {
        return Optional.of("nexus:userschangepw:create");
      }
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (uri.startsWith("/service/rest/internal/ui/security/")) {
      return internalUiSecurityPermission(method, uri);
    }
    if (uri.startsWith("/internal/blob-stores")) {
      return internalBlobStorePermission(method, uri);
    }
    if (uri.equals("/internal/ui-settings")) {
      return "GET".equals(method)
          ? Optional.empty()
          : Optional.of("nexus:settings:update");
    }
    if (uri.startsWith("/internal/security/")) {
      return internalSecurityPermission(method, uri);
    }
    if (uri.startsWith("/internal/repositories")
        || uri.startsWith("/internal/browse")
        || uri.startsWith("/internal/search/components")
        || uri.startsWith("/internal/migration/nexus")) {
      return Optional.empty();
    }
    if (uri.startsWith("/internal/") || uri.startsWith("/service/rest/internal/")) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    return Optional.empty();
  }

  private Optional<String> serviceRestPermission(String method, String uri) {
    String path = uri.substring("/service/rest/v1/security/".length());
    if (path.equals("user-sources")) {
      return Optional.of("nexus:users:read");
    }
    if (path.startsWith("users/") || path.equals("users")) {
      if (path.endsWith("/change-password") && "PUT".equals(method)) {
        return Optional.of("nexus:*");
      }
      return Optional.of("nexus:users:" + crudAction(method));
    }
    if (path.startsWith("roles/") || path.equals("roles")) {
      return Optional.of("nexus:roles:" + crudAction(method));
    }
    if (path.startsWith("privileges/") || path.equals("privileges")) {
      return Optional.of("nexus:privileges:" + crudAction(method));
    }
    if (path.startsWith("realms/")) {
      return Optional.of("GET".equals(method) ? "nexus:settings:read" : "nexus:settings:update");
    }
    if (path.startsWith("content-selectors/") || path.equals("content-selectors")) {
      return Optional.of("nexus:selectors:" + crudAction(method));
    }
    if (path.equals("api-keys/current") || path.startsWith("api-keys/current/")) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (path.startsWith("api-keys/") || path.equals("api-keys")) {
      return Optional.of("nexus:apikey:" + crudAction(method));
    }
    return Optional.of("nexus:settings:" + ("GET".equals(method) ? "read" : "update"));
  }

  private Optional<String> internalUiSecurityPermission(String method, String uri) {
    String path = uri.substring("/service/rest/internal/ui/security/".length());
    if (path.equals("permissions")) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (path.equals("realm-settings") || path.equals("realm-types")) {
      return Optional.of("nexus:settings:" + ("GET".equals(method) ? "read" : "update"));
    }
    if (path.startsWith("users/") || path.equals("users") || path.equals("user-sources")) {
      if (path.endsWith("/change-password") && "PUT".equals(method)) {
        return Optional.of("nexus:userschangepw:create");
      }
      return Optional.of("nexus:users:" + crudAction(method));
    }
    if (path.startsWith("roles/") || path.equals("roles")
        || path.equals("role-references") || path.equals("role-sources")) {
      return Optional.of("nexus:roles:" + crudAction(method));
    }
    if (path.startsWith("privileges/") || path.equals("privileges")
        || path.equals("privilege-references") || path.equals("privilege-types")) {
      return Optional.of("nexus:privileges:" + crudAction(method));
    }
    return Optional.of("nexus:settings:" + ("GET".equals(method) ? "read" : "update"));
  }

  private Optional<String> internalBlobStorePermission(String method, String uri) {
    if ("POST".equals(method) && uri.matches("/internal/blob-stores/[^/]+/check")) {
      return Optional.of("nexus:blobstores:update");
    }
    return Optional.of("nexus:blobstores:" + crudAction(method));
  }

  private Optional<String> internalSecurityPermission(String method, String uri) {
    String path = uri.substring("/internal/security/".length());
    if (path.equals("bootstrap") || path.equals("bootstrap/admin")) {
      return Optional.empty();
    }
    if (path.equals("session") || path.equals("basic/login")) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (path.equals("login/options") || path.equals("login")) {
      return Optional.empty();
    }
    if (path.equals("summary")) {
      return Optional.of("nexus:settings:read");
    }
    if (path.equals("audit-log")) {
      return Optional.of("nexus:settings:read");
    }
    if (path.equals("oidc/login") || path.equals("oidc/callback") || path.equals("logout")) {
      return Optional.empty();
    }
    if (path.startsWith("users/") || path.equals("users")) {
      return Optional.of("nexus:users:" + crudAction(method));
    }
    if (path.startsWith("roles/") || path.equals("roles")) {
      return Optional.of("nexus:roles:" + crudAction(method));
    }
    if (path.startsWith("privileges/") || path.equals("privileges")) {
      return Optional.of("nexus:privileges:" + crudAction(method));
    }
    if (path.startsWith("realms/") || path.equals("realms")) {
      return Optional.of("GET".equals(method) ? "nexus:settings:read" : "nexus:settings:update");
    }
    if (path.equals("ldap")) {
      return Optional.of("GET".equals(method) ? "nexus:ldap:read" : "nexus:ldap:update");
    }
    if (path.equals("api-keys/current") || path.startsWith("api-keys/current/")) {
      return Optional.of(AUTHENTICATED_ONLY);
    }
    if (path.startsWith("api-keys/") || path.equals("api-keys")) {
      return Optional.of("nexus:apikey:" + crudAction(method));
    }
    return Optional.of("nexus:settings:" + ("GET".equals(method) ? "read" : "update"));
  }

  private String crudAction(String method) {
    return switch (method) {
      case "GET", "HEAD" -> "read";
      case "POST" -> "create";
      case "PUT", "PATCH" -> "update";
      case "DELETE" -> "delete";
      default -> "*";
    };
  }

  private String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      uri = uri.substring(contextPath.length());
    }
    return uri.replace("%2f", "/").replace("%2F", "/");
  }

  private void challenge(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String uri = stripContextPath(request);
    if (shouldRedirectToWelcome(request, uri)) {
      response.sendRedirect(AUTH_REQUIRED_WELCOME);
      return;
    }
    if (shouldSendBasicChallenge(request, uri)) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"kkrepo\"");
    }
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
  }

  private boolean shouldRedirectToWelcome(HttpServletRequest request, String uri) {
    return !uri.equals("/internal/security/basic/login")
        && isUiInternalEndpoint(uri)
        && isBrowserNavigation(request);
  }

  private boolean shouldSendBasicChallenge(HttpServletRequest request, String uri) {
    if (isUiInternalEndpoint(uri) && !uri.equals("/internal/security/basic/login")) {
      return false;
    }
    return uri.equals("/internal/security/basic/login")
        || (!basicAuthSuppressed(request) && !uri.equals("/internal/security/session"));
  }

  private boolean isUiInternalEndpoint(String uri) {
    return uri.startsWith("/internal/") || uri.startsWith("/service/rest/internal/");
  }

  private boolean isBrowserNavigation(HttpServletRequest request) {
    String fetchMode = request.getHeader("Sec-Fetch-Mode");
    if ("navigate".equalsIgnoreCase(fetchMode)) {
      return true;
    }
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
  }

  private boolean basicAuthSuppressed(HttpServletRequest request) {
    return Optional.ofNullable(request.getSession(false))
        .map(session -> session.getAttribute(SecurityAuthenticationService.BASIC_AUTH_SUPPRESSED_ATTRIBUTE))
        .filter(Boolean.TRUE::equals)
        .isPresent();
  }
}
