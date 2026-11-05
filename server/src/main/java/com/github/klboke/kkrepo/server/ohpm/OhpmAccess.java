package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPath;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPathParser;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OhpmAccess {
  public static final String PATH_ATTRIBUTE = OhpmAccess.class.getName() + ".path";
  private final SecurityAuthenticationService authentication;
  private final AccessDecisionService permissions;
  private final RepositoryDao repositories;
  private final ObjectMapper json;
  private final OhpmPathParser paths = new OhpmPathParser();

  public OhpmAccess(SecurityAuthenticationService authentication, AccessDecisionService permissions,
      RepositoryDao repositories, ObjectMapper json) {
    this.authentication = authentication;
    this.permissions = permissions;
    this.repositories = repositories;
    this.json = json;
  }

  public boolean authorize(HttpServletRequest request, HttpServletResponse response,
      RepositoryRecord repository, String rawPath) throws IOException {
    try {
      OhpmPath path = paths.parse(request.getMethod(), rawPath);
      request.setAttribute(PATH_ATTRIBUTE, path);
      if (path.kind() == OhpmPath.Kind.UNKNOWN || path.kind() == OhpmPath.Kind.LOGIN) {
        throw new OhpmException(404, "OHPM endpoint is not available");
      }
      repository = repositories.findById(repository.id())
          .filter(value -> value.format() == RepositoryFormat.OHPM)
          .orElseThrow(() -> new OhpmException(404, "Repository does not exist"));
      request.setAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, repository);
      if (!repository.online()) throw new OhpmException(503, "Repository is offline");
      if (path.kind() == OhpmPath.Kind.PING) return true;
      PermissionAction action = switch (path.kind()) {
        case METADATA, DOWNLOAD -> PermissionAction.READ;
        case PUBLISH, STREAM_PUBLISH -> PermissionAction.ADD;
        case UNPUBLISH -> PermissionAction.DELETE;
        case DIST_TAG -> PermissionAction.EDIT;
        default -> throw new OhpmException(404, "OHPM endpoint is not available");
      };
      boolean explicit = authentication.hasPresentedCredentials(request);
      if (action != PermissionAction.READ && !explicit) throw new OhpmException(401, "Authentication required");
      HttpServletRequest credentials = explicit ? new HttpServletRequestWrapper(request) {
        @Override public HttpSession getSession(boolean create) { return null; }
        @Override public HttpSession getSession() { return null; }
      } : request;
      var subject = authentication.authenticateOhpmFresh(credentials);
      if (subject.isEmpty() && !explicit && action == PermissionAction.READ) {
        subject = authentication.authenticateAnonymousFresh();
      }
      if (subject.isEmpty()) throw new OhpmException(401, "Authentication required");
      request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject.get());
      require(subject.get(), repository.name(), path.assetPath(), action, explicit ? 403 : 401);
      return true;
    } catch (OhpmException error) {
      response.setStatus(error.status());
      response.setContentType("application/json");
      response.setHeader("Cache-Control", "no-store");
      if (!"HEAD".equals(request.getMethod())) json.writeValue(response.getOutputStream(), error.response());
      return false;
    }
  }

  public void requirePublish(HttpServletRequest request, String repository, String packageId, boolean existingPackage) {
    AuthenticatedSubject subject = authentication.authenticateOhpmFresh(new HttpServletRequestWrapper(request) {
      @Override public HttpSession getSession(boolean create) { return null; }
      @Override public HttpSession getSession() { return null; }
    }).orElseThrow(() -> new OhpmException(401, "Authentication required"));
    require(subject, repository, packageId, PermissionAction.ADD, 403);
    if (existingPackage) require(subject, repository, packageId, PermissionAction.EDIT, 403);
  }

  private void require(AuthenticatedSubject subject, String repository, String path,
      PermissionAction action, int deniedStatus) {
    var permission = new RepositoryPermission(repository, RepositoryFormat.OHPM, path, action);
    var decision = permissions.decideAllFresh(subject.permissionSubject(), List.of(permission)).get(permission);
    if (decision == null || !decision.allowed()) throw new OhpmException(deniedStatus, "Permission denied");
  }
}
