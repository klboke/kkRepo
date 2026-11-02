package com.github.klboke.kkrepo.server.winget;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.winget.WingetException;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetPackage;
import com.github.klboke.kkrepo.protocol.winget.WingetSearchRequest;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Facade that applies repository type behavior for WinGet REST operations. */
@Service
public class WingetService {
  private static final Logger log = LoggerFactory.getLogger(WingetService.class);

  private final WingetHostedService hosted;
  private final WingetProxyService proxy;

  public WingetService(WingetHostedService hosted, WingetProxyService proxy) {
    this.hosted = hosted;
    this.proxy = proxy;
  }

  public WingetManifest publish(RepositoryRuntime runtime, WingetManifest manifest) {
    requireHosted(runtime, "publish");
    return hosted.publish(runtime, manifest);
  }

  public WingetManifest replace(RepositoryRuntime runtime, WingetManifest manifest) {
    requireHosted(runtime, "replace");
    return hosted.replace(runtime, manifest);
  }

  public int delete(
      RepositoryRuntime runtime, String packageIdentifier, String packageVersion, String channel) {
    requireHosted(runtime, "delete");
    return hosted.delete(runtime, packageIdentifier, packageVersion, channel);
  }

  public WingetManifest manifest(
      RepositoryRuntime runtime, String packageIdentifier, String packageVersion, String channel) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.manifest(runtime, packageIdentifier, packageVersion, channel);
      case PROXY -> proxy.manifest(runtime, packageIdentifier, packageVersion, channel);
      case GROUP -> groupManifest(runtime, packageIdentifier, packageVersion, channel);
    };
  }

  public WingetSearchPage search(
      RepositoryRuntime runtime, WingetSearchRequest request, byte[] rawBody,
      String continuationToken, int offset, int limit) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.search(runtime, request, offset, limit);
      case PROXY -> proxy.search(runtime, request, rawBody, continuationToken, offset, limit);
      case GROUP -> groupSearch(runtime, request, rawBody, limit);
    };
  }

  private WingetManifest groupManifest(
      RepositoryRuntime group, String packageIdentifier, String packageVersion, String channel) {
    List<WingetManifest> manifests = new ArrayList<>();
    for (RepositoryRuntime member : group.members()) {
      try {
        WingetManifest manifest = manifest(member, packageIdentifier, packageVersion, channel);
        if (!manifest.versions().isEmpty()) {
          manifests.add(manifest);
        }
      } catch (WingetException missing) {
        if (missing.status() != 404) {
          log.debug("WinGet group member {} failed: {}", member.name(), missing.getMessage());
        }
      }
    }
    if (manifests.isEmpty()) {
      throw WingetException.notFound("Package manifest not found: " + packageIdentifier);
    }
    return WingetManifests.merge(packageIdentifier, manifests);
  }

  private WingetSearchPage groupSearch(
      RepositoryRuntime group, WingetSearchRequest request, byte[] rawBody, int limit) {
    Map<String, WingetPackage> packages = new LinkedHashMap<>();
    for (RepositoryRuntime member : group.members()) {
      WingetSearchPage page;
      try {
        page = search(member, request, rawBody, null, 0, limit);
      } catch (RuntimeException failure) {
        log.debug("WinGet group member {} search failed: {}", member.name(), failure.getMessage());
        continue;
      }
      for (WingetPackage candidate : page.packages()) {
        WingetPackage current = packages.get(candidate.packageIdentifier());
        if (current == null) {
          packages.put(candidate.packageIdentifier(), candidate);
        } else {
          packages.put(candidate.packageIdentifier(), mergePackage(current, candidate));
        }
      }
      if (packages.size() >= limit) break;
    }
    List<WingetPackage> results = new ArrayList<>(packages.values());
    if (results.size() > limit) {
      results = new ArrayList<>(results.subList(0, limit));
    }
    return new WingetSearchPage(results, null, null);
  }

  private static WingetPackage mergePackage(WingetPackage first, WingetPackage second) {
    List<com.github.klboke.kkrepo.protocol.winget.WingetVersionSummary> versions =
        new ArrayList<>(first.versions());
    Map<String, Boolean> seen = new LinkedHashMap<>();
    for (var version : first.versions()) {
      seen.put(version.packageVersion() + '\u0000' + version.channel(), Boolean.TRUE);
    }
    for (var version : second.versions()) {
      if (seen.putIfAbsent(version.packageVersion() + '\u0000' + version.channel(), Boolean.TRUE) == null) {
        versions.add(version);
      }
    }
    return new WingetPackage(first.packageIdentifier(),
        first.packageName().isBlank() ? second.packageName() : first.packageName(),
        first.publisher().isBlank() ? second.publisher() : first.publisher(), versions);
  }

  private static void requireHosted(RepositoryRuntime runtime, String operation) {
    if (runtime.type() != RepositoryType.HOSTED) {
      throw WingetException.methodNotAllowed(
          "WinGet " + operation + " is only supported on hosted repositories");
    }
  }
}
