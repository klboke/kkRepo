package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.helm.HelmChartMetadata;
import com.github.klboke.kkrepo.protocol.helm.HelmChartPackageParser;
import com.github.klboke.kkrepo.protocol.helm.HelmIndex;
import com.github.klboke.kkrepo.server.blob.BufferedUpload;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Helm chart publication replayed through the normal Helm writer. */
@Component
final class HelmStagingContentAdapter implements StagingContentAdapter {
  private final HelmChartPackageParser parser = new HelmChartPackageParser();
  private final RawHostedService hosted;
  private final HelmHostedService helmHosted;
  private final StagingDao staging;

  HelmStagingContentAdapter(RawHostedService hosted, HelmHostedService helmHosted) {
    this(hosted, helmHosted, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  HelmStagingContentAdapter(
      RawHostedService hosted, HelmHostedService helmHosted, StagingDao staging) {
    this.hosted = hosted;
    this.helmHosted = helmHosted;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.HELM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || !path.endsWith(".tgz") || path.contains("..")) {
      return Optional.empty();
    }
    return Optional.of(path);
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
        .orElseThrow(() -> new IllegalArgumentException(
            "Staged Helm writes require a chart .tgz path"));
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "helm-staging-", ".tgz");
      BufferedUpload.Digests digests = BufferedUpload.copy(
          body, temp, BufferedUpload.Checksums.WITH_SHA512);
      HelmChartMetadata chart = parser.parse(Files.newInputStream(temp));
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path, temp, contentType, Map.of(), actor, ip);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("helmName", chart.name());
      attributes.put("helmVersion", chart.version());
      attributes.put("helmApiVersion", chart.apiVersion());
      attributes.put("helmDescription", chart.description());
      attributes.put("helmAppVersion", chart.appVersion());
      attributes.put("helmIcon", chart.icon());
      attributes.put("helmSources", chart.sources());
      attributes.put("helmMaintainers", chart.maintainers());
      attributes.put("helmDigest", blob.sha256());
      attributes.put("helmCreated", Instant.now().toString());
      attributes.put("helmUrls", List.of(path));
      return new StagedUpload(blob, path, contentType, attributes);
    } catch (IOException error) {
      throw new IllegalArgumentException("Unable to read Helm chart", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    if (staging == null) return Optional.empty();
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (!path.equals("index.yaml")) return Optional.empty();
    List<HelmIndex.ChartRecord> charts = new ArrayList<>();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id()
          || item.format() != RepositoryFormat.HELM
          || item.action().equals(StagingDao.ITEM_DELETE)) {
        continue;
      }
      Object name = item.attributes().get("helmName");
      Object version = item.attributes().get("helmVersion");
      if (name == null || version == null) continue;
      charts.add(new HelmIndex.ChartRecord(
          String.valueOf(name),
          String.valueOf(version),
          string(item.attributes().get("helmApiVersion")),
          string(item.attributes().get("helmDescription")),
          string(item.attributes().get("helmAppVersion")),
          string(item.attributes().get("helmIcon")),
          Instant.parse(String.valueOf(item.attributes().getOrDefault(
              "helmCreated", Instant.now().toString()))),
          string(item.attributes().get("helmDigest")),
          strings(item.attributes().get("helmUrls")),
          strings(item.attributes().get("helmSources")),
          maintainers(item.attributes().get("helmMaintainers"))));
    }
    if (charts.isEmpty()) return Optional.empty();
    byte[] staged = HelmIndex.buildHosted(charts, Instant.now());
    byte[] live = null;
    try {
      MavenResponse index = helmHosted.get(runtime, "index.yaml", false);
      if (index.body() != null) live = index.body().readAllBytes();
    } catch (MavenExceptions.MavenNotFoundException | IOException ignored) {
      // no live index yet
    }
    byte[] body = live == null
        ? staged : HelmIndex.mergeGroupIndexes(List.of(live, staged), Instant.now());
    String etag = sha1(body);
    if (headOnly) {
      return Optional.of(MavenResponse.noBody(
          200, body.length, HelmIndex.CONTENT_TYPE, etag, Instant.now()));
    }
    return Optional.of(MavenResponse.ok(
        new ByteArrayInputStream(body), body.length, HelmIndex.CONTENT_TYPE, etag, Instant.now()));
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, String>> maintainers(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(Map.class::isInstance)
        .map(item -> (Map<String, String>) item)
        .toList();
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
    return helmHosted.delete(runtime, path).status() != 404;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    try (InputStream body = source.open()) {
      helmHosted.put(
          runtime, item.logicalPath(), body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
