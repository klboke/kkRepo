package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPath;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Ansible Galaxy collection artifact publication. */
@Component
final class AnsibleStagingContentAdapter extends PathReplayStagingAdapter {
  private static final String COLLECTION_ATTRIBUTES = "ansibleCollection";

  private final AnsibleGalaxyPathParser parser = new AnsibleGalaxyPathParser();
  private final AnsibleGalaxyService ansibleHosted;
  private final AnsibleCollectionArchiveInspector inspector;
  private final StagingDao staging;

  AnsibleStagingContentAdapter(
      RawHostedService hosted,
      AnsibleGalaxyService ansibleHosted,
      AnsibleCollectionArchiveInspector inspector,
      StagingDao staging) {
    super(hosted);
    this.ansibleHosted = ansibleHosted;
    this.inspector = inspector;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.ANSIBLEGALAXY;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      AnsibleGalaxyPath path = parser.parse(rawPath);
      return switch (path.kind()) {
        case ARTIFACT, VERSION_LIST, VERSION_DETAIL -> Optional.of(path.rawPath());
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
    AnsibleGalaxyPath path;
    try {
      path = parser.parse(requestedPath);
    } catch (IllegalArgumentException invalid) {
      throw new MavenExceptions.BadRequestException("Invalid Ansible Galaxy staging path", invalid);
    }
    if (path.kind() != AnsibleGalaxyPath.Kind.ARTIFACT) {
      throw new MavenExceptions.BadRequestException(
          "Ansible Galaxy staging only accepts collection artifacts");
    }
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspector.inspect(body);
    try {
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path.rawPath(), inspected.file(), contentType, Map.of(), actor, ip);
      Map<String, Object> descriptor = new LinkedHashMap<>();
      descriptor.put("namespace", inspected.namespace());
      descriptor.put("name", inspected.name());
      descriptor.put("version", inspected.version());
      descriptor.put("filename", inspected.filename());
      descriptor.put("sha256", inspected.sha256());
      descriptor.put("size", inspected.size());
      descriptor.put("metadata", inspected.metadata());
      descriptor.put("dependencies", inspected.dependencies());
      if (inspected.requiresAnsible() != null) {
        descriptor.put("requiresAnsible", inspected.requiresAnsible());
      }
      descriptor.put("publishedAt", Instant.now().toString());
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put(COLLECTION_ATTRIBUTES, descriptor);
      return new StagedUpload(blob, path.rawPath(), contentType, attributes);
    } finally {
      AnsibleCollectionArchiveInspector.delete(inspected.file());
    }
  }

  /**
   * Serves the collection version list and version detail from live versions plus staged
   * collections, so `ansible-galaxy collection install` resolves a staged release.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    AnsibleGalaxyPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (repositoryBaseUrl == null
        || path.namespace() == null || path.name() == null
        || (path.kind() != AnsibleGalaxyPath.Kind.VERSION_LIST
            && path.kind() != AnsibleGalaxyPath.Kind.VERSION_DETAIL)) {
      return Optional.empty();
    }
    List<String> versions = new ArrayList<>();
    Map<String, Object> detail = null;
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id()
          || item.format() != RepositoryFormat.ANSIBLEGALAXY) {
        continue;
      }
      Map<String, Object> descriptor = mapValue(item.attributes().get(COLLECTION_ATTRIBUTES));
      if (!path.namespace().equals(String.valueOf(descriptor.get("namespace")))
          || !path.name().equals(String.valueOf(descriptor.get("name")))) {
        continue;
      }
      Object version = descriptor.get("version");
      if (version == null) continue;
      versions.add(String.valueOf(version));
      if (path.kind() == AnsibleGalaxyPath.Kind.VERSION_DETAIL
          && path.version() != null && path.version().equals(String.valueOf(version))) {
        detail = descriptor;
      }
    }
    if (path.kind() == AnsibleGalaxyPath.Kind.VERSION_DETAIL) {
      if (detail == null) {
        return Optional.empty();
      }
      return Optional.of(ansibleHosted.stagedVersionDetail(
          runtime, path.namespace(), path.name(), path.version(), repositoryBaseUrl,
          String.valueOf(detail.get("filename")),
          String.valueOf(detail.get("sha256")),
          detail.get("size") instanceof Number number ? number.longValue() : 0L,
          mapValue(detail.get("metadata")),
          mapValue(detail.get("dependencies")),
          detail.get("requiresAnsible") == null
              ? null : String.valueOf(detail.get("requiresAnsible")),
          instant(detail.get("publishedAt")),
          headOnly));
    }
    if (versions.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(ansibleHosted.stagedVersionList(
        runtime, path.namespace(), path.name(), repositoryBaseUrl, versions, 100, 0, headOnly));
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> ansibleHosted.putArtifact(
        runtime, item.logicalPath(), body, actor, ip));
  }

  private static Map<String, Object> mapValue(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, entry) -> result.put(String.valueOf(key), entry));
    return result;
  }

  private static Instant instant(Object value) {
    try {
      return value == null ? null : Instant.parse(String.valueOf(value));
    } catch (java.time.format.DateTimeParseException invalid) {
      return null;
    }
  }
}
