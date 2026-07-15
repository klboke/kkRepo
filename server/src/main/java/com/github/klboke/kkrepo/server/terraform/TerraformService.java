package com.github.klboke.kkrepo.server.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.protocol.terraform.TerraformVersions;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/** HashiCorp Registry Protocol implementation shared by hosted, proxy, and group recipes. */
@Service
public class TerraformService {
  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
  private static final String OCTET = MediaType.APPLICATION_OCTET_STREAM_VALUE;
  private static final String PROTOCOLS = "5.0";

  private final ObjectMapper mapper;
  private final TerraformAssetSupport assets;
  private final TerraformArchiveInspector inspector;
  private final TerraformSigningService signing;
  private final TerraformSignatureVerifier signatureVerifier;
  private final TerraformRegistryDao registry;
  private final TerraformPublishLeaseManager leases;
  private final RepositoryRuntimeRegistry runtimes;
  private final RawProxyService proxy;
  private final HttpRemoteFetcher fetcher;
  private final TerraformPathParser paths = new TerraformPathParser();

  public TerraformService(
      ObjectMapper mapper,
      TerraformAssetSupport assets,
      TerraformArchiveInspector inspector,
      TerraformSigningService signing,
      TerraformSignatureVerifier signatureVerifier,
      TerraformRegistryDao registry,
      TerraformPublishLeaseManager leases,
      RepositoryRuntimeRegistry runtimes,
      RawProxyService proxy,
      HttpRemoteFetcher fetcher) {
    this.mapper = mapper;
    this.assets = assets;
    this.inspector = inspector;
    this.signing = signing;
    this.signatureVerifier = signatureVerifier;
    this.registry = registry;
    this.leases = leases;
    this.runtimes = runtimes;
    this.proxy = proxy;
    this.fetcher = fetcher;
  }

  public MavenResponse get(
      RepositoryRuntime runtime, TerraformPath path, String repositoryBaseUrl, boolean headOnly) {
    return get(runtime, path, repositoryBaseUrl, null, headOnly);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      TerraformPath path,
      String repositoryBaseUrl,
      String urlTokenSegment,
      boolean headOnly) {
    return get(runtime, path, new RequestUrls(repositoryBaseUrl, urlTokenSegment), headOnly);
  }

  private MavenResponse get(
      RepositoryRuntime runtime, TerraformPath path, RequestUrls urls, boolean headOnly) {
    if (path.kind() == TerraformPath.Kind.UNKNOWN) throw notFound(path.rawPath());
    return switch (runtime.type()) {
      case HOSTED -> hostedGet(runtime, path, urls, headOnly);
      case PROXY -> proxyGet(runtime, path, urls, headOnly);
      case GROUP -> groupGet(runtime, path, urls, headOnly);
    };
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      TerraformPath path,
      InputStream body,
      String contentType,
      String contentDisposition,
      String actor,
      String ip) {
    return publish(runtime, path, body, contentType, contentDisposition, actor, ip, false);
  }

  /**
   * Replays a validated migration asset while preserving publication leases and coordinate
   * uniqueness. A Nexus repository may be frozen with DENY, so migration alone treats DENY as
   * ALLOW_ONCE instead of requiring an operator to mutate the imported repository configuration.
   */
  MavenResponse putForMigration(
      RepositoryRuntime runtime,
      TerraformPath path,
      InputStream body,
      String contentType,
      String contentDisposition,
      String actor,
      String ip) {
    return publish(runtime, path, body, contentType, contentDisposition, actor, ip, true);
  }

  private MavenResponse publish(
      RepositoryRuntime runtime,
      TerraformPath path,
      InputStream body,
      String contentType,
      String contentDisposition,
      String actor,
      String ip,
      boolean migration) {
    if (!runtime.isHosted()) throw new MavenExceptions.MethodNotAllowed("Terraform group/proxy repositories are read-only");
    return switch (path.kind()) {
      case MODULE_ARCHIVE -> putModule(runtime, path, body, contentType, actor, ip, migration);
      case PROVIDER_DOWNLOAD ->
          putProvider(runtime, path, body, contentType, contentDisposition, actor, ip, migration);
      default -> throw new MavenExceptions.MethodNotAllowed("Unsupported Terraform PUT path: " + path.rawPath());
    };
  }

  private MavenResponse hostedGet(
      RepositoryRuntime runtime, TerraformPath path, RequestUrls urls, boolean headOnly) {
    return switch (path.kind()) {
      case MODULE_VERSIONS -> json(moduleVersions(runtime, path), headOnly);
      case MODULE_DOWNLOAD -> moduleDownload(runtime, path, urls);
      case MODULE_ARCHIVE -> assets.serve(runtime, path.rawPath(), headOnly);
      case PROVIDER_VERSIONS -> json(providerVersions(runtime, path), headOnly);
      case PROVIDER_DOWNLOAD -> providerDownload(runtime, path, urls, headOnly);
      case PROVIDER_ARCHIVE -> servePublishedProviderArchive(runtime, path, headOnly);
      case PROVIDER_SHA256SUMS, PROVIDER_SHA256SUMS_SIGNATURE ->
          servePublishedProviderMetadata(runtime, path, headOnly);
      default -> throw notFound(path.rawPath());
    };
  }

