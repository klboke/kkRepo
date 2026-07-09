package com.github.klboke.kkrepo.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(CsrfProtectionFilter.FILTER_ORDER)
public class CsrfProtectionFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 6;
  public static final String CSRF_HEADER = "X-Nexus-Plus-CSRF-Token";
  public static final String CSRF_COOKIE = "KKREPO_CSRF";
  private static final String SESSION_ATTRIBUTE = CsrfProtectionFilter.class.getName() + ".TOKEN";
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final String apiKeyHeader;
  private final boolean cookieSecure;
  private final NexusLegacyUiCompatibility legacyUi;

  public CsrfProtectionFilter(
      @Value("${kkrepo.security.token-header:X-Nexus-Plus-Token}") String apiKeyHeader,
      @Value("${kkrepo.security.csrf.cookie-secure:false}") boolean cookieSecure,
      NexusLegacyUiCompatibility legacyUi) {
    this.apiKeyHeader = apiKeyHeader;
    this.cookieSecure = cookieSecure;
    this.legacyUi = legacyUi;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (!csrfProtectedPath(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
    boolean safe = SAFE_METHODS.contains(method);
    String token = csrfToken(request, safe && csrfTokenBootstrapPath(request));
    if (token != null) {
      exposeToken(response, request, token);
    }

    if (!safe && !hasNonCookieCredentials(request) && !validSubmittedToken(request, token)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF token is missing or invalid");
      return;
    }

    filterChain.doFilter(request, response);
  }

  private boolean csrfProtectedPath(HttpServletRequest request) {
    String uri = stripContextPath(request);
    return uri.startsWith("/internal/")
        || legacyUi.csrfProtectedPath(uri)
        || uri.startsWith("/service/rest/v1/security/");
  }

  private boolean csrfTokenBootstrapPath(HttpServletRequest request) {
    String uri = stripContextPath(request);
    return uri.equals("/internal/security/session")
        || legacyUi.csrfTokenBootstrapPath(uri);
  }

  private String csrfToken(HttpServletRequest request, boolean create) {
    HttpSession session = request.getSession(create);
    if (session == null) {
      return null;
    }
    Object existing = session.getAttribute(SESSION_ATTRIBUTE);
    if (existing instanceof String token && !token.isBlank()) {
      return token;
    }
    if (!create) {
      return null;
    }
    String token = newToken();
    session.setAttribute(SESSION_ATTRIBUTE, token);
    return token;
  }

  private boolean hasNonCookieCredentials(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && !authorization.isBlank()) {
      return true;
    }
    String apiKey = request.getHeader(apiKeyHeader);
    return apiKey != null && !apiKey.isBlank();
  }

  private boolean validSubmittedToken(HttpServletRequest request, String expected) {
    if (expected == null || expected.isBlank()) {
      return false;
    }
    String submitted = firstNonBlank(
        request.getHeader(CSRF_HEADER),
        request.getHeader("X-CSRF-Token"),
        request.getParameter("_csrf"));
    return expected.equals(submitted);
  }

  private void exposeToken(HttpServletResponse response, HttpServletRequest request, String token) {
    response.setHeader(CSRF_HEADER, token);
    StringBuilder cookie = new StringBuilder()
        .append(CSRF_COOKIE).append("=").append(token)
        .append("; Path=").append(cookiePath(request))
        .append("; SameSite=Lax");
    if (cookieSecure) {
      cookie.append("; Secure");
    }
    response.addHeader("Set-Cookie", cookie.toString());
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static String cookiePath(HttpServletRequest request) {
    String contextPath = request.getContextPath();
    return contextPath == null || contextPath.isBlank() ? "/" : contextPath;
  }
}
