package com.github.klboke.kkrepo.server.repositories;

import java.util.List;

/**
 * Plain request payloads used by {@link RepositoryService}. Kept in one file because they
 * are tiny and only meaningful together.
 */
public final class RepositoryCommands {
  private RepositoryCommands() {
  }

  public record CreateCommand(
      String name,
      String recipe,
      Boolean online,
      String blobStoreName,
      Boolean strictContentTypeValidation,
      HostedSettings hosted,
      ProxySettings proxy,
      RawSettings raw,
      DockerSettings docker,
      CargoSettings cargo,
      GroupSettings group) {
  }

  public record UpdateCommand(
      Boolean online,
      String blobStoreName,
      Boolean strictContentTypeValidation,
      HostedSettings hosted,
      ProxySettings proxy,
      RawSettings raw,
      DockerSettings docker,
      CargoSettings cargo,
      GroupSettings group) {
  }

  public record HostedSettings(
      String writePolicy,
      String versionPolicy,
      String layoutPolicy) {
  }

  public record ProxySettings(
      String remoteUrl,
      Integer contentMaxAgeMinutes,
      Integer metadataMaxAgeMinutes,
      Boolean autoBlock,
      String remoteUsername,
      String remotePassword,
      Boolean remotePasswordConfigured,
      String remoteBearerToken,
      Boolean remoteBearerTokenConfigured) {
    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock,
        String remoteUsername,
        String remotePassword,
        Boolean remotePasswordConfigured) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
          remoteUsername, remotePassword, remotePasswordConfigured, null, null);
    }

    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock, null, null, null, null, null);
    }
  }

  public record RawSettings(
      String contentDisposition) {
  }

  public record DockerSettings(
      Boolean connectorEnabled,
      Integer connectorPort,
      String connectorPublicUrl) {
  }

  public record CargoSettings(
      Boolean requireAuthentication) {
  }

  public record GroupSettings(
      List<String> memberNames) {
  }
}
