package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig;
import java.util.HashSet;
import java.util.List;
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
    OutboundProxyConfig outboundProxy) {

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
