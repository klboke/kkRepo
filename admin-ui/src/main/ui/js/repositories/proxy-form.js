export const MINIMUM_RELEASE_AGE_FORMATS = new Set(["npm", "go", "maven2", "pypi"]);

export function proxyFormValues(proxy) {
  return {
    remoteUrl: proxy.remoteUrl || "",
    allowedRedirectHosts: Array.isArray(proxy.allowedRedirectHosts) ? proxy.allowedRedirectHosts.join(", ") : "",
    remoteAuthenticationType: proxy.remoteAuthenticationType || "auto",
    remoteNtlmDomain: proxy.remoteNtlmDomain || "",
    remoteNtlmHost: proxy.remoteNtlmHost || "",
    remoteUsername: proxy.remoteUsername || "",
    contentMaxAgeMinutes: proxy.contentMaxAgeMinutes ?? "1440",
    metadataMaxAgeMinutes: proxy.metadataMaxAgeMinutes ?? "1440",
    minimumReleaseAgeMinutes: proxy.minimumReleaseAgeMinutes ?? "0",
    autoBlock: proxy.autoBlock !== false,
    blockOutboundConnections: proxy.blockOutboundConnections === true,
    streamingEnabled: proxy.streamingEnabled === true,
  };
}

export function proxyCommand(format, fields, outboundProxy) {
  const { routingRuleName, contentMaxAgeMinutes, metadataMaxAgeMinutes, minimumReleaseAgeMinutes,
    allowedRedirectHosts, clearPassword, clearBearerToken, ...settings } = fields;
  return {
    ...(routingRuleName === undefined ? {} : { routingRuleName }),
    proxy: {
      ...settings,
      remoteUrl: settings.remoteUrl.trim(),
      allowedRedirectHosts: allowedRedirectHosts.split(",").map(value => value.trim()).filter(Boolean),
      contentMaxAgeMinutes: contentMaxAgeMinutes === "" ? null : Number(contentMaxAgeMinutes),
      metadataMaxAgeMinutes: metadataMaxAgeMinutes === "" ? null : Number(metadataMaxAgeMinutes),
      minimumReleaseAgeMinutes: MINIMUM_RELEASE_AGE_FORMATS.has(format) ? Number(minimumReleaseAgeMinutes) : null,
      remotePasswordConfigured: clearPassword ? false : null,
      remoteBearerTokenConfigured: clearBearerToken ? false : null,
      ...outboundProxy,
    },
  };
}
