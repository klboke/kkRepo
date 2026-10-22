package com.github.klboke.kkrepo.server.routing;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Immutable deployment configuration shared by routing, security and public URL generation. */
@Component
public final class RepositoryUrlPrefix implements ServletContextInitializer {
  private static final String ATTRIBUTE = RepositoryUrlPrefix.class.getName();
  private static final String DEFAULT = "/repository";
  private static final Set<String> RESERVED = Set.of(
      "internal", "service", "admin", "browse", "v2", "actuator", "error", ".well-known");
  private final String prefix;

  public RepositoryUrlPrefix(
      @Value("${kkrepo.compatibility.repository-url-prefix:/repository}") String prefix) {
    if (prefix == null || !prefix.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")
        || RESERVED.contains(prefix.substring(1).split("/", 2)[0])) {
      throw new IllegalArgumentException("kkrepo.compatibility.repository-url-prefix must be an "
          + "absolute path without a trailing slash, outside reserved management/protocol routes");
    }
    this.prefix = prefix;
  }

  @Override
  public void onStartup(ServletContext context) {
    context.setAttribute(ATTRIBUTE, prefix);
  }

  /** Includes the separator preceding the repository name; safe before MVC request context setup. */
  public static String path(HttpServletRequest request) {
    ServletContext context = request == null ? null : request.getServletContext();
    Object configured = context == null ? null : context.getAttribute(ATTRIBUTE);
    return (configured instanceof String value ? value : DEFAULT) + "/";
  }

  /** For URL projections during a servlet request. No mutable static or cross-request state. */
  public static String currentPath() {
    return path(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        ? attrs.getRequest() : null);
  }
}
