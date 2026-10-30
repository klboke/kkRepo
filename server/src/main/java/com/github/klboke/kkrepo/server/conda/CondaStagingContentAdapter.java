package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Staged Conda package publication replayed through the normal Conda writer. */
@Component
final class CondaStagingContentAdapter extends PathReplayStagingAdapter {
  private static final String PACKAGE_ATTRIBUTES = "condaPackage";

  private final CondaPathParser parser = new CondaPathParser();
  private final CondaService condaHosted;
  private final CondaArchiveInspector inspector;
  private final StagingDao staging;

  CondaStagingContentAdapter(
      RawHostedService hosted,
      CondaService condaHosted,
      CondaArchiveInspector inspector,
      StagingDao staging) {
    super(hosted);
    this.condaHosted = condaHosted;
    this.inspector = inspector;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.CONDA;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      CondaPath path = parser.parse(rawPath);
      if (path.packageFile()) return Optional.of(path.canonicalPath());
      if (path.kind() == CondaPath.Kind.REPODATA) return Optional.of(path.rawPath());
      return Optional.empty();
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      java.io.InputStream body,
      String contentType,
      String actor,
      String ip) {
    CondaPath path = parser.parse(requestedPath);
    if (!path.packageFile()) {
      return super.stage(runtime, requestedPath, body, contentType, actor, ip);
    }
    CondaArchiveInspector.InspectedPackage inspected =
        inspector.inspect(body, path.filename(), path.subdir());
    AssetBlobRecord blob = hosted.storeStagedBlobFile(
        runtime, path.canonicalPath(), inspected.file(), contentType, Map.of(), actor, ip);
    Map<String, Object> metadata = new LinkedHashMap<>(inspected.metadata());
    metadata.remove("base_url");
    metadata.remove("download_url");
    metadata.put("subdir", path.subdir());
    metadata.put("md5", inspected.md5());
    metadata.put("sha256", inspected.sha256());
    metadata.put("size", inspected.size());
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("filename", inspected.filename());
    descriptor.put("archiveFormat", inspected.archiveFormat());
    descriptor.put("name", inspected.name());
    descriptor.put("version", inspected.version());
    descriptor.put("build", inspected.build());
    descriptor.put("buildNumber", inspected.buildNumber());
    descriptor.put("metadata", metadata);
    descriptor.put("md5", inspected.md5());
    descriptor.put("sha256", inspected.sha256());
    descriptor.put("size", inspected.size());
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PACKAGE_ATTRIBUTES, descriptor);
    return new StagedUpload(blob, path.canonicalPath(), contentType, attributes);
  }

  /**
   * Serves repodata.json (and its bz2/zst variants) from live packages plus staged publications
   * and deletions so conda/mamba resolve a staged package before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    CondaPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.kind() != CondaPath.Kind.REPODATA || path.channel() == null || path.subdir() == null) {
      return Optional.empty();
    }
    List<CondaRegistryDao.PackageRecord> staged = new ArrayList<>();
    Set<String> deleted = new LinkedHashSet<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.CONDA) {
        continue;
      }
      CondaPath itemPath = parser.parse(item.logicalPath());
      if (!itemPath.packageFile()
          || !path.channel().equals(itemPath.channel())
          || !path.subdir().equals(itemPath.subdir())) {
        continue;
      }
      if (StagingDao.ITEM_DELETE.equals(item.action())) {
        deleted.add(itemPath.filename());
        continue;
      }
      if (!(item.attributes().get(PACKAGE_ATTRIBUTES) instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> descriptor = new LinkedHashMap<>();
      raw.forEach((key, value) -> descriptor.put(String.valueOf(key), value));
      Map<String, Object> metadata = new LinkedHashMap<>();
      if (descriptor.get("metadata") instanceof Map<?, ?> rawMetadata) {
        rawMetadata.forEach((key, value) -> metadata.put(String.valueOf(key), value));
      }
      Instant now = item.createdAt() == null ? Instant.now() : item.createdAt();
      staged.add(new CondaRegistryDao.PackageRecord(
          null,
          runtime.id(),
          itemPath.channel(),
          itemPath.subdir(),
          String.valueOf(descriptor.get("filename")),
          String.valueOf(descriptor.get("name")),
          String.valueOf(descriptor.get("version")),
          String.valueOf(descriptor.get("build")),
          descriptor.get("buildNumber") instanceof Number number ? number.longValue() : 0L,
          String.valueOf(descriptor.get("archiveFormat")),
          metadata,
          String.valueOf(descriptor.get("sha256")),
          String.valueOf(descriptor.get("md5")),
          String.valueOf(descriptor.get("sha256")),
          descriptor.get("size") instanceof Number number ? number.longValue() : 0L,
          null,
          null,
          CondaRegistryDao.SOURCE_HOSTED,
          0,
          now,
          now));
    }
    if (staged.isEmpty() && deleted.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(condaHosted.stagedRepodata(runtime, path, staged, deleted, headOnly));
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return condaHosted.delete(runtime, path).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> condaHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}
