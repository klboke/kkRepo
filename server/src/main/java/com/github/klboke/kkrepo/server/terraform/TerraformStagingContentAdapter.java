package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
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

/** Staged Terraform archive publication replayed through the normal Terraform writer. */
@Component
final class TerraformStagingContentAdapter extends PathReplayStagingAdapter {
  private static final String PROVIDER_ATTRIBUTES = "terraformProvider";

  private final TerraformPathParser parser = new TerraformPathParser();
  private final TerraformService terraformHosted;
  private final StagingDao staging;

  TerraformStagingContentAdapter(
      RawHostedService hosted, TerraformService terraformHosted, StagingDao staging) {
    super(hosted);
    this.terraformHosted = terraformHosted;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.TERRAFORM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      TerraformPath path = parser.parse(rawPath);
      if (path.kind() == TerraformPath.Kind.UNKNOWN) return Optional.empty();
      return Optional.of(path.rawPath());
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    return stage(runtime, requestedPath, body, contentType, null, actor, ip);
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    String protocols = request.getHeader(TerraformService.PROVIDER_PROTOCOLS_HEADER);
    try (InputStream body = request.getInputStream()) {
      return Optional.of(stage(
          runtime, requestedPath, body, request.getContentType(), protocols, actor, ip));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String protocols,
      String actor,
      String ip) {
    TerraformPath path = parser.parse(requestedPath);
    if (path.kind() != TerraformPath.Kind.PROVIDER_ARCHIVE) {
      AssetBlobRecord blob = hosted.storeStagedBlob(
          runtime, path.rawPath(), body, contentType, Map.of(), actor, ip);
      return new StagedUpload(blob, path.rawPath(), contentType, Map.of());
    }
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "terraform-staging-", ".zip");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path.rawPath(), temp, contentType, Map.of(), actor, ip);
      Map<String, Object> descriptor = new LinkedHashMap<>();
      descriptor.put("namespace", path.namespace());
      descriptor.put("name", path.name());
      descriptor.put("version", path.version());
      descriptor.put("os", path.os());
      descriptor.put("arch", path.arch());
      descriptor.put("filename", path.filename());
      descriptor.put("sha256", blob.sha256());
      descriptor.put("size", blob.size());
      descriptor.put("protocols", TerraformService.normalizeProviderProtocols(protocols));
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put(PROVIDER_ATTRIBUTES, descriptor);
      return new StagedUpload(blob, path.rawPath(), contentType, attributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to store staged Terraform provider", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  /**
   * Serves provider versions, download metadata and SHA256SUMS (plus signature) from live and
   * staged platforms so Terraform can install a staged provider before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    TerraformPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.namespace() == null || path.name() == null) {
      return Optional.empty();
    }
    List<StagingItemRecord> items = matchingItems(runtime, sessionId, path.namespace(), path.name());
    if (items.isEmpty()) {
      return Optional.empty();
    }
    return switch (path.kind()) {
      case PROVIDER_VERSIONS -> {
        List<Map<String, Object>> platforms = new ArrayList<>();
        for (StagingItemRecord item : items) {
          Map<String, Object> descriptor = descriptor(item);
          if (descriptor == null || !path.name().equals(descriptor.get("name"))) continue;
          platforms.add(Map.of(
              "version", descriptor.get("version"),
              "protocols", descriptor.get("protocols"),
              "os", descriptor.get("os"),
              "arch", descriptor.get("arch")));
        }
        yield platforms.isEmpty() ? Optional.empty() : Optional.of(
            terraformHosted.stagedProviderVersions(
                runtime, path.namespace(), path.name(), platforms, headOnly));
      }
      case PROVIDER_DOWNLOAD -> {
        if (repositoryBaseUrl == null || path.version() == null) {
          yield Optional.empty();
        }
        Map<String, Object> match = null;
        for (StagingItemRecord item : items) {
          Map<String, Object> descriptor = descriptor(item);
          if (descriptor != null
              && path.version().equals(descriptor.get("version"))
              && path.os() != null && path.os().equals(descriptor.get("os"))) {
            match = descriptor;
            if (path.arch() == null || path.arch().equals(descriptor.get("arch"))) {
              break;
            }
          }
        }
        if (match == null) {
          yield Optional.empty();
        }
        yield Optional.of(terraformHosted.stagedProviderDownload(
            runtime, path.namespace(), path.name(),
            String.valueOf(match.get("version")), String.valueOf(match.get("os")),
            String.valueOf(match.get("arch")), repositoryBaseUrl,
            String.valueOf(match.get("filename")), String.valueOf(match.get("sha256")),
            String.valueOf(match.get("protocols")), headOnly));
      }
      case PROVIDER_SHA256SUMS, PROVIDER_SHA256SUMS_SIGNATURE -> {
        List<Map<String, String>> archives = new ArrayList<>();
        for (StagingItemRecord item : items) {
          Map<String, Object> descriptor = descriptor(item);
          if (descriptor != null && path.version() != null
              && path.version().equals(descriptor.get("version"))) {
            archives.add(Map.of(
                "filename", String.valueOf(descriptor.get("filename")),
                "sha256", String.valueOf(descriptor.get("sha256"))));
          }
        }
        if (archives.isEmpty()) {
          yield Optional.empty();
        }
        yield Optional.of(terraformHosted.stagedProviderSums(
            runtime, path.namespace(), path.name(), path.version(), archives,
            path.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE, headOnly));
      }
      default -> Optional.empty();
    };
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> terraformHosted.put(
        runtime, parser.parse(item.logicalPath()), body, item.contentType(), null, null,
        actor, ip));
  }

  private List<StagingItemRecord> matchingItems(
      RepositoryRuntime runtime, long sessionId, String namespace, String name) {
    List<StagingItemRecord> matches = new ArrayList<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.TERRAFORM) {
        continue;
      }
      Map<String, Object> descriptor = descriptor(item);
      if (descriptor != null
          && namespace.equals(descriptor.get("namespace"))
          && name.equals(descriptor.get("name"))) {
        matches.add(item);
      }
    }
    return matches;
  }

  private static Map<String, Object> descriptor(StagingItemRecord item) {
    Object raw = item.attributes().get(PROVIDER_ATTRIBUTES);
    if (!(raw instanceof Map<?, ?> value)) {
      return null;
    }
    Map<String, Object> descriptor = new LinkedHashMap<>();
    value.forEach((key, entry) -> descriptor.put(String.valueOf(key), entry));
    return descriptor;
  }
}
