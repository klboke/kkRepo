package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import com.github.klboke.kkrepo.server.proxy.ProxyRequestAudit;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OhpmProxyService {
  private static final String METADATA = "ohpmProxyMetadata";
  private static final String SOURCE = "ohpmProxySource";
  private static final String MISSING = "ohpmProxyMissing";
  private static final String NEGATIVE = "ohpm-proxy-negative";
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final AssetDao assets;
  private final RawHostedService content;
  private final BlobStorageRegistry storages;
  private final ArtifactDownloadPolicy downloads;
  private final HttpRemoteFetcher fetcher;
  private final ProxyStateDao proxyState;
  private final NexusLikeCacheController cache;
  private final SharedCache shared;
  private final TransientTransactionRetry transactions;
  private final ObjectMapper json;
  private final long maxFileBytes;
  private final Duration negativeTtl;

  public OhpmProxyService(AssetDao assets, RawHostedService content, BlobStorageRegistry storages,
      ArtifactDownloadPolicy downloads, HttpRemoteFetcher fetcher, ProxyStateDao proxyState,
      NexusLikeCacheController cache, SharedCache shared, TransientTransactionRetry transactions,
      ObjectMapper json, @Value("${kkrepo.ohpm.max-file-bytes:1073741824}") long maxFileBytes,
      @Value("${kkrepo.proxy.negative-cache.enabled:true}") boolean negativeEnabled,
      @Value("${kkrepo.proxy.negative-cache.ttl-minutes:5}") long negativeMinutes) {
    if (maxFileBytes < 1) throw new IllegalArgumentException("Invalid OHPM upload size limit");
    this.assets = assets;
    this.content = content;
    this.storages = storages;
    this.downloads = downloads;
    this.fetcher = fetcher;
    this.proxyState = proxyState;
    this.cache = cache;
    this.shared = shared;
    this.transactions = transactions;
    this.json = json;
    this.maxFileBytes = maxFileBytes;
    this.negativeTtl = Duration.ofMinutes(negativeEnabled ? Math.max(0, negativeMinutes) : 0);
  }

  public MavenResponse metadata(RepositoryRuntime runtime, OhpmPackageId packageId,
      String repositoryBaseUrl, boolean head) throws IOException {
    MetadataEntry entry = metadata(runtime, packageId);
    byte[] bytes = json.writeValueAsBytes(entry.value().rewritten(repositoryBaseUrl));
    String etag = hash(new String(bytes, StandardCharsets.UTF_8));
    return (head ? MavenResponse.noBody(200, bytes.length, "application/json", etag, entry.modified())
        : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", etag, entry.modified()))
        .withHeader("Cache-Control", "private, no-cache");
  }

  public MavenResponse download(RepositoryRuntime runtime, OhpmPackageId packageId, String path, boolean head)
      throws IOException {
    MetadataEntry metadata = metadata(runtime, packageId);
    var download = metadata.value().download(path).orElseThrow(() -> error(404, "OHPM file does not exist"));
    var request = remote(runtime, download.remoteUrl(), HttpRemoteFetcher.TimeoutProfile.CONTENT);
    String source = source(runtime);
    String identity = source + ":" + hash(download.remoteUrl()) + ":" + download.integrity().value();
    String token = cache.currentDurableToken(runtime.id(), NexusCacheType.CONTENT);
    String key = key(runtime, path, identity, token);
    AssetRecord before = assets.findAssetByPath(runtime.id(), path).orElse(null);
    Optional<ContentEntry> cached = content(before, identity, download);
    if (cached.isPresent() && fresh(runtime, cached.get().asset(), NexusCacheType.CONTENT,
        runtime.contentMaxAgeMinutesOrDefault())) return serve(runtime, cached.get(), head);
    negative(key);
    if (proxyState.isBlocked(runtime.id(), Instant.now())) {
      return serve(runtime, cached.orElseThrow(() -> error(502, "OHPM upstream temporarily blocked")), head);
    }
    if (cached.isPresent()) request = conditional(request, cached.get().asset());
    try (var result = fetcher.fetch(request)) {
      if (result.status() == 304 && cached.isPresent()) {
        touch(runtime, cached.get().asset(), NexusCacheType.CONTENT, token);
        success(runtime, key);
        return serve(runtime, cached.get(), head);
      }
      if (result.status() == 200) {
        Path file = staged(result.body(), download);
        try {
          ContentEntry stored = transactions.execute("Cache OHPM file " + path, () -> {
            AssetRecord root = assets.findAssetByPathForUpdate(runtime.id(), packageId.id())
                .orElseThrow(() -> error(502, "OHPM metadata changed during download"));
            MetadataEntry current = compatible(root, source).orElseThrow(
                () -> error(502, "OHPM upstream configuration changed during download"));
            if (current.value().download(path).filter(download::equals).isEmpty()
                || !token.equals(cache.currentDurableToken(runtime.id(), NexusCacheType.CONTENT))) {
              throw error(502, "OHPM metadata or cache policy changed during download");
            }
            AssetRecord currentFile = assets.findAssetByPathForUpdate(runtime.id(), path).orElse(null);
            if (!unchanged(before, currentFile)) {
              return content(currentFile, identity, download)
                  .orElseThrow(() -> error(502, "OHPM cached content changed during download"));
            }
            var manifest = metadata.value().rewritten("").path("versions").path(download.version());
            ComponentRecord component = new ComponentRecord(null, runtime.id(), RepositoryFormat.OHPM,
                packageId.scope(), packageId.name(), download.version(), "ohpm-package",
                PersistenceHashes.componentCoordinateHash(packageId.scope(), packageId.name(), download.version()),
                json.convertValue(manifest, MAP), Instant.now());
            content.putInternalWithComponentFileAtBrowsePath(runtime, path, file, "application/octet-stream",
                Map.of(), "proxy", ProxyRequestAudit.currentClientIp(), component,
                packageId.id() + "/" + download.version() + "/" + packageId.name() + "-"
                    + download.version() + (download.hsp() ? ".hsp" : ".har"));
            AssetRecord asset = assets.findAssetByPathForUpdate(runtime.id(), path).orElseThrow();
            var attrs = attributes(result, identity, NexusCacheType.CONTENT, token, Map.of());
            assets.touchAssetLastUpdatedAndAttributes(asset.id(), Instant.now(), attrs);
            return new ContentEntry(asset, assets.findBlobById(asset.assetBlobId()).orElseThrow());
          });
          success(runtime, key);
          return serve(runtime, stored, head);
        } finally {
          TempBlobFiles.deleteQuietly(file);
        }
      }
      rejectNegative(runtime, path, before, key, result.status());
      if (result.status() >= 500) {
        failure(runtime, result.status());
        if (cached.isPresent()) return serve(runtime, cached.get(), head);
      }
      throw error(502, "OHPM upstream download failed");
    } catch (SecurityValidationException denied) {
      throw error(502, "OHPM upstream URL is not allowed");
    } catch (IOException | UpstreamBodyReadException unavailable) {
      failure(runtime, 0);
      if (cached.isPresent()) return serve(runtime, cached.get(), head);
      throw error(502, "OHPM upstream download unavailable");
    }
  }

  private MetadataEntry metadata(RepositoryRuntime runtime, OhpmPackageId packageId) throws IOException {
    requireProxy(runtime);
    String source = source(runtime);
    String token = cache.currentDurableToken(runtime.id(), NexusCacheType.METADATA);
    String key = key(runtime, packageId.id(), source, token);
    AssetRecord before = assets.findAssetByPath(runtime.id(), packageId.id()).orElse(null);
    Optional<MetadataEntry> cached = compatible(before, source);
    if (cached.isPresent() && fresh(runtime, before, NexusCacheType.METADATA,
        runtime.metadataMaxAgeMinutesOrDefault())) return cached.get();
    negative(key);
    if (proxyState.isBlocked(runtime.id(), Instant.now())) {
      return cached.orElseThrow(() -> error(502, "OHPM upstream temporarily blocked"));
    }
    String url = RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), packageId.id());
    var request = remote(runtime, url, HttpRemoteFetcher.TimeoutProfile.METADATA);
    if (cached.isPresent()) request = conditional(request, before);
    try (var result = fetcher.fetch(request)) {
      OhpmPackageMetadata value;
      if (result.status() == 304 && cached.isPresent()) value = cached.get().value();
      else if (result.status() == 200) {
        try {
          value = OhpmPackageMetadata.read(packageId, UpstreamBodyReadException.wrap(result.body()));
          for (var download : value.downloads()) {
            remote(runtime, download.remoteUrl(), HttpRemoteFetcher.TimeoutProfile.CONTENT);
          }
        } catch (IllegalArgumentException | IOException invalid) {
          throw error(502, "Invalid OHPM upstream metadata");
        }
      } else {
        rejectNegative(runtime, packageId.id(), before, key, result.status());
        if (result.status() >= 500) {
          failure(runtime, result.status());
          if (cached.isPresent()) return cached.get();
        }
        throw error(502, "OHPM upstream metadata failed");
      }
      MetadataEntry stored = transactions.execute("Cache OHPM metadata " + packageId.id(), () -> {
        Instant now = Instant.now();
        assets.tryInsertAsset(new AssetRecord(null, runtime.id(), null, null, RepositoryFormat.OHPM,
            packageId.id(), PersistenceHashes.pathHash(packageId.id()), packageId.name(),
            "ohpm-proxy-metadata", "application/json", null, null, now, Map.of()));
        AssetRecord current = assets.findAssetByPathForUpdate(runtime.id(), packageId.id()).orElseThrow();
        if (!unchanged(before, current)) {
          return compatible(current, source).orElse(new MetadataEntry(value, now));
        }
        if (!token.equals(cache.currentDurableToken(runtime.id(), NexusCacheType.METADATA))) {
          return new MetadataEntry(value, now);
        }
        Map<String, Object> previous = cached.isPresent() ? before.attributes() : Map.of();
        Map<String, Object> attrs = attributes(result, source, NexusCacheType.METADATA, token, previous);
        attrs.put(METADATA, json.convertValue(value.original(), MAP));
        assets.touchAssetLastUpdatedAndAttributes(current.id(), now, attrs);
        return new MetadataEntry(value, now);
      });
      success(runtime, key);
      return stored;
    } catch (SecurityValidationException denied) {
      throw error(502, "OHPM upstream URL is not allowed");
    } catch (IOException | UpstreamBodyReadException unavailable) {
      failure(runtime, 0);
      return cached.orElseThrow(() -> error(502, "OHPM upstream metadata unavailable"));
    }
  }

  private Optional<MetadataEntry> compatible(AssetRecord asset, String source) {
    if (asset == null || asset.format() != RepositoryFormat.OHPM
        || !source.equals(asset.attributes().get(SOURCE)) || !asset.attributes().containsKey(METADATA)) {
      return Optional.empty();
    }
    return Optional.of(new MetadataEntry(OhpmPackageMetadata.parse(OhpmPackageId.parse(asset.path()),
        json.valueToTree(asset.attributes().get(METADATA))), asset.lastUpdatedAt()));
  }

  private Optional<ContentEntry> content(AssetRecord before, String identity,
      OhpmPackageMetadata.Download download) {
    return Optional.ofNullable(before)
        .filter(asset -> asset.format() == RepositoryFormat.OHPM && asset.assetBlobId() != null
            && identity.equals(asset.attributes().get(SOURCE)) && !asset.attributes().containsKey(MISSING))
        .flatMap(asset -> assets.findBlobById(asset.assetBlobId()).map(blob -> new ContentEntry(asset, blob)))
        .filter(entry -> HexFormat.of().formatHex(Base64.getDecoder().decode(download.integrity().value().substring(7)))
            .equals(entry.blob().attributes().get("sha512")));
  }

  private MavenResponse serve(RepositoryRuntime runtime, ContentEntry entry, boolean head) {
    AssetBlobRecord blob = entry.blob();
    downloads.beforeReadFromRepository(entry.asset().id(), blob.id(), runtime.id());
    var reference = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    return OhpmContentIntegrity.attach((head ? MavenResponse.noBody(200, blob.size(), "application/octet-stream",
        blob.sha256(), entry.asset().lastUpdatedAt())
        : MavenResponse.ok(() -> storages.forBlobStoreId(blob.blobStoreId()).get(reference)
            .orElseThrow(() -> error(502, "OHPM cached content is unavailable")),
            blob.size(), "application/octet-stream", blob.sha256(), entry.asset().lastUpdatedAt()))
        .withHeader("Cache-Control", "private, no-cache"), blob.attributes().get("sha512"));
  }

  private boolean fresh(RepositoryRuntime runtime, AssetRecord asset, NexusCacheType type, int minutes) {
    return !cache.isDurablyStale(runtime.id(), type,
        NexusLikeCacheInfo.fromAttributes(asset.attributes()).orElse(null), minutes, Instant.now());
  }

  private void touch(RepositoryRuntime runtime, AssetRecord expected, NexusCacheType type, String token) {
    transactions.execute("Revalidate OHPM cache " + expected.path(), () -> {
      var current = assets.findAssetByPathForUpdate(runtime.id(), expected.path()).orElse(null);
      if (current != null && unchanged(expected, current)
          && token.equals(cache.currentDurableToken(runtime.id(), type))) {
        Instant now = Instant.now();
        assets.touchAssetLastUpdatedAndAttributes(current.id(), now, NexusLikeCacheInfo.applyToAttributes(
            current.attributes(), new NexusLikeCacheInfo(now, token, type)));
      }
      return null;
    });
  }

  private Map<String, Object> attributes(HttpRemoteFetcher.Result result, String source,
      NexusCacheType type, String token, Map<String, Object> previous) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put(SOURCE, source);
    if (result.status() == 304) {
      for (String field : new String[] {"remoteEtag", "remoteLastModified"}) {
        if (previous.containsKey(field)) attrs.put(field, previous.get(field));
      }
    }
    if (result.etag() != null) attrs.put("remoteEtag", result.etag());
    if (result.lastModified() != null) attrs.put("remoteLastModified", result.lastModified().toString());
    return NexusLikeCacheInfo.applyToAttributes(attrs, new NexusLikeCacheInfo(Instant.now(), token, type));
  }

  private static HttpRemoteFetcher.Request conditional(HttpRemoteFetcher.Request request, AssetRecord asset) {
    Object modified = asset.attributes().get("remoteLastModified");
    return request.withConditional((String) asset.attributes().get("remoteEtag"),
        modified == null ? null : Instant.parse(modified.toString()));
  }

  static HttpRemoteFetcher.Request remote(RepositoryRuntime runtime, String url,
      HttpRemoteFetcher.TimeoutProfile profile) {
    try {
      return HttpRemoteFetcher.Request.get(url).withTimeoutProfile(profile).withRepositoryRedirectBoundary(runtime);
    } catch (SecurityValidationException | IllegalArgumentException invalid) {
      throw error(502, "OHPM upstream URL is not allowed");
    }
  }

  private String source(RepositoryRuntime runtime) throws IOException {
    return hash(json.writeValueAsString(Arrays.asList(runtime.proxyRemoteUrl(), runtime.proxyRemoteUsername(),
        runtime.proxyRemotePassword(), runtime.proxyRemoteBearerToken(),
        runtime.ntlmCredentials() == null ? null : runtime.ntlmCredentials().cacheKey(),
        runtime.strictRedirectHosts().stream().sorted().toList(), runtime.contentRedirectPolicy())));
  }

  private Path staged(InputStream body, OhpmPackageMetadata.Download download) {
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-ohpm-proxy-", ".download");
      InputStream input = UpstreamBodyReadException.wrap(body);
      try (var output = Files.newOutputStream(file)) {
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
          if (count > maxFileBytes - size) throw error(502, "OHPM upstream file exceeds size limit");
          output.write(buffer, 0, count);
          size += count;
        }
        if (size == 0) throw error(502, "OHPM upstream file is empty");
      }
      try (InputStream staged = Files.newInputStream(file)) {
        if (!download.integrity().matches(staged)) throw error(502, "OHPM upstream integrity mismatch");
      }
      return file;
    } catch (IOException failure) {
      TempBlobFiles.deleteQuietly(file);
      throw new UncheckedIOException("OHPM cache staging failed", failure);
    } catch (RuntimeException failure) {
      TempBlobFiles.deleteQuietly(file);
      throw failure;
    }
  }

  private void negative(String key) {
    if (negativeTtl.isZero()) return;
    shared.getString(NEGATIVE, key).ifPresent(status -> {
      throw error(Integer.parseInt(status), "OHPM upstream package or file is unavailable");
    });
  }

  private void rejectNegative(RepositoryRuntime runtime, String path, AssetRecord before, String key, int status) {
    if (status == 404 || status == 410 || status == 451) {
      proxyState.recordSuccess(runtime.id(), Instant.now());
      boolean current = transactions.execute("Record OHPM upstream removal " + path, () -> {
        AssetRecord asset = assets.findAssetByPathForUpdate(runtime.id(), path).orElse(null);
        if (asset == null) return before == null;
        if (!unchanged(before, asset)) return false;
        Map<String, Object> attributes = new LinkedHashMap<>(asset.attributes());
        attributes.remove(METADATA);
        attributes.put(MISSING, status);
        assets.touchAssetLastUpdatedAndAttributes(asset.id(), Instant.now(), attributes);
        return true;
      });
      if (current && !negativeTtl.isZero()) shared.putString(NEGATIVE, key, Integer.toString(status), negativeTtl);
      throw error(status, "OHPM upstream package or file is unavailable");
    }
  }

  private void success(RepositoryRuntime runtime, String key) {
    proxyState.recordSuccess(runtime.id(), Instant.now());
    shared.evict(NEGATIVE, key);
  }

  private void failure(RepositoryRuntime runtime, int status) {
    int failures = proxyState.loadState(runtime.id()).map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long seconds = runtime.autoBlockOrDefault() ? BACKOFF_SECONDS[Math.min(failures, BACKOFF_SECONDS.length - 1)] : 0;
    proxyState.recordFailure(runtime.id(), seconds, "OHPM upstream failure: " + status, Instant.now());
  }

  private static boolean unchanged(AssetRecord expected, AssetRecord current) {
    if (current == null) return expected == null;
    return expected == null ? current.attributes().isEmpty()
        : Objects.equals(expected.id(), current.id()) && Objects.equals(expected.assetBlobId(), current.assetBlobId())
            && Objects.equals(expected.lastUpdatedAt(), current.lastUpdatedAt())
            && expected.attributes().equals(current.attributes());
  }

  private static String key(RepositoryRuntime runtime, String path, String source, String token) {
    return runtime.id() + ":" + hash(path + "\n" + source + "\n" + token);
  }

  private static String hash(String value) {
    return HexFormat.of().formatHex(PersistenceHashes.sha256(value));
  }

  private static void requireProxy(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.OHPM || !runtime.isProxy()) {
      throw error(405, "Operation requires an OHPM proxy repository");
    }
    if (!runtime.online()) throw error(503, "Repository is offline");
  }

  private static OhpmException error(int status, String message) {
    return new OhpmException(status, message);
  }

  private record MetadataEntry(OhpmPackageMetadata value, Instant modified) {}
  private record ContentEntry(AssetRecord asset, AssetBlobRecord blob) {}
}
