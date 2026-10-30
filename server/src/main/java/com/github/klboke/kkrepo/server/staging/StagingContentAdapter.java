package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Format-specific staged-content boundary.
 *
 * <p>The generic staging service owns sessions, isolation, preflight, promotion transactions and
 * blob ownership. A format module contributes an adapter that understands how to validate an
 * upload, where its logical asset path lives, and how its live content is removed. This keeps the
 * core staging branch independent from Arch, Open VSX or any future format implementation.
 */
public interface StagingContentAdapter {
  boolean supports(RepositoryFormat format);

  /** Returns the concrete asset path, or empty when this request is not a staged asset path. */
  Optional<String> normalizePath(String rawPath);

  /** True when the format accepts a staged publication body at this request path (for example POST). */
  default boolean supportsPublication(String rawPath) {
    return false;
  }

  StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip);

  /**
   * Request-aware staging hook for multipart publication endpoints. Returning empty falls back to
   * {@link #stage}.
   */
  default Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    return Optional.empty();
  }

  /** Removes live content during promotion; returns false when the path was already absent. */
  boolean delete(RepositoryRuntime runtime, String path);

  /**
   * Replays the format's normal publication path during promotion. Returning empty falls back to
   * the generic component-projection promotion.
   */
  default Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return Optional.empty();
  }

  /**
   * Builds a generated metadata response for a staging view when no staged item exists for the
   * requested path. Formats that do not generate metadata return empty.
   */
  default Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    return Optional.empty();
  }

  /** Variant that also receives the external repository base URL. */
  default Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    return generatedMetadata(runtime, sessionId, rawPath, headOnly);
  }

  /** True when generated metadata must take precedence over a staged item at the same path. */
  default boolean generatedMetadataPreferred(RepositoryRuntime runtime, String rawPath) {
    return false;
  }

  /**
   * Format-specific preflight rule. Return a human-readable conflict, or {@code null} to allow the
   * generic checks to decide.
   */
  default String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    return null;
  }

  /**
   * Uploaded staged blob plus the final logical path. {@code logicalPath} may differ from the
   * request path for publication endpoints that derive their storage path from the payload.
   */
  record StagedUpload(
      AssetBlobRecord blob,
      String logicalPath,
      String contentType,
      Map<String, Object> attributes) {
  }
}
