package com.github.klboke.kkrepo.server.rubygems;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Staged RubyGems gem publication. */
@Component
final class RubygemsStagingContentAdapter implements StagingContentAdapter {
  private static final String GEM_ATTRIBUTES = "gemMetadata";

  private final RawHostedService hosted;
  private final RubygemsService rubygemsHosted;
  private final StagingDao staging;

  RubygemsStagingContentAdapter(
      RawHostedService hosted, RubygemsService rubygemsHosted, StagingDao staging) {
    this.hosted = hosted;
    this.rubygemsHosted = rubygemsHosted;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.RUBYGEMS;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    return Optional.of(path);
  }

  @Override
  public boolean supportsPublication(String rawPath) {
    return normalizePath(rawPath).filter("api/v1/gems"::equals).isPresent();
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "rubygems-staging-", ".gem");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      RubygemsService.UploadPlan plan = rubygemsHosted.planPush(temp);
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, plan.gemPath(), temp, contentType, Map.of(), actor, ip);
      return new StagedUpload(
          blob, plan.gemPath(), contentType, Map.of(GEM_ATTRIBUTES, descriptor(plan.metadata())));
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read RubyGems package", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  /**
   * Serves the compact index ({@code versions} and {@code info/{gem}}) from live gems plus staged
   * publications and deletions.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    String path = normalizePath(rawPath).orElse(null);
    if (path == null || !(path.equals("versions") || path.startsWith("info/"))) {
      return Optional.empty();
    }
    List<RubygemsService.GemMetadata> staged = new ArrayList<>();
    Set<String> deleted = new LinkedHashSet<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id()
          || item.format() != RepositoryFormat.RUBYGEMS) {
        continue;
      }
      if (StagingDao.ITEM_DELETE.equals(item.action())) {
        deleted.add(item.logicalPath());
        continue;
      }
      RubygemsService.GemMetadata gem = gem(item.attributes().get(GEM_ATTRIBUTES));
      if (gem != null) {
        staged.add(gem);
      }
    }
    if (staged.isEmpty() && deleted.isEmpty()) {
      return Optional.empty();
    }
    if (path.equals("versions")) {
      return Optional.of(rubygemsHosted.stagedCompactVersions(runtime, staged, deleted, headOnly));
    }
    String name = path.substring("info/".length());
    return Optional.of(
        rubygemsHosted.stagedCompactInfo(runtime, name, staged, deleted, headOnly));
  }

  private static Map<String, Object> descriptor(RubygemsService.GemMetadata gem) {
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("name", gem.name());
    descriptor.put("version", gem.version());
    descriptor.put("platform", gem.platform());
    descriptor.put("sha256", gem.sha256());
    descriptor.put("summary", gem.summary());
    descriptor.put("authors", gem.authors());
    List<Map<String, Object>> dependencies = new ArrayList<>();
    for (RubygemsService.GemDependency dependency : gem.dependencies()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", dependency.name());
      entry.put("requirements", dependency.requirements());
      dependencies.add(entry);
    }
    descriptor.put("dependencies", dependencies);
    return descriptor;
  }

  private static RubygemsService.GemMetadata gem(Object raw) {
    if (!(raw instanceof Map<?, ?> value)) {
      return null;
    }
    Map<String, Object> descriptor = new LinkedHashMap<>();
    value.forEach((key, entry) -> descriptor.put(String.valueOf(key), entry));
    Object name = descriptor.get("name");
    Object version = descriptor.get("version");
    if (name == null || version == null) {
      return null;
    }
    List<RubygemsService.GemDependency> dependencies = new ArrayList<>();
    if (descriptor.get("dependencies") instanceof List<?> rows) {
      for (Object row : rows) {
        if (row instanceof Map<?, ?> dependency) {
          Object dependencyName = dependency.get("name");
          if (dependencyName == null) continue;
          dependencies.add(new RubygemsService.GemDependency(
              String.valueOf(dependencyName), strings(dependency.get("requirements"))));
        }
      }
    }
    return new RubygemsService.GemMetadata(
        String.valueOf(name),
        String.valueOf(version),
        descriptor.get("platform") == null ? "ruby" : String.valueOf(descriptor.get("platform")),
        dependencies,
        descriptor.get("sha256") == null ? "" : String.valueOf(descriptor.get("sha256")),
        descriptor.get("summary") == null ? "" : String.valueOf(descriptor.get("summary")),
        strings(descriptor.get("authors")));
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> rows)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    rows.forEach(entry -> result.add(String.valueOf(entry)));
    return result;
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    try (InputStream body = request.getInputStream()) {
      return Optional.of(stage(
          runtime, requestedPath, body, request.getContentType(), actor, ip));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return rubygemsHosted.delete(runtime, path).status() != 404;
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "RubyGems staging does not support deletions";
    if (live != null) return "RubyGems package already exists: " + item.logicalPath();
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
      rubygemsHosted.push(runtime, body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
