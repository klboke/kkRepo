package com.github.klboke.kkrepo.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class ClientAddressFilter extends OncePerRequestFilter {
  private final ForwardedHeaderPolicy policy;

  public ClientAddressFilter(ForwardedHeaderPolicy policy) {
    this.policy = policy;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    request.setAttribute(ClientAddress.ATTRIBUTE, policy.clientAddress(request));
    chain.doFilter(request, response);
  }
}
