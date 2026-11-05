package com.github.klboke.kkrepo.server.openvsx;

import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxCoordinates;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxPath;
import com.github.klboke.kkrepo.server.blob.BufferedUpload;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Hosted Open VSX publication and metadata generation. */
@Service
public class OpenVsxHostedService {
  private final ComponentDao components;
  private final OpenVsxAssetSupport assets;
  private final OpenVsxVsixInspector inspector;
  private final OpenVsxMetadata metadata;
  private final long maxExtensionSize;

  public OpenVsxHostedService(
      ComponentDao components,
      OpenVsxAssetSupport assets,
      OpenVsxVsixInspector inspector,
      OpenVsxMetadata metadata,
      @Value("${kkrepo.openvsx.max-extension-size:268435456}") long maxExtensionSize) {
    this.components = components;
    this.assets = assets;
    this.inspector = inspector;
    this.metadata = metadata;
    this.maxExtensionSize = Math.max(1, maxExtensionSize);
  }

  public Published publish(
      RepositoryRuntime runtime,
      InputStream body,
      String actor,
      String ip,
      String baseUrl,
      String repository) {
    requireHosted(runtime);
    enforceWritePolicy(runtime);
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "openvsx-", ".vsix");
      BufferedUpload.Digests digests =
          BufferedUpload.copy(body, temp, BufferedUpload.Checksums.WITH_SHA512);
      if (digests.size() > maxExtensionSize) {
        throw new MavenExceptions.BadRequestException(
            "VSIX exceeds the configured maximum extension size");
      }
      OpenVsxVsixInspector.Inspected inspected = inspector.inspect(temp, null);
      String versionKey = OpenVsxComponentFactory.versionKey(
          inspected.version(), inspected.targetPlatform());
      byte[] hash = PersistenceHashes.componentCoordinateHash(
          inspected.namespace(), OpenVsxCoordinates.normalizeName(inspected.name()), versionKey);
      Optional<ComponentRecord> existing =
          components.findByCoordinateHash(runtime.id(), hash);
      if (existing.isPresent()) {
        throw new MavenExceptions.BadRequestException(
            "Extension version already exists: " + inspected.namespace() + "."
                + inspected.name() + "@" + inspected.version());
      }
      String path = OpenVsxComponentFactory.storagePath(inspected);
      boolean created = assets.storeHosted(runtime, inspected, temp, path, digests.sha1(),
          digests.sha256(), digests.size(), actor, ip);
      if (!created) {
        throw new MavenExceptions.BadRequestException(
            "Extension version already exists: " + inspected.namespace() + "."
                + inspected.name() + "@" + inspected.version());
      }
      try {
        List<OpenVsxExtensionView> views = views(runtime, inspected.namespace(), inspected.name());
        OpenVsxExtensionView selected = views.stream()
            .filter(view -> view.version().equals(inspected.version()))
            .filter(view -> java.util.Objects.equals(view.targetPlatform(),
                inspected.targetPlatform()))
            .findFirst()
            .orElse(views.isEmpty() ? null : views.getFirst());
        Map<String, Object> json = metadata.extensionJson(
            baseUrl, repository, views, selected, inspected.targetPlatform());
        if (json == null) {
          return new Published(MavenResponse.created(), path);
        }
        String location = baseUrl + "/" + repository + "/api/" + inspected.namespace() + "/"
            + OpenVsxCoordinates.normalizeName(inspected.name()) + "/" + inspected.version();
        return new Published(
            metadata.json(json, false).withStatus(201).withHeader("Location", location), path);
      } catch (RuntimeException error) {
        // Do not leave a visible asset when the publication response cannot be built.
        try {
          assets.delete(runtime, path);
        } catch (RuntimeException ignored) {
          // Preserve the original failure.
        }
        throw error;
      }
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read VSIX upload", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      OpenVsxPath path,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      boolean headOnly) {
    requireHosted(runtime);
    return switch (path.kind()) {
      case ROOT -> info(headOnly);
      case REGISTRY_VERSION -> metadata.json(
          metadata.registryVersion(registryVersion(), maxExtensionSize), headOnly);
      case NAMESPACE -> metadata.json(
          metadata.namespaceJson(path.namespace(),
              views(runtime).stream()
                  .filter(view -> view.namespace().equalsIgnoreCase(path.namespace()))
                  .toList(),
              baseUrl, repository), headOnly);
      case EXTENSION -> {
        List<OpenVsxExtensionView> views = views(runtime, path.namespace(), path.extension());
        OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(
            metadata.extensionJson(baseUrl, repository, views, selected,
                query.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSION, PLATFORM_VERSION -> {
        List<OpenVsxExtensionView> views = views(runtime, path.namespace(), path.extension());
        OpenVsxExtensionView selected = metadata.findVersion(views, path.version(),
            path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(
            metadata.extensionJson(baseUrl, repository, views, selected,
                path.targetPlatform()), headOnly);
      }
      case PLATFORM_EXTENSION -> {
        List<OpenVsxExtensionView> views = views(runtime, path.namespace(), path.extension());
        OpenVsxExtensionView selected = metadata.latest(views, path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(
            metadata.extensionJson(baseUrl, repository, views, selected,
                path.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSIONS, PLATFORM_VERSIONS -> metadata.json(
          metadata.versionsJson(baseUrl, repository,
              views(runtime, path.namespace(), path.extension()), query.offset(), query.size()),
          headOnly);
      case VERSION_REFERENCES, PLATFORM_VERSION_REFERENCES -> metadata.json(
          metadata.versionReferencesJson(baseUrl, repository,
              views(runtime, path.namespace(), path.extension()), query.offset(), query.size()),
          headOnly);
      case SEARCH -> metadata.json(
          metadata.searchJson(baseUrl, repository,
              metadata.latestPerExtension(filter(runtime, query)),
              query.offset(), query.size(), query.includeAllVersions(),
              filter(runtime, query)), headOnly);
      case QUERY -> metadata.json(
          metadata.queryJson(baseUrl, repository, filter(runtime, query), query.offset(),
              query.size(), filter(runtime, query)), headOnly);
      case VSCODE_QUERY -> metadata.json(
          metadata.vscodeQueryJson(baseUrl, repository,
              metadata.latestPerExtension(filter(runtime, query)), query.offset(),
              query.size()), headOnly);
      case VSCODE_LATEST -> {
        List<OpenVsxExtensionView> views = views(runtime, path.namespace(), path.extension());
        OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(
            metadata.extensionJson(baseUrl, repository, views, selected, null), headOnly);
      }
      case REVIEWS -> metadata.json(
          Map.of("reviews", List.of(), "offset", 0, "totalSize", 0), headOnly);
      case FILE, PLATFORM_FILE -> {
        OpenVsxExtensionView view = requireView(runtime, path.namespace(), path.extension(),
            path.version(), path.targetPlatform());
        String member = path.filePath();
        if (member == null || member.isBlank() || member.equals(view.fileName())) {
          yield assets.serve(runtime, view.storagePath(), headOnly);
        }
        yield assets.extract(runtime, view.storagePath(), member, headOnly);
      }
      case VSCODE_VSPACKAGE -> {
        OpenVsxExtensionView view = requireView(runtime, path.namespace(), path.extension(),
            path.version(), null);
        yield assets.serve(runtime, view.storagePath(), headOnly);
      }
      case VSCODE_ASSET -> {
        OpenVsxExtensionView view = requireView(runtime, path.namespace(), path.extension(),
            path.version(), null);
        yield assets.extract(runtime, view.storagePath(), assetMember(path.assetType(), view),
            headOnly);
      }
      case VSCODE_UNPKG -> {
        OpenVsxExtensionView view = requireView(runtime, path.namespace(), path.extension(),
            path.version(), null);
        yield assets.extract(runtime, view.storagePath(), path.filePath(), headOnly);
      }
      default -> throw notFound(path.rawPath());
    };
  }

  public record Published(MavenResponse response, String path) {
  }

  public MavenResponse getItem(
      RepositoryRuntime runtime,
      String namespace,
      String extension,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      boolean headOnly) {
    requireHosted(runtime);
    List<OpenVsxExtensionView> views = views(runtime, namespace, extension);
    OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
    if (selected == null) {
      throw notFound(namespace + "/" + extension);
    }
    return metadata.json(metadata.itemJson(baseUrl, repository, selected), headOnly);
  }

  public MavenResponse deleteExtension(
      RepositoryRuntime runtime, String namespace, String extension, String version,
      String targetPlatform, boolean enforcePolicy) {
    OpenVsxExtensionView view = requireView(runtime, namespace, extension, version, targetPlatform);
    return delete(runtime, view.storagePath(), enforcePolicy);
  }

  public MavenResponse delete(RepositoryRuntime runtime, String path, boolean enforcePolicy) {
    requireHosted(runtime);
    if (enforcePolicy && !WritePolicy.parse(runtime.writePolicy()).checkDeleteAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy forbids deletion");
    }
    if (assets.find(runtime, path).isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    assets.delete(runtime, path);
    return MavenResponse.noBody(204);
  }

  List<OpenVsxExtensionView> views(RepositoryRuntime runtime) {
    return components.listByRepositoryId(runtime.id()).stream()
        .filter(component -> component.format()
            == com.github.klboke.kkrepo.core.RepositoryFormat.OPENVSX)
        .map(OpenVsxExtensionView::fromComponent)
        .toList();
  }

  /** Serves registry metadata from live views plus staged extension projections. */
  MavenResponse stagedGet(
      RepositoryRuntime runtime,
      OpenVsxPath path,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      List<ComponentRecord> staged,
      boolean headOnly) {
    requireHosted(runtime);
    List<OpenVsxExtensionView> stagedViews = staged.stream()
        .filter(component -> component.format()
            == com.github.klboke.kkrepo.core.RepositoryFormat.OPENVSX)
        .map(OpenVsxExtensionView::fromComponent)
        .toList();
    List<OpenVsxExtensionView> all = new ArrayList<>(views(runtime));
    all.addAll(stagedViews);
    if (path.kind() == OpenVsxPath.Kind.NAMESPACE) {
      List<OpenVsxExtensionView> namespaceViews = all.stream()
          .filter(view -> view.namespace().equalsIgnoreCase(path.namespace()))
          .toList();
      return metadata.json(
          metadata.namespaceJson(path.namespace(), namespaceViews, baseUrl, repository), headOnly);
    }
    List<OpenVsxExtensionView> views = all.stream()
        .filter(view -> view.namespace() != null
            && view.namespace().equalsIgnoreCase(path.namespace()))
        .filter(view -> view.name().equalsIgnoreCase(
            com.github.klboke.kkrepo.protocol.openvsx.OpenVsxCoordinates.normalizeName(
                path.extension())))
        .toList();
    return switch (path.kind()) {
      case EXTENSION -> {
        OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(metadata.extensionJson(
            baseUrl, repository, views, selected, query.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSION, PLATFORM_VERSION -> {
        OpenVsxExtensionView selected = metadata.findVersion(
            views, path.version(), path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(metadata.extensionJson(
            baseUrl, repository, views, selected, path.targetPlatform()), headOnly);
      }
      case PLATFORM_EXTENSION -> {
        OpenVsxExtensionView selected = metadata.latest(views, path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(metadata.extensionJson(
            baseUrl, repository, views, selected, path.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSIONS, PLATFORM_VERSIONS -> metadata.json(
          metadata.versionsJson(baseUrl, repository, views, query.offset(), query.size()),
          headOnly);
      case VERSION_REFERENCES, PLATFORM_VERSION_REFERENCES -> metadata.json(
          metadata.versionReferencesJson(
              baseUrl, repository, views, query.offset(), query.size()), headOnly);
      default -> throw notFound(path.rawPath());
    };
  }

  List<OpenVsxExtensionView> views(
      RepositoryRuntime runtime, String namespace, String name) {
    return components.listByName(runtime.id(), OpenVsxCoordinates.normalizeName(name)).stream()
        .filter(component -> component.format()
            == com.github.klboke.kkrepo.core.RepositoryFormat.OPENVSX)
        .filter(component -> component.namespace() != null
            && component.namespace().equalsIgnoreCase(namespace))
        .map(OpenVsxExtensionView::fromComponent)
        .toList();
  }

  private List<OpenVsxExtensionView> filter(RepositoryRuntime runtime, OpenVsxQuery query) {
    List<OpenVsxExtensionView> all = views(runtime);
    List<OpenVsxExtensionView> filtered = new ArrayList<>();
    for (OpenVsxExtensionView view : all) {
      if (!query.matches(view.namespace(), view.name(), view.version())) continue;
      if (query.hasTextQuery() && !matchesText(view, query.query())) continue;
      if (query.category() != null && !query.category().isBlank()
          && !categories(view).contains(query.category())) {
        continue;
      }
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
            && view.description().toLowerCase(java.util.Locale.ROOT).contains(needle))
        || view.namespace().toLowerCase(java.util.Locale.ROOT).contains(needle);
  }

  private static List<String> categories(OpenVsxExtensionView view) {
    Object value = view.packageJson().get("categories");
    if (value instanceof List<?> list) {
      return list.stream().map(Object::toString).toList();
    }
    return List.of();
  }

  private OpenVsxExtensionView requireView(
      RepositoryRuntime runtime, String namespace, String name, String version,
      String targetPlatform) {
    List<OpenVsxExtensionView> views = views(runtime, namespace, name);
    OpenVsxExtensionView view = version == null
        ? metadata.latest(views, targetPlatform)
        : metadata.findVersion(views, version, targetPlatform);
    if (view == null) {
      throw new MavenExceptions.MavenNotFoundException(
          namespace + "/" + name + "/" + (version == null ? "latest" : version));
    }
    return view;
  }

  private static String assetMember(String assetType, OpenVsxExtensionView view) {
    if (assetType == null) return "";
    return switch (assetType) {
      case "Microsoft.VisualStudio.Services.Icons.Default" -> stringOr(view.packageJson().get("icon"),
          "extension/icon.png");
      case "Microsoft.VisualStudio.Services.Content.Details" -> "extension/README.md";
      case "Microsoft.VisualStudio.Services.Content.Changelog" -> "extension/CHANGELOG.md";
      case "Microsoft.VisualStudio.Code.Manifest" -> "extension/package.json";
      case "Microsoft.VisualStudio.Services.Content.License" -> "extension/LICENSE";
      case "Microsoft.VisualStudio.Services.VsixManifest" -> "extension.vsixmanifest";
      case "Microsoft.VisualStudio.Services.VsixSignature" -> "extension.vsixsignature";
      default -> "";
    };
  }

  private static String stringOr(Object value, String fallback) {
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private MavenResponse info(boolean headOnly) {
    byte[] bytes = "Open VSX repository\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (headOnly) return MavenResponse.noBody(200, bytes.length, "text/plain", null, null);
    return MavenResponse.ok(new java.io.ByteArrayInputStream(bytes), bytes.length, "text/plain",
        null, null);
  }

  private static String registryVersion() {
    return "kkrepo-openvsx-1";
  }

  private static MavenExceptions.MavenNotFoundException notFound(String path) {
    return new MavenExceptions.MavenNotFoundException(path);
  }

  private static void requireHosted(RepositoryRuntime runtime) {
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Open VSX publication is only available on hosted repositories");
    }
    if (!runtime.online()) {
      throw new MavenExceptions.BadUpstreamException("Repository is offline");
    }
  }

  private static void enforceWritePolicy(RepositoryRuntime runtime) {
    if (WritePolicy.parse(runtime.writePolicy()) == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy forbids publication");
    }
  }
}
