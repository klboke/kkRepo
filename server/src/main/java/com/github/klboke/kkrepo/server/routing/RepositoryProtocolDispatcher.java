package com.github.klboke.kkrepo.server.routing;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Resolves one repository runtime and dispatches the request to the most specific route. */
@Component
public class RepositoryProtocolDispatcher {
  private static final Comparator<RegisteredRoute> ROUTE_ORDER =
      Comparator.comparingInt((RegisteredRoute route) -> route.route().pathSpecificity())
          .reversed()
          .thenComparingInt(route -> route.route().order())
          .thenComparing(route -> route.handler().getClass().getName());

  private final RepositoryRuntimeRegistry runtimes;
  private final Map<RouteKey, List<RegisteredRoute>> routes;

  public RepositoryProtocolDispatcher(
      RepositoryRuntimeRegistry runtimes, List<RepositoryProtocolHandler> handlers) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    Objects.requireNonNull(handlers, "handlers");
    Map<RouteKey, List<RegisteredRoute>> registered = new HashMap<>();
    for (RepositoryProtocolHandler handler : handlers) {
      if (handler == null) {
        throw new IllegalStateException("Repository protocol handler list contains null");
      }
      List<RepositoryProtocolRoute> handlerRoutes = CollectionValidator.requireRoutes(handler);
      for (RepositoryProtocolRoute route : handlerRoutes) {
        registered.computeIfAbsent(RouteKey.from(route), ignored -> new ArrayList<>())
            .add(new RegisteredRoute(route, handler));
      }
    }
    if (registered.isEmpty()) {
      throw new IllegalStateException("No repository protocol routes are registered");
    }
    Map<RouteKey, List<RegisteredRoute>> indexed = new HashMap<>();
    registered.forEach((key, value) -> {
      List<RegisteredRoute> ordered = value.stream().sorted(ROUTE_ORDER).toList();
      requireUnambiguousRegistrations(key, ordered);
      indexed.put(key, ordered);
    });
    this.routes = Map.copyOf(indexed);
  }

  public ResponseEntity<?> dispatchRead(
      String repositoryName, HttpServletRequest request, HttpMethod method) {
    try {
      return dispatch(repositoryName, request, method, null);
    } catch (IOException | ServletException error) {
      throw new IllegalStateException("Repository read handler failed", error);
    }
  }

  public ResponseEntity<?> dispatch(
      String repositoryName,
      HttpServletRequest request,
      HttpMethod method,
      MultipartFile multipartFile) throws IOException, ServletException {
    return dispatch(
        repositoryName, request, method, request.getContentType(), multipartFile);
  }

  public ResponseEntity<?> dispatch(
      String repositoryName,
      HttpServletRequest request,
      HttpMethod method,
      String contentType,
      MultipartFile multipartFile) throws IOException, ServletException {
    RepositoryRuntime runtime = resolveRuntime(repositoryName, request);
    String path = routePath(repositoryName, request);
    RouteKey key = new RouteKey(runtime.format(), runtime.type(), method);
    for (RegisteredRoute candidate : routes.getOrDefault(key, List.of())) {
      if (!candidate.route().matches(runtime, method, path)) continue;
      return candidate.handler().handle(new RepositoryProtocolRequest(
          runtime, repositoryName, path, method, request, contentType, multipartFile));
    }
    throw new MavenExceptions.MethodNotAllowed(
        "No repository protocol handler for " + runtime.format() + "/" + runtime.type()
            + " " + method + " " + path);
  }

  private RepositoryRuntime resolveRuntime(String name, HttpServletRequest request) {
    Object record = request.getAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE);
    if (record instanceof RepositoryRecord repository) {
      if (!name.equals(repository.name())) {
        throw new MavenExceptions.MavenNotFoundException("Repository not found: " + name);
      }
      return runtimes.resolve(repository)
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
              "Repository not found: " + name));
    }
    return runtimes.resolve(name)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
            "Repository not found: " + name));
  }

  private static String routePath(String name, HttpServletRequest request) {
    Object normalized = request.getAttribute(
        RepositorySecurityFilter.NORMALIZED_REPOSITORY_PATH_ATTRIBUTE);
    if (normalized instanceof String path) return path;
    String uri = request.getRequestURI();
    String root = request.getContextPath() + "/repository/" + name;
    if (uri.equals(root)) return "";
    String prefix = root + "/";
    return uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
  }

  private record RegisteredRoute(
      RepositoryProtocolRoute route, RepositoryProtocolHandler handler) {}

  private record RouteKey(
      RepositoryFormat format, RepositoryType type, HttpMethod method) {
    static RouteKey from(RepositoryProtocolRoute route) {
      return new RouteKey(route.format(), route.type(), route.method());
    }
  }

  private static void requireUnambiguousRegistrations(
      RouteKey key, List<RegisteredRoute> registrations) {
    Map<RegistrationKey, RegisteredRoute> registered = new HashMap<>();
    for (RegisteredRoute current : registrations) {
      RegistrationKey registration = new RegistrationKey(
          current.route().pathPattern(), current.route().order());
      RegisteredRoute previous = registered.putIfAbsent(registration, current);
      if (previous != null) {
        throw new IllegalStateException(
            "Ambiguous repository protocol route registration for " + key.format() + "/"
                + key.type() + " " + key.method() + " " + current.route().pathPattern()
                + ": " + previous.handler().getClass().getName() + " and "
                + current.handler().getClass().getName());
      }
    }
  }

  private record RegistrationKey(String pathPattern, int order) {}

  private static final class CollectionValidator {
    private CollectionValidator() {}

    static List<RepositoryProtocolRoute> requireRoutes(RepositoryProtocolHandler handler) {
      var routes = handler.routes();
      if (routes == null || routes.isEmpty() || routes.stream().anyMatch(Objects::isNull)) {
        throw new IllegalStateException(
            "Repository protocol handler must register at least one non-null route: "
                + handler.getClass().getName());
      }
      return List.copyOf(routes);
    }
  }
}
