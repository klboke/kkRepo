package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
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
  private final RepositoryDao repositories;

  public UploadLimitsFilter(
      @Value("${kkrepo.security.upload.max-request-bytes:1073741824}") long maxRequestBytes,
      NexusLegacyUiCompatibility legacyUi,
      RepositoryDao repositories) {
    this.maxRequestBytes = maxRequestBytes;
    this.legacyUi = legacyUi;
    this.repositories = repositories;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (maxRequestBytes > 0 && uploadPath(request) && request.getContentLengthLong() > maxRequestBytes) {
      if (swiftRepositoryPath(request)) {
        swiftTooLarge(response);
        return;
      }
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Upload exceeds configured limit");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean swiftRepositoryPath(HttpServletRequest request) {
    String uri = stripContextPath(request);
    if (!uri.startsWith("/repository/")) {
      return false;
    }
    String remaining = uri.substring("/repository/".length());
    int slash = remaining.indexOf('/');
    String repositoryName = slash < 0 ? remaining : remaining.substring(0, slash);
    return !repositoryName.isBlank()
        && repositories.findByName(repositoryName)
            .filter(repository -> repository.format() == RepositoryFormat.SWIFT)
            .isPresent();
  }

  private static void swiftTooLarge(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    response.setContentType("application/problem+json");
    response.setHeader("Content-Version", "1");
    response.setHeader("Cache-Control", "no-store");
    response.getOutputStream().write(("{\"type\":\"about:blank\",\"title\":\"Content Too Large\","
        + "\"status\":413,\"detail\":\"Upload exceeds configured limit\"}")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
