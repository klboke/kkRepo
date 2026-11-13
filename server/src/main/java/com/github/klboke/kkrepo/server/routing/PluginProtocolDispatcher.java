package com.github.klboke.kkrepo.server.routing;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MultipartPart;
import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryPolicy;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.security.ClientAddress;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Adapts the framework-agnostic {@link RepositoryProtocolHandler} plugin SPI to the server routing
 * boundary.
 *
 * <p>The adapter is registered as one legacy handler. The existing dispatcher therefore still owns
 * repository resolution, routing-policy enforcement, path normalization and route precedence; the
 * plugin only sees a neutral request and produces a neutral response.
 */
public final class PluginProtocolDispatcher implements LegacyRepositoryProtocolHandler {
  private static final Comparator<Registration> ROUTE_ORDER =
      Comparator.comparingInt(
              (Registration registration) -> registration.route().pathSpecificity())
          .reversed()
          .thenComparingInt(registration -> registration.route().order())
          .thenComparing(registration -> registration.handler().getClass().getName());

  private final List<Registration> registrations;
  private final List<RepositoryProtocolRoute> routes;
  private final List<PluginHostService> hostServices;

  public PluginProtocolDispatcher(List<RepositoryProtocolHandler> handlers) {
    this(handlers, List.of());
  }

  public PluginProtocolDispatcher(
      List<RepositoryProtocolHandler> handlers, List<PluginHostService> hostServices) {
    Objects.requireNonNull(handlers, "handlers");
    this.hostServices = hostServices == null ? List.of() : List.copyOf(hostServices);
    List<Registration> collected = new ArrayList<>();
    List<RepositoryProtocolRoute> converted = new ArrayList<>();
    for (RepositoryProtocolHandler handler : handlers) {
      if (handler == null) {
        throw new IllegalStateException("Plugin protocol handler list contains null");
      }
      Collection<com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute> handlerRoutes =
          handler.routes();
      if (handlerRoutes == null || handlerRoutes.isEmpty()
          || handlerRoutes.stream().anyMatch(Objects::isNull)) {
        throw new IllegalStateException(
            "Plugin protocol handler must register at least one non-null route: "
                + handler.getClass().getName());
      }
      for (com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute route : handlerRoutes) {
        collected.add(new Registration(route, handler));
        converted.add(toLegacyRoute(route, handler));
      }
    }
    collected.sort(ROUTE_ORDER);
    this.registrations = List.copyOf(collected);
    this.routes = List.copyOf(converted);
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return routes;
  }

  @Override
  public boolean allowsEmptyRoutes() {
    return true;
  }

  @Override
  public ResponseEntity<?> handle(RepositoryProtocolRequest request)
      throws IOException, ServletException {
    Registration registration = match(request.runtime(), request.method(), request.path());
    if (registration == null) {
      throw new MavenExceptions.MethodNotAllowed(
          "No plugin repository protocol handler for " + request.runtime().format() + "/"
              + request.runtime().type() + " " + request.method() + " " + request.path());
    }
    RepositoryResponse response = registration.handler().handle(
        toNeutralRequest(request, registration.handler().usesRawRequest()));
    if (response == null) {
      throw new IllegalStateException(
          "Plugin repository protocol handler returned no response: "
              + registration.handler().getClass().getName());
    }
    HttpHeaders headers = new HttpHeaders();
    response.headers().forEach((name, values) -> {
      if (name != null && values != null) {
        headers.put(name, new ArrayList<>(values));
      }
    });
    if (response.isStreaming()) {
      StreamingResponseBody stream = response.stream()::writeTo;
      return ResponseEntity.status(response.status()).headers(headers).body(stream);
    }
    return ResponseEntity.status(response.status()).headers(headers).body(response.body());
  }

  private Registration match(RepositoryRuntime runtime, HttpMethod method, String path) {
    FormatId format = new FormatId(runtime.format().id());
    RepositoryKind kind = RepositoryKind.valueOf(runtime.type().name());
    for (Registration registration : registrations) {
      if (registration.route().matches(format, kind, method.name(), path)) {
        return registration;
      }
    }
    return null;
  }

  private RepositoryRequest toNeutralRequest(RepositoryProtocolRequest request, boolean rawRequest)
      throws IOException {
    HttpServletRequest servlet = request.servletRequest();
    RepositoryDescriptor descriptor = descriptor(request.runtime());
    Map<String, List<String>> headers = new LinkedHashMap<>();
    Enumeration<String> names = servlet.getHeaderNames();
    if (names != null) {
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        headers.put(name, Collections.list(servlet.getHeaders(name)));
      }
    }
    Map<String, String> query = new LinkedHashMap<>();
    servlet.getParameterMap().forEach((name, values) -> {
      if (name != null && values != null && values.length > 0) {
        query.put(name, values[0]);
      }
    });
    return new RepositoryRequest(
        descriptor,
        request.repositoryName(),
        request.path(),
        request.method().name(),
        headers,
        query,
        !rawRequest && request.multipartFile() == null
            ? body(servlet, request.method())
            : new byte[0],
        rawRequest ? null : multipart(request.multipartFile()),
        new HostContext(descriptor, hostServices, ClientAddress.from(servlet), servlet),
        Map.of());
  }

  private static byte[] body(HttpServletRequest request, HttpMethod method) throws IOException {
    if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
      return new byte[0];
    }
    return request.getInputStream().readAllBytes();
  }

  private static MultipartPart multipart(MultipartFile file) throws IOException {
    if (file == null) {
      return null;
    }
    return new MultipartPart(
        file.getName(),
        file.getOriginalFilename(),
        file.getContentType(),
        file.getBytes());
  }

  private static RepositoryDescriptor descriptor(RepositoryRuntime runtime) {
    return new RepositoryDescriptor(
        runtime.id(),
        runtime.name(),
        new FormatId(runtime.format().id()),
        RepositoryKind.valueOf(runtime.type().name()),
        runtime.recipeName(),
        runtime.online(),
        runtime.blobStoreId(),
        runtime.writePolicy(),
        runtime.versionPolicy(),
        runtime.layoutPolicy(),
        runtime.proxyRemoteUrl(),
        Map.of());
  }

  private static RepositoryProtocolRoute toLegacyRoute(
      com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute route,
      RepositoryProtocolHandler handler) {
    RepositoryFormat format;
    try {
      format = RepositoryFormat.fromJson(route.format().value());
    } catch (IllegalArgumentException unknown) {
      throw new IllegalStateException(
          "Plugin protocol handler " + handler.getClass().getName()
              + " uses a format unknown to the server: " + route.format(),
          unknown);
    }
    RepositoryType type = RepositoryType.valueOf(route.type().name());
    HttpMethod method = HttpMethod.valueOf(route.method());
    return new RepositoryProtocolRoute(
        format, type, method, route.pathPattern(), route.order());
  }

  private record Registration(
      com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute route,
      RepositoryProtocolHandler handler) {
  }

  private record HostContext(
      RepositoryDescriptor repository,
      List<PluginHostService> services,
      String clientAddress,
      HttpServletRequest servletRequest) implements RepositoryContext {

    @Override
    public java.util.Optional<BlobStorage> blobs() {
      return java.util.Optional.empty();
    }

    @Override
    public <T> java.util.Optional<T> service(Class<T> type) {
      return services.stream()
          .filter(type::isInstance)
          .map(type::cast)
          .findFirst();
    }

    @Override
    public RepositoryPolicy policy() {
      return new RepositoryPolicy(
          repository.writePolicy(), repository.versionPolicy(), repository.layoutPolicy());
    }

    @Override
    public Object hostContext() {
      return servletRequest;
    }
  }
}
