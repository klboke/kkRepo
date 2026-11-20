package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePath;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser;
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

/** Staged Alpine APK publication replayed through the normal Alpine writer. */
@Component
final class AlpineStagingContentAdapter extends PathReplayStagingAdapter {
  private static final String PACKAGE_ATTRIBUTES = "alpinePackage";

  private final AlpinePathParser parser = new AlpinePathParser();
  private final AlpineService alpineHosted;
  private final AlpinePackageInspector inspector;
  private final StagingDao staging;

  AlpineStagingContentAdapter(
      RawHostedService hosted,
      AlpineService alpineHosted,
      AlpinePackageInspector inspector,
      StagingDao staging) {
    super(hosted);
    this.alpineHosted = alpineHosted;
    this.inspector = inspector;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.ALPINE;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      AlpinePath path = parser.parse(rawPath);
      return switch (path.kind()) {
        case PACKAGE, INDEX, V3_INDEX -> Optional.of(path.normalized());
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
    AlpinePath path = parser.parse(requestedPath);
    if (path.kind() != AlpinePath.Kind.PACKAGE) {
      throw new com.github.klboke.kkrepo.server.maven.MavenExceptions.BadRequestException(
          "Alpine staging only accepts package paths");
    }
    try (AlpinePackageInspector.InspectedPackage inspected =
        inspector.inspect(body, path.filename())) {
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path.normalized(), inspected.file(), contentType, Map.of(), actor, ip);
      AlpineIndexRecord index = inspected.info().indexRecord(inspected.identity(), inspected.size());
      Map<String, Object> fields = new LinkedHashMap<>();
      index.fields().forEach(field ->
          fields.put(Character.toString(field.name()), field.value()));
      Map<String, Object> descriptor = new LinkedHashMap<>();
      descriptor.put("name", inspected.info().name());
      descriptor.put("version", inspected.info().version());
      descriptor.put("architecture", inspected.info().architecture());
      descriptor.put("filename", inspected.filename());
      descriptor.put("identity", inspected.identity());
      descriptor.put("dataSha256", inspected.dataSha256());
      descriptor.put("sha256", inspected.sha256());
      descriptor.put("size", inspected.size());
      descriptor.put("fields", fields);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put(PACKAGE_ATTRIBUTES, descriptor);
      return new StagedUpload(blob, path.normalized(), contentType, attributes);
    }
  }

  /**
   * Serves a signed APKINDEX from live packages plus staged publications and deletions so apk
   * clients resolve a staged package before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    AlpinePath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.kind() != AlpinePath.Kind.INDEX && path.kind() != AlpinePath.Kind.V3_INDEX) {
      return Optional.empty();
    }
    if (path.namespace() == null) {
      return Optional.empty();
    }
    List<AlpineRegistryDao.PackageRecord> staged = new ArrayList<>();
    Set<String> deleted = new LinkedHashSet<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.ALPINE) {
        continue;
      }
      AlpinePath itemPath = parser.parse(item.logicalPath());
      if (itemPath.kind() != AlpinePath.Kind.PACKAGE
          || !path.namespace().equals(itemPath.namespace())) {
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
      staged.add(new AlpineRegistryDao.PackageRecord(
          null,
          runtime.id(),
          itemPath.distribution(),
          itemPath.channel(),
          itemPath.repositoryArchitecture(),
          String.valueOf(descriptor.get("name")),
          String.valueOf(descriptor.get("version")),
          String.valueOf(descriptor.get("architecture")),
          String.valueOf(descriptor.get("filename")),
          item.logicalPath(),
          fields,
          String.valueOf(descriptor.get("identity")),
          String.valueOf(descriptor.get("dataSha256")),
          String.valueOf(descriptor.get("sha256")),
          descriptor.get("size") instanceof Number number ? number.longValue() : 0L,
          null,
          null,
          AlpineRegistryDao.SOURCE_HOSTED,
          0,
          now,
          now,
          now));
    }
    if (staged.isEmpty() && deleted.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(alpineHosted.stagedIndex(runtime, path, staged, deleted, headOnly));
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return alpineHosted.delete(runtime, path, "staging promotion", false).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> alpineHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}
