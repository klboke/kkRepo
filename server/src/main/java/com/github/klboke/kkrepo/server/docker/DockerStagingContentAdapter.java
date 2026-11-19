package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Staged Docker/OCI manifest publication.
 *
 * <p>Blob uploads remain content-addressed live blobs; only the manifest/tag binding is staged.
 * This makes a tag become visible atomically at promotion while a failed or cancelled release never
 * exposes the staged manifest. Blobs must still exist when promotion runs, matching the normal
 * Docker push ordering where the manifest is uploaded last.
 */
@Component
final class DockerStagingContentAdapter implements StagingContentAdapter {
  private static final String MANIFEST_PREFIX = "manifest/";
  private static final String BLOB_PREFIX = "blob/";

  private final RawHostedService hosted;
  private final DockerHostedService dockerHosted;

  DockerStagingContentAdapter(RawHostedService hosted, DockerHostedService dockerHosted) {
    this.hosted = hosted;
    this.dockerHosted = dockerHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.DOCKER;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.startsWith(BLOB_PREFIX)) {
      return path.length() > BLOB_PREFIX.length() ? Optional.of(path) : Optional.empty();
    }
    if (!path.startsWith(MANIFEST_PREFIX)) return Optional.empty();
    String rest = path.substring(MANIFEST_PREFIX.length());
    int slash = rest.lastIndexOf('/');
    if (slash <= 0 || slash == rest.length() - 1) return Optional.empty();
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
    String path = normalizePath(requestedPath)
        .orElseThrow(() -> new DockerProtocolException(
            DockerErrorCode.UNSUPPORTED, "unsupported staged Docker path", 405));
    AssetBlobRecord blob = hosted.storeStagedBlob(
        runtime, path, body, contentType, Map.of(), actor, ip);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("dockerContentType", contentType);
    attributes.put("dockerTags", List.of());
    return new StagedUpload(blob, path, contentType, attributes);
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    String path = normalizePath(requestedPath).orElse(null);
    if (path == null) {
      return Optional.empty();
    }
    List<String> tags = request.getParameterValues("tag") == null
        ? List.of()
        : Arrays.stream(request.getParameterValues("tag"))
            .filter(value -> value != null && !value.isBlank())
            .toList();
    try (InputStream body = request.getInputStream()) {
      AssetBlobRecord blob = hosted.storeStagedBlob(
          runtime, path, body, request.getContentType(), Map.of(), actor, ip);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("dockerContentType", request.getContentType());
      attributes.put("dockerTags", tags);
      return Optional.of(new StagedUpload(
          blob, path, request.getContentType(), attributes));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    if (path != null && path.startsWith(BLOB_PREFIX)) {
      return false;
    }
    ManifestCoordinate coordinate = coordinate(path);
    return dockerHosted.deleteManifest(
        runtime, coordinate.imageName(), coordinate.reference()).status() != 404;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    if (item.logicalPath() != null && item.logicalPath().startsWith(BLOB_PREFIX)) {
      return Optional.of("retained");
    }
    ManifestCoordinate coordinate = coordinate(item.logicalPath());
    List<String> tags = tags(item.attributes());
    try (InputStream body = source.open()) {
      dockerHosted.putManifest(
          runtime, coordinate.imageName(), coordinate.reference(), body,
          item.contentType(), tags, actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static ManifestCoordinate coordinate(String path) {
    String value = path == null ? "" : path.trim();
    if (!value.startsWith(MANIFEST_PREFIX)) {
      throw new DockerProtocolException(
          DockerErrorCode.UNSUPPORTED, "unsupported staged Docker path", 405);
    }
    String rest = value.substring(MANIFEST_PREFIX.length());
    int slash = rest.lastIndexOf('/');
    if (slash <= 0 || slash == rest.length() - 1) {
      throw new DockerProtocolException(
          DockerErrorCode.UNSUPPORTED, "unsupported staged Docker manifest path", 405);
    }
    return new ManifestCoordinate(rest.substring(0, slash), rest.substring(slash + 1));
  }

  private static List<String> tags(Map<String, Object> attributes) {
    if (attributes == null) return List.of();
    Object value = attributes.get("dockerTags");
    if (!(value instanceof List<?> list)) return List.of();
    List<String> tags = new ArrayList<>();
    list.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .filter(tag -> !tag.isBlank())
        .forEach(tags::add);
    return List.copyOf(tags);
  }

  private record ManifestCoordinate(String imageName, String reference) {
  }
}
