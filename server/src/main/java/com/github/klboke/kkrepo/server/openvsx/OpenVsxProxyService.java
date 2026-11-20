package com.github.klboke.kkrepo.server.openvsx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxCoordinates;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxPath;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Open VSX proxy reads backed by the shared durable asset/proxy cache. */
@Service
public class OpenVsxProxyService {
  private static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;
  private static final int MAX_PROJECTED_VERSIONS = 100;

  private final RawProxyService proxy;
  private final ComponentDao components;
  private final OpenVsxMetadata metadata;
  private final ObjectMapper objectMapper;
  private final SharedCache sharedCache;
  private final long maxExtensionSize;
  private static final String REFRESH_ALL_NAMESPACE = "openvsx-refresh-all";
  private static final java.time.Duration REFRESH_ALL_TTL = java.time.Duration.ofSeconds(30);

  public OpenVsxProxyService(
      RawProxyService proxy,
      ComponentDao components,
      OpenVsxMetadata metadata,
      ObjectMapper objectMapper,
      SharedCache sharedCache,
      @Value("${kkrepo.openvsx.max-extension-size:268435456}") long maxExtensionSize) {
    this.proxy = proxy;
    this.components = components;
    this.metadata = metadata;
    this.objectMapper = objectMapper;
    this.sharedCache = sharedCache;
    this.maxExtensionSize = Math.max(1, maxExtensionSize);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      OpenVsxPath path,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      boolean headOnly) {
    requireProxy(runtime);
    return switch (path.kind()) {
      case ROOT -> info(headOnly);
      case REGISTRY_VERSION -> metadata.json(
          metadata.registryVersion("kkrepo-openvsx-1", maxExtensionSize), headOnly);
      case NAMESPACE -> metadata.json(
          metadata.namespaceJson(path.namespace(),
              refreshAll(runtime).stream()
                  .filter(view -> view.namespace().equalsIgnoreCase(path.namespace()))
                  .toList(),
              baseUrl, repository), headOnly);
      case EXTENSION -> {
        List<OpenVsxExtensionView> views = refresh(runtime, path.namespace(), path.extension());
        OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(
            metadata.extensionJson(baseUrl, repository, views, selected,
                query.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSION, PLATFORM_VERSION -> {
        List<OpenVsxExtensionView> views = refresh(runtime, path.namespace(), path.extension());
        OpenVsxExtensionView selected = metadata.findVersion(views, path.version(),
            path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(
            metadata.extensionJson(baseUrl, repository, views, selected,
                path.targetPlatform()), headOnly);
      }
      case PLATFORM_EXTENSION -> {
        List<OpenVsxExtensionView> views = refresh(runtime, path.namespace(), path.extension());
        OpenVsxExtensionView selected = metadata.latest(views, path.targetPlatform());
        if (selected == null) throw notFound(path.rawPath());
        yield metadata.json(
            metadata.extensionJson(baseUrl, repository, views, selected,
                path.targetPlatform()), headOnly);
      }
      case EXTENSION_VERSIONS, PLATFORM_VERSIONS -> metadata.json(
          metadata.versionsJson(baseUrl, repository,
              refresh(runtime, path.namespace(), path.extension()), query.offset(), query.size()),
          headOnly);
      case VERSION_REFERENCES, PLATFORM_VERSION_REFERENCES -> metadata.json(
          metadata.versionReferencesJson(baseUrl, repository,
              refresh(runtime, path.namespace(), path.extension()), query.offset(), query.size()),
          headOnly);
      case SEARCH, QUERY, VSCODE_QUERY -> {
        List<OpenVsxExtensionView> views = filter(refreshAll(runtime), query);
        List<OpenVsxExtensionView> latest = metadata.latestPerExtension(views);
        Object body = path.kind() == OpenVsxPath.Kind.VSCODE_QUERY
            ? metadata.vscodeQueryJson(baseUrl, repository, latest, query.offset(), query.size())
            : path.kind() == OpenVsxPath.Kind.SEARCH
                ? metadata.searchJson(baseUrl, repository, latest, query.offset(), query.size(),
                    query.includeAllVersions(), views)
                : metadata.queryJson(baseUrl, repository, views, query.offset(), query.size(),
                    views);
        yield metadata.json(body, headOnly);
      }
      case VSCODE_LATEST -> {
        List<OpenVsxExtensionView> views = refresh(runtime, path.namespace(), path.extension());
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
          yield fetchVsix(runtime, view, headOnly);
        }
        yield fetchMember(runtime, view, member, headOnly);
      }
      case VSCODE_VSPACKAGE -> fetchVsix(runtime,
          requireView(runtime, path.namespace(), path.extension(), path.version(), null), headOnly);
      case VSCODE_ASSET -> {
        OpenVsxExtensionView view = requireView(runtime, path.namespace(), path.extension(),
            path.version(), null);
        yield fetchMember(runtime, view, assetMember(path.assetType(), view), headOnly);
      }
      case VSCODE_UNPKG -> {
        OpenVsxExtensionView view = requireView(runtime, path.namespace(), path.extension(),
            path.version(), null);
        yield fetchMember(runtime, view, path.filePath(), headOnly);
      }
      default -> throw notFound(path.rawPath());
    };
  }

  private List<OpenVsxExtensionView> refreshAll(RepositoryRuntime runtime) {
    String cacheKey = Long.toString(runtime.id());
    try {
      if (sharedCache.getString(REFRESH_ALL_NAMESPACE, cacheKey).isPresent()) {
        return views(runtime);
      }
    } catch (RuntimeException ignored) {
      // A cache outage must only cost performance, not correctness.
    }
    Set<String> seen = new HashSet<>();
    for (ComponentRecord component : components.listByRepositoryId(runtime.id())) {
      if (component.format() != RepositoryFormat.OPENVSX
          || component.namespace() == null || component.namespace().isBlank()
          || component.name() == null || component.name().isBlank()) {
        continue;
      }
      if (!seen.add(component.namespace() + "\u0000" + component.name())) continue;
      try {
        refresh(runtime, component.namespace(), component.name());
      } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException e) {
        // One broken upstream extension must not hide the other already-known extensions.
      }
    }
    try {
      sharedCache.putString(REFRESH_ALL_NAMESPACE, cacheKey, "1", REFRESH_ALL_TTL);
    } catch (RuntimeException ignored) {
      // Performance hint only.
    }
    return views(runtime);
  }

  List<OpenVsxExtensionView> extensionViews(
      RepositoryRuntime runtime, String namespace, String name) {
    return refresh(runtime, namespace, name);
  }

  List<OpenVsxExtensionView> allViews(RepositoryRuntime runtime) {
    return refreshAll(runtime);
  }

  public MavenResponse getItem(
      RepositoryRuntime runtime,
      String namespace,
      String extension,
      OpenVsxQuery query,
      String baseUrl,
      String repository,
      boolean headOnly) {
    requireProxy(runtime);
    List<OpenVsxExtensionView> views = refresh(runtime, namespace, extension);
    OpenVsxExtensionView selected = metadata.latest(views, query.targetPlatform());
    if (selected == null) {
      throw notFound(namespace + "/" + extension);
    }
    return metadata.json(metadata.itemJson(baseUrl, repository, selected), headOnly);
  }

  private List<OpenVsxExtensionView> refresh(
      RepositoryRuntime runtime, String namespace, String extension) {
    String remoteBase = remoteBase(runtime);
    String metadataPath = metadataCachePath(namespace, extension);
    Map<String, Object> latest = fetchJson(runtime, metadataPath,
        remoteBase + "api/" + namespace + "/" + extension);
    project(runtime, namespace, extension, latest, null);

    Object allVersions = latest.get("allVersions");
    String latestVersion = string(latest.get("version"), null);
    if (allVersions instanceof Map<?, ?> map) {
      int projected = 0;
      for (Object key : map.keySet()) {
        if (projected++ >= MAX_PROJECTED_VERSIONS) break;
        String version = key.toString();
        if (!OpenVsxCoordinates.validVersion(version) || version.equals(latestVersion)) continue;
        projectSynthetic(runtime, namespace, extension, latest, version);
      }
    }
    return views(runtime, namespace, extension);
  }

  private void projectSynthetic(
      RepositoryRuntime runtime,
      String namespace,
      String extension,
      Map<String, Object> latest,
      String version) {
    Map<String, Object> synthetic = new LinkedHashMap<>(latest);
    synthetic.put("version", version);
    synthetic.remove("files");
    synthetic.put("preRelease", version.contains("-"));
    project(runtime, namespace, extension, synthetic, version, false);
  }

  private void project(
      RepositoryRuntime runtime,
      String namespace,
      String extension,
      Map<String, Object> metadata,
      String fallbackVersion) {
    project(runtime, namespace, extension, metadata, fallbackVersion, true);
  }

  private void project(
      RepositoryRuntime runtime,
      String namespace,
      String extension,
      Map<String, Object> metadata,
      String fallbackVersion,
      boolean authoritative) {
    String version = string(metadata.get("version"), fallbackVersion);
    if (!OpenVsxCoordinates.validVersion(version)) return;
    String targetPlatform = string(metadata.get("targetPlatform"), null);
    if (!OpenVsxCoordinates.validTargetPlatform(targetPlatform)
        || targetPlatform == null || targetPlatform.isBlank()) {
      targetPlatform = null;
    }
    String fileName = fileName(namespace, extension, version, targetPlatform);
    Object files = metadata.get("files");
    String remoteFile = null;
    if (files instanceof Map<?, ?> map) {
      Object download = map.get("download");
      if (download != null) {
        remoteFile = download.toString();
        int slash = remoteFile.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < remoteFile.length()) {
          String candidate = remoteFile.substring(slash + 1);
          if (safeFileName(candidate)) fileName = candidate;
        }
      }
    }
    if (!authoritative) {
      remoteFile = null;
    }
    String path = OpenVsxComponentFactory.storagePath(
        namespace, extension, version, targetPlatform, fileName);
    String versionKey = OpenVsxComponentFactory.versionKey(version, targetPlatform);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("openvsxNamespace", namespace);
    attributes.put("openvsxExtension", extension);
    attributes.put("openvsxVersion", version);
    attributes.put("openvsxTargetPlatform", targetPlatform);
    attributes.put("openvsxPrerelease", Boolean.TRUE.equals(metadata.get("preRelease"))
        || version.contains("-"));
    attributes.put("openvsxFileName", fileName);
    attributes.put("openvsxPath", path);
    attributes.put("openvsxPackageJson", metadata);
    attributes.put("openvsxTimestamp", string(metadata.get("timestamp"), Instant.EPOCH.toString()));
    attributes.put("openvsxSize", 0L);
    attributes.put("openvsxDisplayName", string(metadata.get("displayName"), null));
    attributes.put("openvsxDescription", string(metadata.get("description"), null));
    attributes.put("cacheSource", "proxy");
    if (remoteFile != null) attributes.put("openvsxRemoteFile", remoteFile);
    byte[] coordinateHash = PersistenceHashes.componentCoordinateHash(namespace,
        OpenVsxCoordinates.normalizeName(extension), versionKey);
    Optional<ComponentRecord> existing = components.findByCoordinateHash(
        runtime.id(), coordinateHash);
    if (existing.isPresent()
        && java.util.Objects.equals(existing.get().attributes(), attributes)) {
      return;
    }
    ComponentRecord component = new ComponentRecord(
        null, runtime.id(), RepositoryFormat.OPENVSX, namespace,
        OpenVsxCoordinates.normalizeName(extension), versionKey,
        OpenVsxComponentFactory.KIND, coordinateHash,
        java.util.Collections.unmodifiableMap(attributes), Instant.now());
    components.upsertReturningId(component);
  }

  private MavenResponse fetchVsix(
      RepositoryRuntime runtime, OpenVsxExtensionView view, boolean headOnly) {
    view = ensureVersionProjected(runtime, view);
    String remote = string(attributes(runtime, view).get("openvsxRemoteFile"), null);
    if (remote == null || remote.isBlank()) {
      remote = remoteBase(runtime) + "api/" + view.namespace() + "/" + view.name() + "/"
          + view.version() + "/file/" + view.fileName();
    }
    ComponentRecord component = component(runtime, view);
    return proxy.getPinnedAssetFromUrlWithComponent(
        runtime, view.storagePath(), remote, component, headOnly);
  }

  private MavenResponse fetchMember(
      RepositoryRuntime runtime, OpenVsxExtensionView view, String member, boolean headOnly) {
    if (member == null || member.isBlank() || member.endsWith("/")) {
      throw new MavenExceptions.MavenNotFoundException(member);
    }
    view = ensureVersionProjected(runtime, view);
    String remote = remoteBase(runtime) + "api/" + view.namespace() + "/" + view.name() + "/"
        + view.version() + "/file/" + encodePathSegments(member);
    String cachePath = ".openvsx/files/" + view.namespace() + "/" + view.name() + "/"
        + view.version() + "/" + member;
    return proxy.getPinnedAssetFromUrlUnindexed(runtime, cachePath, remote, headOnly);
  }

  private OpenVsxExtensionView ensureVersionProjected(
      RepositoryRuntime runtime, OpenVsxExtensionView view) {
    Map<String, Object> attributes = attributes(runtime, view);
    if (attributes.get("openvsxRemoteFile") != null) return view;
    Map<String, Object> versionMetadata = fetchJson(runtime,
        metadataCachePath(view.namespace(), view.name()) + "/" + view.version(),
        remoteBase(runtime) + "api/" + view.namespace() + "/" + view.name() + "/"
            + view.version());
    project(runtime, view.namespace(), view.name(), versionMetadata, view.version());
    return views(runtime, view.namespace(), view.name()).stream()
        .filter(candidate -> candidate.versionKey().equals(view.versionKey()))
        .findFirst()
        .orElse(view);
  }

  private Map<String, Object> attributes(
      RepositoryRuntime runtime, OpenVsxExtensionView view) {
    return components.findByCoordinateHash(runtime.id(), PersistenceHashes.componentCoordinateHash(
            view.namespace(), OpenVsxCoordinates.normalizeName(view.name()), view.versionKey()))
        .map(ComponentRecord::attributes)
        .orElse(Map.of());
  }

  private ComponentRecord component(RepositoryRuntime runtime, OpenVsxExtensionView view) {
    Map<String, Object> attributes = attributes(runtime, view);
    String remote = string(attributes.get("openvsxRemoteFile"), null);
    Map<String, Object> updated = new LinkedHashMap<>(attributes);
    if (remote != null) updated.put("openvsxRemoteFile", remote);
    return new ComponentRecord(
        null, runtime.id(), RepositoryFormat.OPENVSX, view.namespace(),
        OpenVsxCoordinates.normalizeName(view.name()), view.versionKey(),
        OpenVsxComponentFactory.KIND,
        PersistenceHashes.componentCoordinateHash(view.namespace(),
            OpenVsxCoordinates.normalizeName(view.name()), view.versionKey()),
        java.util.Collections.unmodifiableMap(updated), Instant.now());
  }

  private OpenVsxExtensionView requireView(
      RepositoryRuntime runtime, String namespace, String name, String version,
      String targetPlatform) {
    List<OpenVsxExtensionView> views = refresh(runtime, namespace, name);
    OpenVsxExtensionView view = version == null
        ? metadata.latest(views, targetPlatform)
        : metadata.findVersion(views, version, targetPlatform);
    if (view == null) throw notFound(namespace + "/" + name + "/" + version);
    return view;
  }

  private List<OpenVsxExtensionView> views(RepositoryRuntime runtime) {
    return components.listByRepositoryId(runtime.id()).stream()
        .filter(component -> component.format() == RepositoryFormat.OPENVSX)
        .map(OpenVsxExtensionView::fromComponent)
        .toList();
  }

  private List<OpenVsxExtensionView> views(
      RepositoryRuntime runtime, String namespace, String name) {
    return components.listByName(runtime.id(), OpenVsxCoordinates.normalizeName(name)).stream()
        .filter(component -> component.format() == RepositoryFormat.OPENVSX)
        .filter(component -> component.namespace() != null
            && component.namespace().equalsIgnoreCase(namespace))
        .map(OpenVsxExtensionView::fromComponent)
        .toList();
  }

  private List<OpenVsxExtensionView> filter(
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

  private Map<String, Object> fetchJson(
      RepositoryRuntime runtime, String cachePath, String remoteUrl) {
    MavenResponse response = proxy.getMetadataFromUrlHidden(runtime, cachePath, remoteUrl, false);
    try (InputStream body = response.body()) {
      if (body == null) throw new MavenExceptions.MavenNotFoundException(cachePath);
      byte[] bytes = body.readNBytes(MAX_METADATA_BYTES + 1);
      if (bytes.length > MAX_METADATA_BYTES) {
        throw new MavenExceptions.BadUpstreamException("Open VSX metadata is too large");
      }
      return objectMapper.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException("Invalid Open VSX upstream metadata", error);
    }
  }

  private static String metadataCachePath(String namespace, String extension) {
    return ".openvsx/meta/" + namespace + "/" + OpenVsxCoordinates.normalizeName(extension);
  }

  private static String remoteBase(RepositoryRuntime runtime) {
    String base = runtime.proxyRemoteUrl();
    if (base == null || base.isBlank()) {
      throw new MavenExceptions.BadUpstreamException("Open VSX proxy has no remote URL");
    }
    return base.endsWith("/") ? base : base + "/";
  }

  private static String fileName(
      String namespace, String name, String version, String targetPlatform) {
    return namespace + "." + name + "-" + version
        + (targetPlatform == null || targetPlatform.isBlank() || "universal".equals(targetPlatform)
            ? "" : "-" + targetPlatform)
        + ".vsix";
  }

  private static String encodePathSegments(String value) {
    return java.util.Arrays.stream(value.split("/", -1))
        .map(segment -> java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20"))
        .collect(java.util.stream.Collectors.joining("/"));
  }

  private static boolean safeFileName(String value) {
    if (value == null || value.isBlank() || value.length() > 255
        || value.equals(".") || value.equals("..")
        || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
      return false;
    }
    return value.codePoints().noneMatch(ch -> ch < 0x20 || ch == 0x7f);
  }

  private static String assetMember(String assetType, OpenVsxExtensionView view) {
    if (assetType == null) return "";
    return switch (assetType) {
      case "Microsoft.VisualStudio.Services.Icons.Default" -> stringOr(
          view.packageJson().get("icon"), "extension/icon.png");
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

  private static String string(Object value, String fallback) {
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private static MavenResponse info(boolean headOnly) {
    byte[] bytes = "Open VSX proxy repository\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (headOnly) return MavenResponse.noBody(200, bytes.length, "text/plain", null, null);
    return MavenResponse.ok(new java.io.ByteArrayInputStream(bytes), bytes.length, "text/plain",
        null, null);
  }

  private static MavenExceptions.MavenNotFoundException notFound(String path) {
    return new MavenExceptions.MavenNotFoundException(path);
  }

  private static void requireProxy(RepositoryRuntime runtime) {
    if (!runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Open VSX proxy reads are only available on proxy repositories");
    }
    if (!runtime.online()) {
      throw new MavenExceptions.BadUpstreamException("Repository is offline");
    }
  }
}
