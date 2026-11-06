package com.github.klboke.kkrepo.server.composer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Staged Composer archive publication.
 *
 * <p>Composer clients normally publish through the Components API. Staging accepts an archive at
 * {@code [dist/]vendor/name/version.zip} with {@code X-KkRepo-Staging}; promotion replays the same
 * Composer archive writer used by the Components API.
 */
@Component
final class ComposerStagingContentAdapter implements StagingContentAdapter {
  private static final String METADATA_ATTRIBUTES = "composerMetadata";
  private static final ComposerPathParser PARSER = new ComposerPathParser();

  private final RawHostedService hosted;
  private final ComposerHostedService composerHosted;
  private final ComponentDao components;
  private final ComposerArchiveInspector inspector;
  private final ObjectMapper objectMapper;
  private final StagingDao staging;

  ComposerStagingContentAdapter(
      RawHostedService hosted,
      ComposerHostedService composerHosted,
      ComponentDao components,
      ComposerArchiveInspector inspector,
      ObjectMapper objectMapper,
      StagingDao staging) {
    this.hosted = hosted;
    this.composerHosted = composerHosted;
    this.components = components;
    this.inspector = inspector;
    this.objectMapper = objectMapper;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.COMPOSER;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    String[] parts = path.split("/");
    if (parts.length < 3 || parts.length > 4 || path.contains("..")) {
      return Optional.empty();
    }
    int offset = parts.length == 4 && "dist".equals(parts[0]) ? 1 : 0;
    if (offset == 1 && parts.length != 4) return Optional.empty();
    String vendor = parts[offset];
    String name = parts[offset + 1];
    String versionFile = parts[offset + 2];
    if (vendor.isBlank() || name.isBlank() || !versionFile.endsWith(".zip")) {
      return Optional.empty();
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
    ComposerCoordinate coordinate = coordinate(requestedPath);
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "composer-staging-", ".zip");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      ComposerArchiveInspector.Inspected inspected = inspector.inspect(
          temp, coordinate.fileName(), coordinate.name(), coordinate.version());
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, coordinate.path(), temp, contentType, Map.of(), actor, ip);
      Map<String, Object> metadata = new LinkedHashMap<>(inspected.metadata());
      metadata.putIfAbsent("name", coordinate.name());
      metadata.putIfAbsent("version", coordinate.version());
      Map<String, Object> dist = new LinkedHashMap<>();
      dist.put("type", coordinate.fileName().endsWith(".zip") ? "zip" : "tar");
      dist.put("reference", coordinate.version());
      dist.put("shasum", blob.sha1());
      metadata.put("dist", dist);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("composerName", coordinate.name());
      attributes.put("composerVersion", coordinate.version());
      attributes.put("composerFileName", coordinate.fileName());
      attributes.put(METADATA_ATTRIBUTES, metadata);
      return new StagedUpload(blob, coordinate.path(), contentType, attributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to inspect staged Composer archive", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  /**
   * Serves {@code p2/{vendor}/{package}.json} from live versions plus staged archives. Staged
   * entries point their {@code dist.url} at the staged archive path so a Composer client can
   * install the release before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    ComposerPath path = PARSER.parse(rawPath);
    if (path.kind() != ComposerPath.Kind.PACKAGE_METADATA
        || path.packageName() == null || repositoryBaseUrl == null) {
      return Optional.empty();
    }
    Map<String, Map<String, Object>> byVersion = new LinkedHashMap<>();
    for (Map<String, Object> entry :
        composerHosted.versionEntries(runtime, path.packageName(), path.dev(), repositoryBaseUrl)) {
      Object version = entry.get("version");
      if (version != null) byVersion.put(String.valueOf(version), entry);
    }
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.COMPOSER) {
        continue;
      }
      Object name = item.attributes().get("composerName");
      Object version = item.attributes().get("composerVersion");
      if (name == null || version == null
          || !path.packageName().equals(String.valueOf(name))
          || path.dev() != isDev(String.valueOf(version))
          || !(item.attributes().get(METADATA_ATTRIBUTES) instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      raw.forEach((key, value) -> entry.put(String.valueOf(key), value));
      entry.put("dist", stagedDist(entry.get("dist"), repositoryBaseUrl, item.logicalPath()));
      byVersion.put(String.valueOf(version), entry);
    }
    if (byVersion.isEmpty()) {
      return Optional.empty();
    }
    List<Map<String, Object>> versions = new ArrayList<>(byVersion.values());
    versions.sort(Comparator.comparing(
        entry -> String.valueOf(entry.get("version")), Comparator.reverseOrder()));
    return Optional.of(ComposerResponses.json(
        objectMapper,
        Map.of("packages", Map.of(path.packageName(), versions)),
        null, headOnly));
  }

  private static Map<String, Object> stagedDist(
      Object raw, String baseUrl, String stagedPath) {
    Map<String, Object> dist = new LinkedHashMap<>();
    if (raw instanceof Map<?, ?> map) {
      map.forEach((key, value) -> dist.put(String.valueOf(key), value));
    }
    dist.remove("path");
    dist.put("url", baseUrl + "/" + stagedPath);
    return dist;
  }

  private static boolean isDev(String version) {
    String value = version == null ? "" : version.toLowerCase(java.util.Locale.ROOT);
    return value.startsWith("dev-") || value.endsWith("-dev") || value.contains(".x-dev");
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed("Composer staging does not support deletions");
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "Composer staging does not support deletions";
    Object name = item.attributes().get("composerName");
    Object version = item.attributes().get("composerVersion");
    if (name != null && version != null
        && components.findByNameAndVersion(
            repository.id(), String.valueOf(name), String.valueOf(version)).isPresent()) {
      return "Composer package version already exists: " + name + " " + version;
    }
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    ComposerCoordinate coordinate = coordinate(item.logicalPath());
    try (InputStream body = source.open()) {
      composerHosted.uploadArchive(
          runtime, body, coordinate.fileName(), item.contentType(),
          coordinate.name(), coordinate.version(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static ComposerCoordinate coordinate(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    String[] parts = path.split("/");
    int offset = parts.length == 4 && "dist".equals(parts[0]) ? 1 : 0;
    if (parts.length - offset != 3) {
      throw new MavenExceptions.BadRequestException(
          "Composer staging requires [dist/]vendor/name/version.zip");
    }
    String fileName = parts[offset + 2];
    if (!fileName.endsWith(".zip")) {
      throw new MavenExceptions.BadRequestException(
          "Composer staging requires a .zip archive");
    }
    String version = fileName.substring(0, fileName.length() - 4);
    return new ComposerCoordinate(
        path, parts[offset] + "/" + parts[offset + 1], version, fileName);
  }

  private record ComposerCoordinate(
      String path, String name, String version, String fileName) {
  }
}
