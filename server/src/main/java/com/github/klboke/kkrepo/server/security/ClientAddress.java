package com.github.klboke.kkrepo.server.security;

import jakarta.servlet.http.HttpServletRequest;

/** Request-scoped provenance; never replaces the transport peer used for proxy trust. */
public final class ClientAddress {
  static final String ATTRIBUTE = ClientAddress.class.getName() + ".resolved";

  private ClientAddress() {}

  public static String from(HttpServletRequest request) {
    Object resolved = request.getAttribute(ATTRIBUTE);
    return resolved instanceof String address ? address : request.getRemoteAddr();
  }
}