  private Map<String, Object> moduleVersions(RepositoryRuntime runtime, TerraformPath request) {
    String prefix = "v1/modules/" + request.namespace() + "/" + request.name() + "/" + request.system() + "/";
    Set<String> versions = new LinkedHashSet<>();
    for (AssetRecord asset : assets.list(runtime, prefix)) {
      TerraformPath parsed = paths.parse(asset.path());
      if (parsed.kind() == TerraformPath.Kind.MODULE_ARCHIVE) versions.add(parsed.version());
    }
    List<Map<String, Object>> rows = TerraformVersions.descending(versions).stream()
        .map(version -> Map.<String, Object>of("version", version)).toList();
    return Map.of("modules", List.of(Map.of(
        "source", request.namespace() + "/" + request.name() + "/" + request.system(),
        "versions", rows)));
  }

  private MavenResponse moduleDownload(RepositoryRuntime runtime, TerraformPath request, RequestUrls urls) {
    String prefix = "v1/modules/" + request.namespace() + "/" + request.name() + "/"
        + request.system() + "/" + request.version() + "/";
    AssetRecord archive = assets.list(runtime, prefix).stream()
        .filter(asset -> paths.parse(asset.path()).kind() == TerraformPath.Kind.MODULE_ARCHIVE)
        .min(Comparator.comparing(AssetRecord::path))
        .orElseThrow(() -> notFound(prefix));
    return MavenResponse.noBody(204).withHeader("X-Terraform-Get", publicUrl(urls, archive.path()));
  }

  private Map<String, Object> providerVersions(RepositoryRuntime runtime, TerraformPath request) {
    String prefix = "v1/providers/" + request.namespace() + "/" + request.name() + "/";
    Set<String> versions = new LinkedHashSet<>();
    for (AssetRecord asset : assets.list(runtime, prefix)) {
      TerraformPath parsed = paths.parse(asset.path());
      if (parsed.kind() == TerraformPath.Kind.PROVIDER_ARCHIVE
          && registry.findProviderState(runtime.id(), request.namespace(), request.name(), parsed.version()).isPresent()) {
        versions.add(parsed.version());
      }
    }
    List<Map<String, Object>> values = new ArrayList<>();
    for (String version : TerraformVersions.descending(versions)) {
      List<Map<String, Object>> platforms = publishedProviderPlatforms(
              runtime, request.namespace(), request.name(), version).stream()
          .map(row -> Map.<String, Object>of("os", row.os(), "arch", row.arch()))
          .toList();
      if (platforms.isEmpty()) continue;
      values.add(Map.of("version", version, "protocols", List.of(PROTOCOLS), "platforms", platforms));
    }
    return Map.of("versions", values);
  }

  private List<TerraformRegistryDao.ProviderPlatform> publishedProviderPlatforms(
      RepositoryRuntime runtime, String namespace, String name, String version) {
    return registry.listProviderPlatforms(runtime.id(), namespace, name, version).stream()
        .filter(platform -> assets.find(runtime, platform.assetPath()).isPresent())
        .toList();
  }

