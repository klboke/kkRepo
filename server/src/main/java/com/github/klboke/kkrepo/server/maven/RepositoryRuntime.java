package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable per-request snapshot of a repository's configuration relevant to serving Maven
 * traffic. Built fresh for each request by {@link RepositoryRuntimeRegistry} — no in-process
 * cache yet because round 1 must remain stateless across replicas.
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
    Set<String> allowedRedirectHosts) {

  public RepositoryRuntime {
    if (allowedRedirectHosts == null || allowedRedirectHosts.isEmpty()) {
      allowedRedirectHosts = Set.of();
    } else {
      LinkedHashSet<String> normalized = new LinkedHashSet<>();
      for (String host : allowedRedirectHosts) {
        String value = normalizeRedirectHost(host);
        // Operator configuration is exact-host only. The wildcard is reserved for explicit,
        // protocol-owned integrity-pinned download flows and must never be activated from stored
        // repository attributes, even if those attributes bypass RepositoryService validation.
        if (!value.isBlank() && !"*".equals(value)) normalized.add(value);
      }
      allowedRedirectHosts = Set.copyOf(normalized);
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

  /** Combines operator-configured hosts with protocol-owned redirect destinations. */
  public Set<String> allowedRedirectHostsWith(Set<String> protocolRedirectHosts) {
    if (protocolRedirectHosts == null || protocolRedirectHosts.isEmpty()) {
      return allowedRedirectHosts;
    }
    LinkedHashSet<String> merged = new LinkedHashSet<>(allowedRedirectHosts);
    for (String host : protocolRedirectHosts) {
      String value = normalizeRedirectHost(host);
      if (!value.isBlank()) merged.add(value);
    }
    return Set.copyOf(merged);
  }

  public boolean allowsRedirectHost(String host) {
    return allowedRedirectHosts.contains(normalizeRedirectHost(host));
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
