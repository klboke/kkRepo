package com.github.klboke.kkrepo.server.pub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.pub.PubPackageName;
import com.github.klboke.kkrepo.protocol.pub.PubPath;
import com.github.klboke.kkrepo.protocol.pub.PubPaths;
import com.github.klboke.kkrepo.protocol.pub.PubVersions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PubGroupService {
  private final PubHostedService hosted;
  private final PubProxyService proxy;
  private final ObjectMapper objectMapper;

  public PubGroupService(PubHostedService hosted, PubProxyService proxy, ObjectMapper objectMapper) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(RepositoryRuntime runtime, PubPath path, String baseUrl, boolean headOnly) {
    ensureGroup(runtime);
    return switch (path.kind()) {
      case PACKAGE_METADATA -> packageMetadata(runtime, path.packageName(), baseUrl, headOnly);
      case VERSION_METADATA -> versionMetadata(runtime, path.packageName(), path.version(), baseUrl, headOnly);
      case VERSION_JSON -> versionJson(runtime, path.packageName(), path.version(), baseUrl, headOnly);
      case ARCHIVE -> download(runtime, path.packageName(), path.version(), headOnly);
      case PACKAGE_NAMES, PACKAGE_NAME_COMPLETION -> packageNames(runtime, headOnly);
      case PUBLISH_INIT, PUBLISH_UPLOAD, PUBLISH_FINALIZE ->
          throw new PubExceptions.PubNotFoundException(path.rawPath());
      default -> throw new PubExceptions.PubNotFoundException(path.rawPath());
    };
  }

  MavenResponse packageMetadata(RepositoryRuntime runtime, String packageName, String baseUrl, boolean headOnly) {
    String normalized = PubPackageName.require(packageName);
    MergedMetadata merged = merge(runtime, normalized, baseUrl, new HashSet<>());
    return PubResponses.json(objectMapper, merged.body(), 200, null, merged.lastModified(), headOnly);
  }

  MavenResponse versionMetadata(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      String baseUrl,
      boolean headOnly) {
    String safeVersion = PubVersions.require(version);
    MergedMetadata merged = merge(runtime, packageName, baseUrl, new HashSet<>());
    for (Map<String, Object> entry : PubProxyService.versions(merged.body())) {
      if (safeVersion.equals(String.valueOf(entry.get("version")))) {
        return PubResponses.json(objectMapper, entry, 200, null, merged.lastModified(), headOnly);
      }
    }
    throw new PubExceptions.PubNotFoundException(packageName + " " + safeVersion);
  }

  MavenResponse download(RepositoryRuntime runtime, String packageName, String version, boolean headOnly) {
    return download(runtime, packageName, version, headOnly, new HashSet<>());
  }

  MavenResponse versionJson(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      String baseUrl,
      boolean headOnly) {
    return versionJson(runtime, packageName, version, baseUrl, headOnly, new HashSet<>());
  }

  private MavenResponse versionJson(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      String baseUrl,
      boolean headOnly,
      Set<Long> resolvingGroups) {
    ensureGroup(runtime);
    if (!resolvingGroups.add(runtime.id())) {
      throw new PubExceptions.PubNotFoundException(PubPaths.versionJsonPath(packageName, version));
    }
    PubExceptions.BadUpstreamException lastUpstream = null;
    try {
      for (RepositoryRuntime member : runtime.members()) {
        if (!eligible(member)) {
          continue;
        }
        String memberBaseUrl = memberBaseUrl(baseUrl, member);
        try {
          if (member.isGroup()) {
            return versionJson(member, packageName, version, memberBaseUrl, headOnly, resolvingGroups);
          }
          return switch (member.type()) {
            case HOSTED -> hosted.versionJson(member, packageName, version, memberBaseUrl, headOnly);
            case PROXY -> proxy.versionJson(member, packageName, version, headOnly);
            case GROUP -> throw new IllegalStateException("Unexpected Pub group version.json branch");
          };
        } catch (PubExceptions.PubNotFoundException ignored) {
          // Continue with the next member.
        } catch (PubExceptions.BadUpstreamException e) {
          lastUpstream = e;
        }
      }
    } finally {
      resolvingGroups.remove(runtime.id());
    }
    if (lastUpstream != null) {
      throw lastUpstream;
    }
    throw new PubExceptions.PubNotFoundException(PubPaths.versionJsonPath(packageName, version));
  }

  private MavenResponse download(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      boolean headOnly,
      Set<Long> resolvingGroups) {
    ensureGroup(runtime);
    if (!resolvingGroups.add(runtime.id())) {
      throw new PubExceptions.PubNotFoundException(PubPaths.archivePath(packageName, version));
    }
    PubExceptions.BadUpstreamException lastUpstream = null;
    try {
      for (RepositoryRuntime member : runtime.members()) {
        if (!eligible(member)) {
          continue;
        }
        try {
          if (member.isGroup()) {
            return download(member, packageName, version, headOnly, resolvingGroups);
          }
          return switch (member.type()) {
            case HOSTED -> hosted.download(member, packageName, version, headOnly);
            case PROXY -> proxy.download(member, packageName, version, headOnly);
            case GROUP -> throw new IllegalStateException("Unexpected Pub group download branch");
          };
        } catch (PubExceptions.PubNotFoundException ignored) {
          // Continue with the next member.
        } catch (PubExceptions.BadUpstreamException e) {
          lastUpstream = e;
        }
      }
    } finally {
      resolvingGroups.remove(runtime.id());
    }
    if (lastUpstream != null) {
      throw lastUpstream;
    }
    throw new PubExceptions.PubNotFoundException(PubPaths.archivePath(packageName, version));
  }

  private MergedMetadata merge(
      RepositoryRuntime runtime,
      String packageName,
      String baseUrl,
      Set<Long> resolvingGroups) {
    ensureGroup(runtime);
    String normalized = PubPackageName.require(packageName);
    if (!resolvingGroups.add(runtime.id())) {
      throw new PubExceptions.PubNotFoundException(normalized);
    }
    Map<String, Map<String, Object>> byVersion = new LinkedHashMap<>();
    Instant lastModified = null;
    PubExceptions.BadUpstreamException lastUpstream = null;
    try {
      for (RepositoryRuntime member : runtime.members()) {
        if (!eligible(member)) {
          continue;
        }
        try {
          MergedMetadata memberMetadata = memberMetadata(member, normalized, baseUrl, resolvingGroups);
          lastModified = later(lastModified, memberMetadata.lastModified());
          for (Map<String, Object> entry : PubProxyService.versions(memberMetadata.body())) {
            String version = PubVersions.require(String.valueOf(entry.get("version")));
            byVersion.putIfAbsent(version, rewriteVersion(entry, normalized, version, baseUrl));
          }
        } catch (PubExceptions.PubNotFoundException ignored) {
          // Continue with the next member.
        } catch (PubExceptions.BadUpstreamException e) {
          lastUpstream = e;
        }
      }
    } finally {
      resolvingGroups.remove(runtime.id());
    }
    if (byVersion.isEmpty()) {
      if (lastUpstream != null) {
        throw lastUpstream;
      }
      throw new PubExceptions.PubNotFoundException(normalized);
    }
    List<Map<String, Object>> versions = new ArrayList<>(byVersion.values());
    versions.sort(Comparator.comparing(entry -> String.valueOf(entry.get("version")), PubVersions.COMPARATOR));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", normalized);
    body.put("latest", PubMetadataSupport.latestStableFirst(
        versions, entry -> String.valueOf(entry.get("version"))));
    body.put("versions", versions);
    return new MergedMetadata(body, lastModified);
  }

  private MergedMetadata memberMetadata(
      RepositoryRuntime member,
      String packageName,
      String baseUrl,
      Set<Long> resolvingGroups) {
    if (member.isGroup()) {
      return merge(member, packageName, baseUrl, resolvingGroups);
    }
    MavenResponse response = switch (member.type()) {
      case HOSTED -> hosted.packageMetadata(member, packageName, baseUrl, false);
      case PROXY -> proxy.packageMetadata(member, packageName, baseUrl, false);
      case GROUP -> throw new IllegalStateException("Unexpected Pub group metadata branch");
    };
    return new MergedMetadata(readJson(response), response.lastModified());
  }

  private MavenResponse packageNames(RepositoryRuntime runtime, boolean headOnly) {
    return PubResponses.json(objectMapper,
        Map.of("packages", packageNames(runtime, new HashSet<>())),
        200,
        headOnly);
  }

  private List<String> packageNames(RepositoryRuntime runtime, Set<Long> resolvingGroups) {
    ensureGroup(runtime);
    if (!resolvingGroups.add(runtime.id())) {
      return List.of();
    }
    LinkedHashSet<String> packages = new LinkedHashSet<>();
    try {
      for (RepositoryRuntime member : runtime.members()) {
        if (!eligible(member)) {
          continue;
        }
        try {
          if (member.isGroup()) {
            packages.addAll(packageNames(member, resolvingGroups));
          } else {
            packages.addAll(packageNamesFrom(packageNamesResponse(member)));
          }
        } catch (PubExceptions.PubNotFoundException ignored) {
          // Continue with the next member.
        }
      }
    } finally {
      resolvingGroups.remove(runtime.id());
    }
    return List.copyOf(packages);
  }

  private MavenResponse packageNamesResponse(RepositoryRuntime member) {
    PubPath path = new PubPath(PubPath.Kind.PACKAGE_NAMES, "api/package-names", null, null, null);
    return switch (member.type()) {
      case HOSTED -> hosted.get(member, path, member.name(), false);
      case PROXY -> proxy.get(member, path, member.name(), false);
      case GROUP -> throw new IllegalStateException("Unexpected Pub group package-names branch");
    };
  }

  private List<String> packageNamesFrom(MavenResponse response) {
    Map<String, Object> body = readJson(response);
    Object value = body.get("packages");
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .map(String::valueOf)
        .map(PubPackageName::require)
        .toList();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readJson(MavenResponse response) {
    try (var body = response.body()) {
      return objectMapper.readValue(body, PubProxyService.JSON_MAP);
    } catch (IOException e) {
      throw new PubExceptions.BadUpstreamException("Failed reading Pub group member metadata", e);
    }
  }

  private Map<String, Object> rewriteVersion(
      Map<String, Object> entry,
      String packageName,
      String version,
      String baseUrl) {
    Map<String, Object> rewritten = new LinkedHashMap<>(entry);
    rewritten.put("archive_url", baseUrl + "/" + PubPaths.apiArchivePath(packageName, version));
    return rewritten;
  }

  private String memberBaseUrl(String groupBaseUrl, RepositoryRuntime member) {
    if (groupBaseUrl == null || groupBaseUrl.isBlank()) {
      return member.name();
    }
    int slash = groupBaseUrl.lastIndexOf('/');
    if (slash < 0) {
      return member.name();
    }
    return groupBaseUrl.substring(0, slash + 1) + member.name();
  }

  private void ensureGroup(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.PUB || !runtime.isGroup()) {
      throw new PubExceptions.MethodNotAllowed("Operation is only valid on group Pub repositories");
    }
  }

  private boolean eligible(RepositoryRuntime member) {
    return member.online()
        && member.format() == RepositoryFormat.PUB
        && (member.isHosted() || member.isProxy() || member.isGroup());
  }

  private static Instant later(Instant left, Instant right) {
    if (left == null) return right;
    if (right == null) return left;
    return left.isAfter(right) ? left : right;
  }

  private record MergedMetadata(Map<String, Object> body, Instant lastModified) {
  }
}
