package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityAuditDao.AuditLogRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(ManagementAuditFilter.FILTER_ORDER)
public class ManagementAuditFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 9;
  private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  private final SecurityAuditDao auditDao;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final boolean legacyUiEnabled;

  public ManagementAuditFilter(
      SecurityAuditDao auditDao,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      @Value("${kkrepo.nexus.legacy-ui.enabled:false}") boolean legacyUiEnabled) {
    this.auditDao = auditDao;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
    this.legacyUiEnabled = legacyUiEnabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (!auditedMutation(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    StatusCaptureResponse wrapped = new StatusCaptureResponse(response);
    Throwable failure = null;
    try {
      filterChain.doFilter(request, wrapped);
    } catch (IOException | ServletException | RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      record(request, wrapped.status(), failure);
    }
  }

  private void record(HttpServletRequest request, int status, Throwable failure) {
    AuthenticatedSubject subject = subject(request);
    String outcome = failure == null && status < 400 ? "SUCCESS" : "FAILURE";
    try {
      auditDao.insert(new AuditLogRecord(
          LocalDateTime.now(),
          subject == null ? null : subject.source(),
          subject == null ? null : subject.userId(),
          subject == null ? null : subject.realmId(),
          subject == null ? null : subject.apiKeyId(),
          remoteAddress(request, forwardedHeaderPolicy),
          request.getMethod(),
          stripContextPath(request),
          permission(request),
          status,
          outcome,
          failure == null ? Map.of() : Map.of("error", failure.getClass().getSimpleName())));
    } catch (RuntimeException ignored) {
      // Audit persistence must not hide the original management outcome.
    }
  }

  private boolean auditedMutation(HttpServletRequest request) {
    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
    if (READ_METHODS.contains(method)) {
      return false;
    }
    String uri = stripContextPath(request);
    return uri.startsWith("/internal/")
        || uri.startsWith("/service/rest/v1/security/")
        || (legacyUiEnabled
            && (uri.startsWith("/service/rest/internal/")
                || uri.equals("/service/extdirect")
                || uri.equals("/service/rapture/session")
                || uri.equals("/service/rest/wonderland/authenticate")));
  }

  private static AuthenticatedSubject subject(HttpServletRequest request) {
    Object attribute = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    return attribute instanceof AuthenticatedSubject subject ? subject : null;
  }

  private static String permission(HttpServletRequest request) {
    Object attribute = request.getAttribute(SecurityManagementFilter.REQUESTED_PERMISSION_ATTRIBUTE);
    return attribute == null ? null : attribute.toString();
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

  private static class StatusCaptureResponse extends HttpServletResponseWrapper {
    private int status = 200;

    private StatusCaptureResponse(HttpServletResponse response) {
      super(response);
    }

    @Override
    public void setStatus(int sc) {
      status = sc;
      super.setStatus(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      status = sc;
      super.sendError(sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
      status = sc;
      super.sendError(sc);
    }

    int status() {
      return status;
    }
  }
}
