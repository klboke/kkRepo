package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
  private final NexusLegacyUiCompatibility legacyUi;

  public ManagementAuditFilter(
      SecurityAuditDao auditDao,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      NexusLegacyUiCompatibility legacyUi) {
    this.auditDao = auditDao;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
    this.legacyUi = legacyUi;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    boolean managementMutation = auditedMutation(request);
    if (!managementMutation && !possibleRepositoryPublish(request)) {
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
      if (managementMutation || swiftRepositoryPublish(request)) {
        record(request, wrapped.status(), failure);
      }
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
          auditDetails(request, failure)));
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
        || legacyUi.auditedMutationPath(uri);
  }

  private static boolean possibleRepositoryPublish(HttpServletRequest request) {
    return "PUT".equalsIgnoreCase(request.getMethod())
        && stripContextPath(request).startsWith("/repository/");
  }

  private static boolean swiftRepositoryPublish(HttpServletRequest request) {
    Object value = request.getAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE);
    return possibleRepositoryPublish(request)
        && value instanceof RepositoryRecord repository
        && repository.format() == RepositoryFormat.SWIFT;
  }

  private static Map<String, Object> auditDetails(
      HttpServletRequest request, Throwable failure) {
    LinkedHashMap<String, Object> details = new LinkedHashMap<>();
    Object value = request.getAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE);
    if (value instanceof RepositoryRecord repository
        && repository.format() == RepositoryFormat.SWIFT
        && "PUT".equalsIgnoreCase(request.getMethod())) {
      details.put("format", "swift");
      details.put("repository", repository.name());
      details.put("operation", "publish");
      String[] segments = stripContextPath(request).split("/", -1);
      if (segments.length == 6 && "repository".equals(segments[1])) {
        details.put("coordinate", segments[3] + "." + segments[4] + "@" + segments[5]);
      }
    }
    if (failure != null) {
      details.put("error", failure.getClass().getSimpleName());
    }
    return details.isEmpty() ? Map.of() : Map.copyOf(details);
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
