package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerPath;
import com.github.klboke.kkrepo.protocol.docker.DockerPathParser;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(DockerAuthFilter.FILTER_ORDER)
public class DockerAuthFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 15;

  private final RepositoryRuntimeRegistry registry;
  private final DockerAuthService authService;
  private final SecurityAuthenticationService authenticationService;
  private final AccessDecisionService accessDecisionService;
  private final DockerPathParser parser = new DockerPathParser();

  public DockerAuthFilter(
      RepositoryRuntimeRegistry registry,
      DockerAuthService authService,
      SecurityAuthenticationService authenticationService,
      AccessDecisionService accessDecisionService) {
    this.registry = registry;
    this.authService = authService;
    this.authenticationService = authenticationService;
    this.accessDecisionService = accessDecisionService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    if (!isDockerRoute(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    if (isBaseRoute(request)) {
      authenticateBase(request, response, filterChain);
      return;
    }
    DockerTarget target = target(request);
    if (target == null || target.path().kind() == DockerPath.Kind.BASE) {
      filterChain.doFilter(request, response);
      return;
    }
    Optional<RepositoryRuntime> runtime = registry.resolve(target.repository());
    if (runtime.isEmpty() || runtime.get().format() != RepositoryFormat.DOCKER) {
      filterChain.doFilter(request, response);
      return;
    }
    String action = dockerAction(request.getMethod(), target.path());
    String[] challengeActions = challengeActions(action);
    Optional<AuthenticatedSubject> subject;
    try {
      subject = bearer(request)
          .flatMap(token -> authService.authenticateBearer(
              token, target.repository(), target.path().imageName(), action));
    } catch (DockerProtocolException e) {
      challenge(response, request, target, challengeActions);
      return;
    }
    if (subject.isEmpty()) {
      subject = authenticationService.authenticate(request);
    }
    if (subject.isEmpty() && "pull".equals(action)) {
      subject = authenticationService.authenticateAnonymous();
    }
    if (subject.isEmpty()) {
      challenge(response, request, target, challengeActions);
      return;
    }
    PermissionAction permission = permissionFor(action, request.getMethod(), target.path());
    if (permission != null && !accessDecisionService.decide(
        subject.get().permissionSubject(),
        new RepositoryPermission(
            target.repository(), RepositoryFormat.DOCKER, target.path().imageName(), permission)).allowed()) {
      challenge(response, request, target, challengeActions);
      return;
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject.get());
    filterChain.doFilter(request, response);
  }

  private void authenticateBase(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Optional<AuthenticatedSubject> subject = bearer(request)
        .flatMap(authService::authenticateBearer);
    if (subject.isEmpty()) {
      subject = authenticationService.authenticate(request);
    }
    if (subject.isEmpty()) {
      challengeBase(response, request);
      return;
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject.get());
    filterChain.doFilter(request, response);
  }

  private void challengeBase(HttpServletResponse response, HttpServletRequest request) throws IOException {
    response.setHeader(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
        authService.challenge(tokenRealm(request), service(request)));
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
  }

  private void challenge(
      HttpServletResponse response,
      HttpServletRequest request,
      DockerTarget target,
      String[] actions) throws IOException {
    response.setHeader(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION);
    if (target.path().kind() == DockerPath.Kind.CATALOG) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
          authService.registryCatalogChallenge(tokenRealm(request), service(request)));
    } else {
      String challengeRepository = target.connectorRoute() ? target.path().imageName() : target.repository();
      String challengeImageName = target.connectorRoute() ? null : target.path().imageName();
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
          authService.challenge(
              tokenRealm(request), service(request), challengeRepository, challengeImageName, actions));
    }
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
  }

  private DockerTarget target(HttpServletRequest request) {
    String path = stripContextPath(request);
    if (!path.startsWith("/v2/")) {
      return null;
    }
    String raw = path.substring("/v2/".length());
    if (raw.isBlank()) {
      return null;
    }
    String connectorRepository = connectorRepositoryOrNull(request);
    if (connectorRepository != null) {
      try {
        return new DockerTarget(connectorRepository, parser.parse(raw), true);
      } catch (DockerProtocolException e) {
        return null;
      }
    }
    int slash = raw.indexOf('/');
    if (slash <= 0) {
      return null;
    }
    String repository = raw.substring(0, slash);
    try {
      return new DockerTarget(repository, parser.parse(raw.substring(slash + 1)), false);
    } catch (DockerProtocolException e) {
      return null;
    }
  }

  private static String connectorRepositoryOrNull(HttpServletRequest request) {
    Object repository = request.getAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE);
    if (repository instanceof String value && !value.isBlank()) {
      return value;
    }
    return null;
  }

  private static String dockerAction(String method, DockerPath path) {
    String normalized = method == null ? "" : method.toUpperCase();
    if ("DELETE".equals(normalized)) {
      return "delete";
    }
    if (path.kind() == DockerPath.Kind.CATALOG) {
      return "catalog";
    }
    if (path.kind() == DockerPath.Kind.UPLOAD_START
        || path.kind() == DockerPath.Kind.UPLOAD_SESSION
        || ("PUT".equals(normalized) && path.kind() == DockerPath.Kind.MANIFEST)) {
      return "push";
    }
    return "pull";
  }

  private static PermissionAction permissionFor(String action) {
    return switch (action) {
      case "pull" -> PermissionAction.READ;
      case "catalog" -> PermissionAction.ADMIN;
      case "delete" -> PermissionAction.DELETE;
      default -> null;
    };
  }

  private static PermissionAction permissionFor(String action, String method, DockerPath path) {
    if ("push".equals(action)) {
      return path.kind() == DockerPath.Kind.MANIFEST
          ? PermissionAction.EDIT
          : PermissionAction.ADD;
    }
    return permissionFor(action);
  }

  private static String[] challengeActions(String action) {
    return "push".equals(action) ? new String[] {"pull", "push"} : new String[] {action};
  }

  private static Optional<String> bearer(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
      return Optional.empty();
    }
    return Optional.of(header.substring("Bearer ".length()).trim()).filter(token -> !token.isBlank());
  }

  private boolean isDockerRoute(HttpServletRequest request) {
    String path = stripContextPath(request);
    return path.equals("/v2") || path.equals("/v2/") || path.startsWith("/v2/");
  }

  private boolean isBaseRoute(HttpServletRequest request) {
    String path = stripContextPath(request);
    return path.equals("/v2") || path.equals("/v2/");
  }

  private static String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static String tokenRealm(HttpServletRequest request) {
    return baseUrl(request) + "/service/rest/v1/docker/token";
  }

  private static String service(HttpServletRequest request) {
    return request.getServerName() + ":" + request.getServerPort();
  }

  private static String baseUrl(HttpServletRequest request) {
    String scheme = request.getScheme();
    int port = request.getServerPort();
    boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
        || ("https".equalsIgnoreCase(scheme) && port == 443);
    return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
  }

  private record DockerTarget(String repository, DockerPath path, boolean connectorRoute) {
  }
}
