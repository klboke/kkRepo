package com.github.klboke.kkrepo.server.yum;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingMetadataSnapshotCache;
import java.io.ByteArrayInputStream;
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

/** Staged Yum RPM publication replayed through the normal Yum writer. */
@Component
final class YumStagingContentAdapter extends PathReplayStagingAdapter {
  static final String RPM_ATTRIBUTES = "yumRpmAttributes";

  private static final String XML_CONTENT_TYPE = "application/xml";
  private static final String GZIP_CONTENT_TYPE = "application/x-gzip";

  private final YumService yumHosted;
  private final StagingDao staging;
  private final StagingMetadataSnapshotCache metadataSnapshots;

  YumStagingContentAdapter(RawHostedService hosted, YumService yumHosted, StagingDao staging) {
    this(hosted, yumHosted, staging, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  YumStagingContentAdapter(
      RawHostedService hosted,
      YumService yumHosted,
      StagingDao staging,
      StagingMetadataSnapshotCache metadataSnapshots) {
    super(hosted);
    this.yumHosted = yumHosted;
    this.staging = staging;
    this.metadataSnapshots = metadataSnapshots;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.YUM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    return Optional.of(path);
  }

  /**
   * Parses the RPM header while staging so the staged view can rebuild repodata without reading
   * the blob again.
   */
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
    if (!path.endsWith(".rpm")) {
      AssetBlobRecord blob = hosted.storeStagedBlob(
          runtime, path, body, contentType, Map.of(), actor, ip);
      return new StagedUpload(blob, path, contentType, Map.of());
    }
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "yum-staging-", ".rpm");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      RpmMetadata metadata;
      try {
        metadata = RpmMetadataParser.parse(temp);
      } catch (IOException error) {
        throw new MavenExceptions.BadRequestException("Unable to inspect staged RPM", error);
      }
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path, temp, contentType, Map.of(), actor, ip);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put(RPM_ATTRIBUTES, metadata.toAttributes());
      return new StagedUpload(blob, path, contentType, attributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to store staged RPM", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return yumHosted.delete(runtime, path).status() != 404;
  }

  /**
   * Rebuilds {@code repodata/repomd.xml}, primary, filelists and other from live assets plus staged
   * RPMs so a Yum/DNF client can resolve a staged package before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    String root = YumMetadataDepth.metadataRoot(rawPath);
    if (root == null) {
      return Optional.empty();
    }
    String relativePath = rawPath.substring(root.length());
    if (!relativePath.startsWith("repodata/")) {
      return Optional.empty();
    }
    // Repodata is rendered once per session/repository/root revision. Serving a HEAD or the
    // primary/filelists files afterwards reuses the snapshot instead of re-reading the catalogue
    // and recompressing every file.
    java.util.function.Supplier<Optional<StagingMetadataSnapshotCache.Snapshot>> loader =
        () -> renderMetadata(runtime, sessionId, root);
    Optional<StagingMetadataSnapshotCache.Snapshot> snapshot = metadataSnapshots == null
        ? loader.get()
        : metadataSnapshots.find(
            new StagingMetadataSnapshotCache.Key(sessionId, runtime.id(), "yum:" + root),
            loader);
    if (snapshot.isEmpty()) {
      return Optional.empty();
    }
    StagingMetadataSnapshotCache.File file = snapshot.get().files().get(relativePath);
    return file == null
        ? Optional.empty()
        : Optional.of(bytes(file.body(), file.contentType(), headOnly));
  }

  /**
   * Generates every repodata file of a root together so one snapshot covers repomd.xml and the
   * three metadata payloads.
   */
  private Optional<StagingMetadataSnapshotCache.Snapshot> renderMetadata(
      RepositoryRuntime runtime, long sessionId, String root) {
    List<YumService.RpmAsset> assets = new ArrayList<>();
    for (YumService.RpmAsset asset : yumHosted.liveAssets(runtime)) {
      if (root.equals(YumMetadataDepth.root(asset.path(), runtime.yumRepodataDepth()))) {
        assets.add(relative(asset, root));
      }
    }
    boolean staged = false;
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.YUM) {
        continue;
      }
      if (!root.equals(YumMetadataDepth.root(item.logicalPath(), runtime.yumRepodataDepth()))) {
        continue;
      }
      RpmMetadata metadata = RpmMetadata.fromAttributes(
          mapValue(item.attributes().get(RPM_ATTRIBUTES)));
      if (metadata == null) {
        continue;
      }
      staged = true;
      assets.add(new YumService.RpmAsset(
          relativePath(item.logicalPath(), root), metadata,
          item.sha256(), item.size() == null ? 0L : item.size(),
          item.createdAt() == null ? java.time.Instant.now() : item.createdAt()));
    }
    if (!staged) {
      return Optional.empty();
    }
    assets.sort(java.util.Comparator.comparing(YumService.RpmAsset::path));
    YumService.MetadataFile primary = YumService.gzipMetadata(YumService.primaryXml(assets));
    YumService.MetadataFile filelists = YumService.gzipMetadata(YumService.filelistsXml(assets));
    YumService.MetadataFile other = YumService.gzipMetadata(YumService.otherXml(assets));
    List<YumService.RepomdEntry> entries = List.of(
        new YumService.RepomdEntry("primary", "repodata/" + primary.fileName(), primary),
        new YumService.RepomdEntry("filelists", "repodata/" + filelists.fileName(), filelists),
        new YumService.RepomdEntry("other", "repodata/" + other.fileName(), other));
    Map<String, StagingMetadataSnapshotCache.File> files = new LinkedHashMap<>();
    files.put("repodata/repomd.xml", new StagingMetadataSnapshotCache.File(
        YumService.repomdXml(entries).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        XML_CONTENT_TYPE));
    for (YumService.RepomdEntry entry : entries) {
      files.put(entry.href(), new StagingMetadataSnapshotCache.File(
          entry.file().bytes(), GZIP_CONTENT_TYPE));
    }
    return Optional.of(new StagingMetadataSnapshotCache.Snapshot(files));
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> yumHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }

  private static YumService.RpmAsset relative(YumService.RpmAsset asset, String root) {
    return new YumService.RpmAsset(
        relativePath(asset.path(), root), asset.metadata(), asset.sha256(), asset.size(),
        asset.lastUpdated());
  }

  private static String relativePath(String path, String root) {
    return root.isEmpty() ? path : path.substring(root.length());
  }

  private static Map<String, Object> mapValue(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, entry) -> result.put(String.valueOf(key), entry));
    return result;
  }

  private static MavenResponse bytes(byte[] body, String contentType, boolean headOnly) {
    return headOnly
        ? MavenResponse.noBody(200, body.length, contentType, null, null)
        : MavenResponse.ok(
            new ByteArrayInputStream(body), body.length, contentType, null, null);
  }
}
