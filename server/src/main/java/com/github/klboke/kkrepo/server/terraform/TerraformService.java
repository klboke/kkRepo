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
import java.util.TreeMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/** HashiCorp Registry Protocol implementation shared by hosted, proxy, and group recipes. */
@Service
public class TerraformService {
  public static final String PROVIDER_PROTOCOLS_HEADER = "X-Terraform-Provider-Protocols";
  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
  private static final String OCTET = MediaType.APPLICATION_OCTET_STREAM_VALUE;
  private static final String DEFAULT_PROVIDER_PROTOCOLS = "5.0";

  private final ObjectMapper mapper;
  private final TerraformAssetSupport assets;
  private final TerraformArchiveInspector inspector;
  private final TerraformSigningService signing;
  private final TerraformSignatureVerifier signatureVerifier;
  private final TerraformRegistryDao registry;
  private final TerraformPublishLeaseManager leases;
  private final TerraformComponentService components;
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
      TerraformComponentService components,
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
    this.components = components;
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
    return put(runtime, path, body, contentType, contentDisposition, null, actor, ip);
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      TerraformPath path,
      InputStream body,
      String contentType,
      String contentDisposition,
      String providerProtocols,
      String actor,
      String ip) {
    return publish(
        runtime, path, body, contentType, contentDisposition, providerProtocols, actor, ip, false);
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
    return publish(runtime, path, body, contentType, contentDisposition, null, actor, ip, true);
  }

  /**
   * Restores a Nexus Terraform proxy archive as cache data without treating it as a client
   * publication. Module download metadata discovers a restored module archive directly. Provider
   * metadata recreates the remote route from the configured upstream; {@link RawProxyService} then
   * reuses this fresh local asset and the provider path verifies its SHA-256 against the current
   * upstream download metadata.
   */
  MavenResponse restoreProxyCacheForMigration(
      RepositoryRuntime runtime,
      TerraformPath path,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    if (!runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Terraform proxy cache restore requires a proxy repository");
    }
    boolean module = path.kind() == TerraformPath.Kind.MODULE_ARCHIVE;
    boolean provider = path.kind() == TerraformPath.Kind.PROVIDER_ARCHIVE;
    if (!module && !provider) {
      throw new MavenExceptions.MethodNotAllowed(
          "Unsupported Terraform proxy cache path: " + path.rawPath());
    }

    Path buffered = inspector.bufferAndInspect(
        body, path.filename(), module, provider ? path.name() : null);
    String leaseKey = publishLeaseKey("proxy-cache-migration", runtime.id(), path.rawPath());
    try (TerraformPublishLeaseManager.Lease lease = leases.acquire(
        leaseKey, java.time.Duration.ofMinutes(5), java.time.Duration.ofSeconds(30))) {
      if (assets.find(runtime, path.rawPath()).isPresent()) {
        return MavenResponse.created();
      }
      lease.assertHeld();
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("terraformKind", module ? "proxy-module-archive" : "proxy-provider-archive");
      attributes.put("migrationSource", "nexus-repository-data-migration");
      attributes.put("namespace", path.namespace());
      attributes.put("name", path.name());
      attributes.put("version", path.version());
      if (module) {
        attributes.put("system", path.system());
      } else {
        attributes.put("os", path.os());
        attributes.put("arch", path.arch());
      }
      try (InputStream in = Files.newInputStream(buffered)) {
        assets.storeWithComponent(
            runtime,
            path.rawPath(),
            in,
            contentType == null ? OCTET : contentType,
            attributes,
            actor,
            ip,
            path.module()
                ? components.moduleComponent(runtime, path, Instant.now())
                : components.providerComponent(runtime, path, Instant.now()));
      }
      lease.assertHeld();
      return MavenResponse.created();
    } catch (IOException e) {
      throw new IllegalStateException("Failed restoring Terraform proxy cache archive", e);
    } finally {
      try {
        Files.deleteIfExists(buffered);
      } catch (IOException ignored) {
      }
    }
  }

  private MavenResponse publish(
      RepositoryRuntime runtime,
      TerraformPath path,
      InputStream body,
      String contentType,
      String contentDisposition,
      String providerProtocols,
      String actor,
      String ip,
      boolean migration) {
    if (!runtime.isHosted()) throw new MavenExceptions.MethodNotAllowed("Terraform group/proxy repositories are read-only");
    return switch (path.kind()) {
      case MODULE_ARCHIVE -> putModule(runtime, path, body, contentType, actor, ip, migration);
      case PROVIDER_DOWNLOAD ->
          putProvider(
              runtime, path, body, contentType, contentDisposition, providerProtocols, actor, ip,
              migration);
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
    if (versions.isEmpty()) throw notFound(request.rawPath());
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
      List<TerraformRegistryDao.ProviderPlatform> publishedPlatforms = publishedProviderPlatforms(
          runtime, request.namespace(), request.name(), version);
      List<Map<String, Object>> platforms = publishedPlatforms.stream()
          .map(row -> Map.<String, Object>of("os", row.os(), "arch", row.arch()))
          .toList();
      if (platforms.isEmpty()) continue;
      values.add(Map.of(
          "version", version,
          "protocols", providerVersionProtocols(publishedPlatforms),
          "platforms", platforms));
    }
    if (values.isEmpty()) throw notFound(request.rawPath());
    return providerVersionsDocument(request, values);
  }

  private static Map<String, Object> providerVersionsDocument(
      TerraformPath request, List<Map<String, Object>> versions) {
    LinkedHashMap<String, Object> body = new LinkedHashMap<>();
    body.put("id", request.namespace() + "/" + request.name());
    body.put("versions", versions);
    body.put("warnings", null);
    return body;
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
    body.put("download_url", publicUrl(urls, nexusProviderArchivePath(request, platform)));
    body.put("shasums_url", publicUrl(urls, nexusProviderShasumsPath(request)));
    body.put("shasums_signature_url", publicUrl(urls, nexusProviderSignaturePath(request)));
    body.put("shasum", platform.sha256());
    body.put("signing_keys", Map.of("gpg_public_keys", List.of(Map.of(
        "key_id", key.keyId(), "ascii_armor", key.publicKey(), "trust_signature", ""))));
    return json(body, headOnly);
  }

  private static String nexusProviderArchivePath(
      TerraformPath request, TerraformRegistryDao.ProviderPlatform platform) {
    return nexusProviderPlatformPath(request) + "/" + platform.filename();
  }

  private static String nexusProviderShasumsPath(TerraformPath request) {
    return nexusProviderPlatformPath(request) + "/SHA256SUMS";
  }

  private static String nexusProviderSignaturePath(TerraformPath request) {
    return nexusProviderPlatformPath(request) + "/SHA256SUMS.sig";
  }

  private static String nexusProviderPlatformPath(TerraformPath request) {
    return "v1/providers/" + request.namespace() + "/" + request.name() + "/"
        + request.version() + "/download/" + request.os() + "/" + request.arch();
  }

  private MavenResponse servePublishedProviderArchive(
      RepositoryRuntime runtime, TerraformPath path, boolean headOnly) {
    TerraformRegistryDao.ProviderPlatform published = publishedProviderPlatforms(
            runtime, path.namespace(), path.name(), path.version()).stream()
        .filter(row -> path.arch() == null
            ? row.assetPath().equals(path.rawPath())
            : row.os().equals(path.os())
                && row.arch().equals(path.arch())
                && row.filename().equals(path.filename()))
        .findFirst()
        .orElseThrow(() -> notFound(path.rawPath()));
    return assets.serve(runtime, published.assetPath(), headOnly);
  }

  private MavenResponse servePublishedProviderMetadata(
      RepositoryRuntime runtime, TerraformPath path, boolean headOnly) {
    TerraformRegistryDao.ProviderState state = registry.findProviderState(
            runtime.id(), path.namespace(), path.name(), path.version())
        .orElseThrow(() -> notFound(path.rawPath()));
    String published = path.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS
        ? state.shasumsPath() : state.signaturePath();
    if (isNexusProviderMetadataAlias(path)) {
      boolean platformPublished = publishedProviderPlatforms(
              runtime, path.namespace(), path.name(), path.version()).stream()
          .anyMatch(platform -> platform.os().equals(path.os())
              && platform.arch().equals(path.arch()));
      if (!platformPublished) {
        throw notFound(path.rawPath());
      }
      return assets.serve(runtime, published, headOnly);
    }
    if (!published.equals(path.rawPath())) {
      throw notFound(path.rawPath());
    }
    return assets.serve(runtime, path.rawPath(), headOnly);
  }

  private static boolean isNexusProviderMetadataAlias(TerraformPath path) {
    return path.os() != null
        && path.arch() != null
        && ("SHA256SUMS".equals(path.filename()) || "SHA256SUMS.sig".equals(path.filename()));
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
      lease.assertHeld();
      try (InputStream in = Files.newInputStream(buffered)) {
        assets.storeWithComponent(
            runtime, path.rawPath(), in, contentType == null ? OCTET : contentType,
            Map.of(
                "terraformKind", "module-archive",
                "namespace", path.namespace(), "name", path.name(), "system", path.system(),
                "version", path.version(), "sha256Validated", true), actor, ip,
            components.moduleComponent(runtime, path, Instant.now()));
      }
      lease.assertHeld();
      return MavenResponse.created();
    } catch (IOException e) {
      throw new IllegalStateException("Failed storing Terraform module archive", e);
    } finally {
      try { Files.deleteIfExists(buffered); } catch (IOException ignored) {}
    }
  }

  private MavenResponse putProvider(
      RepositoryRuntime runtime, TerraformPath path, InputStream body, String contentType,
      String contentDisposition, String requestedProtocols, String actor, String ip,
      boolean migration) {
    enforceWrite(runtime, path.rawPath(), migration);
    String protocols = normalizeProviderProtocols(requestedProtocols);
    String filename;
    try {
      filename = filename(contentDisposition);
      TerraformPathParser.requireFilename(filename);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.BadRequestException("Invalid Terraform provider filename");
    }
    String expectedPrefix = "terraform-provider-" + path.name() + "_" + path.version()
        + "_" + path.os() + "_" + path.arch();
    if (!filename.startsWith(expectedPrefix) || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      throw new MavenExceptions.BadRequestException(
          "Provider filename must match " + expectedPrefix + "*.zip");
    }
    // Buffer and inspect before acquiring the renewable lease so slow client uploads do not hold a
    // shared database claim before publication work starts.
    Path buffered = inspector.bufferAndInspect(body, filename, false, path.name());
    String leaseKey = publishLeaseKey(
        "provider", runtime.id(), path.namespace(), path.name(), path.version());
    try (TerraformPublishLeaseManager.Lease lease = leases.acquire(
        leaseKey, java.time.Duration.ofMinutes(5), java.time.Duration.ofSeconds(30))) {
      List<TerraformRegistryDao.ProviderPlatform> current = publishedProviderPlatforms(
          runtime, path.namespace(), path.name(), path.version());
      Optional<TerraformRegistryDao.ProviderPlatform> existing = current.stream()
          .filter(row -> row.os().equals(path.os()) && row.arch().equals(path.arch()))
          .findFirst();
      if (existing.isPresent() && !"ALLOW".equals(effectiveWritePolicy(runtime, migration))) {
        throw new MavenExceptions.WritePolicyDenied("Terraform provider platform already exists");
      }
      boolean inconsistentVersionProtocols = current.stream()
          .filter(row -> !(row.os().equals(path.os()) && row.arch().equals(path.arch())))
          .map(TerraformRegistryDao.ProviderPlatform::protocols)
          .map(TerraformService::normalizeProviderProtocols)
          .anyMatch(publishedProtocols -> !publishedProtocols.equals(protocols));
      if (inconsistentVersionProtocols) {
        throw new MavenExceptions.BadRequestException(
            "Provider protocols must match every platform in the same version");
      }
      long revision = registry.findProviderState(runtime.id(), path.namespace(), path.name(), path.version())
          .map(state -> state.revision() + 1).orElse(1L);
      String assetPath = "v1/providers/" + path.namespace() + "/" + path.name() + "/" + path.version()
          + "/package/" + path.os() + "/" + filename;
      // Always validate and persist the incoming archive. A prior publication attempt may have
      // failed after storing an orphaned STAGING asset but before committing the platform row;
      // reusing that blob would publish stale content instead of the operator's retry.
      try (InputStream in = Files.newInputStream(buffered)) {
        assets.storeUnindexed(runtime, assetPath, in, contentType == null ? OCTET : contentType,
            Map.of(
                "terraformKind", "provider-archive", "namespace", path.namespace(), "type", path.name(),
                "version", path.version(), "os", path.os(), "arch", path.arch(),
                "protocols", protocolList(protocols), "publishState", "STAGING", "revision", revision), actor, ip);
      }
      AssetRecord stored = assets.find(runtime, assetPath).orElseThrow(() -> notFound(assetPath));
      AssetBlobRecord blob = assets.blob(stored);
      if (blob == null || blob.sha256() == null) throw new IllegalStateException("Provider archive digest is missing");
      TerraformRegistryDao.ProviderPlatform published = new TerraformRegistryDao.ProviderPlatform(
          runtime.id(), path.namespace(), path.name(), path.version(), path.os(), path.arch(), filename,
          assetPath, blob.sha256(), protocols, revision, Instant.now());
      List<TerraformRegistryDao.ProviderPlatform> next = new ArrayList<>(current.stream()
          .filter(row -> !(row.os().equals(path.os()) && row.arch().equals(path.arch())))
          .toList());
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
      TerraformRegistryDao.ProviderState state = new TerraformRegistryDao.ProviderState(
          runtime.id(), path.namespace(), path.name(), path.version(), revision,
          sumsPath, signaturePath, key.revision(), Instant.now());
      List<String> activePaths = new ArrayList<>(
          next.stream().map(TerraformRegistryDao.ProviderPlatform::assetPath).toList());
      activePaths.add(sumsPath);
      activePaths.add(signaturePath);
      lease.assertHeld();
      components.publishProvider(runtime, published, state, activePaths);
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
    MavenResponse cached = proxy.getMetadataFromUrlUnindexed(runtime, cachePath, remote, false);
    byte[] bytes = responseBytes(cached);
    return bytes(bytes, JSON, headOnly);
  }

  private MavenResponse proxyModuleDownload(RepositoryRuntime runtime, TerraformPath path, RequestUrls urls) {
    Optional<CachedModuleDownload> cachedRoute = cachedModuleDownload(runtime, path);
    if (cachedRoute.filter(route -> route.isFresh(
        runtime.metadataMaxAgeMinutesOrDefault(), Instant.now())).isPresent()) {
      return moduleDownloadResponse(cachedRoute.orElseThrow().localPath(), urls);
    }
    Optional<String> cachedArchive = cachedModuleArchive(runtime, path);
    if (cachedArchive.isPresent()) {
      return moduleDownloadResponse(cachedArchive.orElseThrow(), urls);
    }
    try {
      String remote = remoteUrl(runtime, path);
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
        String resolved = resolveModuleSource(remote, upstream);
        String absolute = directHttpModuleArchive(resolved);
        if (absolute == null) {
          return MavenResponse.noBody(204).withHeader("X-Terraform-Get", resolved);
        }
        String filename = safeRemoteFilename(absolute,
            path.name() + "_" + path.version() + ".zip");
        String local = "v1/modules/" + path.namespace() + "/" + path.name() + "/" + path.system()
            + "/" + path.version() + "/" + filename;
        storeRoute(runtime, local, absolute, null);
        storeModuleDownload(runtime, path, local);
        return moduleDownloadResponse(local, urls);
      });
    } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException e) {
      if (cachedRoute.isPresent()) {
        return moduleDownloadResponse(cachedRoute.orElseThrow().localPath(), urls);
      }
      throw e;
    } catch (IOException e) {
      if (cachedRoute.isPresent()) {
        return moduleDownloadResponse(cachedRoute.orElseThrow().localPath(), urls);
      }
      throw new MavenExceptions.BadUpstreamException("Failed reading Terraform module upstream", e);
    }
  }

  @SuppressWarnings("unchecked")
  private MavenResponse proxyProviderDownload(
      RepositoryRuntime runtime, TerraformPath path, RequestUrls urls, boolean headOnly) {
    String remote = remoteUrl(runtime, path);
    String cachePath = ".terraform/upstream/" + sha256(remote) + ".json";
    Map<String, Object> body = readJson(responseBytes(
        proxy.getMetadataFromUrlUnindexed(runtime, cachePath, remote, false)));
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
        + "/download/" + path.os() + "/" + path.arch() + "/" + filename;
    String localSums = nexusProviderShasumsPath(path);
    String localSignature = nexusProviderSignaturePath(path);
    String expected = string(body.get("shasum"));
    var component = components.providerComponent(runtime, path, Instant.now());
    byte[] shasumsBytes = responseBytes(
        proxy.getMetadataFromUrlWithComponent(runtime, localSums, sums, component, false));
    byte[] signatureBytes = responseBytes(
        proxy.getMetadataFromUrlWithComponent(
            runtime, localSignature, signature, component, false));
    verifyChecksumEntry(shasumsBytes, expected, filename);
    signatureVerifier.verify(shasumsBytes, signatureBytes, upstreamPublicKeys(body));
    storeRoute(runtime, localDownload, download, expected);
    // The download metadata, checksum manifest, and signature form one verified snapshot. Pin the
    // metadata assets to that snapshot so a shorter content TTL cannot refresh either route
    // independently while clients are still using the cached provider download response.
    storeRoute(runtime, localSums, sums, sha256(shasumsBytes));
    storeRoute(runtime, localSignature, signature, sha256(signatureBytes));
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
    TerraformPath path = paths.parse(localPath);
    if (assets.find(runtime, routePath(localPath)).isEmpty()) {
      if (path.kind() == TerraformPath.Kind.MODULE_ARCHIVE
          && assets.find(runtime, localPath).isPresent()) {
        return assets.serve(runtime, localPath, headOnly);
      }
      throw notFound(localPath);
    }
    Map<String, Object> route = readJson(assets.bytes(runtime, routePath(localPath)));
    String remote = string(route.get("remoteUrl"));
    String expected = string(route.get("sha256"));
    var component = components.componentForPublicPath(
            runtime, path, Instant.now())
        .orElseThrow(() -> new IllegalStateException(
            "Terraform proxy route has no logical component: " + localPath));
    MavenResponse response = expected == null || expected.isBlank()
        ? proxy.getAssetFromUrlWithComponent(runtime, localPath, remote, component, headOnly)
        : proxy.getPinnedAssetFromUrlWithComponent(
            runtime, localPath, remote, component, headOnly);
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
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // A group member may legitimately have no metadata for the requested package.
      } catch (MavenExceptions.BadUpstreamException e) {
        lastUpstreamFailure = e;
      }
    }
    if (versions.isEmpty() && lastUpstreamFailure != null) {
      throw lastUpstreamFailure;
    }
    if (versions.isEmpty()) throw notFound(path.rawPath());
    List<Map<String, Object>> sorted = TerraformVersions.descending(versions.keySet()).stream()
        .map(versions::get).toList();
    return path.kind() == TerraformPath.Kind.MODULE_VERSIONS
        ? json(Map.of("modules", List.of(Map.of(
            "source", path.namespace() + "/" + path.name() + "/" + path.system(), "versions", sorted))), headOnly)
        : json(providerVersionsDocument(path, sorted), headOnly);
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
    String canonicalPath = path.kind() == TerraformPath.Kind.PROVIDER_VERSIONS
            && path.rawPath().endsWith("/versions.json")
        ? path.rawPath().substring(0, path.rawPath().length() - ".json".length())
        : path.rawPath();
    String suffix = canonicalPath.substring(prefix.length());
    return ensureSlash(service) + suffix;
  }

  private String discovery(RepositoryRuntime runtime, String key) {
    String root = ensureSlash(runtime.proxyRemoteUrl());
    String remote = root + ".well-known/terraform.json";
    String local = ".terraform/upstream/discovery-" + sha256(remote) + ".json";
    Map<String, Object> document = readJson(responseBytes(
        proxy.getMetadataFromUrlUnindexed(runtime, local, remote, false)));
    String value = string(document.get(key));
    if (value == null || value.isBlank()) {
      throw new MavenExceptions.BadUpstreamException("Terraform discovery omitted " + key);
    }
    return URI.create(remote).resolve(value).toString();
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

  private void storeModuleDownload(
      RepositoryRuntime runtime, TerraformPath download, String localPath) {
    assets.storeBytes(
        runtime,
        moduleDownloadPath(download.rawPath()),
        jsonBytes(Map.of("localPath", localPath)),
        JSON,
        Map.of("terraformKind", "module-download-metadata", "targetPath", download.rawPath()));
  }

  private Optional<CachedModuleDownload> cachedModuleDownload(
      RepositoryRuntime runtime, TerraformPath download) {
    String cachePath = moduleDownloadPath(download.rawPath());
    Optional<AssetRecord> asset = assets.find(runtime, cachePath);
    if (asset.isEmpty()) return Optional.empty();
    String localPath = string(readJson(assets.bytes(runtime, cachePath)).get("localPath"));
    TerraformPath archive = paths.parse(localPath);
    if (!sameModuleArchive(download, archive)) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid cached Terraform module download route for " + download.rawPath());
    }
    return Optional.of(new CachedModuleDownload(localPath, asset.orElseThrow().lastUpdatedAt()));
  }

  private Optional<String> cachedModuleArchive(
      RepositoryRuntime runtime, TerraformPath download) {
    String prefix = "v1/modules/" + download.namespace() + "/" + download.name() + "/"
        + download.system() + "/" + download.version() + "/";
    return assets.list(runtime, prefix).stream()
        .map(AssetRecord::path)
        .filter(candidate -> {
          try {
            return sameModuleArchive(download, paths.parse(candidate));
          } catch (IllegalArgumentException ignored) {
            return false;
          }
        })
        .sorted()
        .findFirst();
  }

  private static boolean sameModuleArchive(TerraformPath download, TerraformPath archive) {
    return archive.kind() == TerraformPath.Kind.MODULE_ARCHIVE
        && download.namespace().equals(archive.namespace())
        && download.name().equals(archive.name())
        && download.system().equals(archive.system())
        && download.version().equals(archive.version());
  }

  private static MavenResponse moduleDownloadResponse(String localPath, RequestUrls urls) {
    return MavenResponse.noBody(204).withHeader("X-Terraform-Get", publicUrl(urls, localPath));
  }

  private static String moduleDownloadPath(String rawPath) {
    return ".terraform/module-downloads/" + sha256(rawPath) + ".json";
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
    if (value == null || value.isBlank()) return List.of(DEFAULT_PROVIDER_PROTOCOLS);
    return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
  }

  private static List<String> providerVersionProtocols(
      List<TerraformRegistryDao.ProviderPlatform> platforms) {
    if (platforms.isEmpty()) return List.of(DEFAULT_PROVIDER_PROTOCOLS);
    String protocols = normalizeProviderProtocols(platforms.getFirst().protocols());
    if (platforms.stream()
        .skip(1)
        .map(TerraformRegistryDao.ProviderPlatform::protocols)
        .map(TerraformService::normalizeProviderProtocols)
        .anyMatch(value -> !value.equals(protocols))) {
      throw new IllegalStateException("Terraform provider version has inconsistent protocols");
    }
    return protocolList(protocols);
  }

  static String normalizeProviderProtocols(String value) {
    String input = value == null || value.isBlank() ? DEFAULT_PROVIDER_PROTOCOLS : value.trim();
    if (input.length() > 128) {
      throw new MavenExceptions.BadRequestException("Invalid Terraform provider protocols");
    }
    TreeMap<Integer, Integer> versions = new TreeMap<>();
    for (String raw : input.split(",", -1)) {
      String protocol = raw.trim();
      if (!protocol.matches("(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})")) {
        throw new MavenExceptions.BadRequestException("Invalid Terraform provider protocols");
      }
      String[] parts = protocol.split("\\.");
      int major = Integer.parseInt(parts[0]);
      int minor = Integer.parseInt(parts[1]);
      if (major < 5 || versions.putIfAbsent(major, minor) != null) {
        throw new MavenExceptions.BadRequestException("Invalid Terraform provider protocols");
      }
    }
    return versions.entrySet().stream()
        .map(entry -> entry.getKey() + "." + entry.getValue())
        .collect(java.util.stream.Collectors.joining(","));
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

  private static String resolveModuleSource(String base, String value) {
    try {
      URI source = URI.create(value);
      return source.isAbsolute() ? value : URI.create(base).resolve(source).toString();
    } catch (RuntimeException e) {
      throw new MavenExceptions.BadUpstreamException("Terraform upstream module URL is invalid");
    }
  }

  private static String directHttpModuleArchive(String candidate) {
    try {
      URI uri = URI.create(candidate);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
        return null;
      }
      String path = uri.getRawPath();
      if (path == null || path.contains("//") || uri.getRawFragment() != null) return null;
      String lower = path.toLowerCase(Locale.ROOT);
      for (String suffix : List.of(
          ".zip", ".bz2", ".tar.bz2", ".tar.tbz2", ".tbz2",
          ".gz", ".tar.gz", ".tgz", ".xz", ".tar.xz", ".txz")) {
        if (lower.endsWith(suffix)) return candidate;
      }
      // Non-archive HTTP addresses can be vanity URLs or go-getter/VCS sources. Their query and
      // subdirectory semantics belong to Terraform and must survive unchanged.
      return null;
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.BadUpstreamException("Terraform upstream module URL is invalid");
    }
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
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
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

  private record CachedModuleDownload(String localPath, Instant cachedAt) {
    private boolean isFresh(int maxAgeMinutes, Instant now) {
      if (cachedAt == null) return false;
      return maxAgeMinutes < 0
          || cachedAt.plusSeconds(maxAgeMinutes * 60L).isAfter(now);
    }
  }

  private record RequestUrls(String repositoryBaseUrl, String urlTokenSegment) {}
}
