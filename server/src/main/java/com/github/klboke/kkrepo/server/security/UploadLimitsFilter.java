package com.github.klboke.kkrepo.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(UploadLimitsFilter.FILTER_ORDER)
public class UploadLimitsFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 7;
  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "MKCOL");

  private final long maxRequestBytes;
  private final NexusLegacyUiCompatibility legacyUi;

  public UploadLimitsFilter(
      @Value("${kkrepo.security.upload.max-request-bytes:1073741824}") long maxRequestBytes,
      NexusLegacyUiCompatibility legacyUi) {
    this.maxRequestBytes = maxRequestBytes;
    this.legacyUi = legacyUi;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (maxRequestBytes > 0 && uploadPath(request) && request.getContentLengthLong() > maxRequestBytes) {
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Upload exceeds configured limit");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean uploadPath(HttpServletRequest request) {
    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
    if (!WRITE_METHODS.contains(method)) {
      return false;
    }
    String uri = stripContextPath(request);
    return uri.startsWith("/repository/")
        || uri.equals("/service/rest/v1/components")
        || legacyUi.internalUiUploadPath(uri);
  }

  private static String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }
}
