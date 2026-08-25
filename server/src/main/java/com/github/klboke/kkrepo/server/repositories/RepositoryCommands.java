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
      GroupSettings group,
      AptSettings apt,
      AlpineSettings alpine,
      PypiSettings pypi) {
    public CreateCommand(
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
      this(name, recipe, online, blobStoreName, strictContentTypeValidation, hosted, proxy,
          raw, docker, cargo, group, null, null, null);
    }

    public CreateCommand(
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
        GroupSettings group,
        AptSettings apt) {
      this(name, recipe, online, blobStoreName, strictContentTypeValidation, hosted, proxy,
          raw, docker, cargo, group, apt, null, null);
    }

    /** Compatibility constructor for callers that predate PyPI proxy index-path settings. */
    public CreateCommand(
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
        GroupSettings group,
        AptSettings apt,
        AlpineSettings alpine) {
      this(name, recipe, online, blobStoreName, strictContentTypeValidation, hosted, proxy,
          raw, docker, cargo, group, apt, alpine, null);
    }
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
      GroupSettings group,
      AptSettings apt,
      AlpineSettings alpine,
      PypiSettings pypi) {
    public UpdateCommand(
        Boolean online,
        String blobStoreName,
        Boolean strictContentTypeValidation,
        HostedSettings hosted,
        ProxySettings proxy,
        RawSettings raw,
        DockerSettings docker,
        CargoSettings cargo,
        GroupSettings group) {
      this(online, blobStoreName, strictContentTypeValidation, hosted, proxy, raw, docker,
          cargo, group, null, null, null);
    }

    public UpdateCommand(
        Boolean online,
        String blobStoreName,
        Boolean strictContentTypeValidation,
        HostedSettings hosted,
        ProxySettings proxy,
        RawSettings raw,
        DockerSettings docker,
        CargoSettings cargo,
        GroupSettings group,
        AptSettings apt) {
      this(online, blobStoreName, strictContentTypeValidation, hosted, proxy, raw, docker,
          cargo, group, apt, null, null);
    }

    /** Compatibility constructor for callers that predate PyPI proxy index-path settings. */
    public UpdateCommand(
        Boolean online,
        String blobStoreName,
        Boolean strictContentTypeValidation,
        HostedSettings hosted,
        ProxySettings proxy,
        RawSettings raw,
        DockerSettings docker,
        CargoSettings cargo,
        GroupSettings group,
        AptSettings apt,
        AlpineSettings alpine) {
      this(online, blobStoreName, strictContentTypeValidation, hosted, proxy, raw, docker,
          cargo, group, apt, alpine, null);
    }
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
      Boolean remoteBearerTokenConfigured,
      String outboundProxyType,
      String outboundProxyHost,
      Integer outboundProxyPort,
      String outboundProxyUsername,
      String outboundProxyPassword,
      Boolean outboundProxyPasswordConfigured,
      Integer minimumReleaseAgeMinutes,
      List<String> allowedRedirectHosts) {
    /** Compatibility constructor for callers that predate redirect-host allowlisting. */
    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock,
        String remoteUsername,
        String remotePassword,
        Boolean remotePasswordConfigured,
        String remoteBearerToken,
        Boolean remoteBearerTokenConfigured,
        String outboundProxyType,
        String outboundProxyHost,
        Integer outboundProxyPort,
        String outboundProxyUsername,
        String outboundProxyPassword,
        Boolean outboundProxyPasswordConfigured,
        Integer minimumReleaseAgeMinutes) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
          remoteUsername, remotePassword, remotePasswordConfigured,
          remoteBearerToken, remoteBearerTokenConfigured,
          outboundProxyType, outboundProxyHost, outboundProxyPort,
          outboundProxyUsername, outboundProxyPassword, outboundProxyPasswordConfigured,
          minimumReleaseAgeMinutes, null);
    }

    /** Compatibility constructor for callers that predate npm release-age protection. */
    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock,
        String remoteUsername,
        String remotePassword,
        Boolean remotePasswordConfigured,
        String remoteBearerToken,
        Boolean remoteBearerTokenConfigured,
        String outboundProxyType,
        String outboundProxyHost,
        Integer outboundProxyPort,
        String outboundProxyUsername,
        String outboundProxyPassword,
        Boolean outboundProxyPasswordConfigured) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
          remoteUsername, remotePassword, remotePasswordConfigured,
          remoteBearerToken, remoteBearerTokenConfigured,
          outboundProxyType, outboundProxyHost, outboundProxyPort,
          outboundProxyUsername, outboundProxyPassword, outboundProxyPasswordConfigured,
          null);
    }

    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock,
        String remoteUsername,
        String remotePassword,
        Boolean remotePasswordConfigured) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
          remoteUsername, remotePassword, remotePasswordConfigured, null, null, null, null, null, null, null, null);
    }

    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock,
        String remoteUsername,
        String remotePassword,
        Boolean remotePasswordConfigured,
        String remoteBearerToken,
        Boolean remoteBearerTokenConfigured) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
          remoteUsername, remotePassword, remotePasswordConfigured,
          remoteBearerToken, remoteBearerTokenConfigured,
          null, null, null, null, null, null);
    }

    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
          null, null, null, null, null, null, null, null, null, null, null);
    }

    public ProxySettings(
        String remoteUrl,
        Integer contentMaxAgeMinutes,
        Integer metadataMaxAgeMinutes,
        Boolean autoBlock,
        Integer minimumReleaseAgeMinutes) {
      this(remoteUrl, contentMaxAgeMinutes, metadataMaxAgeMinutes, autoBlock,
          null, null, null, null, null, null, null, null, null, null, null,
          minimumReleaseAgeMinutes);
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

  /** APT distribution and signed-metadata behavior shared by hosted and proxy recipes. */
  public record AptSettings(
      String distribution,
      String component,
      List<String> architectures,
      Boolean flat,
      Boolean enforceDistribution,
      String metadataMode,
      Integer validUntilDays,
      String origin,
      String label) {
  }

  /** APK v2 namespace, signing, and upstream trust behavior. */
  public record AlpineSettings(
      List<String> distributions,
      List<String> channels,
      List<String> architectures,
      String metadataMode,
      Boolean verifyUpstreamSignatures,
      Boolean staleIfError,
      String keyFilename,
      String signatureType,
      String description,
      List<String> upstreamPublicKeys) {
  }

  /** Nexus-compatible PyPI proxy settings. Empty indexPath means the upstream root index. */
  public record PypiSettings(String indexPath) {
  }

  public record GroupSettings(
      List<String> memberNames) {
  }
}
