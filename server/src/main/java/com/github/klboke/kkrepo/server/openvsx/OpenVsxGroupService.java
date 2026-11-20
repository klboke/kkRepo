package com.github.klboke.kkrepo.server.openvsx;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxPath;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Ordered Open VSX group resolution. First online member wins each version/route. */
@Service
public class OpenVsxGroupService {
  private final OpenVsxHostedService hosted;
  private final OpenVsxProxyService proxy;
  private final OpenVsxMetadata metadata;

  public OpenVsxGroupService(
      OpenVsxHostedService hosted, OpenVsxProxyService proxy, OpenVsxMetadata metadata) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.metadata = metadata;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      OpenVsxPath path,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      boolean headOnly) {
    requireGroup(runtime);
    if (path.kind() == OpenVsxPath.Kind.ROOT) {
      byte[] bytes = "Open VSX group repository\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      return headOnly
          ? MavenResponse.noBody(200, bytes.length, "text/plain", null, null)
          : MavenResponse.ok(new java.io.ByteArrayInputStream(bytes), bytes.length, "text/plain",
              null, null);
    }
    if (path.file()) {
      return routeFile(runtime, path, query, baseUrl, repository, headOnly, new HashSet<>());
    }
    if (path.kind() == OpenVsxPath.Kind.REGISTRY_VERSION) {
      return metadata.json(metadata.registryVersion("kkrepo-openvsx-1", 268435456L), headOnly);
    }
    List<OpenVsxExtensionView> all = allViews(runtime, new HashSet<>());
    return switch (path.kind()) {
      case NAMESPACE -> metadata.json(
          metadata.namespaceJson(path.namespace(),
              all.stream()
                  .filter(view -> view.namespace().equalsIgnoreCase(path.namespace()))
                  .toList(),
              baseUrl, repository), headOnly);
      case EXTENSION, PLATFORM_EXTENSION -> {
        List<OpenVsxExtensionView> views = viewsForGroup(
            runtime, path.namespace(), path.extension(), new HashSet<>());
        OpenVsxExtensionView selected = metadata.latest(views, path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(metadata.extensionJson(baseUrl, repository, views, selected,
            path.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSION, PLATFORM_VERSION -> {
        List<OpenVsxExtensionView> views = viewsForGroup(
            runtime, path.namespace(), path.extension(), new HashSet<>());
        OpenVsxExtensionView selected = metadata.findVersion(views, path.version(),
            path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(metadata.extensionJson(baseUrl, repository, views, selected,
            path.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSIONS, PLATFORM_VERSIONS -> metadata.json(
          metadata.versionsJson(baseUrl, repository,
              viewsForGroup(runtime, path.namespace(), path.extension(), new HashSet<>()),
              query.offset(), query.size()), headOnly);
      case VERSION_REFERENCES, PLATFORM_VERSION_REFERENCES -> metadata.json(
          metadata.versionReferencesJson(baseUrl, repository,
              viewsForGroup(runtime, path.namespace(), path.extension(), new HashSet<>()),
              query.offset(), query.size()), headOnly);
      case SEARCH, QUERY, VSCODE_QUERY -> {
        List<OpenVsxExtensionView> filtered = filter(all, query);
        List<OpenVsxExtensionView> latest = metadata.latestPerExtension(filtered);
        Object body = path.kind() == OpenVsxPath.Kind.VSCODE_QUERY
            ? metadata.vscodeQueryJson(baseUrl, repository, latest, query.offset(), query.size())
            : path.kind() == OpenVsxPath.Kind.SEARCH
                ? metadata.searchJson(baseUrl, repository, latest, query.offset(), query.size(),
                    query.includeAllVersions(), filtered)
                : metadata.queryJson(baseUrl, repository, filtered, query.offset(), query.size(),
                    filtered);
        yield metadata.json(body, headOnly);
      }
      case VSCODE_LATEST -> {
        List<OpenVsxExtensionView> views = viewsForGroup(
            runtime, path.namespace(), path.extension(), new HashSet<>());
        OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(metadata.extensionJson(baseUrl, repository, views, selected, null),
            headOnly);
      }
      case REVIEWS -> metadata.json(
          Map.of("reviews", List.of(), "offset", 0, "totalSize", 0), headOnly);
      default -> throw notFound(path.rawPath());
    };
  }

  public MavenResponse getItem(
      RepositoryRuntime runtime,
      String namespace,
      String extension,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      boolean headOnly) {
    requireGroup(runtime);
    List<OpenVsxExtensionView> views = viewsForGroup(
        runtime, namespace, extension, new HashSet<>());
    OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
    if (selected == null) {
      throw notFound(namespace + "/" + extension);
    }
    return metadata.json(metadata.itemJson(baseUrl, repository, selected), headOnly);
  }

  private MavenResponse routeFile(
      RepositoryRuntime group,
      OpenVsxPath path,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      boolean headOnly,
      Set<Long> resolving) {
    if (!resolving.add(group.id())) {
      throw new MavenExceptions.BadUpstreamException("Open VSX group cycle detected");
    }
    MavenExceptions.BadUpstreamException last = null;
    try {
      for (RepositoryRuntime member : group.members()) {
        if (!eligible(member)) continue;
        try {
          if (member.type() == RepositoryType.GROUP) {
            return routeFile(member, path, query, baseUrl, repository, headOnly, resolving);
          }
          return memberService(member).get(member, path, query, baseUrl, repository, headOnly);
        } catch (MavenExceptions.MavenNotFoundException ignored) {
          // Next member.
        } catch (MavenExceptions.BadUpstreamException error) {
          last = error;
        } finally {
          resolving.remove(member.id());
        }
      }
      if (last != null) throw last;
      throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    } finally {
      resolving.remove(group.id());
    }
  }

  private List<OpenVsxExtensionView> allViews(RepositoryRuntime group, Set<Long> resolving) {
    if (!resolving.add(group.id())) return List.of();
    Map<String, OpenVsxExtensionView> merged = new LinkedHashMap<>();
    try {
      for (RepositoryRuntime member : group.members()) {
        if (!eligible(member)) continue;
        List<OpenVsxExtensionView> views;
        try {
          views = member.type() == RepositoryType.GROUP
              ? allViews(member, resolving)
              : memberService(member).allViews(member);
        } catch (MavenExceptions.BadUpstreamException | MavenExceptions.MavenNotFoundException e) {
          continue;
        }
        for (OpenVsxExtensionView view : views) {
          merged.putIfAbsent(view.namespace() + "." + view.name() + "@" + view.versionKey(), view);
        }
      }
    } finally {
      resolving.remove(group.id());
    }
    return List.copyOf(merged.values());
  }

  private List<OpenVsxExtensionView> viewsForGroup(
      RepositoryRuntime group, String namespace, String extension, Set<Long> resolving) {
    if (!resolving.add(group.id())) return List.of();
    Map<String, OpenVsxExtensionView> merged = new LinkedHashMap<>();
    try {
      for (RepositoryRuntime member : group.members()) {
        if (!eligible(member)) continue;
        List<OpenVsxExtensionView> views;
        try {
          views = switch (member.type()) {
            case HOSTED -> hosted.views(member, namespace, extension);
            case PROXY -> proxy.extensionViews(member, namespace, extension);
            case GROUP -> viewsForGroup(member, namespace, extension, resolving);
          };
        } catch (MavenExceptions.BadUpstreamException | MavenExceptions.MavenNotFoundException e) {
          continue;
        }
        for (OpenVsxExtensionView view : views) {
          merged.putIfAbsent(view.namespace() + "." + view.name() + "@" + view.versionKey(), view);
        }
      }
    } finally {
      resolving.remove(group.id());
    }
    return List.copyOf(merged.values());
  }

  private static List<OpenVsxExtensionView> filter(
      List<OpenVsxExtensionView> views, OpenVsxQuery query) {
    List<OpenVsxExtensionView> filtered = new ArrayList<>();
    for (OpenVsxExtensionView view : views) {
      if (!query.matches(view.namespace(), view.name(), view.version())) continue;
      if (query.hasTextQuery() && !matchesText(view, query.query())) continue;
      filtered.add(view);
    }
    return filtered;
  }

  private static boolean matchesText(OpenVsxExtensionView view, String query) {
    String needle = query.toLowerCase(java.util.Locale.ROOT);
    return view.name().toLowerCase(java.util.Locale.ROOT).contains(needle)
        || (view.displayName() != null
            && view.displayName().toLowerCase(java.util.Locale.ROOT).contains(needle))
        || (view.description() != null
            && view.description().toLowerCase(java.util.Locale.ROOT).contains(needle));
  }

  private MemberService memberService(RepositoryRuntime member) {
    return switch (member.format()) {
      case OPENVSX -> switch (member.type()) {
        case HOSTED -> new MemberService() {
          @Override public MavenResponse get(
              RepositoryRuntime runtime, OpenVsxPath path, OpenVsxQuery query, String baseUrl,
              String repository, boolean headOnly) {
            return hosted.get(runtime, path, query, baseUrl, repository, headOnly);
          }
          @Override public List<OpenVsxExtensionView> allViews(RepositoryRuntime runtime) {
            return hosted.views(runtime);
          }
        };
        case PROXY -> new MemberService() {
          @Override public MavenResponse get(
              RepositoryRuntime runtime, OpenVsxPath path, OpenVsxQuery query, String baseUrl,
              String repository, boolean headOnly) {
            return proxy.get(runtime, path, query, baseUrl, repository, headOnly);
          }
          @Override public List<OpenVsxExtensionView> allViews(RepositoryRuntime runtime) {
            return proxy.allViews(runtime);
          }
        };
        case GROUP -> throw new MavenExceptions.BadUpstreamException(
            "Nested Open VSX groups must be resolved through the group path");
      };
      default -> throw new MavenExceptions.BadUpstreamException(
          "Open VSX group member has a different format");
    };
  }

  private static boolean eligible(RepositoryRuntime member) {
    return member.online() && member.format() == RepositoryFormat.OPENVSX;
  }

  private static void requireGroup(RepositoryRuntime runtime) {
    if (!runtime.isGroup()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Open VSX group reads are only available on group repositories");
    }
  }

  private static MavenExceptions.MavenNotFoundException notFound(String path) {
    return new MavenExceptions.MavenNotFoundException(path);
  }

  private interface MemberService {
    MavenResponse get(
        RepositoryRuntime runtime, OpenVsxPath path, OpenVsxQuery query, String baseUrl,
        String repository, boolean headOnly);

    List<OpenVsxExtensionView> allViews(RepositoryRuntime runtime);
  }
}