  private MavenResponse providerDownload(
      RepositoryRuntime runtime, TerraformPath request, RequestUrls urls, boolean headOnly) {
    TerraformRegistryDao.ProviderState state = registry.findProviderState(
            runtime.id(), request.namespace(), request.name(), request.version())
        .orElseThrow(() -> notFound(request.rawPath()));
    TerraformRegistryDao.ProviderPlatform platform = publishedProviderPlatforms(
            runtime, request.namespace(), request.name(), request.version()).stream()
        .filter(row -> row.os().equals(request.os()) && row.arch().equals(request.arch()))
        .findFirst().orElseThrow(() -> notFound(request.rawPath()));
    TerraformRegistryDao.SigningKey key = registry.findSigningKey(runtime.id(), state.signingKeyRevision())
        .orElseThrow(() -> new IllegalStateException("Terraform signing key revision is missing"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("protocols", protocolList(platform.protocols()));
    body.put("os", platform.os());
    body.put("arch", platform.arch());
    body.put("filename", platform.filename());
    body.put("download_url", publicUrl(urls, platform.assetPath()));
    body.put("shasums_url", publicUrl(urls, state.shasumsPath()));
    body.put("shasums_signature_url", publicUrl(urls, state.signaturePath()));
    body.put("shasum", platform.sha256());
    body.put("signing_keys", Map.of("gpg_public_keys", List.of(Map.of(
        "key_id", key.keyId(), "ascii_armor", key.publicKey(), "trust_signature", ""))));
    return json(body, headOnly);
  }

  private MavenResponse servePublishedProviderArchive(
      RepositoryRuntime runtime, TerraformPath path, boolean headOnly) {
    boolean published = publishedProviderPlatforms(
            runtime, path.namespace(), path.name(), path.version()).stream()
        .anyMatch(row -> row.assetPath().equals(path.rawPath()));
    if (!published) throw notFound(path.rawPath());
    return assets.serve(runtime, path.rawPath(), headOnly);
  }

  private MavenResponse servePublishedProviderMetadata(
      RepositoryRuntime runtime, TerraformPath path, boolean headOnly) {
    TerraformRegistryDao.ProviderState state = registry.findProviderState(
            runtime.id(), path.namespace(), path.name(), path.version())
        .orElseThrow(() -> notFound(path.rawPath()));
    String published = path.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS
        ? state.shasumsPath() : state.signaturePath();
    if (!published.equals(path.rawPath())
        && !isStoredProviderMetadataRevision(runtime, path, state)) {
      throw notFound(path.rawPath());
    }
    return assets.serve(runtime, path.rawPath(), headOnly);
  }

  private boolean isStoredProviderMetadataRevision(
      RepositoryRuntime runtime, TerraformPath path, TerraformRegistryDao.ProviderState state) {
    String prefix = "v1/providers/" + path.namespace() + "/" + path.name() + "/"
        + path.version() + "/metadata-r";
    int separator = path.rawPath().indexOf('/', prefix.length());
    if (!path.rawPath().startsWith(prefix) || separator < 0) return false;
    try {
      long revision = Long.parseLong(path.rawPath().substring(prefix.length(), separator));
      return revision > 0 && revision <= state.revision()
          && assets.find(runtime, path.rawPath()).isPresent();
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private MavenResponse putModule(
      RepositoryRuntime runtime, TerraformPath path, InputStream body, String contentType,
      String actor, String ip, boolean migration) {
    // Fail fast before buffering, then repeat this authoritative check while holding the shared
    // coordinate lease so concurrent replicas cannot both publish different filenames.
    enforceModuleWrite(runtime, path, migration);
    Path buffered = inspector.bufferAndInspect(body, path.filename(), true, null);
    String leaseKey = publishLeaseKey(
        "module", runtime.id(), path.namespace(), path.name(), path.system(), path.version());
    try (TerraformPublishLeaseManager.Lease lease = leases.acquire(
        leaseKey, java.time.Duration.ofMinutes(5), java.time.Duration.ofSeconds(30))) {
      enforceModuleWrite(runtime, path, migration);
      try (InputStream in = Files.newInputStream(buffered)) {
        assets.store(runtime, path.rawPath(), in, contentType == null ? OCTET : contentType,
            Map.of(
                "terraformKind", "module-archive",
                "namespace", path.namespace(), "name", path.name(), "system", path.system(),
                "version", path.version(), "sha256Validated", true), actor, ip);
        return MavenResponse.created();
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed storing Terraform module archive", e);
    } finally {
      try { Files.deleteIfExists(buffered); } catch (IOException ignored) {}
    }
  }

  private MavenResponse putProvider(
      RepositoryRuntime runtime, TerraformPath path, InputStream body, String contentType,
      String contentDisposition, String actor, String ip, boolean migration) {
    enforceWrite(runtime, path.rawPath(), migration);
    String filename = filename(contentDisposition);
    String expectedPrefix = "terraform-provider-" + path.name() + "_" + path.version()
        + "_" + path.os() + "_" + path.arch();
    if (!filename.startsWith(expectedPrefix) || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      throw new MavenExceptions.BadRequestException(
          "Provider filename must match " + expectedPrefix + "*.zip");
    }
    // Buffer and inspect before acquiring the fixed-TTL lease. Slow uploads must not consume the
    // lease lifetime and allow another replica to publish the same provider version concurrently.
    Path buffered = inspector.bufferAndInspect(body, filename, false, path.name());
    String leaseKey = publishLeaseKey(
        "provider", runtime.id(), path.namespace(), path.name(), path.version());
    try (TerraformPublishLeaseManager.Lease lease = leases.acquire(
        leaseKey, java.time.Duration.ofMinutes(5), java.time.Duration.ofSeconds(30))) {
      List<TerraformRegistryDao.ProviderPlatform> current = publishedProviderPlatforms(
          runtime, path.namespace(), path.name(), path.version());
      boolean exists = current.stream().anyMatch(row -> row.os().equals(path.os()) && row.arch().equals(path.arch()));
      if (exists) throw new MavenExceptions.WritePolicyDenied("Terraform provider platform already exists");
      long revision = registry.findProviderState(runtime.id(), path.namespace(), path.name(), path.version())
          .map(state -> state.revision() + 1).orElse(1L);
      String assetPath = "v1/providers/" + path.namespace() + "/" + path.name() + "/" + path.version()
          + "/package/" + path.os() + "/" + filename;
      // Always validate and persist the incoming archive. A prior publication attempt may have
      // failed after storing an orphaned STAGING asset but before committing the platform row;
      // reusing that blob would publish stale content instead of the operator's retry.
      try (InputStream in = Files.newInputStream(buffered)) {
        assets.store(runtime, assetPath, in, contentType == null ? OCTET : contentType,
            Map.of(
                "terraformKind", "provider-archive", "namespace", path.namespace(), "type", path.name(),
                "version", path.version(), "os", path.os(), "arch", path.arch(),
                "protocols", List.of(PROTOCOLS), "publishState", "STAGING", "revision", revision), actor, ip);
      }
      AssetRecord stored = assets.find(runtime, assetPath).orElseThrow(() -> notFound(assetPath));
      AssetBlobRecord blob = assets.blob(stored);
      if (blob == null || blob.sha256() == null) throw new IllegalStateException("Provider archive digest is missing");
      TerraformRegistryDao.ProviderPlatform published = new TerraformRegistryDao.ProviderPlatform(
          runtime.id(), path.namespace(), path.name(), path.version(), path.os(), path.arch(), filename,
          assetPath, blob.sha256(), PROTOCOLS, revision, Instant.now());
      List<TerraformRegistryDao.ProviderPlatform> next = new ArrayList<>(current);
      next.add(published);
      next.sort(Comparator.comparing(TerraformRegistryDao.ProviderPlatform::filename));
      byte[] shasums = next.stream().map(row -> row.sha256() + "  " + row.filename() + "\n")
          .collect(java.util.stream.Collectors.joining()).getBytes(StandardCharsets.UTF_8);
      TerraformSigningService.SigningMaterial key = signing.active(runtime);
      byte[] signature = signing.sign(shasums, key);
      String fileBase = "terraform-provider-" + path.name() + "_" + path.version() + "_SHA256SUMS";
      String metadataBase = "v1/providers/" + path.namespace() + "/" + path.name() + "/" + path.version()
          + "/metadata-r" + revision + "/";
      String sumsPath = metadataBase + fileBase;
      String signaturePath = sumsPath + ".sig";
      assets.storeBytes(runtime, sumsPath, shasums, MediaType.TEXT_PLAIN_VALUE,
          Map.of("terraformKind", "provider-shasums", "revision", revision));
      assets.storeBytes(runtime, signaturePath, signature, OCTET,
          Map.of("terraformKind", "provider-signature", "revision", revision, "keyId", key.keyId()));
      registry.publishProvider(published, new TerraformRegistryDao.ProviderState(
          runtime.id(), path.namespace(), path.name(), path.version(), revision,
          sumsPath, signaturePath, key.revision(), Instant.now()));
      return MavenResponse.created();
    } catch (IOException e) {
      throw new IllegalStateException("Failed storing Terraform provider archive", e);
    } finally {
      try { Files.deleteIfExists(buffered); } catch (IOException ignored) {}
    }
  }

  private MavenResponse proxyGet(
      RepositoryRuntime runtime, TerraformPath path, RequestUrls urls, boolean headOnly) {
    return switch (path.kind()) {
      case MODULE_VERSIONS, PROVIDER_VERSIONS -> proxyMetadata(runtime, path, headOnly);
      case MODULE_DOWNLOAD -> proxyModuleDownload(runtime, path, urls);
      case PROVIDER_DOWNLOAD -> proxyProviderDownload(runtime, path, urls, headOnly);
      case MODULE_ARCHIVE, PROVIDER_ARCHIVE, PROVIDER_SHA256SUMS, PROVIDER_SHA256SUMS_SIGNATURE ->
          proxyRoute(runtime, path.rawPath(), headOnly);
      default -> throw notFound(path.rawPath());
    };
  }

  private MavenResponse proxyMetadata(RepositoryRuntime runtime, TerraformPath path, boolean headOnly) {
    String remote = remoteUrl(runtime, path);
    String cachePath = ".terraform/upstream/" + sha256(remote) + ".json";
    MavenResponse cached = proxy.getMetadataFromUrl(runtime, cachePath, remote, false);
    byte[] bytes = responseBytes(cached);
    return bytes(bytes, JSON, headOnly);
  }

  private MavenResponse proxyModuleDownload(RepositoryRuntime runtime, TerraformPath path, RequestUrls urls) {
    String remote = remoteUrl(runtime, path);
    try {
      HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request.get(remote)
          .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA).withRepository(runtime);
      return fetcher.fetchWithBodyRetry(request, path.rawPath(), result -> {
        if (result.status() == 404) throw notFound(path.rawPath());
        if (result.status() < 200 || result.status() >= 300) {
          throw new MavenExceptions.BadUpstreamException("Terraform upstream returned " + result.status());
        }
        String upstream = result.header("X-Terraform-Get");
        if (upstream == null || upstream.isBlank()) {
          throw new MavenExceptions.BadUpstreamException("Terraform upstream omitted X-Terraform-Get");
        }
        String absolute = httpModuleSource(remote, upstream);
        if (absolute == null) {
          return MavenResponse.noBody(204).withHeader("X-Terraform-Get", upstream);
        }
        String filename = safeRemoteFilename(absolute,
            path.name() + "_" + path.version() + ".zip");
        String local = "v1/modules/" + path.namespace() + "/" + path.name() + "/" + path.system()
            + "/" + path.version() + "/" + filename;
        storeRoute(runtime, local, absolute, null);
        return MavenResponse.noBody(204).withHeader("X-Terraform-Get", publicUrl(urls, local));
      });
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Failed reading Terraform module upstream", e);
    }
  }

  @SuppressWarnings("unchecked")
  private MavenResponse proxyProviderDownload(
      RepositoryRuntime runtime, TerraformPath path, RequestUrls urls, boolean headOnly) {
    String remote = remoteUrl(runtime, path);
    String cachePath = ".terraform/upstream/" + sha256(remote) + ".json";
    Map<String, Object> body = readJson(responseBytes(
        proxy.getMetadataFromUrl(runtime, cachePath, remote, false)));
    String filename = string(body.get("filename"));
    try {
      TerraformPathParser.requireFilename(filename);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid Terraform upstream provider filename", e);
    }
    String download = absolute(remote, string(body.get("download_url")));
    String sums = absolute(remote, string(body.get("shasums_url")));
    String signature = absolute(remote, string(body.get("shasums_signature_url")));
    String localDownload = "v1/providers/" + path.namespace() + "/" + path.name() + "/" + path.version()
        + "/package/" + path.os() + "/" + filename;
    String sumsFile = safeRemoteFilename(sums,
        "terraform-provider-" + path.name() + "_" + path.version() + "_SHA256SUMS");
    String localSums = "v1/providers/" + path.namespace() + "/" + path.name() + "/" + path.version()
        + "/metadata-proxy/" + sumsFile;
    String localSignature = localSums + ".sig";
    String expected = string(body.get("shasum"));
    byte[] shasumsBytes = responseBytes(
        proxy.getMetadataFromUrl(runtime, localSums, sums, false));
    byte[] signatureBytes = responseBytes(
        proxy.getMetadataFromUrl(runtime, localSignature, signature, false));
    verifyChecksumEntry(shasumsBytes, expected, filename);
    signatureVerifier.verify(shasumsBytes, signatureBytes, upstreamPublicKeys(body));
    storeRoute(runtime, localDownload, download, expected);
    storeRoute(runtime, localSums, sums, null);
    storeRoute(runtime, localSignature, signature, null);
    Map<String, Object> rewritten = new LinkedHashMap<>(body);
    rewritten.put("download_url", publicUrl(urls, localDownload));
    rewritten.put("shasums_url", publicUrl(urls, localSums));
    rewritten.put("shasums_signature_url", publicUrl(urls, localSignature));
    return json(rewritten, headOnly);
  }

  @SuppressWarnings("unchecked")
  private static List<String> upstreamPublicKeys(Map<String, Object> body) {
    Object signing = body.get("signing_keys");
    if (!(signing instanceof Map<?, ?> keys)) {
      throw new MavenExceptions.BadUpstreamException("Terraform upstream omitted signing keys");
    }
    Object gpg = keys.get("gpg_public_keys");
    if (!(gpg instanceof List<?> values)) {
      throw new MavenExceptions.BadUpstreamException("Terraform upstream omitted GPG signing keys");
    }
    List<String> armors = values.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(value -> string(value.get("ascii_armor")))
        .filter(value -> value != null && !value.isBlank())
        .toList();
    if (armors.isEmpty()) throw new MavenExceptions.BadUpstreamException("Terraform upstream omitted GPG signing keys");
    return armors;
  }

  private static void verifyChecksumEntry(byte[] shasums, String expected, String filename) {
    if (expected == null || expected.isBlank()) {
      throw new MavenExceptions.BadUpstreamException("Terraform upstream omitted provider checksum");
    }
    String expectedLine = expected.toLowerCase(Locale.ROOT) + "  " + filename;
    boolean present = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(shasums)).toString().lines()
        .map(String::trim).anyMatch(line -> line.equalsIgnoreCase(expectedLine));
    if (!present) {
      throw new MavenExceptions.BadUpstreamException("Terraform upstream checksum manifest does not contain " + filename);
    }
  }

  private MavenResponse proxyRoute(RepositoryRuntime runtime, String localPath, boolean headOnly) {
    Map<String, Object> route = readJson(assets.bytes(runtime, routePath(localPath)));
    String remote = string(route.get("remoteUrl"));
    String expected = string(route.get("sha256"));
    MavenResponse response = proxy.getAssetFromUrl(runtime, localPath, remote, headOnly);
    if (expected != null && !expected.isBlank()) {
      AssetRecord asset = assets.find(runtime, localPath).orElseThrow(() -> notFound(localPath));
      AssetBlobRecord blob = assets.blob(asset);
      if (blob == null || !expected.equalsIgnoreCase(blob.sha256())) {
        if (response.hasBody()) {
          try {
            response.body().close();
          } catch (IOException ignored) {
            // The checksum failure is the actionable upstream error.
          }
        }
        assets.delete(runtime, localPath);
        throw new MavenExceptions.BadUpstreamException("Terraform provider checksum mismatch for " + localPath);
      }
    }
    return response;
  }

  private MavenResponse groupGet(
      RepositoryRuntime group, TerraformPath path, RequestUrls urls, boolean headOnly) {
    if (headOnly && (path.kind() == TerraformPath.Kind.MODULE_ARCHIVE
        || path.kind() == TerraformPath.Kind.PROVIDER_ARCHIVE
        || path.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS
        || path.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE)) {
      // Nexus 3.92's Terraform group facet does not dispatch HEAD to member archive assets even
      // though GET for the same URL succeeds. Keep this intentionally asymmetric behavior pinned
      // by the black-box compatibility suite.
      throw notFound(path.rawPath());
    }
    if (path.kind() == TerraformPath.Kind.MODULE_VERSIONS
        || path.kind() == TerraformPath.Kind.PROVIDER_VERSIONS) {
      return mergeGroupVersions(group, path, urls, headOnly);
    }
    String bindingKey = sourceBindingKey(path.rawPath());
    Optional<TerraformRegistryDao.SourceBinding> existing = registry.findSourceBinding(group.id(), bindingKey);
    if (existing.isPresent()) {
      RepositoryRuntime member = runtimes.resolveById(existing.get().memberRepositoryId()).orElse(null);
      if (member != null) {
        try { return get(member, path, urls, headOnly); }
        catch (MavenExceptions.MavenNotFoundException ignored) {}
      }
    }
    for (RepositoryRuntime member : group.members()) {
      try {
        MavenResponse response = get(member, path, urls, headOnly);
        return bindGroupResponse(group, member, path, response);
      } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException ignored) {
        // Nexus groups probe the next member on a missing or unavailable member.
      }
    }
    throw notFound(path.rawPath());
  }

  @SuppressWarnings("unchecked")
  private MavenResponse mergeGroupVersions(
      RepositoryRuntime group, TerraformPath path, RequestUrls urls, boolean headOnly) {
    Map<String, Map<String, Object>> versions = new LinkedHashMap<>();
    MavenExceptions.BadUpstreamException lastUpstreamFailure = null;
    boolean producedMetadata = false;
    for (RepositoryRuntime member : group.members()) {
      try {
        Map<String, Object> body = readJson(responseBytes(get(member, path, urls, false)));
        if (path.kind() == TerraformPath.Kind.MODULE_VERSIONS) {
          List<Map<String, Object>> modules = (List<Map<String, Object>>) body.getOrDefault("modules", List.of());
          for (Map<String, Object> module : modules) {
            for (Map<String, Object> version : (List<Map<String, Object>>) module.getOrDefault("versions", List.of())) {
              versions.putIfAbsent(string(version.get("version")), version);
            }
          }
        } else {
          for (Map<String, Object> version : (List<Map<String, Object>>) body.getOrDefault("versions", List.of())) {
            String number = string(version.get("version"));
            Map<String, Object> existing = versions.get(number);
            if (existing == null) {
              versions.put(number, new LinkedHashMap<>(version));
            } else {
              mergeProviderVersion(existing, version);
            }
          }
        }
        producedMetadata = true;
      } catch (MavenExceptions.BadUpstreamException e) {
        lastUpstreamFailure = e;
      } catch (RuntimeException ignored) {
        // Continue with healthy members.
      }
    }
    if (!producedMetadata && lastUpstreamFailure != null) {
      throw lastUpstreamFailure;
    }
    List<Map<String, Object>> sorted = TerraformVersions.descending(versions.keySet()).stream()
        .map(versions::get).toList();
    return path.kind() == TerraformPath.Kind.MODULE_VERSIONS
        ? json(Map.of("modules", List.of(Map.of(
            "source", path.namespace() + "/" + path.name() + "/" + path.system(), "versions", sorted))), headOnly)
        : json(Map.of("versions", sorted), headOnly);
  }

  @SuppressWarnings("unchecked")
  private static void mergeProviderVersion(
      Map<String, Object> existing, Map<String, Object> incoming) {
    Map<String, Map<String, Object>> platforms = new LinkedHashMap<>();
    for (Map<String, Object> source : List.of(existing, incoming)) {
      for (Map<String, Object> platform
          : (List<Map<String, Object>>) source.getOrDefault("platforms", List.of())) {
        String key = string(platform.get("os")) + "\u0000" + string(platform.get("arch"));
        platforms.putIfAbsent(key, platform);
      }
    }
    existing.put("platforms", List.copyOf(platforms.values()));

    Set<String> protocols = new LinkedHashSet<>();
    for (Map<String, Object> source : List.of(existing, incoming)) {
      for (Object protocol : (List<?>) source.getOrDefault("protocols", List.of())) {
        if (protocol != null) protocols.add(protocol.toString());
      }
    }
    existing.put("protocols", List.copyOf(protocols));
  }

  @SuppressWarnings("unchecked")
  private MavenResponse bindGroupResponse(
      RepositoryRuntime group, RepositoryRuntime member, TerraformPath request, MavenResponse response) {
    Set<String> pathsToBind = new LinkedHashSet<>();
    if (request.kind() == TerraformPath.Kind.MODULE_DOWNLOAD) {
      String value = response.headers().get("X-Terraform-Get");
      if (value != null) pathsToBind.add(canonicalRepositoryPath(value, group.name()));
    } else if (request.kind() == TerraformPath.Kind.PROVIDER_DOWNLOAD && response.hasBody()) {
      byte[] bytes = responseBytes(response);
      Map<String, Object> json = readJson(bytes);
      for (String field : List.of("download_url", "shasums_url", "shasums_signature_url")) {
        String value = string(json.get(field));
        if (value != null) pathsToBind.add(canonicalRepositoryPath(value, group.name()));
      }
      // Replace the consumed response body so the client receives the same metadata.
      response = bytes(bytes, JSON, false);
    }
    Instant now = Instant.now();
    for (String bound : pathsToBind) {
      if (bound == null || bound.isBlank()) continue;
      registry.upsertSourceBinding(new TerraformRegistryDao.SourceBinding(
          group.id(), sourceBindingKey(bound), member.id(), memberRevision(member, request),
          now.plus(24, ChronoUnit.HOURS), now));
    }
    return response;
  }

  private long memberRevision(RepositoryRuntime member, TerraformPath path) {
    if (path.version() == null) return 0;
    return registry.findProviderState(member.id(), path.namespace(), path.name(), path.version())
        .map(TerraformRegistryDao.ProviderState::revision).orElse(0L);
  }

  private String remoteUrl(RepositoryRuntime runtime, TerraformPath path) {
    String key = path.module() ? "modules.v1" : "providers.v1";
    String service = discovery(runtime, key);
    String prefix = path.module() ? "v1/modules/" : "v1/providers/";
    String suffix = path.rawPath().substring(prefix.length());
    return ensureSlash(service) + suffix;
  }

  private String discovery(RepositoryRuntime runtime, String key) {
    String root = ensureSlash(runtime.proxyRemoteUrl());
    String remote = root + ".well-known/terraform.json";
    String local = ".terraform/upstream/discovery-" + sha256(remote) + ".json";
    Map<String, Object> document = readJson(responseBytes(
        proxy.getMetadataFromUrl(runtime, local, remote, false)));
    String value = string(document.get(key));
    if (value == null || value.isBlank()) {
      throw new MavenExceptions.BadUpstreamException("Terraform discovery omitted " + key);
    }
    return URI.create(root).resolve(value).toString();
  }

  private void storeRoute(RepositoryRuntime runtime, String localPath, String remoteUrl, String expectedSha256) {
    Map<String, Object> route = new LinkedHashMap<>();
    route.put("remoteUrl", remoteUrl);
    if (expectedSha256 != null && !expectedSha256.isBlank()) route.put("sha256", expectedSha256);
    assets.storeBytes(runtime, routePath(localPath), jsonBytes(route), JSON,
        Map.of("terraformKind", "proxy-route", "targetPath", localPath));
  }

  private static String routePath(String localPath) {
    return ".terraform/routes/" + sha256(localPath) + ".json";
  }

  private void enforceWrite(RepositoryRuntime runtime, String path, boolean migration) {
    String policy = effectiveWritePolicy(runtime, migration);
    if ("DENY".equals(policy)) throw new MavenExceptions.WritePolicyDenied("Repository write policy is DENY");
    if ("ALLOW_ONCE".equals(policy) && assets.find(runtime, path).isPresent()) {
      throw new MavenExceptions.WritePolicyDenied("Terraform coordinate already exists");
    }
  }

  private void enforceModuleWrite(
      RepositoryRuntime runtime, TerraformPath path, boolean migration) {
    String policy = effectiveWritePolicy(runtime, migration);
    if ("DENY".equals(policy)) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy is DENY");
    }
    String prefix = "v1/modules/" + path.namespace() + "/" + path.name() + "/"
        + path.system() + "/" + path.version() + "/";
    List<AssetRecord> existing = assets.list(runtime, prefix).stream()
        .filter(asset -> asset.path().startsWith(prefix))
        .filter(asset -> paths.parse(asset.path()).kind() == TerraformPath.Kind.MODULE_ARCHIVE)
        .toList();
    if (existing.isEmpty()) return;
    if ("ALLOW".equals(policy) && existing.size() == 1
        && existing.getFirst().path().equals(path.rawPath())) {
      return;
    }
    throw new MavenExceptions.WritePolicyDenied("Terraform module version already exists");
  }

  private static String effectiveWritePolicy(RepositoryRuntime runtime, boolean migration) {
    String policy = runtime.writePolicy() == null
        ? "ALLOW_ONCE" : runtime.writePolicy().toUpperCase(Locale.ROOT);
    return migration && "DENY".equals(policy) ? "ALLOW_ONCE" : policy;
  }

  private String filename(String contentDisposition) {
    return contentDispositionFilename(contentDisposition);
  }

  static String contentDispositionFilename(String contentDisposition) {
    if (contentDisposition == null) {
      throw new MavenExceptions.BadRequestException("Content-Disposition filename is required");
    }
    String value = null;
    boolean filenameSeen = false;
    boolean quoted = false;
    boolean escaped = false;
    int parameterStart = 0;
    for (int i = 0; i <= contentDisposition.length(); i++) {
      char current = i == contentDisposition.length() ? ';' : contentDisposition.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (quoted && current == '\\') {
        escaped = true;
      } else if (current == '"') {
        quoted = !quoted;
      } else if (current == ';' && !quoted) {
        String parameter = contentDisposition.substring(parameterStart, i).trim();
        parameterStart = i + 1;
        int equals = parameter.indexOf('=');
        if (equals <= 0 || !"filename".equalsIgnoreCase(parameter.substring(0, equals).trim())) {
          continue;
        }
        if (filenameSeen) {
          throw new MavenExceptions.BadRequestException(
              "Content-Disposition has duplicate filename values");
        }
        filenameSeen = true;
        value = dispositionParameterValue(parameter.substring(equals + 1).trim());
      }
    }
    if (quoted || escaped) {
      throw new MavenExceptions.BadRequestException("Content-Disposition has an invalid quoted value");
    }
    if (!filenameSeen) {
      throw new MavenExceptions.BadRequestException("Content-Disposition filename is required");
    }
    TerraformPathParser.requireFilename(value);
    return value;
  }

  private static String dispositionParameterValue(String raw) {
    if (!raw.startsWith("\"")) return raw;
    if (raw.length() < 2 || raw.charAt(raw.length() - 1) != '"') {
      throw new MavenExceptions.BadRequestException("Content-Disposition has an invalid quoted value");
    }
    StringBuilder decoded = new StringBuilder(raw.length() - 2);
    boolean escaped = false;
    for (int i = 1; i < raw.length() - 1; i++) {
      char current = raw.charAt(i);
      if (escaped) {
        decoded.append(current);
        escaped = false;
      } else if (current == '\\') {
        escaped = true;
      } else if (current == '"') {
        throw new MavenExceptions.BadRequestException(
            "Content-Disposition has an invalid quoted value");
      } else {
        decoded.append(current);
      }
    }
    if (escaped) {
      throw new MavenExceptions.BadRequestException("Content-Disposition has an invalid quoted value");
    }
    return decoded.toString();
  }

  private MavenResponse json(Map<String, ?> value, boolean headOnly) {
    return bytes(jsonBytes(value), JSON, headOnly);
  }

  private static MavenResponse bytes(byte[] body, String contentType, boolean headOnly) {
    return headOnly
        ? MavenResponse.noBody(200, body.length, contentType, null, null)
        : MavenResponse.ok(new ByteArrayInputStream(body), body.length, contentType, null, null);
  }

  private byte[] jsonBytes(Object value) {
    try { return mapper.writeValueAsBytes(value); }
    catch (IOException e) { throw new IllegalStateException("Failed rendering Terraform metadata", e); }
  }

  private Map<String, Object> readJson(byte[] bytes) {
    try { return mapper.readValue(bytes, new TypeReference<>() {}); }
    catch (IOException e) { throw new MavenExceptions.BadUpstreamException("Invalid Terraform upstream JSON", e); }
  }

  private static byte[] responseBytes(MavenResponse response) {
    InputStream body = response.body();
    if (body == null) return new byte[0];
    try (body) { return body.readAllBytes(); }
    catch (IOException e) { throw new MavenExceptions.BadUpstreamException("Failed reading Terraform response", e); }
  }

  private static List<String> protocolList(String value) {
    if (value == null || value.isBlank()) return List.of(PROTOCOLS);
    return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
  }

  private static String safeRemoteFilename(String url, String fallback) {
    try {
      String path = URI.create(url).getPath();
      String leaf = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
      if (!leaf.isBlank()) {
        TerraformPathParser.requireFilename(leaf);
        return leaf;
      }
    } catch (RuntimeException ignored) {}
    TerraformPathParser.requireFilename(fallback);
    return fallback;
  }

  private static String absolute(String base, String value) {
    if (value == null || value.isBlank()) throw new MavenExceptions.BadUpstreamException("Terraform upstream URL is missing");
    return URI.create(base).resolve(value).toString();
  }

  private static String httpModuleSource(String base, String value) {
    String candidate = value;
    if (value.startsWith("/") || value.startsWith("./") || value.startsWith("../")) {
      try {
        candidate = URI.create(base).resolve(value).toString();
      } catch (RuntimeException e) {
        throw new MavenExceptions.BadUpstreamException("Terraform upstream module URL is invalid");
      }
    }
    return candidate.regionMatches(true, 0, "http://", 0, "http://".length())
            || candidate.regionMatches(true, 0, "https://", 0, "https://".length())
        ? candidate
        : null;
  }

  private static String repositoryPath(String url, String repository) {
    if (url == null) return null;
    String marker = "/repository/" + repository + "/";
    int index = url.indexOf(marker);
    return index < 0 ? null : url.substring(index + marker.length());
  }

  private String canonicalRepositoryPath(String url, String repository) {
    String path = repositoryPath(url, repository);
    return path == null ? null : paths.parseRequestPath(path).canonicalPath();
  }

  private static String publicUrl(RequestUrls urls, String assetPath) {
    return publicUrl(urls.repositoryBaseUrl(), urls.urlTokenSegment(), assetPath);
  }

  static String publicUrl(String repositoryBaseUrl, String urlTokenSegment, String assetPath) {
    if (urlTokenSegment == null || urlTokenSegment.isBlank()) {
      return repositoryBaseUrl + "/" + assetPath;
    }
    String prefix;
    if (assetPath.startsWith("v1/modules/")) {
      prefix = "v1/modules/";
    } else if (assetPath.startsWith("v1/providers/")) {
      prefix = "v1/providers/";
    } else {
      return repositoryBaseUrl + "/" + assetPath;
    }
    return repositoryBaseUrl + "/" + prefix + urlTokenSegment + "/"
        + assetPath.substring(prefix.length());
  }

  private static String ensureSlash(String value) {
    if (value == null || value.isBlank()) throw new MavenExceptions.BadUpstreamException("Terraform remote URL is missing");
    return value.endsWith("/") ? value : value + "/";
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sourceBindingKey(String assetPath) {
    return "asset:sha256:" + sha256(assetPath);
  }

  private static String publishLeaseKey(String kind, long repositoryId, String... coordinates) {
    return kind + ":" + repositoryId + ":" + sha256(String.join("\0", coordinates));
  }

  private static MavenExceptions.MavenNotFoundException notFound(String path) {
    return new MavenExceptions.MavenNotFoundException(path);
  }

  private record RequestUrls(String repositoryBaseUrl, String urlTokenSegment) {}
}
