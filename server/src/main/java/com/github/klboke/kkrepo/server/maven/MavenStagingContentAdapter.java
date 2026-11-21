package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.maven.MavenContentType;
import com.github.klboke.kkrepo.protocol.maven.metadata.Maven2Metadata;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenVersionComparator;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Staged Maven publication.
 *
 * <p>Staging only retains the uploaded bytes. Promotion replays the normal Maven writer, which
 * applies layout/version policy and rebuilds generated metadata exactly like a live PUT.
 */
@Component
final class MavenStagingContentAdapter implements StagingContentAdapter {
  private final MavenPathParser parser = new MavenPathParser();
  private final RawHostedService hosted;
  private final MavenHostedService mavenHosted;
  private final StagingDao staging;
  private final ComponentDao components;

  MavenStagingContentAdapter(RawHostedService hosted, MavenHostedService mavenHosted) {
    this(hosted, mavenHosted, null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  MavenStagingContentAdapter(
      RawHostedService hosted,
      MavenHostedService mavenHosted,
      StagingDao staging,
      ComponentDao components) {
    this.hosted = hosted;
    this.mavenHosted = mavenHosted;
    this.staging = staging;
    this.components = components;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.MAVEN2;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      MavenPath path = parser.parsePath(rawPath);
      return Optional.of(path.path());
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
    String path = normalizePath(requestedPath)
        .orElseThrow(() -> new MavenExceptions.BadRequestException("Invalid Maven path"));
    AssetBlobRecord blob = hosted.storeStagedBlob(
        runtime, path, body, contentType, Map.of(), actor, ip);
    Map<String, Object> attributes = new LinkedHashMap<>();
    Coordinates coordinates = parser.parsePath(path).coordinates();
    if (coordinates != null) {
      attributes.put("mavenGroupId", coordinates.groupId());
      attributes.put("mavenArtifactId", coordinates.artifactId());
      attributes.put("mavenVersion", coordinates.version());
      attributes.put("mavenSnapshot", coordinates.snapshot());
    }
    return new StagedUpload(blob, path, contentType, attributes);
  }

  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    if (staging == null || components == null) return Optional.empty();
    MavenPath path = parser.parsePath(rawPath);
    if (!parser.isRepositoryMetadata(path)) return Optional.empty();
    String suffix = "/maven-metadata.xml";
    String base = path.path().substring(0, path.path().length() - suffix.length());
    String[] parts = base.split("/");
    if (parts.length < 2 || parts[parts.length - 1].endsWith("SNAPSHOT")) {
      return Optional.empty();
    }
    String artifactId = parts[parts.length - 1];
    String groupId = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
    List<String> versions = new ArrayList<>();
    for (ComponentRecord component : components.listByGa(runtime.id(), groupId, artifactId)) {
      if (component.version() != null) versions.add(component.version());
    }
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id()
          || item.format() != RepositoryFormat.MAVEN2
          || item.action().equals(StagingDao.ITEM_DELETE)) {
        continue;
      }
      Object group = item.attributes().get("mavenGroupId");
      Object artifact = item.attributes().get("mavenArtifactId");
      Object version = item.attributes().get("mavenVersion");
      if (version != null && groupId.equals(String.valueOf(group))
          && artifactId.equals(String.valueOf(artifact))) {
        versions.add(String.valueOf(version));
      }
    }
    versions = versions.stream().distinct().toList();
    if (versions.isEmpty()) return Optional.empty();
    List<String> sorted = new ArrayList<>(versions);
    sorted.sort(MavenVersionComparator.INSTANCE);
    String latest = sorted.get(sorted.size() - 1);
    String release = null;
    for (int i = sorted.size() - 1; i >= 0; i--) {
      if (!sorted.get(i).endsWith("SNAPSHOT")) {
        release = sorted.get(i);
        break;
      }
    }
    byte[] xml = MavenMetadataXml.write(Maven2Metadata.artifactLevel(
        Instant.now(), groupId, artifactId, latest, release, sorted));
    String etag = sha1(xml);
    if (headOnly) {
      return Optional.of(MavenResponse.noBody(
          200, xml.length, MavenContentType.XML, etag, Instant.now()));
    }
    return Optional.of(MavenResponse.ok(
        new ByteArrayInputStream(xml), xml.length, MavenContentType.XML, etag, Instant.now()));
  }

  private static String sha1(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return mavenHosted.delete(runtime, parser.parsePath(path)).status() != 404;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    try (InputStream body = source.open()) {
      mavenHosted.put(
          runtime, parser.parsePath(item.logicalPath()), body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
