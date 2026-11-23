package com.github.klboke.kkrepo.server.arch;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.arch.ArchMediaTypes;
import com.github.klboke.kkrepo.protocol.arch.ArchPath;
import com.github.klboke.kkrepo.protocol.arch.ArchPathParser;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Staged content support for pacman repositories.
 *
 * <p>Packages are inspected while they are staged. Promotion reuses the existing blob and publishes
 * the component projection stored in the staging item; generated pacman databases derive from those
 * component rows, so they update as soon as promotion commits.
 */
@Component
final class ArchStagingContentAdapter implements StagingContentAdapter {
  private static final long MAX_SIGNATURE_BYTES = 1024 * 1024;

  private final ArchPathParser parser = new ArchPathParser();
  private final RawHostedService hosted;
  private final ArchPackageInspector inspector;
  private final ArchAssetSupport assets;
  private final ArchService archHosted;
  private final StagingDao staging;

  ArchStagingContentAdapter(
      RawHostedService hosted,
      ArchPackageInspector inspector,
      ArchAssetSupport assets,
      ArchService archHosted,
      StagingDao staging) {
    this.hosted = hosted;
    this.inspector = inspector;
    this.assets = assets;
    this.archHosted = archHosted;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.ARCH;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    ArchPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.generated() || path.databaseSignature()) {
      return Optional.of(path.rawPath());
    }
    if (path.kind() != ArchPath.Kind.PACKAGE && path.kind() != ArchPath.Kind.SIGNATURE) {
      return Optional.empty();
    }
    return Optional.of(path.fileName());
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    String fileName = normalizePath(requestedPath)
        .orElseThrow(() -> new MavenExceptions.BadRequestException(
            "PUT requires a *.pkg.tar.* package or package signature path"));
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "arch-staging-", ".pkg.tar");
      BufferedUpload.Digests digests =
          BufferedUpload.copy(body, temp, BufferedUpload.Checksums.WITH_SHA512);
      if (fileName.endsWith(".sig")) {
        if (digests.size() > MAX_SIGNATURE_BYTES) {
          throw new MavenExceptions.BadRequestException("Package signature is too large");
        }
        AssetBlobRecord blob = hosted.storeStagedBlobFile(
            runtime, fileName, temp, ArchMediaTypes.SIGNATURE,
            Map.of("archSignature", true), actor, ip);
        return new StagedUpload(
            blob,
            fileName,
            ArchMediaTypes.SIGNATURE,
            Map.of(StagingComponents.UNINDEXED_ATTRIBUTE, true));
      }
      ArchPackageInspector.Inspected inspected = inspector.inspect(
          temp, fileName, digests.size(), digests.md5(), digests.sha256());
      ComponentRecord component = assets.component(
          runtime, inspected, digests.md5(), digests.sha1(), digests.sha256());
      Map<String, Object> blobAttributes = component.attributes();
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, fileName, temp, ArchMediaTypes.forPackage(fileName), blobAttributes, actor, ip);
      Map<String, Object> itemAttributes = new LinkedHashMap<>();
      itemAttributes.put(StagingComponents.ATTRIBUTE, StagingComponents.encode(component));
      return new StagedUpload(
          blob, fileName, ArchMediaTypes.forPackage(fileName), itemAttributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read pacman package", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  /**
   * Serves {@code <repo>.db}/{@code .files} (and their signatures) from live components plus staged
   * packages so pacman resolves a staged release before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    ArchPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (!path.generated() && !path.databaseSignature()) {
      return Optional.empty();
    }
    List<ComponentRecord> staged = new ArrayList<>();
    Set<String> deleted = new LinkedHashSet<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.ARCH) {
        continue;
      }
      if ("DELETE".equals(item.action())) {
        deleted.add(item.logicalPath());
        continue;
      }
      StagingComponents.decode(
              runtime.id(), RepositoryFormat.ARCH, item.attributes(),
              item.createdAt() == null ? Instant.now() : item.createdAt())
          .ifPresent(staged::add);
    }
    if (staged.isEmpty() && deleted.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(archHosted.stagedDatabase(runtime, path, staged, deleted, headOnly));
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    ArchPath parsed = parser.parse(path);
    boolean deleted = hosted.deleteInternal(runtime, path).status() != 404;
    if (parsed.kind() == ArchPath.Kind.PACKAGE) {
      hosted.deleteInternal(runtime, path + ".sig");
    }
    return deleted;
  }
}
