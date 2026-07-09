package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(SecurityRateLimitFilter.FILTER_ORDER)
public class SecurityRateLimitFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 5;
  private static final String RATE_LIMIT_NAMESPACE = "security-rate-limit";

  private final SharedCache sharedCache;
  private final int loginLimit;
  private final int bootstrapLimit;
  private final String apiKeyHeader;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final KkRepoMetrics metrics;
  private final Duration window;
  private final boolean legacyUiEnabled;

  public SecurityRateLimitFilter(
      @Value("${kkrepo.security.rate-limit.login-per-minute:20}") int loginLimit,
      @Value("${kkrepo.security.rate-limit.bootstrap-per-minute:5}") int bootstrapLimit,
      @Value("${kkrepo.security.token-header:X-Nexus-Plus-Token}") String apiKeyHeader,
      SharedCache sharedCache,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      KkRepoMetrics metrics,
      @Value("${kkrepo.nexus.legacy-ui.enabled:false}") boolean legacyUiEnabled) {
    this.loginLimit = loginLimit;
    this.bootstrapLimit = bootstrapLimit;
    this.apiKeyHeader = apiKeyHeader;
    this.sharedCache = sharedCache;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
    this.metrics = metrics;
    this.window = Duration.ofMinutes(1);
    this.legacyUiEnabled = legacyUiEnabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Limit limit = limitFor(request);
    if (limit != null && !allow(limit.key(), limit.maxRequests())) {
      metrics.recordRateLimitBlocked(limit.type());
      response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
      response.sendError(429, "Too many requests");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private Limit limitFor(HttpServletRequest request) {
    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
    String uri = stripContextPath(request);
    String remote = remoteAddress(request, forwardedHeaderPolicy);
    if ("POST".equals(method) && uri.equals("/internal/security/bootstrap/admin")) {
      return new Limit("bootstrap", "bootstrap:" + remote, bootstrapLimit);
    }
    if ("POST".equals(method) && (uri.equals("/internal/security/login")
        || (legacyUiEnabled && (uri.equals("/service/rapture/session")
            || uri.equals("/service/rest/wonderland/authenticate")
            || uri.equals("/service/extdirect"))))) {
      return new Limit("login", "login:" + remote, loginLimit);
    }
    if (managementAuthPath(uri) && hasPresentedCredentials(request)) {
      return new Limit("management_auth", "management-auth:" + remote, loginLimit);
    }
    return null;
  }

  private boolean managementAuthPath(String uri) {
    return uri.startsWith("/internal/security/")
        || uri.startsWith("/service/rest/v1/security/")
        || (legacyUiEnabled
            && (uri.startsWith("/service/rest/internal/ui/security/")
                || uri.equals("/service/extdirect")));
  }

  private boolean hasPresentedCredentials(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && !authorization.isBlank()) {
      return true;
    }
    String apiKey = request.getHeader(apiKeyHeader);
    return apiKey != null && !apiKey.isBlank();
  }

  private boolean allow(String key, int maxRequests) {
    if (maxRequests <= 0) {
      return true;
    }
    return sharedCache.increment(RATE_LIMIT_NAMESPACE, key, window) <= maxRequests;
  }

  private static String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static String remoteAddress(HttpServletRequest request, ForwardedHeaderPolicy forwardedHeaderPolicy) {
    String forwarded = forwardedHeaderPolicy.trusted(request) ? request.getHeader("X-Forwarded-For") : null;
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",", 2)[0].trim();
    }
    return request.getRemoteAddr();
  }

  private record Limit(String type, String key, int maxRequests) {
  }
}
