package com.github.klboke.kkrepo.server.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboundRequestPolicy {
  private final boolean allowPrivateAddresses;
  private final Set<String> allowedHosts;
  private final HostResolver hostResolver;

  @Autowired
  public OutboundRequestPolicy(
      @Value("${kkrepo.security.outbound.allow-private-addresses:false}") boolean allowPrivateAddresses,
      @Value("${kkrepo.security.outbound.allowed-hosts:}") String allowedHosts) {
    this(allowPrivateAddresses, parseHosts(allowedHosts), InetAddress::getAllByName);
  }

  OutboundRequestPolicy(
      boolean allowPrivateAddresses, String allowedHosts, HostResolver hostResolver) {
    this(allowPrivateAddresses, parseHosts(allowedHosts), hostResolver);
  }

  private OutboundRequestPolicy(
      boolean allowPrivateAddresses, Set<String> allowedHosts, HostResolver hostResolver) {
    this.allowPrivateAddresses = allowPrivateAddresses;
    this.allowedHosts = allowedHosts;
    this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
  }

  public static OutboundRequestPolicy allowPrivateForTests() {
    return new OutboundRequestPolicy(true, Set.of(), InetAddress::getAllByName);
  }

  public URI validateHttpUri(String rawUrl, String purpose) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new SecurityValidationException(purpose + " URL is required");
    }
    try {
      return validateHttpUri(new URI(rawUrl), purpose);
    } catch (URISyntaxException e) {
      throw new SecurityValidationException(purpose + " URL is not valid: " + e.getMessage(), e);
    }
  }

  public URI validateHttpUri(URI uri, String purpose) {
    String host = validatedHost(uri, purpose);
    if (allowedHosts.contains(normalizeHost(host))) {
      return uri;
    }
    return resolveHttpTarget(uri, purpose).uri();
  }

  /**
   * Validates an outbound HTTP URL and captures the exact DNS answers approved by the policy.
   * Callers must connect to one of {@link ResolvedHttpTarget#addresses()} instead of resolving the
   * host name again; otherwise a DNS change between validation and connect can bypass the address
   * checks.
   */
  public ResolvedHttpTarget resolveHttpTarget(String rawUrl, String purpose) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new SecurityValidationException(purpose + " URL is required");
    }
    try {
      return resolveHttpTarget(new URI(rawUrl), purpose);
    } catch (URISyntaxException e) {
      throw new SecurityValidationException(purpose + " URL is not valid: " + e.getMessage(), e);
    }
  }

  public ResolvedHttpTarget resolveHttpTarget(URI uri, String purpose) {
    String host = validatedHost(uri, purpose);
    InetAddress[] addresses;
    try {
      addresses = hostResolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new SecurityValidationException(purpose + " URL host cannot be resolved: " + host, e);
    }
    if (addresses == null || addresses.length == 0) {
      throw new SecurityValidationException(purpose + " URL host cannot be resolved: " + host);
    }
    for (InetAddress address : addresses) {
      if (address == null) {
        throw new SecurityValidationException(purpose + " URL host cannot be resolved: " + host);
      }
    }
    boolean addressChecksRequired =
        !allowPrivateAddresses && !allowedHosts.contains(normalizeHost(host));
    if (addressChecksRequired) {
      for (InetAddress address : addresses) {
        if (blockedAddress(address)) {
          throw new SecurityValidationException(
              purpose + " URL resolves to a private or local address: " + host + " -> " + address.getHostAddress());
        }
      }
    }
    return new ResolvedHttpTarget(uri, Arrays.asList(addresses));
  }

  private String validatedHost(URI uri, String purpose) {
    if (uri == null) {
      throw new SecurityValidationException(purpose + " URL is required");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new SecurityValidationException(purpose + " URL must be http or https");
    }
    if (uri.getUserInfo() != null) {
      throw new SecurityValidationException(purpose + " URL must not include user info");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new SecurityValidationException(purpose + " URL must have a host");
    }
    if (uri.getPort() == 0 || uri.getPort() > 65535) {
      throw new SecurityValidationException(purpose + " URL has an invalid port");
    }
    return host;
  }

  private boolean blockedAddress(InetAddress address) {
    return address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()
        || isIpv4MetadataAddress(address)
        || isIpv6UniqueLocal(address);
  }

  private static boolean isIpv4MetadataAddress(InetAddress address) {
    if (!(address instanceof Inet4Address)) {
      return false;
    }
    byte[] raw = address.getAddress();
    int a = raw[0] & 0xff;
    int b = raw[1] & 0xff;
    return (a == 169 && b == 254) || a == 0 || a == 127;
  }

  private static boolean isIpv6UniqueLocal(InetAddress address) {
    if (!(address instanceof Inet6Address)) {
      return false;
    }
    int first = address.getAddress()[0] & 0xff;
    return (first & 0xfe) == 0xfc;
  }

  private static Set<String> parseHosts(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(","))
        .map(OutboundRequestPolicy::normalizeHost)
        .filter(host -> !host.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String normalizeHost(String host) {
    String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    return value;
  }

  @FunctionalInterface
  interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /** Policy-issued connection capability; only this class can create a validated instance. */
  public static final class ResolvedHttpTarget {
    private final URI uri;
    private final List<InetAddress> addresses;

    private ResolvedHttpTarget(URI uri, List<InetAddress> addresses) {
      this.uri = uri;
      this.addresses = List.copyOf(addresses);
    }

    public URI uri() {
      return uri;
    }

    public List<InetAddress> addresses() {
      return addresses;
    }
  }
}
