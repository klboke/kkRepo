package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.conan.ConanPath;
import com.github.klboke.kkrepo.protocol.conan.ConanPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Conan revision file publication. */
@Component
final class ConanStagingContentAdapter implements StagingContentAdapter {
  private static final String CHECKSUM_HEADER = "X-Checksum-Sha1";

  private final ConanPathParser parser = new ConanPathParser();
  private final RawHostedService hosted;
  private final ConanService conanHosted;

  ConanStagingContentAdapter(RawHostedService hosted, ConanService conanHosted) {
    this.hosted = hosted;
    this.conanHosted = conanHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.CONAN;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      ConanPath path = parser.parse(URLDecoder.decode(
          rawPath == null ? "" : rawPath, StandardCharsets.UTF_8));
      return path.fileResource() ? Optional.of(path.rawPath()) : Optional.empty();
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
    throw new ConanExceptions.BadRequest(
        "Conan staging requires the X-Checksum-Sha1 upload header");
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    String checksum = request.getHeader(CHECKSUM_HEADER);
    if (checksum == null || !checksum.matches("(?i)[0-9a-f]{40}")) {
      throw new ConanExceptions.BadRequest(
          "Conan staging requires a valid X-Checksum-Sha1 header");
    }
    String path = normalizePath(requestedPath)
        .orElseThrow(() -> new ConanExceptions.BadRequest(
            "Conan staging accepts revision file paths only"));
    try (InputStream body = request.getInputStream()) {
      AssetBlobRecord blob = hosted.storeStagedBlob(
          runtime, path, body, request.getContentType(), Map.of(), actor, ip);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("conanChecksumSha1", checksum.toLowerCase(java.util.Locale.ROOT));
      return Optional.of(new StagedUpload(
          blob, path, request.getContentType(), attributes));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return conanHosted.delete(runtime, path).status() != 404;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    String checksum = String.valueOf(item.attributes().get("conanChecksumSha1"));
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "staging", actor, null, null, null);
    try (InputStream body = source.open()) {
      conanHosted.put(
          runtime, item.logicalPath(), body, item.size() == null ? 0L : item.size(),
          item.contentType(), checksum, false, subject, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
