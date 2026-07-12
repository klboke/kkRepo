package com.github.klboke.kkrepo.server.composer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPackageName;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.protocol.composer.ComposerPaths;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ComposerHostedService {
  private static final Logger log = LoggerFactory.getLogger(ComposerHostedService.class);
  private final ObjectMapper objectMapper;
  private final ComponentDao componentDao;
  private final ComposerAssetSupport assets;
  private final ComposerArchiveInspector archiveInspector;
  private final ComposerComponentWriter componentWriter;

  public ComposerHostedService(
      ObjectMapper objectMapper,
      ComponentDao componentDao,
      ComposerAssetSupport assets,
      ComposerArchiveInspector archiveInspector,
      ComposerComponentWriter componentWriter) {
    this.objectMapper = objectMapper;
    this.componentDao = componentDao;
    this.assets = assets;
    this.archiveInspector = archiveInspector;
    this.componentWriter = componentWriter;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      ComposerPath path,
      String baseUrl,
      String filter,
      boolean headOnly) {
    ensureHosted(runtime);
    return switch (path.kind()) {
      case ROOT, PACKAGES -> packages(runtime, baseUrl, headOnly);
      case PACKAGE_METADATA -> packageDocument(runtime, path.packageName(), path.dev(), baseUrl)
          .map(document -> ComposerResponses.json(
              objectMapper, document.body(), document.lastModified(), headOnly))
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.rawPath()));
      case PROVIDERS -> providers(runtime, path.packageName(), headOnly);
      case PACKAGE_LIST -> packageList(runtime, filter, headOnly);
      case DIST -> assets.serve(runtime, normalizePath(path.rawPath()), headOnly);
      case UNKNOWN -> throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    };
  }

  public String uploadArchive(
      RepositoryRuntime runtime,
      InputStream body,
      String fileName,
      String contentType,
      String nameOverride,
      String versionOverride,
      String createdBy,
      String createdByIp) throws IOException {
    ensureHosted(runtime);
    enforceWritePolicy(runtime, fileName);
    String safeFileName = safeFileName(fileName);
    Path temp = Files.createTempFile("kkrepo-composer-", suffix(safeFileName));
    try {
      Files.copy(body, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      ComposerArchiveInspector.Inspected inspected = archiveInspector.inspect(
          temp, safeFileName, nameOverride, versionOverride);
      if (componentDao.findByNameAndVersion(runtime.id(), inspected.name(), inspected.version()).isPresent()) {
        throw new MavenExceptions.WritePolicyDenied(
            "Composer package version already exists: " + inspected.name() + " " + inspected.version());
      }
      String archiveType = distType(safeFileName);
      String distPath = ComposerPaths.componentDist(inspected.name(), inspected.version(), archiveType);
      // A request-unique path keeps a losing upload from overwriting or deleting the winner's blob.
      String stagingPath = "_composer/uploads/" + UUID.randomUUID() + "/" + safeFileName;
      try (InputStream input = Files.newInputStream(temp)) {
        assets.store(runtime, stagingPath, input, contentType(contentType, distPath),
            Map.of("composerPackage", inspected.name(), "composerVersion", inspected.version()),
            createdBy, createdByIp);
      }
      try {
        AssetRecord asset = assets.find(runtime, stagingPath)
            .orElseThrow(() -> new IllegalStateException("Composer staging asset was not persisted: " + stagingPath));
        AssetBlobRecord blob = assets.blob(asset, stagingPath);
        Map<String, Object> metadata = new LinkedHashMap<>(inspected.metadata());
        metadata.put("dist", Map.of(
            "type", archiveType,
            "reference", inspected.version(),
            "shasum", blob.sha1(),
            "path", distPath));
        componentWriter.bindHostedArchive(
            runtime, asset, inspected.name(), inspected.version(), metadata, distPath, createdBy, createdByIp);
        return distPath;
      } finally {
        deleteStaging(runtime, stagingPath);
      }
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  private void deleteStaging(RepositoryRuntime runtime, String stagingPath) {
    try {
      assets.delete(runtime, stagingPath);
    } catch (RuntimeException e) {
      log.warn("Failed to delete Composer upload staging asset {}/{}",
          runtime.name(), stagingPath, e);
    }
  }

  private void enforceWritePolicy(RepositoryRuntime runtime, String path) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (!policy.checkCreateAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Write policy DENY forbids writing " + path);
    }
  }

  Optional<PackageDocument> packageDocument(
      RepositoryRuntime runtime,
      String packageName,
      boolean dev,
      String baseUrl) {
    ensureHosted(runtime);
    String name = ComposerPackageName.require(packageName);
    List<ComponentRecord> records = componentDao.listByName(runtime.id(), name).stream()
        .filter(record -> record.format() == RepositoryFormat.COMPOSER)
        .filter(record -> dev == isDev(record.version()))
        .sorted(Comparator.comparing(ComponentRecord::version).reversed())
        .toList();
    if (records.isEmpty()) return Optional.empty();
    List<Map<String, Object>> versions = new ArrayList<>(records.size());
    Instant lastModified = null;
    for (ComponentRecord record : records) {
      Map<String, Object> metadata = metadata(record);
      versions.add(rewriteHostedDist(metadata, baseUrl));
      if (lastModified == null || record.lastUpdatedAt().isAfter(lastModified)) {
        lastModified = record.lastUpdatedAt();
      }
    }
    return Optional.of(new PackageDocument(
        Map.of("packages", Map.of(name, versions)), lastModified, runtime.name()));
  }

  List<String> packageNames(RepositoryRuntime runtime) {
    ensureHosted(runtime);
    return componentDao.listByRepositoryId(runtime.id()).stream()
        .filter(record -> record.format() == RepositoryFormat.COMPOSER)
        .map(ComponentRecord::name)
        .filter(ComposerPackageName::isValid)
        .distinct()
        .sorted()
        .toList();
  }

  Set<String> providerNames(RepositoryRuntime runtime, String virtualPackage) {
    ensureHosted(runtime);
    Set<String> providers = new LinkedHashSet<>();
    for (ComponentRecord record : componentDao.listByRepositoryId(runtime.id())) {
      if (record.format() != RepositoryFormat.COMPOSER) continue;
      Object provide = metadata(record).get("provide");
      if (provide instanceof Map<?, ?> values && values.containsKey(virtualPackage)) {
        providers.add(record.name());
      }
    }
    return providers;
  }

  private MavenResponse packages(RepositoryRuntime runtime, String baseUrl, boolean headOnly) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packages", List.of());
    body.put("metadata-url", baseUrl + "/p2/%package%.json");
    body.put("providers-api", baseUrl + "/providers/%package%.json");
    body.put("list", baseUrl + "/packages/list.json");
    return ComposerResponses.json(objectMapper, body, latestUpdate(runtime), headOnly);
  }

  private MavenResponse packageList(RepositoryRuntime runtime, String filter, boolean headOnly) {
    List<String> names = packageNames(runtime).stream()
        .filter(name -> globMatches(name, filter))
        .toList();
    return ComposerResponses.json(objectMapper, Map.of("packageNames", names), latestUpdate(runtime), headOnly);
  }

  private MavenResponse providers(RepositoryRuntime runtime, String virtualPackage, boolean headOnly) {
    List<Map<String, Object>> providers = providerNames(runtime, virtualPackage).stream()
        .map(name -> Map.<String, Object>of("name", name))
        .toList();
    return ComposerResponses.json(objectMapper, Map.of("providers", providers), latestUpdate(runtime), headOnly);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> metadata(ComponentRecord record) {
    Object value = record.attributes().get("composerMetadata");
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalStateException("Composer component has no metadata: " + record.name());
    }
    return new LinkedHashMap<>((Map<String, Object>) map);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> rewriteHostedDist(Map<String, Object> metadata, String baseUrl) {
    Map<String, Object> copy = new LinkedHashMap<>(metadata);
    Object value = copy.get("dist");
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> dist = new LinkedHashMap<>((Map<String, Object>) map);
      Object path = dist.remove("path");
      if (path != null) dist.put("url", baseUrl + "/" + path);
      copy.put("dist", dist);
    }
    return copy;
  }

  private Instant latestUpdate(RepositoryRuntime runtime) {
    return componentDao.listByRepositoryId(runtime.id()).stream()
        .filter(record -> record.format() == RepositoryFormat.COMPOSER)
        .map(ComponentRecord::lastUpdatedAt)
        .max(Comparator.naturalOrder())
        .orElse(Instant.EPOCH);
  }

  private static boolean isDev(String version) {
    String value = version == null ? "" : version.toLowerCase(Locale.ROOT);
    return value.startsWith("dev-") || value.endsWith("-dev") || value.contains(".x-dev");
  }

  private static boolean globMatches(String value, String filter) {
    if (filter == null || filter.isBlank()) return true;
    String regex = java.util.regex.Pattern.quote(filter.trim()).replace("\\Q*\\E", ".*");
    return value.matches("(?i)^" + regex + "$");
  }

  private static String safeFileName(String fileName) {
    String value = fileName == null ? "package.zip" : fileName.trim();
    int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
    if (slash >= 0) value = value.substring(slash + 1);
    if (value.isBlank() || value.equals(".") || value.equals("..") || value.length() > 255) {
      throw new ComposerExceptions.BadRequestException("Invalid Composer archive filename");
    }
    return value;
  }

  private static String suffix(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".tar.gz")) return ".tar.gz";
    if (lower.endsWith(".tar.bz2")) return ".tar.bz2";
    int dot = lower.lastIndexOf('.');
    return dot < 0 ? ".tmp" : lower.substring(dot);
  }

  private static String normalizePath(String path) {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String distType(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".zip")) return "zip";
    if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return "tar";
    if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) return "tar";
    if (lower.endsWith(".tar")) return "tar";
    throw new ComposerExceptions.BadRequestException("Unsupported Composer archive: " + fileName);
  }

  private static String contentType(String hint, String fileName) {
    if (hint != null && !hint.isBlank()) return hint;
    return fileName.toLowerCase(Locale.ROOT).endsWith(".zip")
        ? "application/zip" : "application/octet-stream";
  }

  private static void ensureHosted(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.COMPOSER || !runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on hosted Composer repositories");
    }
  }

  record PackageDocument(Map<String, Object> body, Instant lastModified, String sourceRepository) {
  }
}
