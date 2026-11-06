package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.r.RPath;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.protocol.r.RVersions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Staged R source package publication replayed through the normal R writer. */
@Component
final class RStagingContentAdapter extends PathReplayStagingAdapter {
  private static final String PACKAGE_ATTRIBUTES = "rPackage";

  private final RPathParser parser = new RPathParser();
  private final RService rHosted;
  private final RSourcePackageInspector inspector;
  private final StagingDao staging;

  RStagingContentAdapter(
      RawHostedService hosted,
      RService rHosted,
      RSourcePackageInspector inspector,
      StagingDao staging) {
    super(hosted);
    this.rHosted = rHosted;
    this.inspector = inspector;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.R;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      RPath path = parser.parse(rawPath);
      return switch (path.kind()) {
        case SOURCE_PACKAGE, PACKAGES_GZIP -> Optional.of(path.normalized());
        default -> Optional.empty();
      };
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
    RPath path;
    try {
      path = parser.parse(requestedPath);
    } catch (IllegalArgumentException invalid) {
      throw new MavenExceptions.BadRequestException("Invalid R staging path", invalid);
    }
    if (path.kind() != RPath.Kind.SOURCE_PACKAGE) {
      throw new MavenExceptions.BadRequestException(
          "R staging only accepts src/contrib source packages");
    }
    try (RSourcePackageInspector.InspectedPackage inspected =
        inspector.inspect(body, path.filename())) {
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path.normalized(), inspected.file(), contentType, Map.of(), actor, ip);
      Map<String, Object> descriptor = new LinkedHashMap<>();
      descriptor.put("name", inspected.metadata().packageName());
      descriptor.put("version", inspected.metadata().version());
      descriptor.put("filename", inspected.filename());
      descriptor.put("md5", inspected.md5());
      descriptor.put("sha256", inspected.sha256());
      descriptor.put("size", inspected.size());
      descriptor.put("controlFields", new LinkedHashMap<>(inspected.metadata()
          .indexFields(inspected.md5(), inspected.filename())));
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put(PACKAGE_ATTRIBUTES, descriptor);
      return new StagedUpload(blob, path.normalized(), contentType, attributes);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return rHosted.delete(runtime, path, "staging promotion", false).status() != 404;
  }

  /**
   * Serves {@code src/contrib/PACKAGES.gz} from live rows plus staged source packages so an R
   * client can resolve a staged release before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    RPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.kind() != RPath.Kind.PACKAGES_GZIP) {
      return Optional.empty();
    }
    List<RRegistryDao.PackageRecord> staged = new ArrayList<>();
    Set<String> deletedPaths = new LinkedHashSet<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.R) {
        continue;
      }
      if (StagingDao.ITEM_DELETE.equals(item.action())) {
        deletedPaths.add(item.logicalPath());
        continue;
      }
      Map<String, Object> descriptor = mapValue(item.attributes().get(PACKAGE_ATTRIBUTES));
      String name = text(descriptor.get("name"));
      String version = text(descriptor.get("version"));
      String filename = text(descriptor.get("filename"));
      if (name == null || version == null || filename == null) {
        continue;
      }
      String md5 = text(descriptor.get("md5"));
      String sha256 = text(descriptor.get("sha256"));
      long size = descriptor.get("size") instanceof Number number ? number.longValue() : 0L;
      Instant now = item.createdAt() == null ? Instant.now() : item.createdAt();
      staged.add(new RRegistryDao.PackageRecord(
          null,
          runtime.id(),
          RService.SOURCE_NAMESPACE,
          RService.COMPONENT,
          RService.ARCHITECTURE,
          name,
          version,
          RVersions.orderKey(version),
          RService.ARCHITECTURE,
          filename,
          item.logicalPath(),
          mapValue(descriptor.get("controlFields")),
          md5,
          sha256,
          sha256,
          size,
          null,
          null,
          RRegistryDao.SOURCE_HOSTED,
          0,
          now,
          now,
          now));
    }
    if (staged.isEmpty() && deletedPaths.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(rHosted.stagedPackagesIndex(runtime, path, staged, deletedPaths, headOnly));
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> rHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }

  private static Map<String, Object> mapValue(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, entry) -> result.put(String.valueOf(key), entry));
    return result;
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
