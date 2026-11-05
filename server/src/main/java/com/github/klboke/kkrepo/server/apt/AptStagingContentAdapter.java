package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.apt.AptPath;
import com.github.klboke.kkrepo.protocol.apt.AptPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
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

/** Staged Debian package publication replayed through the normal APT writer. */
@Component
final class AptStagingContentAdapter extends PathReplayStagingAdapter {
  private static final String PACKAGE_ATTRIBUTES = "aptPackage";

  private final AptPathParser parser = new AptPathParser();
  private final AptService aptHosted;
  private final AptDebPackageInspector inspector;
  private final StagingDao staging;

  AptStagingContentAdapter(
      RawHostedService hosted,
      AptService aptHosted,
      AptDebPackageInspector inspector,
      StagingDao staging) {
    super(hosted);
    this.aptHosted = aptHosted;
    this.inspector = inspector;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.APT;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      AptPath path = parser.parse(rawPath);
      return switch (path.kind()) {
        case PACKAGE, PACKAGES, RELEASE, IN_RELEASE, RELEASE_SIGNATURE ->
            Optional.of(path.normalized());
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
      java.io.InputStream body,
      String contentType,
      String actor,
      String ip) {
    AptPath path = parser.parse(requestedPath);
    if (path.kind() != AptPath.Kind.PACKAGE) {
      return super.stage(runtime, requestedPath, body, contentType, actor, ip);
    }
    try (AptDebPackageInspector.InspectedPackage inspected = inspector.inspect(body, path.filename())) {
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path.normalized(), inspected.file(), contentType, Map.of(), actor, ip);
      Map<String, Object> fields = new LinkedHashMap<>();
      inspected.control().fields().forEach(fields::put);
      Map<String, Object> descriptor = new LinkedHashMap<>();
      descriptor.put("filename", inspected.filename());
      descriptor.put("packageName", inspected.control().packageName());
      descriptor.put("version", inspected.control().version());
      descriptor.put("architecture", inspected.control().architecture());
      descriptor.put("sourcePackage", inspected.control().sourcePackageName());
      descriptor.put("fields", fields);
      descriptor.put("md5", inspected.md5());
      descriptor.put("sha1", inspected.sha1());
      descriptor.put("sha256", inspected.sha256());
      descriptor.put("size", inspected.size());
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put(PACKAGE_ATTRIBUTES, descriptor);
      return new StagedUpload(blob, path.normalized(), contentType, attributes);
    }
  }

  /**
   * Serves Packages (plus gz/xz/bz2), Release, InRelease and Release.gpg from live packages plus
   * staged publications and deletions, so apt can install a staged package before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    AptPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.kind() != AptPath.Kind.PACKAGES
        && path.kind() != AptPath.Kind.RELEASE
        && path.kind() != AptPath.Kind.IN_RELEASE
        && path.kind() != AptPath.Kind.RELEASE_SIGNATURE) {
      return Optional.empty();
    }
    List<AptRegistryDao.PackageRecord> staged = new ArrayList<>();
    Set<String> deleted = new LinkedHashSet<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.APT) {
        continue;
      }
      AptPath itemPath = parser.parse(item.logicalPath());
      if (itemPath.kind() != AptPath.Kind.PACKAGE) {
        continue;
      }
      if (StagingDao.ITEM_DELETE.equals(item.action())) {
        deleted.add(item.logicalPath());
        continue;
      }
      if (!(item.attributes().get(PACKAGE_ATTRIBUTES) instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> descriptor = new LinkedHashMap<>();
      raw.forEach((key, value) -> descriptor.put(String.valueOf(key), value));
      Map<String, Object> fields = new LinkedHashMap<>();
      if (descriptor.get("fields") instanceof Map<?, ?> rawFields) {
        rawFields.forEach((key, value) -> fields.put(String.valueOf(key), value));
      }
      Instant now = item.createdAt() == null ? Instant.now() : item.createdAt();
      staged.add(new AptRegistryDao.PackageRecord(
          null,
          runtime.id(),
          path.distribution(),
          path.component(),
          String.valueOf(descriptor.get("architecture")),
          String.valueOf(descriptor.get("packageName")),
          String.valueOf(descriptor.get("version")),
          descriptor.get("sourcePackage") == null
              ? null : String.valueOf(descriptor.get("sourcePackage")),
          String.valueOf(descriptor.get("filename")),
          item.logicalPath(),
          fields,
          String.valueOf(descriptor.get("md5")),
          String.valueOf(descriptor.get("sha1")),
          String.valueOf(descriptor.get("sha256")),
          descriptor.get("size") instanceof Number number ? number.longValue() : 0L,
          null,
          null,
          AptRegistryDao.SOURCE_HOSTED,
          0,
          now,
          now,
          now));
    }
    if (staged.isEmpty() && deleted.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(aptHosted.stagedMetadata(runtime, path, staged, deleted, headOnly));
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return aptHosted.delete(runtime, path, "staging promotion", false).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> aptHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}
