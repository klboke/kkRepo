package com.github.klboke.kkrepo.server.npm;

import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmTarballCompatibility;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stateless npm tarball resolution from a shared raw packument snapshot.
 * Performs no I/O, cache lookup, host authorization, or credential handling. The proxy obtains
 * metadata and downloads the result through the common outbound HTTP policy on every replica.
 */
final class NpmTarballResolver {
  private NpmTarballResolver() {}

  enum Source { DECLARED_URL, LEGACY_BASENAME, REQUEST_PATH_FALLBACK }

  record Download(String upstreamUrl, String assetPath, Source source) {}

  static Download resolve(
      String remoteBaseUrl,
      NpmPackageId packageId,
      String requestedIdentity,
      String rawRequestPath,
      Map<String, Object> packageRoot) {
    Map<String, Set<String>> identities = declaredUrls(packageRoot);
    boolean exact = identities.containsKey(requestedIdentity);
    String identity = exact ? requestedIdentity
        : NpmTarballCompatibility.legacyBasenameAlias(identities.keySet(), requestedIdentity);
    String assetPath = packageId.tarballPath(requestedIdentity);
    if (identity == null) {
      return switch (NpmTarballCompatibility.fallback(identities.keySet(), requestedIdentity)) {
        case REQUEST_PATH -> new Download(
            RemoteUrlBuilder.repositoryPathString(remoteBaseUrl, rawRequestPath),
            assetPath, Source.REQUEST_PATH_FALLBACK);
        case UNDECLARED_PATH -> throw new NpmExceptions.NpmNotFoundException(
            "Tarball path is not declared for " + packageId.id());
        case AMBIGUOUS_BASENAME -> throw ambiguous(packageId);
      };
    }
    Set<String> urls = identities.get(identity);
    if (urls.size() != 1) throw ambiguous(packageId);
    String upstreamUrl = declaredUrl(remoteBaseUrl, urls.iterator().next(), packageId);
    return new Download(upstreamUrl, assetPath, exact ? Source.DECLARED_URL : Source.LEGACY_BASENAME);
  }

  private static Map<String, Set<String>> declaredUrls(Map<String, Object> packageRoot) {
    Map<String, Set<String>> identities = new LinkedHashMap<>();
    if (packageRoot == null || !(packageRoot.get(NpmMetadata.VERSIONS) instanceof Map<?, ?> versions)) {
      return identities;
    }
    for (Object value : versions.values()) {
      if (!(value instanceof Map<?, ?> version)
          || !(version.get(NpmMetadata.DIST) instanceof Map<?, ?> dist)
          || !(dist.get(NpmMetadata.TARBALL) instanceof String url)) continue;
      String identity = NpmMetadata.canonicalTarballName(url);
      if (identity != null) identities.computeIfAbsent(identity, ignored -> new LinkedHashSet<>()).add(url);
    }
    return identities;
  }

  private static String declaredUrl(String remoteBaseUrl, String declared, NpmPackageId packageId) {
    try {
      URI target = RemoteUrlBuilder.repositoryBase(remoteBaseUrl).resolve(declared);
      if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
          || !("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme()))) {
        throw new IllegalArgumentException("Invalid upstream tarball URL");
      }
      return target.toString();
    } catch (IllegalArgumentException invalid) {
      throw new NpmExceptions.BadUpstreamException("Invalid upstream tarball URL for " + packageId.id());
    }
  }

  private static NpmExceptions.BadUpstreamException ambiguous(NpmPackageId packageId) {
    return new NpmExceptions.BadUpstreamException("Ambiguous upstream tarball URL for " + packageId.id());
  }
}
