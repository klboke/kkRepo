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
public interface RepositoryProtocolHandler {
  Collection<RepositoryProtocolRoute> routes();

  ResponseEntity<?> handle(RepositoryProtocolRequest request)
      throws IOException, ServletException;
}
