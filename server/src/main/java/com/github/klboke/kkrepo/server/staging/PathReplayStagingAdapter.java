package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Base adapter for formats whose hosted write is a path-addressed payload.
 *
 * <p>Staging retains the bytes at the canonical path; promotion delegates to a format-specific
 * replay callback that invokes the normal writer.
 */
public abstract class PathReplayStagingAdapter implements StagingContentAdapter {
  protected final RawHostedService hosted;

  protected PathReplayStagingAdapter(RawHostedService hosted) {
    this.hosted = hosted;
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
        .orElseThrow(() -> new MavenExceptions.BadRequestException(
            "Unsupported staged path for " + runtime.format().id()));
    AssetBlobRecord blob = hosted.storeStagedBlob(
        runtime, path, body, contentType, Map.of(), actor, ip);
    return new StagedUpload(blob, path, contentType, Map.of());
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return hosted.deleteInternal(runtime, path).status() != 404;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return Optional.of(replay(runtime, item, source, actor, ip));
  }

  protected abstract String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip);

  protected static String replay(
      StagingBlobSource source, java.util.function.Consumer<InputStream> writer) {
    try (InputStream body = source.open()) {
      writer.accept(body);
      return "published";
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }
}
