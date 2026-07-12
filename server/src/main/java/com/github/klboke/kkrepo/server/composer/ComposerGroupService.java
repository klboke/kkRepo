package com.github.klboke.kkrepo.server.composer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.composer.ComposerPackageName;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ComposerGroupService {
  private final ObjectMapper objectMapper;
  private final ComposerHostedService hosted;
  private final ComposerProxyService proxy;
  private final ComposerPathParser parser = new ComposerPathParser();

  public ComposerGroupService(
      ObjectMapper objectMapper,
      ComposerHostedService hosted,
      ComposerProxyService proxy) {
    this.objectMapper = objectMapper;
    this.hosted = hosted;
    this.proxy = proxy;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      ComposerPath path,
      String baseUrl,
      String filter,
      boolean headOnly) {
    ensureGroup(runtime);
    return switch (path.kind()) {
      case ROOT, PACKAGES -> packages(runtime, baseUrl, headOnly);
      case PACKAGE_METADATA -> packageDocument(runtime, path.packageName(), path.dev(), baseUrl)
          .map(value -> ComposerResponses.json(objectMapper, value.body(), value.lastModified(), headOnly))
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.rawPath()));
      case PROVIDERS -> providers(runtime, path.packageName(), headOnly);
      case PACKAGE_LIST -> packageList(runtime, filter, headOnly);
      case DIST -> dist(runtime, path, headOnly);
      case UNKNOWN -> throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    };
  }

  Optional<ComposerHostedService.PackageDocument> packageDocument(
      RepositoryRuntime runtime,
      String packageName,
      boolean dev,
      String baseUrl) {
    ensureGroup(runtime);
    String name = ComposerPackageName.require(packageName);
    for (RepositoryRuntime member : runtime.members()) {
      MemberMatch match = memberMatch(member, name, dev);
      if (!match.exists()) continue;
      if (match.document().isEmpty()) return Optional.empty();
      ComposerHostedService.PackageDocument source = match.document().get();
      Map<String, Object> rewritten = rewriteDistUrls(source.body(), member.name(), baseUrl);
      return Optional.of(new ComposerHostedService.PackageDocument(
          rewritten, source.lastModified(), member.name()));
    }
    return Optional.empty();
  }

  List<String> packageNames(RepositoryRuntime runtime) {
    ensureGroup(runtime);
    Set<String> names = new LinkedHashSet<>();
    for (RepositoryRuntime member : runtime.members()) names.addAll(memberPackageNames(member));
    return names.stream().sorted().toList();
  }

  private MavenResponse packages(RepositoryRuntime runtime, String baseUrl, boolean headOnly) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packages", List.of());
    body.put("metadata-url", baseUrl + "/p2/%package%.json");
    body.put("providers-api", baseUrl + "/providers/%package%.json");
    body.put("list", baseUrl + "/packages/list.json");
    return ComposerResponses.json(objectMapper, body, latestMemberUpdate(runtime), headOnly);
  }

  private MavenResponse packageList(
      RepositoryRuntime runtime,
      String filter,
      boolean headOnly) {
    List<String> names = packageNames(runtime).stream().filter(name -> globMatches(name, filter)).toList();
    return ComposerResponses.json(
        objectMapper, Map.of("packageNames", names), latestMemberUpdate(runtime), headOnly);
  }

  private MavenResponse providers(
      RepositoryRuntime runtime,
      String virtualPackage,
      boolean headOnly) {
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    for (RepositoryRuntime member : runtime.members()) {
      for (Map<String, Object> provider : memberProviders(member, virtualPackage)) {
        Object name = provider.get("name");
        if (name != null) byName.putIfAbsent(name.toString(), provider);
      }
    }
    return ComposerResponses.json(
        objectMapper, Map.of("providers", List.copyOf(byName.values())),
        latestMemberUpdate(runtime), headOnly);
  }

  private MavenResponse dist(RepositoryRuntime group, ComposerPath path, boolean headOnly) {
    String requestedPath = normalizePath(path.rawPath());
    for (RepositoryRuntime member : group.members()) {
      Optional<ComposerHostedService.PackageDocument> stable = memberDocument(member, path.packageName(), false);
      Optional<ComposerHostedService.PackageDocument> dev = memberDocument(member, path.packageName(), true);
      if (stable.isEmpty() && dev.isEmpty()) continue;
      Optional<String> sourcePath = matchingDistPath(stable, dev, member.name(), requestedPath);
      if (sourcePath.isEmpty()) {
        throw new MavenExceptions.MavenNotFoundException(path.rawPath());
      }
      ComposerPath memberPath = parser.parse(sourcePath.get());
      return dispatchGet(member, memberPath, memberBase(member), null, headOnly);
    }
    throw new MavenExceptions.MavenNotFoundException(path.rawPath());
  }

  private Optional<String> matchingDistPath(
      Optional<ComposerHostedService.PackageDocument> stable,
      Optional<ComposerHostedService.PackageDocument> dev,
      String memberName,
      String requestedPath) {
    return java.util.stream.Stream.concat(stable.stream(), dev.stream())
        .flatMap(document -> distUrls(document.body()).stream())
        .map(url -> memberRepositoryPath(url, memberName))
        .filter(requestedPath::equals)
        .findFirst();
  }

  private static List<String> distUrls(Map<String, Object> body) {
    List<String> urls = new ArrayList<>();
    Object packagesValue = body.get("packages");
    if (!(packagesValue instanceof Map<?, ?> packages)) return urls;
    for (Object versionsValue : packages.values()) {
      if (!(versionsValue instanceof List<?> versions)) continue;
      for (Object item : versions) {
        if (!(item instanceof Map<?, ?> version) || !(version.get("dist") instanceof Map<?, ?> dist)) continue;
        Object url = dist.get("url");
        if (url != null) urls.add(url.toString());
      }
    }
    return urls;
  }

  private MemberMatch memberMatch(RepositoryRuntime member, String name, boolean dev) {
    Optional<ComposerHostedService.PackageDocument> requested = memberDocument(member, name, dev);
    if (requested.isPresent()) return new MemberMatch(true, requested);
    Optional<ComposerHostedService.PackageDocument> opposite = memberDocument(member, name, !dev);
    return new MemberMatch(opposite.isPresent(), Optional.empty());
  }

  private Optional<ComposerHostedService.PackageDocument> memberDocument(
      RepositoryRuntime member,
      String name,
      boolean dev) {
    try {
      return switch (member.type()) {
        case HOSTED -> hosted.packageDocument(member, name, dev, memberBase(member));
        case PROXY -> proxy.packageDocument(member, name, dev, memberBase(member));
        case GROUP -> packageDocument(member, name, dev, memberBase(member));
      };
    } catch (MavenExceptions.MavenNotFoundException ignored) {
      return Optional.empty();
    }
  }

  private List<String> memberPackageNames(RepositoryRuntime member) {
    try {
      return switch (member.type()) {
        case HOSTED -> hosted.packageNames(member);
        case PROXY -> proxy.packageNames(member);
        case GROUP -> packageNames(member);
      };
    } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException ignored) {
      return List.of();
    }
  }

  private List<Map<String, Object>> memberProviders(RepositoryRuntime member, String virtualPackage) {
    try {
      return switch (member.type()) {
        case HOSTED -> hosted.providerNames(member, virtualPackage).stream()
            .map(name -> Map.<String, Object>of("name", name)).toList();
        case PROXY -> proxy.providers(member, virtualPackage);
        case GROUP -> {
          Map<String, Map<String, Object>> values = new LinkedHashMap<>();
          for (RepositoryRuntime nested : member.members()) {
            for (Map<String, Object> provider : memberProviders(nested, virtualPackage)) {
              Object name = provider.get("name");
              if (name != null) values.putIfAbsent(name.toString(), provider);
            }
          }
          yield List.copyOf(values.values());
        }
      };
    } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException ignored) {
      return List.of();
    }
  }

  private MavenResponse dispatchGet(
      RepositoryRuntime runtime,
      ComposerPath path,
      String baseUrl,
      String filter,
      boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.get(runtime, path, baseUrl, filter, headOnly);
      case PROXY -> proxy.get(runtime, path, baseUrl, filter, headOnly);
      case GROUP -> get(runtime, path, baseUrl, filter, headOnly);
    };
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> rewriteDistUrls(
      Map<String, Object> body,
      String memberName,
      String baseUrl) {
    Map<String, Object> result = new LinkedHashMap<>(body);
    Object packagesValue = result.get("packages");
    if (!(packagesValue instanceof Map<?, ?> packages)) return result;
    Map<String, Object> rewrittenPackages = new LinkedHashMap<>();
    for (Map.Entry<?, ?> packageEntry : packages.entrySet()) {
      if (!(packageEntry.getValue() instanceof List<?> versions)) continue;
      List<Map<String, Object>> rewrittenVersions = new ArrayList<>();
      for (Object item : versions) {
        if (!(item instanceof Map<?, ?> map)) continue;
        Map<String, Object> version = stringMap(map);
        Object distValue = version.get("dist");
        if (distValue instanceof Map<?, ?> distMap) {
          Map<String, Object> dist = stringMap(distMap);
          Object urlValue = dist.get("url");
          if (urlValue != null) {
            String sourcePath = memberRepositoryPath(urlValue.toString(), memberName);
            dist.put("url", baseUrl + "/" + sourcePath);
            version.put("dist", dist);
          }
        }
        rewrittenVersions.add(version);
      }
      rewrittenPackages.put(packageEntry.getKey().toString(), rewrittenVersions);
    }
    result.put("packages", rewrittenPackages);
    return result;
  }

  private static String memberRepositoryPath(String url, String memberName) {
    try {
      String path = java.net.URI.create(url).getPath();
      String marker = "/repository/" + memberName + "/";
      int index = path.indexOf(marker);
      if (index < 0) throw new IllegalArgumentException();
      String sourcePath = path.substring(index + marker.length());
      if (new ComposerPathParser().parse(sourcePath).kind() != ComposerPath.Kind.DIST) {
        throw new IllegalArgumentException();
      }
      return sourcePath;
    } catch (RuntimeException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid member Composer dist URL", e);
    }
  }

  private static String normalizePath(String path) {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String memberBase(RepositoryRuntime member) {
    return "https://kkrepo.invalid/repository/" + member.name();
  }

  private static Instant latestMemberUpdate(RepositoryRuntime runtime) {
    // The response ETag is derived from the deterministic body. Repository runtime snapshots do
    // not expose a persisted update timestamp, so do not manufacture a per-pod clock value here.
    return Instant.EPOCH;
  }

  private static boolean globMatches(String value, String filter) {
    if (filter == null || filter.isBlank()) return true;
    String regex = java.util.regex.Pattern.quote(filter.trim()).replace("\\Q*\\E", ".*");
    return value.matches("(?i)^" + regex + "$");
  }

  private static Map<String, Object> stringMap(Map<?, ?> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> {
      if (key != null) result.put(key.toString(), value);
    });
    return result;
  }

  private static void ensureGroup(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.COMPOSER || !runtime.isGroup()) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on group Composer repositories");
    }
  }

  private record MemberMatch(
      boolean exists,
      Optional<ComposerHostedService.PackageDocument> document) {
  }

}
