package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.proxy.NtlmCredentials;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import com.github.klboke.kkrepo.server.security.RedirectHosts;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable repository configuration snapshot shared by protocol request paths.
 * {@link RepositoryRuntimeRegistry} caches snapshots with a bounded TTL and invalidates them
 * locally and through catalog broadcasts when repository settings change.
 */
public record RepositoryRuntime(
    long id,
    String name,
    RepositoryFormat format,
    RepositoryType type,
    String recipeName,
    boolean online,
    Long blobStoreId,
    String writePolicy,
    String versionPolicy,
    String layoutPolicy,
    boolean strictContentTypeValidation,
    String proxyRemoteUrl,
    Integer contentMaxAgeMinutes,
    Integer metadataMaxAgeMinutes,
    Boolean autoBlock,
    String proxyRemoteUsername,
    String proxyRemotePassword,
    String proxyRemoteBearerToken,
    String rawContentDisposition,
    Boolean dockerConnectorEnabled,
    Integer dockerConnectorPort,
    String dockerConnectorPublicUrl,
    Boolean cargoRequireAuthentication,
    List<RepositoryRuntime> members,
    OutboundProxyConfig outboundProxy,
    Integer minimumReleaseAgeMinutes,
    Set<String> allowedRedirectHosts,
    NtlmCredentials ntlmCredentials,
    int yumRepodataDepth,
    Integer conanManifestMaxEntries,
    Integer conanManifestMaxBytes) {
  /** Compatibility constructor for snapshots without Conan manifest overrides. */
  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String proxyRemoteUsername,
      String proxyRemotePassword,
      String proxyRemoteBearerToken,
      String rawContentDisposition,
      Boolean dockerConnectorEnabled,
      Integer dockerConnectorPort,
      String dockerConnectorPublicUrl,
      Boolean cargoRequireAuthentication,
      List<RepositoryRuntime> members,
      OutboundProxyConfig outboundProxy,
      Integer minimumReleaseAgeMinutes,
      Set<String> allowedRedirectHosts,
      NtlmCredentials ntlmCredentials,
      int yumRepodataDepth) {
    this(id, name, format, type, recipeName, online, blobStoreId, writePolicy, versionPolicy,
        layoutPolicy, strictContentTypeValidation, proxyRemoteUrl, contentMaxAgeMinutes,
        metadataMaxAgeMinutes, autoBlock, proxyRemoteUsername, proxyRemotePassword,
        proxyRemoteBearerToken, rawContentDisposition, dockerConnectorEnabled, dockerConnectorPort,
        dockerConnectorPublicUrl, cargoRequireAuthentication, members, outboundProxy,
        minimumReleaseAgeMinutes, allowedRedirectHosts, ntlmCredentials, yumRepodataDepth, null, null);
  }

  /** Compatibility constructor for callers that predate Yum repodata-depth settings. */
  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String proxyRemoteUsername,
      String proxyRemotePassword,
      String proxyRemoteBearerToken,
      String rawContentDisposition,
      Boolean dockerConnectorEnabled,
      Integer dockerConnectorPort,
      String dockerConnectorPublicUrl,
      Boolean cargoRequireAuthentication,
      List<RepositoryRuntime> members,
      OutboundProxyConfig outboundProxy,
      Integer minimumReleaseAgeMinutes,
      Set<String> allowedRedirectHosts,
      NtlmCredentials ntlmCredentials) {
    this(id, name, format, type, recipeName, online, blobStoreId, writePolicy, versionPolicy, layoutPolicy,
        strictContentTypeValidation, proxyRemoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes,
        autoBlock, proxyRemoteUsername, proxyRemotePassword, proxyRemoteBearerToken, rawContentDisposition,
        dockerConnectorEnabled, dockerConnectorPort, dockerConnectorPublicUrl, cargoRequireAuthentication,
        members, outboundProxy, minimumReleaseAgeMinutes, allowedRedirectHosts, ntlmCredentials, 0);
  }

  /** Compatibility constructor for callers that predate upstream NTLM authentication. */
  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String proxyRemoteUsername,
      String proxyRemotePassword,
      String proxyRemoteBearerToken,
      String rawContentDisposition,
      Boolean dockerConnectorEnabled,
      Integer dockerConnectorPort,
      String dockerConnectorPublicUrl,
      Boolean cargoRequireAuthentication,
      List<RepositoryRuntime> members,
      OutboundProxyConfig outboundProxy,
      Integer minimumReleaseAgeMinutes,
      Set<String> allowedRedirectHosts) {
    this(id, name, format, type, recipeName, online, blobStoreId, writePolicy, versionPolicy, layoutPolicy,
        strictContentTypeValidation, proxyRemoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
        proxyRemoteUsername, proxyRemotePassword, proxyRemoteBearerToken, rawContentDisposition,
        dockerConnectorEnabled, dockerConnectorPort, dockerConnectorPublicUrl, cargoRequireAuthentication,
        members, outboundProxy, minimumReleaseAgeMinutes, allowedRedirectHosts, null);
  }

  public RepositoryRuntime {
    if (allowedRedirectHosts == null || allowedRedirectHosts.isEmpty()) {
      allowedRedirectHosts = Set.of();
    } else {
      LinkedHashSet<String> normalized = new LinkedHashSet<>();
      for (String host : allowedRedirectHosts) {
        try {
          normalized.add(RedirectHosts.normalizeRule(host));
        } catch (IllegalArgumentException ignored) {
          // Invalid persisted rules fail closed.
        }
      }
      allowedRedirectHosts = Set.copyOf(normalized);
    }
    if (allowedRedirectHosts.contains("*")
        && (proxyRemoteUrl == null || !"https".equalsIgnoreCase(java.net.URI.create(proxyRemoteUrl).getScheme())
            || (outboundProxy != null && outboundProxy.enabled()))) {
      throw new IllegalArgumentException("Standalone * requires an HTTPS upstream and direct outbound transport");
    }
  }

  /** Compatibility constructor for runtime snapshots created before redirect-host allowlisting. */
  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String proxyRemoteUsername,
      String proxyRemotePassword,
      String proxyRemoteBearerToken,
      String rawContentDisposition,
      Boolean dockerConnectorEnabled,
      Integer dockerConnectorPort,
      String dockerConnectorPublicUrl,
      Boolean cargoRequireAuthentication,
      List<RepositoryRuntime> members,
      OutboundProxyConfig outboundProxy,
      Integer minimumReleaseAgeMinutes) {
    this(id, name, format, type, recipeName, online, blobStoreId, writePolicy,
        versionPolicy, layoutPolicy, strictContentTypeValidation, proxyRemoteUrl,
        contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock, proxyRemoteUsername,
        proxyRemotePassword, proxyRemoteBearerToken, rawContentDisposition,
        dockerConnectorEnabled, dockerConnectorPort, dockerConnectorPublicUrl,
        cargoRequireAuthentication, members, outboundProxy, minimumReleaseAgeMinutes, Set.of());
  }

  /** Compatibility constructor for runtime snapshots created before npm release-age protection. */
  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String proxyRemoteUsername,
      String proxyRemotePassword,
      String proxyRemoteBearerToken,
      String rawContentDisposition,
      Boolean dockerConnectorEnabled,
      Integer dockerConnectorPort,
      String dockerConnectorPublicUrl,
      Boolean cargoRequireAuthentication,
      List<RepositoryRuntime> members,
      OutboundProxyConfig outboundProxy) {
    this(id, name, format, type, recipeName, online, blobStoreId, writePolicy,
        versionPolicy, layoutPolicy, strictContentTypeValidation, proxyRemoteUrl,
        contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock, proxyRemoteUsername,
        proxyRemotePassword, proxyRemoteBearerToken, rawContentDisposition,
        dockerConnectorEnabled, dockerConnectorPort, dockerConnectorPublicUrl,
        cargoRequireAuthentication, members, outboundProxy, null);
  }

  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String rawContentDisposition,
      List<RepositoryRuntime> members) {
    this(
        id,
        name,
        format,
        type,
        recipeName,
        online,
        blobStoreId,
        writePolicy,
        versionPolicy,
        layoutPolicy,
        strictContentTypeValidation,
        proxyRemoteUrl,
        contentMaxAgeMinutes,
        metadataMaxAgeMinutes,
        autoBlock,
        null,
        null,
        null,
        rawContentDisposition,
        null,
        null,
        null,
        null,
        members,
        null);
  }

  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String rawContentDisposition,
      Boolean dockerConnectorEnabled,
      Integer dockerConnectorPort,
      String dockerConnectorPublicUrl,
      List<RepositoryRuntime> members) {
    this(
        id,
        name,
        format,
        type,
        recipeName,
        online,
        blobStoreId,
        writePolicy,
        versionPolicy,
        layoutPolicy,
        strictContentTypeValidation,
        proxyRemoteUrl,
        contentMaxAgeMinutes,
        metadataMaxAgeMinutes,
        autoBlock,
        null,
        null,
        null,
        rawContentDisposition,
        dockerConnectorEnabled,
        dockerConnectorPort,
        dockerConnectorPublicUrl,
        null,
        members,
        null);
  }

  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String proxyRemoteUsername,
      String proxyRemotePassword,
      String rawContentDisposition,
      Boolean dockerConnectorEnabled,
      Integer dockerConnectorPort,
      String dockerConnectorPublicUrl,
      List<RepositoryRuntime> members) {
    this(
        id,
        name,
        format,
        type,
        recipeName,
        online,
        blobStoreId,
        writePolicy,
        versionPolicy,
        layoutPolicy,
        strictContentTypeValidation,
        proxyRemoteUrl,
        contentMaxAgeMinutes,
        metadataMaxAgeMinutes,
        autoBlock,
        proxyRemoteUsername,
        proxyRemotePassword,
        null,
        rawContentDisposition,
        dockerConnectorEnabled,
        dockerConnectorPort,
        dockerConnectorPublicUrl,
        null,
        members,
        null);
  }

  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      List<RepositoryRuntime> members) {
    this(
        id,
        name,
        format,
        type,
        recipeName,
        online,
        blobStoreId,
        writePolicy,
        versionPolicy,
        layoutPolicy,
        strictContentTypeValidation,
        proxyRemoteUrl,
        contentMaxAgeMinutes,
        metadataMaxAgeMinutes,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        members,
        null);
  }

  public RepositoryRuntime(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      String recipeName,
      boolean online,
      Long blobStoreId,
      String writePolicy,
      String versionPolicy,
      String layoutPolicy,
      boolean strictContentTypeValidation,
      String proxyRemoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String rawContentDisposition,
      List<RepositoryRuntime> members,
      Integer minimumReleaseAgeMinutes) {
    this(
        id,
        name,
        format,
        type,
        recipeName,
        online,
        blobStoreId,
        writePolicy,
        versionPolicy,
        layoutPolicy,
        strictContentTypeValidation,
        proxyRemoteUrl,
        contentMaxAgeMinutes,
        metadataMaxAgeMinutes,
        autoBlock,
        null,
        null,
        null,
        rawContentDisposition,
        null,
        null,
        null,
        null,
        members,
        null,
        minimumReleaseAgeMinutes);
  }

  public boolean isHosted() {
    return type == RepositoryType.HOSTED;
  }

  public boolean isProxy() {
    return type == RepositoryType.PROXY;
  }

  public boolean isGroup() {
    return type == RepositoryType.GROUP;
  }

  public int contentMaxAgeMinutesOrDefault() {
    return contentMaxAgeMinutes == null ? 1440 : contentMaxAgeMinutes;
  }

  public int metadataMaxAgeMinutesOrDefault() {
    return metadataMaxAgeMinutes == null ? 1440 : metadataMaxAgeMinutes;
  }

  public int effectiveContentMaxAgeMinutesOrDefault() {
    return effectiveMaxAgeMinutes(false, new HashSet<>());
  }

  public int effectiveMetadataMaxAgeMinutesOrDefault() {
    return effectiveMaxAgeMinutes(true, new HashSet<>());
  }

  private int effectiveMaxAgeMinutes(boolean metadata, Set<Long> resolvingGroups) {
    boolean addedGroup = false;
    if (isGroup()) {
      if (!resolvingGroups.add(id)) {
        return -1;
      }
      addedGroup = true;
    }
    try {
      int effective = metadata ? metadataMaxAgeMinutesOrDefault() : contentMaxAgeMinutesOrDefault();
      if (isGroup() && members != null) {
        for (RepositoryRuntime member : members) {
          if (member != null) {
            effective = shortestFiniteMaxAge(
                effective,
                member.effectiveMaxAgeMinutes(metadata, resolvingGroups));
          }
        }
      }
      return effective;
    } finally {
      if (addedGroup) {
        resolvingGroups.remove(id);
      }
    }
  }

  private static int shortestFiniteMaxAge(int left, int right) {
    if (left < 0) {
      return right;
    }
    if (right < 0) {
      return left;
    }
    return Math.min(left, right);
  }

  public boolean autoBlockOrDefault() {
    return autoBlock == null ? true : autoBlock;
  }

  public int minimumReleaseAgeMinutesOrDefault() {
    return minimumReleaseAgeMinutes == null ? 0 : minimumReleaseAgeMinutes;
  }

  public boolean minimumReleaseAgeEnabled() {
    return format == RepositoryFormat.NPM
        && isProxy()
        && minimumReleaseAgeMinutesOrDefault() > 0;
  }

  /** Derived from the existing persisted allowlist; not a separate configuration property. */
  public com.github.klboke.kkrepo.server.security.ProxyRedirectPolicy contentRedirectPolicy() {
    return allowedRedirectHosts.contains("*")
        ? com.github.klboke.kkrepo.server.security.ProxyRedirectPolicy.PUBLIC_HTTPS
        : com.github.klboke.kkrepo.server.security.ProxyRedirectPolicy.ALLOWLIST;
  }

  public Set<String> strictRedirectHosts() {
    return allowedRedirectHosts.stream().filter(host -> !"*".equals(host))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Authentication and discovery never inherit the content-only standalone star. */
  public RepositoryRuntime withoutPublicContentRedirects() {
    return new RepositoryRuntime(
        id, name, format, type,
        recipeName, online, blobStoreId, writePolicy,
        versionPolicy, layoutPolicy, strictContentTypeValidation, proxyRemoteUrl,
        contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock, proxyRemoteUsername,
        proxyRemotePassword, proxyRemoteBearerToken, rawContentDisposition, dockerConnectorEnabled,
        dockerConnectorPort, dockerConnectorPublicUrl, cargoRequireAuthentication, members,
        outboundProxy, minimumReleaseAgeMinutes, strictRedirectHosts(), ntlmCredentials);
  }

  /** Combines operator-configured hosts with protocol-owned redirect destinations. */
  public Set<String> allowedRedirectHostsWith(Set<String> protocolRedirectHosts) {
    if (protocolRedirectHosts == null || protocolRedirectHosts.isEmpty()) {
      return strictRedirectHosts();
    }
    LinkedHashSet<String> merged = new LinkedHashSet<>(strictRedirectHosts());
    for (String host : protocolRedirectHosts) {
      String value = normalizeRedirectHost(host);
      if (!value.isBlank()) merged.add(value);
    }
    return Set.copyOf(merged);
  }

  public boolean allowsRedirectHost(String host) {
    return RedirectHosts.matches(allowedRedirectHosts, host);
  }

  private static String normalizeRedirectHost(String host) {
    String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
    return value;
  }

  public String rawContentDispositionOrDefault() {
    return rawContentDisposition == null || rawContentDisposition.isBlank()
        ? "ATTACHMENT"
        : rawContentDisposition;
  }

  public boolean dockerConnectorEnabledOrDefault() {
    return dockerConnectorEnabled == null ? dockerConnectorPort != null : dockerConnectorEnabled;
  }

  public boolean cargoRequireAuthenticationOrDefault() {
    return Boolean.TRUE.equals(cargoRequireAuthentication);
  }
}
