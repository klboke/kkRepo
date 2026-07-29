package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestAccept;
import com.github.klboke.kkrepo.protocol.docker.DockerPathParser;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DockerProxyService {
  private final DockerBlobStore blobStore;
  private final DockerManifestStore manifestStore;
  private final DockerRemoteRegistryClient remoteClient;
  private final ProxyNegativeCache negativeCache;
  private final DockerMetrics metrics;

  public DockerProxyService(
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      DockerRemoteRegistryClient remoteClient) {
    this(blobStore, manifestStore, remoteClient, null, (DockerMetrics) null);
  }

  public DockerProxyService(
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      DockerRemoteRegistryClient remoteClient,
      ProxyNegativeCache negativeCache) {
    this(blobStore, manifestStore, remoteClient, negativeCache, (DockerMetrics) null);
  }

  @Autowired
  public DockerProxyService(
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      DockerRemoteRegistryClient remoteClient,
      ProxyNegativeCache negativeCache,
      ObjectProvider<DockerMetrics> metricsProvider) {
    this(blobStore, manifestStore, remoteClient, negativeCache,
        metricsProvider == null ? null : metricsProvider.getIfAvailable());
  }

  public DockerProxyService(
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      DockerRemoteRegistryClient remoteClient,
      ProxyNegativeCache negativeCache,
      DockerMetrics metrics) {
    this.blobStore = blobStore;
    this.manifestStore = manifestStore;
    this.remoteClient = remoteClient;
    this.negativeCache = negativeCache;
    this.metrics = metrics;
  }

  public DockerResponse getManifest(RepositoryRuntime runtime, String imageName, String reference, boolean headOnly) {
    return getManifest(runtime, imageName, reference, headOnly, List.of());
  }

  public DockerResponse getManifest(
      RepositoryRuntime runtime,
      String imageName,
      String reference,
      boolean headOnly,
      List<String> acceptHeaders) {
    ensureProxy(runtime);
    DockerManifestStore.StoredManifest cached = null;
    try {
      cached = manifestStore.getManifest(runtime, imageName, reference);
      if (DockerPathParser.isDigestReference(reference) || isFresh(cached.manifest().updatedAt(),
          runtime.metadataMaxAgeMinutesOrDefault(), Instant.now())) {
        recordCache(runtime, "manifest", "hit");
        return serveStored(cached, headOnly, acceptHeaders);
      }
      recordCache(runtime, "manifest", "stale");
    } catch (DockerProtocolException e) {
      if (e.code() != DockerErrorCode.MANIFEST_UNKNOWN) {
        throw e;
      }
      recordCache(runtime, "manifest", "miss");
    }
    String remoteImage = remoteImageName(runtime, imageName);
    String remotePath = remoteImage + "/manifests/" + reference;
    if (isNotFoundCached(runtime, remotePath)) {
      recordCache(runtime, "negative", "hit");
      throw new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, reference);
    }
    try (HttpRemoteFetcher.Result result = remoteClient.get(runtime, remotePath, dockerAccept())) {
      if (result.status() == 404) {
        rememberNotFound(runtime, remotePath);
        recordCache(runtime, "negative", "store_manifest");
        throw new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, reference);
      }
      if (result.status() < 200 || result.status() >= 300) {
        if (cached != null) {
          recordCache(runtime, "manifest", "remote_error_fallback");
          return serveStored(cached, headOnly, acceptHeaders);
        }
        throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "remote returned " + result.status(), 502);
      }
      byte[] body = result.body().readAllBytes();
      var stored = manifestStore.putManifest(
          runtime,
          imageName,
          reference,
          body,
          result.contentType(),
          "proxy",
          runtime.proxyRemoteUrl(),
          false);
      recordCache(runtime, "manifest", "remote_store");
      ensureAccepted(stored.manifest().mediaType(), acceptHeaders);
      manifestStore.beforeRead(stored);
      DockerResponse response = headOnly
          ? DockerResponse.noBody(
              200, body.length, stored.manifest().mediaType(), stored.manifest().updatedAt())
          : DockerResponse.body(200, () -> new java.io.ByteArrayInputStream(body), body.length,
              stored.manifest().mediaType(), stored.manifest().updatedAt());
      return response
          .withContentType(stored.manifest().mediaType())
          .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, stored.manifest().digest());
    } catch (IOException e) {
      if (cached != null) {
        recordCache(runtime, "manifest", "remote_error_fallback");
        return serveStored(cached, headOnly, acceptHeaders);
      }
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "remote fetch failed: " + e.getMessage(), 502);
    }
  }

  public DockerResponse getBlob(RepositoryRuntime runtime, String imageName, DockerDigest digest, boolean headOnly) {
    ensureProxy(runtime);
    try {
      DockerResponse response = blobStore.getBlob(runtime, digest, headOnly);
      manifestStore.beforeBlobRead(runtime, imageName, digest);
      recordCache(runtime, "blob", "hit");
      return response;
    } catch (DockerProtocolException e) {
      if (e.code() != DockerErrorCode.BLOB_UNKNOWN) {
        throw e;
      }
      recordCache(runtime, "blob", "miss");
    }
    String remoteImage = remoteImageName(runtime, imageName);
    DigestedFile remoteBlob = null;
    String remotePath = remoteImage + "/blobs/" + digest.value();
    if (isNotFoundCached(runtime, remotePath)) {
      recordCache(runtime, "negative", "hit");
      throw new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, digest.value());
    }
    try (HttpRemoteFetcher.Result result = remoteClient.get(runtime, remotePath, "application/octet-stream")) {
      if (result.status() == 404) {
        rememberNotFound(runtime, remotePath);
        recordCache(runtime, "negative", "store_blob");
        throw new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, digest.value());
      }
      if (result.status() < 200 || result.status() >= 300) {
        throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "remote returned " + result.status(), 502);
      }
      remoteBlob = spoolAndDigest(runtime, result.body());
      if (!digest.isSha256() || !digest.hex().equals(remoteBlob.sha256())) {
        recordDigest(runtime, "proxy_blob", "failure");
        throw new DockerProtocolException(DockerErrorCode.DIGEST_INVALID, "remote blob digest mismatch", 502);
      }
      recordDigest(runtime, "proxy_blob", "success");
      DockerBlobStore.StoredBlob stored;
      try (InputStream in = Files.newInputStream(remoteBlob.path())) {
        stored = blobStore.putBlob(
            runtime,
            digest,
            in,
            remoteBlob.size(),
            result.contentType(),
            "proxy",
            runtime.proxyRemoteUrl());
      }
      recordCache(runtime, "blob", "remote_store");
      DockerResponse response = headOnly
          ? DockerResponse.noBody(
              200,
              stored.blob().size(),
              blobContentType(stored.blob().contentType()),
              stored.asset().lastUpdatedAt())
          : DockerResponse.body(200, () -> blobStore.storage(runtime).get(
              com.github.klboke.kkrepo.server.blob.BlobReferenceCodec.reference(
                  stored.blob().blobRef(), stored.blob().objectKey(), stored.blob().sha256(), stored.blob().size()))
              .orElseThrow(),
              stored.blob().size(),
              blobContentType(stored.blob().contentType()),
              stored.asset().lastUpdatedAt());
      manifestStore.beforeBlobRead(runtime, imageName, digest);
      return response
          .withContentType(blobContentType(stored.blob().contentType()))
          .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, digest.value())
          .withHeader("Accept-Ranges", "bytes");
    } catch (IOException e) {
      throw new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, "remote blob fetch failed: " + e.getMessage(), 502);
    } finally {
      if (remoteBlob != null) {
        TempBlobFiles.deleteQuietly(remoteBlob.path());
      }
    }
  }

  public DockerTagList tags(RepositoryRuntime runtime, String imageName, String last, int limit) {
    ensureProxy(runtime);
    String remoteImage = remoteImageName(runtime, imageName);
    int pageSize = Math.max(1, Math.min(limit, 1000));
    String remotePath = remoteImage + "/tags/list?n=" + (pageSize + 1)
        + (last == null || last.isBlank() ? "" : "&last=" + java.net.URLEncoder.encode(last, java.nio.charset.StandardCharsets.UTF_8));
    String namePath = remoteImage + "/tags/list";
    if (isNotFoundCached(runtime, namePath)) {
      recordCache(runtime, "negative", "hit");
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, imageName);
    }
    try (HttpRemoteFetcher.Result result = remoteClient.get(runtime, remotePath, DockerConstants.MEDIA_TYPE_JSON)) {
      if (result.status() == 404) {
        rememberNotFound(runtime, namePath);
        recordCache(runtime, "negative", "store_tags");
        throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, imageName);
      }
      if (result.status() < 200 || result.status() >= 300) {
        throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "remote returned " + result.status(), 502);
      }
      Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.body(), Map.class);
      Object rawTags = body.get("tags");
      List<String> tags = rawTags instanceof List<?> list
          ? list.stream().filter(item -> item != null).map(Object::toString).toList()
          : List.of();
      boolean hasNext = tags.size() > pageSize;
      if (hasNext) {
        tags = tags.subList(0, pageSize);
      }
      Object rawName = body.get("name");
      recordCache(runtime, "tags", "remote");
      return new DockerTagList(rawName == null ? imageName : rawName.toString(), tags, hasNext);
    } catch (IOException e) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "remote tag fetch failed: " + e.getMessage(), 502);
    }
  }

  public DockerCatalogList catalog(RepositoryRuntime runtime, String last, int limit) {
    ensureProxy(runtime);
    int pageSize = Math.max(1, Math.min(limit, 1000));
    String remotePath = "_catalog?n=" + (pageSize + 1)
        + (last == null || last.isBlank()
            ? ""
            : "&last=" + java.net.URLEncoder.encode(last, java.nio.charset.StandardCharsets.UTF_8));
    try (HttpRemoteFetcher.Result result = remoteClient.get(runtime, remotePath, DockerConstants.MEDIA_TYPE_JSON)) {
      if (result.status() >= 200 && result.status() < 300) {
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.body(), Map.class);
        Object rawRepositories = body.get("repositories");
        List<String> repositories = rawRepositories instanceof List<?> list
            ? list.stream().filter(item -> item != null).map(Object::toString).sorted().toList()
            : List.of();
        if (last != null && !last.isBlank()) {
          repositories = repositories.stream().filter(repository -> repository.compareTo(last) > 0).toList();
        }
        boolean hasNext = repositories.size() > pageSize;
        if (hasNext) {
          repositories = repositories.subList(0, pageSize);
        }
        return new DockerCatalogList(repositories, hasNext);
      }
    } catch (IOException ignored) {
      // Fall back to the shared cache so catalog remains useful when the upstream disables _catalog.
    }
    return manifestStore.listCatalog(runtime, last, limit);
  }

  public Map<String, Object> referrers(
      RepositoryRuntime runtime, String imageName, DockerDigest digest, String artifactType) {
    ensureProxy(runtime);
    String remoteImage = remoteImageName(runtime, imageName);
    String path = remoteImage + "/referrers/" + digest.value()
        + (artifactType == null || artifactType.isBlank()
            ? ""
            : "?artifactType=" + java.net.URLEncoder.encode(
                artifactType, java.nio.charset.StandardCharsets.UTF_8));
    try (HttpRemoteFetcher.Result result = remoteClient.get(runtime, path, DockerConstants.MEDIA_TYPE_OCI_INDEX)) {
      if (result.status() == 404) {
        Map<String, Object> body = Map.of(
            "schemaVersion", 2,
            "mediaType", DockerConstants.MEDIA_TYPE_OCI_INDEX,
            "manifests", List.of());
        recordReferrers(runtime, "not_found", 0);
        return body;
      }
      if (result.status() < 200 || result.status() >= 300) {
        throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "remote returned " + result.status(), 502);
      }
      Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.body(), Map.class);
      recordReferrers(runtime, "remote", countManifests(body));
      return body;
    } catch (IOException e) {
      throw new DockerProtocolException(
          DockerErrorCode.NAME_UNKNOWN, "remote referrers fetch failed: " + e.getMessage(), 502);
    }
  }

  private static String remoteImageName(RepositoryRuntime runtime, String imageName) {
    String remote = runtime.proxyRemoteUrl() == null ? "" : runtime.proxyRemoteUrl().toLowerCase(java.util.Locale.ROOT);
    if ((remote.contains("registry-1.docker.io") || remote.contains("docker.io"))
        && !imageName.contains("/")) {
      return "library/" + imageName;
    }
    return imageName;
  }

  private static String dockerAccept() {
    return String.join(", ",
        DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST,
        DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST_LIST,
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST,
        DockerConstants.MEDIA_TYPE_OCI_INDEX,
        DockerConstants.MEDIA_TYPE_OCI_ARTIFACT);
  }

  private DockerResponse serveStored(
      DockerManifestStore.StoredManifest stored, boolean headOnly, List<String> acceptHeaders) {
    if (acceptHeaders == null || acceptHeaders.isEmpty()) {
      return manifestStore.serveManifest(stored, headOnly);
    }
    return manifestStore.serveManifest(stored, headOnly, acceptHeaders);
  }

  private static void ensureAccepted(String mediaType, List<String> acceptHeaders) {
    if (!DockerManifestAccept.accepts(acceptHeaders, mediaType)) {
      throw new DockerProtocolException(
          DockerErrorCode.MANIFEST_UNKNOWN,
          "manifest media type is not accepted by the client");
    }
  }

  private static boolean isFresh(Instant updatedAt, int ttlMinutes, Instant now) {
    if (updatedAt == null) return false;
    if (ttlMinutes < 0) return true;
    return updatedAt.plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private boolean isNotFoundCached(RepositoryRuntime runtime, String path) {
    return negativeCache != null && negativeCache.isNotFoundCached(runtime, path);
  }

  private void rememberNotFound(RepositoryRuntime runtime, String path) {
    if (negativeCache != null) {
      negativeCache.rememberNotFound(runtime, path);
    }
  }

  private void recordCache(RepositoryRuntime runtime, String cache, String result) {
    if (metrics != null) {
      metrics.cache(cache, runtime, result);
    }
  }

  private void recordDigest(RepositoryRuntime runtime, String target, String outcome) {
    if (metrics != null) {
      metrics.digestVerification(runtime, target, outcome);
    }
  }

  private void recordReferrers(RepositoryRuntime runtime, String outcome, long count) {
    if (metrics != null) {
      metrics.referrers(runtime, outcome, count);
    }
  }

  private static long countManifests(Map<String, Object> body) {
    Object manifests = body == null ? null : body.get("manifests");
    return manifests instanceof List<?> list ? list.size() : 0;
  }

  private DigestedFile spoolAndDigest(RepositoryRuntime runtime, InputStream body) throws IOException {
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(blobStore.storage(runtime), "docker-proxy-blob-", ".blob");
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      long size;
      try (InputStream in = body; var out = Files.newOutputStream(temp)) {
        byte[] buffer = new byte[TempBlobFiles.responseBufferSize()];
        long written = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
          sha256.update(buffer, 0, read);
          out.write(buffer, 0, read);
          written += read;
        }
        size = written;
      }
      return new DigestedFile(temp, HexFormat.of().formatHex(sha256.digest()), size);
    } catch (NoSuchAlgorithmException e) {
      TempBlobFiles.deleteQuietly(temp);
      throw new IllegalStateException(e);
    } catch (IOException | RuntimeException e) {
      TempBlobFiles.deleteQuietly(temp);
      throw e;
    }
  }

  private static void ensureProxy(RepositoryRuntime runtime) {
    if (runtime.type() != RepositoryType.PROXY) {
      throw new DockerProtocolException(DockerErrorCode.UNSUPPORTED, "not a Docker proxy repository", 405);
    }
  }

  private static String blobContentType(String contentType) {
    return contentType == null || contentType.isBlank()
        ? "application/octet-stream"
        : contentType;
  }

  private record DigestedFile(Path path, String sha256, long size) {
  }
}
