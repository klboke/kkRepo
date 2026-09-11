package com.github.klboke.kkrepo.server.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ForwardedHeaderPolicy {
  private final Set<String> trustedProxies;

  public ForwardedHeaderPolicy(
      @Value("${kkrepo.security.forwarded-headers.trusted-proxies:}") String trustedProxies) {
    this.trustedProxies = parse(trustedProxies);
  }

  public boolean trusted(HttpServletRequest request) {
    if (trustedProxies.isEmpty()) {
      return false;
    }
    String remote = request.getRemoteAddr();
    if (remote == null || remote.isBlank()) {
      return false;
    }
    return trustedProxies.contains(remote.trim()) || trustedProxies.contains(normalize(remote));
  }

  public String serverBaseUrl(HttpServletRequest request) {
    boolean trustedForwarded = trusted(request);
    String scheme = trustedForwarded ? firstHeader(request, "X-Forwarded-Proto", request.getScheme()) : request.getScheme();
    String host = trustedForwarded ? firstHeader(request, "X-Forwarded-Host", request.getServerName()) : request.getServerName();
    if (scheme == null || scheme.isBlank() || host == null || host.isBlank()) {
      return "";
    }
    String portHeader = trustedForwarded ? firstHeader(request, "X-Forwarded-Port", null) : null;
    String authority = host;
    boolean bracketedIpv6WithoutPort = host.startsWith("[") && host.endsWith("]");
    if (!host.contains(":") || bracketedIpv6WithoutPort) {
      int port = parsePort(portHeader, request.getServerPort());
      boolean standard = ("http".equalsIgnoreCase(scheme) && port == 80)
          || ("https".equalsIgnoreCase(scheme) && port == 443);
      if (port > 0 && !standard) {
        authority = host + ":" + port;
      }
    }
    return scheme + "://" + authority;
  }

  private static Set<String> parse(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(item -> !item.isBlank())
        .map(ForwardedHeaderPolicy::normalize)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String normalize(String value) {
    try {
      return InetAddress.getByName(value).getHostAddress();
    } catch (Exception ignored) {
      return value;
    }
  }

  private static String firstHeader(HttpServletRequest request, String name, String fallback) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    int comma = value.indexOf(',');
    return comma < 0 ? value.trim() : value.substring(0, comma).trim();
  }

  private static int parsePort(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
