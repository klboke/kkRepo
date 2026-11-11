package com.github.klboke.kkrepo.server.routing;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collection;
import org.springframework.http.ResponseEntity;

/**
 * Runtime extension point for Nexus-compatible repository routes.
 *
 * <p>Implementations register every route they own. Protocol services remain behind handlers;
 * controllers and security filters depend only on this routing boundary.
 */
public interface LegacyRepositoryProtocolHandler {
  Collection<RepositoryProtocolRoute> routes();

  /**
   * Whether this handler may be registered with no route. Adapters that discover their routes at
   * runtime set this to {@code true}; concrete built-in handlers keep the strict default.
   */
  default boolean allowsEmptyRoutes() {
    return false;
  }

  ResponseEntity<?> handle(RepositoryProtocolRequest request)
      throws IOException, ServletException;
}
