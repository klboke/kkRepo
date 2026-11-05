package com.github.klboke.kkrepo.server.nuget;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.protocol.nuget.NugetPath;
import com.github.klboke.kkrepo.protocol.nuget.NugetPathParser;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged NuGet package publication. */
@Component
final class NugetStagingContentAdapter implements StagingContentAdapter {
  private static final NugetPathParser PARSER = new NugetPathParser();

  private final RawHostedService hosted;
  private final NugetService nugetHosted;
  private final StagingDao staging;

  NugetStagingContentAdapter(
      RawHostedService hosted, NugetService nugetHosted, StagingDao staging) {
    this.hosted = hosted;
    this.nugetHosted = nugetHosted;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.NUGET;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
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
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "nuget-staging-", ".nupkg");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      NugetService.UploadPlan plan = nugetHosted.planPackage(temp);
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, plan.packagePath(), temp, contentType, Map.of(), actor, ip);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("nugetPublishPath", publishPath(requestedPath));
      attributes.put("nugetId", plan.id());
      attributes.put("nugetVersion", plan.version());
      return new StagedUpload(blob, plan.packagePath(), contentType, attributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read NuGet package", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  /**
   * Serves the flat-container version index and registration index from live versions plus staged
   * packages, so dotnet/NuGet clients can restore a staged package before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    NugetPath path = PARSER.parse(rawPath);
    boolean registration = path.kind() == NugetPath.Kind.REGISTRATION_INDEX;
    if (!registration && path.kind() != NugetPath.Kind.FLAT_CONTAINER_VERSION_INDEX) {
      return Optional.empty();
    }
    String packageId = path.packageId();
    if (packageId == null || packageId.isBlank()) {
      return Optional.empty();
    }
    List<String> staged = new ArrayList<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.NUGET) {
        continue;
      }
      Object id = item.attributes().get("nugetId");
      Object version = item.attributes().get("nugetVersion");
      if (id != null && version != null && packageId.equalsIgnoreCase(String.valueOf(id))) {
        staged.add(String.valueOf(version));
      }
    }
    if (staged.isEmpty()) {
      return Optional.empty();
    }
    if (registration) {
      if (repositoryBaseUrl == null) {
        return Optional.empty();
      }
      return Optional.of(nugetHosted.stagedRegistrationIndex(
          runtime, packageId, rawPath, repositoryBaseUrl, staged, headOnly));
    }
    return Optional.of(nugetHosted.stagedVersionIndex(runtime, packageId, staged, headOnly));
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
    return nugetHosted.deletePackage(runtime, path).status() != 404;
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "NuGet staging does not support deletions";
    if (live != null) return "NuGet package already exists: " + item.logicalPath();
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    String publishPath = String.valueOf(
        item.attributes().getOrDefault("nugetPublishPath", "api/v2/package"));
    try (InputStream body = source.open()) {
      nugetHosted.putPackage(
          runtime, publishPath, body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String publishPath(String requestedPath) {
    String value = requestedPath == null ? "" : requestedPath.trim();
    return value.isEmpty() ? "api/v2/package" : value;
  }
}
