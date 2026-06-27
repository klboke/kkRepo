package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import java.util.List;

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
    List<RepositoryRuntime> members) {

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
        members);
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
        members);
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
        members);
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
        members);
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
