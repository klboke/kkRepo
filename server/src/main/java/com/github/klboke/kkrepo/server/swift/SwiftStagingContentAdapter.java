package com.github.klboke.kkrepo.server.swift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.protocol.swift.SwiftMediaTypes;
import com.github.klboke.kkrepo.protocol.swift.SwiftPath;
import com.github.klboke.kkrepo.protocol.swift.SwiftPathParser;
import com.github.klboke.kkrepo.protocol.swift.SwiftReleaseMetadata;
import com.github.klboke.kkrepo.protocol.swift.SwiftReleaseResource;
import com.github.klboke.kkrepo.protocol.swift.SwiftReleaseSigning;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Staged Swift Package Registry publication. */
@Component
final class SwiftStagingContentAdapter implements StagingContentAdapter {
  private static final SwiftPathParser PARSER = new SwiftPathParser();
  private static final String MANIFESTS_ATTRIBUTES = "swiftManifests";

  private final RawHostedService hosted;
  private final SwiftService swiftHosted;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final SwiftArchiveInspector archiveInspector;
  private final ObjectMapper objectMapper;
  private final StagingDao staging;

  SwiftStagingContentAdapter(
      RawHostedService hosted,
      SwiftService swiftHosted,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      SwiftArchiveInspector archiveInspector,
      ObjectMapper objectMapper,
      StagingDao staging) {
    this.hosted = hosted;
    this.swiftHosted = swiftHosted;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
    this.archiveInspector = archiveInspector;
    this.objectMapper = objectMapper;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.SWIFT;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    SwiftPath parsed = PARSER.parse(path);
    if (parsed.kind() == SwiftPath.Kind.SOURCE_ARCHIVE
        && parsed.scope() != null && parsed.name() != null && parsed.version() != null) {
      return Optional.of("swift/" + parsed.scope() + "/" + parsed.name()
          + "/" + parsed.version() + ".zip");
    }
    return Optional.of(path);
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    throw new MavenExceptions.BadRequestException(
        "Swift staging requires a multipart publish request");
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    SwiftPath path = swiftHosted.validatePublishRequest(
        runtime, requestedPath, request.getQueryString(), accept);
    try {
      Collection<Part> parts = request.getParts();
      SwiftMultipartRequest multipart = SwiftMultipartRequest.parse(parts);
      Path temp = null;
      try {
        temp = TempBlobFiles.createTempFile(null, "swift-staging-", ".zip");
        try (InputStream archive = multipart.openArchive()) {
          Files.copy(archive, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        String logicalPath = swiftAssetPath(path);
        AssetBlobRecord blob = hosted.storeStagedBlobFile(
            runtime, logicalPath, temp, "application/zip", Map.of(), actor, ip);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("swiftScope", path.scope());
        attributes.put("swiftName", path.name());
        attributes.put("swiftVersion", path.version());
        attributes.put("swiftMetadata", multipart.metadataJson());
        attributes.put("swiftBaseUrl", repositoryBaseUrl(request, runtime.name()));
        attributes.put("swiftSignatureFormat",
            request.getHeader("X-Swift-Package-Signature-Format"));
        putSignature(attributes, "swiftSourceSignature", multipart.sourceArchiveSignature());
        putSignature(attributes, "swiftMetadataSignature", multipart.metadataSignature());
        captureManifests(temp, attributes);
        return Optional.of(new StagedUpload(
            blob, logicalPath, "application/zip", attributes));
      } finally {
        TempBlobFiles.deleteQuietly(temp);
      }
    } catch (IOException error) {
      throw new SwiftExceptions.BadRequest("Unable to read Swift publish request", error);
    } catch (jakarta.servlet.ServletException error) {
      throw new SwiftExceptions.BadRequest("Unable to parse Swift multipart request", error);
    }
  }

  private void captureManifests(Path archive, Map<String, Object> attributes) {
    try (InputStream input = Files.newInputStream(archive)) {
      SwiftArchiveInspector.InspectedArchive inspected = archiveInspector.inspect(input);
      try {
        List<Map<String, Object>> manifests = new ArrayList<>();
        long manifestBytes = 0;
        for (SwiftArchiveInspector.ManifestEntry manifest : inspected.manifests()) {
          if (manifest.bytes().length > 128 * 1024
              || manifestBytes + manifest.bytes().length > 512 * 1024) {
            continue;
          }
          manifestBytes += manifest.bytes().length;
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("filename", manifest.filename());
          if (manifest.lookupToolsVersion() != null) {
            entry.put("toolsVersion", manifest.lookupToolsVersion());
          }
          if (manifest.declaredToolsVersion() != null) {
            entry.put("declaredToolsVersion", manifest.declaredToolsVersion());
          }
          entry.put("sha256", manifest.sha256Hex());
          entry.put("content", Base64.getEncoder().encodeToString(manifest.bytes()));
          manifests.add(entry);
        }
        attributes.put("swiftArchiveSha256", inspected.sha256Hex());
        attributes.put(MANIFESTS_ATTRIBUTES, manifests);
      } finally {
        try {
          Files.deleteIfExists(inspected.file());
        } catch (IOException ignored) {
        }
      }
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to inspect Swift source archive", error);
    }
  }

  /**
   * Serves the release list, release metadata, default manifest and source archive from staged
   * publications so SwiftPM can resolve a release before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    SwiftPath path = PARSER.parse(rawPath);
    if (path.scope() == null || path.name() == null) {
      return Optional.empty();
    }
    if (path.kind() == SwiftPath.Kind.MANIFEST) {
      return stagedManifest(runtime, sessionId, path, headOnly);
    }
    if (path.kind() != SwiftPath.Kind.RELEASE_LIST
        && path.kind() != SwiftPath.Kind.RELEASE_METADATA) {
      return Optional.empty();
    }
    if (repositoryBaseUrl == null) {
      return Optional.empty();
    }
    Set<String> versions = new LinkedHashSet<>();
    StagingItemRecord detail = null;
    for (StagingItemRecord item : matchingItems(runtime, sessionId, path)) {
      Object version = item.attributes().get("swiftVersion");
      if (version == null) continue;
      versions.add(String.valueOf(version));
      if (path.kind() == SwiftPath.Kind.RELEASE_METADATA
          && path.version() != null && path.version().equals(String.valueOf(version))) {
        detail = item;
      }
    }
    if (path.kind() == SwiftPath.Kind.RELEASE_LIST) {
      if (versions.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(swiftHosted.stagedReleaseList(
          runtime, path.scope(), path.name(), repositoryBaseUrl, List.copyOf(versions), headOnly));
    }
    if (detail == null) {
      return Optional.empty();
    }
    return Optional.of(swiftHosted.stagedReleaseMetadata(
        releaseMetadata(detail, path), headOnly));
  }

  private Optional<MavenResponse> stagedManifest(
      RepositoryRuntime runtime, long sessionId, SwiftPath path, boolean headOnly) {
    for (StagingItemRecord item : matchingItems(runtime, sessionId, path)) {
      if (!path.version().equals(String.valueOf(item.attributes().get("swiftVersion")))) {
        continue;
      }
      Map<String, Object> manifest = defaultManifest(item);
      if (manifest == null) {
        return Optional.empty();
      }
      byte[] bytes = Base64.getDecoder().decode(String.valueOf(manifest.get("content")));
      MavenResponse response = headOnly
          ? MavenResponse.noBody(200, bytes.length, SwiftMediaTypes.MANIFEST, null, null)
          : MavenResponse.ok(
              new ByteArrayInputStream(bytes), bytes.length, SwiftMediaTypes.MANIFEST, null, null);
      return Optional.of(response
          .withHeader("Content-Disposition",
              "attachment; filename=\"" + manifest.get("filename") + "\"")
          .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION));
    }
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> defaultManifest(StagingItemRecord item) {
    Object raw = item.attributes().get(MANIFESTS_ATTRIBUTES);
    if (!(raw instanceof List<?> entries)) {
      return null;
    }
    for (Object entry : entries) {
      if (entry instanceof Map<?, ?> manifest
          && (manifest.get("toolsVersion") == null
              || "Package.swift".equals(manifest.get("filename")))) {
        Map<String, Object> copy = new LinkedHashMap<>();
        manifest.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
      }
    }
    return entries.isEmpty() ? null : (Map<String, Object>) entries.getFirst();
  }

  private SwiftReleaseMetadata releaseMetadata(StagingItemRecord item, SwiftPath path) {
    Map<String, Object> metadata = Map.of();
    Object raw = item.attributes().get("swiftMetadata");
    if (raw != null) {
      try {
        metadata = objectMapper.readValue(
            String.valueOf(raw), new TypeReference<Map<String, Object>>() {});
      } catch (IOException ignored) {
        metadata = Map.of();
      }
    }
    SwiftReleaseSigning signing = null;
    Object signature = item.attributes().get("swiftSourceSignature");
    Object format = item.attributes().get("swiftSignatureFormat");
    if (signature != null && format != null) {
      signing = new SwiftReleaseSigning(String.valueOf(signature), String.valueOf(format));
    }
    String sha256 = String.valueOf(item.attributes().getOrDefault(
        "swiftArchiveSha256", item.sha256()));
    return new SwiftReleaseMetadata(
        path.scope() + "." + path.name(),
        path.version(),
        List.of(SwiftReleaseResource.sourceArchive(sha256, signing)),
        metadata,
        item.createdAt());
  }

  private List<StagingItemRecord> matchingItems(
      RepositoryRuntime runtime, long sessionId, SwiftPath path) {
    List<StagingItemRecord> matches = new ArrayList<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.SWIFT) {
        continue;
      }
      Object scope = item.attributes().get("swiftScope");
      Object name = item.attributes().get("swiftName");
      if (scope != null && name != null
          && path.scope().equalsIgnoreCase(String.valueOf(scope))
          && path.name().equalsIgnoreCase(String.valueOf(name))) {
        matches.add(item);
      }
    }
    return matches;
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed(
        "Swift staging does not support deletions");
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "Swift staging does not support deletions";
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    Map<String, Object> attributes = item.attributes();
    String scope = String.valueOf(attributes.get("swiftScope"));
    String name = String.valueOf(attributes.get("swiftName"));
    String version = String.valueOf(attributes.get("swiftVersion"));
    String metadata = String.valueOf(attributes.getOrDefault("swiftMetadata", "{}"));
    String baseUrl = String.valueOf(attributes.getOrDefault("swiftBaseUrl", ""));
    String signatureFormat = attributes.get("swiftSignatureFormat") == null
        ? null : String.valueOf(attributes.get("swiftSignatureFormat"));
    byte[] sourceSignature = signature(attributes, "swiftSourceSignature");
    byte[] metadataSignature = signature(attributes, "swiftMetadataSignature");
    try (InputStream archive = source.open()) {
      swiftHosted.publishUpload(
          runtime, scope, name, version, archive, metadata, sourceSignature, metadataSignature,
          signatureFormat, baseUrl, actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  private String repositoryBaseUrl(HttpServletRequest request, String repository) {
    return forwardedHeaderPolicy.serverBaseUrl(request)
        + request.getContextPath() + RepositoryUrlPrefix.path(request) + repository;
  }

  private static String swiftAssetPath(SwiftPath path) {
    return "swift/" + path.scope() + "/" + path.name() + "/" + path.version() + ".zip";
  }

  private static void putSignature(Map<String, Object> attributes, String key, byte[] value) {
    if (value != null && value.length > 0) {
      attributes.put(key, Base64.getEncoder().encodeToString(value));
    }
  }

  private static byte[] signature(Map<String, Object> attributes, String key) {
    Object value = attributes == null ? null : attributes.get(key);
    return value == null ? null : Base64.getDecoder().decode(String.valueOf(value));
  }
}
