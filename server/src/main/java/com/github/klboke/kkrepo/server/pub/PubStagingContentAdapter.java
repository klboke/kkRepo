package com.github.klboke.kkrepo.server.pub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.pub.PubPath;
import com.github.klboke.kkrepo.protocol.pub.PubPathParser;
import com.github.klboke.kkrepo.protocol.pub.PubPaths;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Pub package archive publication. */
@Component
final class PubStagingContentAdapter implements StagingContentAdapter {
  private static final PubPathParser PARSER = new PubPathParser();

  private final PubHostedService pubHosted;
  private final ObjectMapper objectMapper;
  private final StagingDao staging;

  PubStagingContentAdapter(
      PubHostedService pubHosted, ObjectMapper objectMapper, StagingDao staging) {
    this.pubHosted = pubHosted;
    this.objectMapper = objectMapper;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.PUB;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    PubPath parsed = PARSER.parse(path);
    if (parsed.kind() == PubPath.Kind.ARCHIVE
        && parsed.packageName() != null && parsed.version() != null) {
      return Optional.of(PubPaths.archivePath(parsed.packageName(), parsed.version()));
    }
    return Optional.of(path);
  }

  /**
   * Serves {@code api/packages/{name}} from live versions plus staged publications so a Pub client
   * can resolve a staged release before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    PubPath path = PARSER.parse(rawPath);
    if (path.kind() != PubPath.Kind.PACKAGE_METADATA
        || path.packageName() == null || repositoryBaseUrl == null) {
      return Optional.empty();
    }
    List<Map<String, Object>> entries = new ArrayList<>(
        pubHosted.packageEntries(runtime, path.packageName(), repositoryBaseUrl));
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.PUB) {
        continue;
      }
      Object packageName = item.attributes().get("pubPackage");
      Object version = item.attributes().get("pubVersion");
      if (packageName == null || version == null
          || !path.packageName().equals(String.valueOf(packageName))) {
        continue;
      }
      entries.add(stagedEntry(item, String.valueOf(packageName), String.valueOf(version),
          repositoryBaseUrl));
    }
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(PubResponses.json(
        objectMapper,
        pubHosted.packageMetadataBodyFromEntries(path.packageName(), entries),
        200, null, null, headOnly));
  }

  private static Map<String, Object> stagedEntry(
      StagingItemRecord item, String packageName, String version, String repositoryBaseUrl) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("version", version);
    entry.put("archive_url",
        repositoryBaseUrl + "/" + PubPaths.apiArchivePath(packageName, version));
    Object sha256 = item.attributes().get("archiveSha256");
    if (sha256 != null) {
      entry.put("archive_sha256", String.valueOf(sha256));
    }
    Object pubspec = item.attributes().get("pubspec");
    if (pubspec instanceof Map<?, ?> raw) {
      Map<String, Object> copy = new LinkedHashMap<>();
      raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
      entry.put("pubspec", copy);
    } else {
      entry.put("pubspec", Map.of("name", packageName, "version", version));
    }
    Object published = item.attributes().get("publishedAt");
    if (published != null) {
      entry.put("published", String.valueOf(published));
    }
    return entry;
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
        "Pub staging is driven through the upload finalize endpoint");
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed("Pub staging does not support deletions");
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "Pub staging does not support deletions";
    if (live != null) return "Pub package version already exists: " + item.logicalPath();
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    try (InputStream body = source.open()) {
      pubHosted.uploadArchive(runtime, body, actor, ip, "staging");
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
