package com.github.klboke.kkrepo.server.openvsx;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxPath;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxPathParser;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxProtocol;
import com.github.klboke.kkrepo.server.blob.BufferedUpload;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingComponents;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Staged content support for Open VSX hosted repositories.
 *
 * <p>The VSIX is inspected while it is staged, so promotion only has to link the existing blob and
 * publish the recorded component projection. Open VSX metadata JSON is generated from live
 * components, which makes promoted extensions visible immediately after commit.
 */
@Component
final class OpenVsxStagingContentAdapter implements StagingContentAdapter {
  private static final Set<OpenVsxPath.Kind> METADATA_KINDS = Set.of(
      OpenVsxPath.Kind.NAMESPACE,
      OpenVsxPath.Kind.EXTENSION,
      OpenVsxPath.Kind.EXTENSION_VERSIONS,
      OpenVsxPath.Kind.VERSION_REFERENCES,
      OpenVsxPath.Kind.EXTENSION_VERSION,
      OpenVsxPath.Kind.PLATFORM_EXTENSION,
      OpenVsxPath.Kind.PLATFORM_VERSIONS,
      OpenVsxPath.Kind.PLATFORM_VERSION_REFERENCES,
      OpenVsxPath.Kind.PLATFORM_VERSION);
  private static final OpenVsxQuery DEFAULT_QUERY =
      new OpenVsxQuery(null, null, null, 18, 0, null, null, false,
          null, null, null, null);

  private final OpenVsxPathParser parser = new OpenVsxPathParser();
  private final RawHostedService hosted;
  private final OpenVsxVsixInspector inspector;
  private final OpenVsxAssetSupport assets;
  private final OpenVsxHostedService openvsxHosted;
  private final StagingDao staging;
  private final long maxExtensionSize;

  OpenVsxStagingContentAdapter(
      RawHostedService hosted,
      OpenVsxVsixInspector inspector,
      OpenVsxAssetSupport assets,
      OpenVsxHostedService openvsxHosted,
      StagingDao staging,
      @Value("${kkrepo.openvsx.max-extension-size:268435456}") long maxExtensionSize) {
    this.hosted = hosted;
    this.inspector = inspector;
    this.assets = assets;
    this.openvsxHosted = openvsxHosted;
    this.staging = staging;
    this.maxExtensionSize = Math.max(1, maxExtensionSize);
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.OPENVSX;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    OpenVsxPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (METADATA_KINDS.contains(path.kind())) {
      return Optional.of(path.rawPath());
    }
    if (path.kind() != OpenVsxPath.Kind.FILE
        && path.kind() != OpenVsxPath.Kind.PLATFORM_FILE) {
      return Optional.empty();
    }
    return Optional.of(OpenVsxComponentFactory.storagePath(
        path.namespace(), path.extension(), path.version(), path.targetPlatform(),
        path.filePath()));
  }

  @Override
  public boolean supportsPublication(String rawPath) {
    try {
      return parser.parse(rawPath).kind() == OpenVsxPath.Kind.PUBLISH;
    } catch (IllegalArgumentException invalid) {
      return false;
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
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "openvsx-staging-", ".vsix");
      BufferedUpload.Digests digests =
          BufferedUpload.copy(body, temp, BufferedUpload.Checksums.WITH_SHA512);
      if (digests.size() > maxExtensionSize) {
        throw new MavenExceptions.BadRequestException(
            "VSIX exceeds the configured maximum extension size");
      }
      OpenVsxVsixInspector.Inspected inspected = inspector.inspect(temp, null);
      String logicalPath = OpenVsxComponentFactory.storagePath(inspected);
      Optional<String> requested = normalizePath(requestedPath);
      if (requested.isPresent() && !requested.get().equals(logicalPath)) {
        throw new MavenExceptions.BadRequestException(
            "VSIX identity does not match the staged path");
      }
      if (assets.find(runtime, logicalPath).isPresent()) {
        throw new MavenExceptions.BadRequestException(
            "Extension version already exists: " + inspected.namespace() + "."
                + inspected.name() + "@" + inspected.version());
      }
      ComponentRecord component = new OpenVsxComponentFactory().component(
          runtime, inspected, logicalPath, digests.sha1(), digests.sha256(), digests.size(),
          Instant.now());
      Map<String, Object> blobAttributes = new LinkedHashMap<>();
      blobAttributes.put("openvsxSha1", digests.sha1());
      blobAttributes.put("openvsxSha256", digests.sha256());
      blobAttributes.put("openvsxSize", digests.size());
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, logicalPath, temp, OpenVsxProtocol.VSIX_MEDIA_TYPE, blobAttributes, actor, ip);
      Map<String, Object> itemAttributes = new LinkedHashMap<>();
      itemAttributes.put(StagingComponents.ATTRIBUTE, StagingComponents.encode(component));
      return new StagedUpload(
          blob, logicalPath, OpenVsxProtocol.VSIX_MEDIA_TYPE, itemAttributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read VSIX upload", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  /**
   * Serves registry metadata JSON from live components plus staged extension projections so a
   * gallery client can resolve a staged VSIX before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    OpenVsxPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (!METADATA_KINDS.contains(path.kind()) || repositoryBaseUrl == null) {
      return Optional.empty();
    }
    List<ComponentRecord> staged = new ArrayList<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id()
          || item.format() != RepositoryFormat.OPENVSX) {
        continue;
      }
      StagingComponents.decode(
              runtime.id(), RepositoryFormat.OPENVSX, item.attributes(),
              item.createdAt() == null ? Instant.now() : item.createdAt())
          .ifPresent(staged::add);
    }
    if (staged.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(openvsxHosted.stagedGet(
        runtime, path, DEFAULT_QUERY, repositoryBaseUrl, runtime.name(), staged, headOnly));
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return hosted.deleteInternal(runtime, path).status() != 404;
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if (live != null) {
      return "Extension version already exists";
    }
    return null;
  }
}
