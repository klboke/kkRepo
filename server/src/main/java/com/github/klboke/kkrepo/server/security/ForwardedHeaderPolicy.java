package com.github.klboke.kkrepo.server.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ForwardedHeaderPolicy {
  private final Set<String> trustedProxies;
  private final List<TrustedNetwork> trustedNetworks;

  public ForwardedHeaderPolicy(
      @Value("${kkrepo.security.forwarded-headers.trusted-proxies:}") String trustedProxies) {
    Set<String> addresses = new HashSet<>();
    List<TrustedNetwork> networks = new ArrayList<>();
    if (trustedProxies != null) {
      for (String value : trustedProxies.split(",")) {
        String entry = value.trim();
        if (entry.isEmpty()) {
          continue;
        }
        if (entry.contains("/")) {
          networks.add(TrustedNetwork.parse(entry));
        } else {
          addresses.add(normalize(entry));
        }
      }
    }
    // Immutable deployment configuration, rebuilt at startup on each replica.
    this.trustedProxies = Set.copyOf(addresses);
    this.trustedNetworks = List.copyOf(networks);
  }

  public boolean trusted(HttpServletRequest request) {
    if (trustedProxies.isEmpty() && trustedNetworks.isEmpty()) {
      return false;
    }
    String remote = request.getRemoteAddr();
    if (remote == null || remote.isBlank()) {
      return false;
    }
    try {
      // Only the immediate peer establishes trust; never resolve request data through DNS.
      InetAddress address = InetAddress.ofLiteral(remote.trim());
      if (trustedProxies.contains(address.getHostAddress())) {
        return true;
      }
      byte[] bytes = address.getAddress();
      return trustedNetworks.stream().anyMatch(network -> network.matches(bytes));
    } catch (IllegalArgumentException ignored) {
      return false;
    }
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

  private static String normalize(String value) {
    try {
      return InetAddress.getByName(value).getHostAddress();
    } catch (Exception ignored) {
      return value;
    }
  }

  private record TrustedNetwork(byte[] address, int prefixLength) {
    private static TrustedNetwork parse(String entry) {
      try {
        int slash = entry.indexOf('/');
        String literal = entry.substring(0, slash);
        String prefix = entry.substring(slash + 1);
        if (literal.contains("%") || !prefix.matches("[0-9]{1,3}")) {
          throw new IllegalArgumentException("expected an unscoped IP address and decimal prefix length");
        }
        byte[] address = InetAddress.ofLiteral(literal).getAddress();
        // The JDK normalizes mapped IPv6 to IPv4; do not reinterpret its 128-bit prefix as IPv4.
        if (literal.contains(":") && address.length == 4) {
          throw new IllegalArgumentException("use an IPv4 CIDR for IPv4-mapped addresses");
        }
        int bits = Integer.parseInt(prefix);
        if (bits > address.length * Byte.SIZE) {
          throw new IllegalArgumentException("prefix length exceeds address size");
        }
        return new TrustedNetwork(address, bits);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Invalid CIDR in kkrepo.security.forwarded-headers.trusted-proxies: " + entry, e);
      }
    }

    private boolean matches(byte[] remote) {
      if (remote.length != address.length) {
        return false;
      }
      int fullBytes = prefixLength / Byte.SIZE;
      for (int i = 0; i < fullBytes; i++) {
        if (remote[i] != address[i]) {
          return false;
        }
      }
      int remainingBits = prefixLength % Byte.SIZE;
      int mask = 0xff << (Byte.SIZE - remainingBits);
      return remainingBits == 0 || (remote[fullBytes] & mask) == (address[fullBytes] & mask);
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
