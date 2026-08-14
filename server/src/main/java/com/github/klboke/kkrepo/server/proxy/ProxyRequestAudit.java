package com.github.klboke.kkrepo.server.proxy;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request-scoped audit metadata for proxy cache writes. */
public final class ProxyRequestAudit {
  private ProxyRequestAudit() {}

  /**
   * Returns the client address that triggered the current cache fill.
   *
   * <p>Background and other internal cache fills have no servlet request and intentionally return
   * {@code null}. The request context is only audit metadata; it is never used as shared state or
   * for correctness across replicas.
   */
  public static String currentClientIp() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return null;
    }
    String remoteAddress = attrs.getRequest().getRemoteAddr();
    return remoteAddress == null || remoteAddress.isBlank() ? null : remoteAddress.trim();
  }
}
