package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.pub.PubPath;
import com.github.klboke.kkrepo.protocol.pub.PubPathParser;
import com.github.klboke.kkrepo.server.npm.NpmTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(RepositorySecurityFilter.FILTER_ORDER)
public class RepositorySecurityFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 20;
  public static final String REPOSITORY_RECORD_ATTRIBUTE =
      RepositorySecurityFilter.class.getName() + ".REPOSITORY_RECORD";
  private static final PubPathParser PUB_PATH_PARSER = new PubPathParser();
  private final SecurityAuthenticationService authenticationService;
  private final AccessDecisionService accessDecisionService;
  private final RepositoryDao repositoryDao;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final boolean anonymousReadEnabled;

  public RepositorySecurityFilter(
      SecurityAuthenticationService authenticationService,
      AccessDecisionService accessDecisionService,
      RepositoryDao repositoryDao,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      @Value("${kkrepo.security.anonymous-read-enabled:false}") boolean anonymousReadEnabled) {
    this.authenticationService = authenticationService;
    this.accessDecisionService = accessDecisionService;
    this.repositoryDao = repositoryDao;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
    this.anonymousReadEnabled = anonymousReadEnabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Optional<RepositoryRequest> securedRequest = resolve(request);
    if (securedRequest.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    RepositoryRequest target = securedRequest.get();
    Optional<RepositoryRecord> repository = repositoryDao.findByName(target.repository());
    if (repository.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }
    request.setAttribute(REPOSITORY_RECORD_ATTRIBUTE, repository.get());
    if (target.npmTokenRoute() && repository.get().format() == RepositoryFormat.NPM) {
      filterChain.doFilter(request, response);
      return;
    }
    if (isInvalidPubPublishRoute(repository.get(), target)) {
      filterChain.doFilter(request, response);
      return;
    }
    Optional<AuthenticatedSubject> authenticated = switch (repository.get().format()) {
      case CARGO -> authenticationService.authenticateCargo(request);
      case PUB -> authenticationService.authenticatePub(request);
      case RUBYGEMS -> authenticationService.authenticateRubygems(request);
      default -> authenticationService.authenticate(request);
    };
    if (authenticated.isEmpty()) {
      authenticated = target.readOnly(repository.get().format())
          ? authenticationService.authenticateAnonymous(anonymousReadEnabled)
          : Optional.empty();
      if (authenticated.isEmpty()) {
        challenge(request, response, repository.get(), target);
        return;
      }
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated.get());

    AccessDecision decision = decide(authenticated.get(), repository.get(), target);
    if (!decision.allowed()) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, decision.reason());
      return;
    }
    filterChain.doFilter(request, response);
  }

  private AccessDecision decide(
      AuthenticatedSubject subject,
      RepositoryRecord repository,
      RepositoryRequest target) {
    AccessDecision lastDenied = AccessDecision.deny("missing permission");
    for (PermissionAction action : target.actions(repository.format())) {
      AccessDecision decision = accessDecisionService.decide(
          subject.permissionSubject(),
          new RepositoryPermission(repository.name(), repository.format(), target.path(), action));
      if (decision.allowed()) {
        return decision;
      }
      lastDenied = decision;
    }
    return lastDenied;
  }

  private Optional<RepositoryRequest> resolve(HttpServletRequest request) {
    String method = request.getMethod();
    String uri = stripContextPath(request);
    if (uri.startsWith("/repository/")) {
      return repositoryRoute(method, uri);
    }
    if (uri.startsWith("/service/rest/repository/browse/")) {
      return browseRoute(method, uri);
    }
    if ("POST".equalsIgnoreCase(method) && uri.equals("/service/rest/v1/components")) {
      String repository = request.getParameter("repository");
      if (repository == null || repository.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new RepositoryRequest(repository.trim(), "", method, List.of(PermissionAction.EDIT), false));
    }
    if (uri.startsWith("/service/rest/internal/ui/upload/")) {
      String repository = uri.substring("/service/rest/internal/ui/upload/".length());
      int slash = repository.indexOf('/');
      if (slash >= 0) {
        repository = repository.substring(0, slash);
      }
      if (repository.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new RepositoryRequest(decode(repository), "", method, List.of(PermissionAction.EDIT), false));
    }
    return Optional.empty();
  }

  private Optional<RepositoryRequest> repositoryRoute(String method, String uri) {
    String remaining = uri.substring("/repository/".length());
    if (remaining.isBlank()) {
      return Optional.empty();
    }
    int slash = remaining.indexOf('/');
    String repository = slash < 0 ? remaining : remaining.substring(0, slash);
    String path = slash < 0 ? "" : remaining.substring(slash + 1);
    if (repository.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new RepositoryRequest(
        decode(repository),
        path,
        method,
        null,
        isNpmTokenRoute(method, path)));
  }

  private Optional<RepositoryRequest> browseRoute(String method, String uri) {
    if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
      return Optional.empty();
    }
    String remaining = uri.substring("/service/rest/repository/browse/".length());
    if (remaining.isBlank()) {
      return Optional.empty();
    }
    int slash = remaining.indexOf('/');
    String repository = slash < 0 ? remaining : remaining.substring(0, slash);
    String path = slash < 0 ? "" : remaining.substring(slash + 1);
    if (repository.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new RepositoryRequest(decode(repository), path, method, List.of(PermissionAction.BROWSE), false));
  }

  private boolean isNpmTokenRoute(String method, String path) {
    String normalizedMethod = method == null ? "" : method.toUpperCase();
    return ("PUT".equals(normalizedMethod) && NpmTokenService.isLoginPath(path))
        || ("DELETE".equals(normalizedMethod) && NpmTokenService.isLogoutPath(path));
  }

  private static List<PermissionAction> actionsForRepository(String method, String path, RepositoryFormat format) {
    if (format == RepositoryFormat.NPM && isNpmAuditRoute(method, path)) {
      return List.of(PermissionAction.READ, PermissionAction.BROWSE);
    }
    if (format == RepositoryFormat.CARGO && isCargoPublishRoute(method, path)) {
      return List.of(PermissionAction.ADD);
    }
    if (format == RepositoryFormat.PUB && isPubPublishRoute(method, path)) {
      return List.of(PermissionAction.ADD);
    }
    if (format == RepositoryFormat.CARGO && isCargoYankRoute(method, path)) {
      return List.of(PermissionAction.EDIT);
    }
    return switch (method.toUpperCase()) {
      case "GET", "HEAD", "OPTIONS", "TRACE" -> List.of(PermissionAction.READ);
      case "POST", "PATCH", "MKCOL" -> List.of(PermissionAction.ADD);
      case "PUT" -> List.of(PermissionAction.EDIT);
      case "DELETE" -> List.of(PermissionAction.DELETE);
      default -> List.of(PermissionAction.ADMIN);
    };
  }

  private static boolean isCargoPublishRoute(String method, String path) {
    return "PUT".equalsIgnoreCase(method)
        && "api/v1/crates/new".equals(path);
  }

  private static boolean isPubPublishRoute(String method, String path) {
    PubPath parsed = PUB_PATH_PARSER.parse(path);
    if ("GET".equalsIgnoreCase(method)) {
      return parsed.kind() == PubPath.Kind.PUBLISH_INIT
          || parsed.kind() == PubPath.Kind.PUBLISH_FINALIZE;
    }
    return "POST".equalsIgnoreCase(method)
        && parsed.kind() == PubPath.Kind.PUBLISH_UPLOAD;
  }

  private static boolean isInvalidPubPublishRoute(RepositoryRecord repository, RepositoryRequest target) {
    return repository.format() == RepositoryFormat.PUB
        && repository.type() != RepositoryType.HOSTED
        && isPubPublishRoute(target.method(), target.path());
  }

  private static boolean isCargoYankRoute(String method, String path) {
    if (!path.startsWith("api/v1/crates/")) {
      return false;
    }
    return ("DELETE".equalsIgnoreCase(method) && path.endsWith("/yank"))
        || ("PUT".equalsIgnoreCase(method) && path.endsWith("/unyank"));
  }

  private static boolean isNpmAuditRoute(String method, String path) {
    return "POST".equalsIgnoreCase(method)
        && isNpmAuditPath(path);
  }

  private static boolean isNpmAuditPath(String path) {
    return "-/npm/v1/security/audits".equals(path)
        || "-/npm/v1/security/audits/quick".equals(path)
        || "-/npm/v1/security/advisories/bulk".equals(path);
  }

  private String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private void challenge(
      HttpServletRequest request,
      HttpServletResponse response,
      RepositoryRecord repository,
      RepositoryRequest target) throws IOException {
    if (repository.format() == RepositoryFormat.CARGO) {
      response.setHeader(
          HttpHeaders.WWW_AUTHENTICATE,
          "Cargo login_url=\"" + quoteHeaderValue(cargoLoginUrl(request, target.repository())) + "\"");
    } else if (repository.format() == RepositoryFormat.PUB) {
      response.setHeader(
          HttpHeaders.WWW_AUTHENTICATE,
          "Bearer realm=\"pub\", message=\"Authentication required\"");
    } else {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"kkrepo\"");
    }
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
  }

  private String cargoLoginUrl(HttpServletRequest request, String repository) {
    String contextPath = request.getContextPath();
    String path = (contextPath == null ? "" : contextPath) + "/repository/" + repository + "/me";
    String baseUrl = forwardedHeaderPolicy.serverBaseUrl(request);
    return baseUrl.isBlank() ? path : baseUrl + path;
  }

  private String quoteHeaderValue(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private record RepositoryRequest(
      String repository,
      String path,
      String method,
      List<PermissionAction> fixedActions,
      boolean npmTokenRoute) {
    private List<PermissionAction> actions(RepositoryFormat format) {
      return fixedActions == null ? actionsForRepository(method, path, format) : fixedActions;
    }

    private boolean readOnly(RepositoryFormat format) {
      return actions(format).stream()
          .allMatch(action -> action == PermissionAction.BROWSE || action == PermissionAction.READ);
    }

  }
}
