package com.github.klboke.kkrepo.server.swift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.swift.SwiftIdentifiers;
import com.github.klboke.kkrepo.protocol.swift.SwiftLink;
import com.github.klboke.kkrepo.protocol.swift.SwiftLinkHeader;
import com.github.klboke.kkrepo.protocol.swift.SwiftMediaTypes;
import com.github.klboke.kkrepo.protocol.swift.SwiftPackageIdentity;
import com.github.klboke.kkrepo.protocol.swift.SwiftPackageName;
import com.github.klboke.kkrepo.protocol.swift.SwiftPath;
import com.github.klboke.kkrepo.protocol.swift.SwiftPathParser;
import com.github.klboke.kkrepo.protocol.swift.SwiftPublishResponse;
import com.github.klboke.kkrepo.protocol.swift.SwiftReleaseList;
import com.github.klboke.kkrepo.protocol.swift.SwiftReleaseMetadata;
import com.github.klboke.kkrepo.protocol.swift.SwiftReleaseResource;
import com.github.klboke.kkrepo.protocol.swift.SwiftReleaseSigning;
import com.github.klboke.kkrepo.protocol.swift.SwiftRequestTarget;
import com.github.klboke.kkrepo.protocol.swift.SwiftScope;
import com.github.klboke.kkrepo.protocol.swift.SwiftToolsVersions;
import com.github.klboke.kkrepo.protocol.swift.SwiftVersions;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Swift Package Registry v1 implementation shared by hosted, GitHub proxy, and group recipes. */
@Service
public class SwiftService {
  private static final Logger LOGGER = LoggerFactory.getLogger(SwiftService.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final int MAX_METADATA_URLS = 100;
  private static final int MAX_METADATA_URL_LENGTH = 4096;
  private static final int MAX_REPOSITORY_URL_LENGTH = 1024;
  private static final Pattern METADATA_EMAIL =
      Pattern.compile("^[^\\s@]+@[^\\s@]+$");
  private static final Set<String> REPOSITORY_URL_SCHEMES =
      Set.of("http", "https", "ssh", "git");
  private static final String SIGNATURE_FORMAT = "cms-1.0.0";
  private static final String SOURCE_HOSTED = "HOSTED";
  private static final String SOURCE_PROXY = "GITHUB_PROXY";
  private static final String GENERATION_PROFILE = "github-zipball-v1";
  private static final String GITHUB_TAGS_FAILURE_CACHE_KEY = "upstream:github:tags";
  private static final long BAD_GATEWAY_CACHE_SECONDS = 15L;

  private final ObjectMapper mapper;
  private final SwiftRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final SwiftAssetSupport assets;
  private final SwiftArchiveInspector inspector;
  private final SwiftGitHubClient github;
  private final SwiftPublishLeaseManager leases;
  private final SwiftComponentService components;
  private final SecurityAuditDao audit;
  private final AccessDecisionService accessDecisions;
  private final SwiftPathParser paths = new SwiftPathParser();

  @Autowired
  public SwiftService(
      ObjectMapper mapper,
      SwiftRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      SwiftAssetSupport assets,
      SwiftArchiveInspector inspector,
      SwiftGitHubClient github,
      SwiftPublishLeaseManager leases,
      SwiftComponentService components,
      SecurityAuditDao audit,
      AccessDecisionService accessDecisions) {
    this.mapper = mapper;
    this.registry = registry;
    this.runtimes = runtimes;
    this.assets = assets;
    this.inspector = inspector;
    this.github = github;
    this.leases = leases;
    this.components = components;
    this.audit = audit;
    this.accessDecisions = accessDecisions;
  }

  public SwiftService(
      ObjectMapper mapper,
      SwiftRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      SwiftAssetSupport assets,
      SwiftArchiveInspector inspector,
      SwiftGitHubClient github,
      SwiftPublishLeaseManager leases,
      SwiftComponentService components,
      SecurityAuditDao audit) {
    this(
        mapper,
        registry,
        runtimes,
        assets,
        inspector,
        github,
        leases,
        components,
        audit,
        null);
  }

  public SwiftService(
      ObjectMapper mapper,
      SwiftRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      SwiftAssetSupport assets,
      SwiftArchiveInspector inspector,
      SwiftGitHubClient github,
      SwiftPublishLeaseManager leases,
      SwiftComponentService components) {
    this(mapper, registry, runtimes, assets, inspector, github, leases, components, null, null);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      String repositoryBaseUrl,
      String accept,
      boolean headOnly) {
    return get(
        runtime,
        rawPath,
        rawQuery,
        repositoryBaseUrl,
        accept,
        headOnly,
        null);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      String repositoryBaseUrl,
      String accept,
      boolean headOnly,
      PermissionSubject subject) {
    SwiftRequestTarget target = parse(
        rawPath, rawQuery, prefersReleaseMetadata(accept));
    SwiftPath path = target.path();
    return switch (path.kind()) {
      case RELEASE_LIST -> {
        negotiate(accept, SwiftMediaTypes.Resource.JSON);
        yield releaseList(runtime, path, repositoryBaseUrl, headOnly);
      }
      case RELEASE_METADATA -> {
        negotiate(accept, SwiftMediaTypes.Resource.JSON);
        yield releaseMetadata(runtime, path, repositoryBaseUrl, headOnly);
      }
      case MANIFEST -> {
        negotiate(accept, SwiftMediaTypes.Resource.MANIFEST);
        yield manifest(runtime, path, target.swiftVersion(), repositoryBaseUrl, headOnly);
      }
      case SOURCE_ARCHIVE -> {
        negotiate(accept, SwiftMediaTypes.Resource.ARCHIVE);
        yield archive(runtime, path, headOnly);
      }
      case IDENTIFIERS -> {
        negotiate(accept, SwiftMediaTypes.Resource.JSON);
        yield identifiers(runtime, target.repositoryUrl(), headOnly, subject);
      }
      case ROOT -> throw new SwiftExceptions.NotFound("Swift registry resource was not found");
      case LOGIN -> throw new SwiftExceptions.MethodNotAllowed("Swift login requires POST");
      case UNKNOWN -> throw new SwiftExceptions.NotFound("Swift registry resource was not found");
    };
  }

  public MavenResponse publish(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      Collection<Part> parts,
      String signatureFormat,
      String repositoryBaseUrl,
      String accept,
      String actor,
      String ip) {
    SwiftPath path = validatePublishRequest(runtime, rawPath, rawQuery, accept);
    SwiftMultipartRequest multipart = SwiftMultipartRequest.parse(parts);
    byte[] sourceSignature = boundedSignature(
        multipart.sourceArchiveSignature(),
        SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES,
        "source-archive-signature");
    byte[] metadataSignature = boundedSignature(
        multipart.metadataSignature(),
        SwiftPublishLimits.MAX_METADATA_SIGNATURE_BYTES,
        "metadata-signature");
    boolean signed = sourceSignature != null;
    String normalizedSignatureFormat = normalizeSignatureFormat(signatureFormat, signed);
    Map<String, Object> metadata = validateMetadata(multipart.metadataJson());
    String metadataJson = writeJson(metadata);
    String scopeLc = SwiftScope.key(path.scope());
    String nameLc = SwiftPackageName.key(path.name());
    String leaseKey = coordinateLeaseKey(runtime.id(), scopeLc, nameLc, path.version());

    if (registry.findTombstone(runtime.id(), scopeLc, nameLc, path.version()).isPresent()) {
      throw new SwiftExceptions.Conflict("This Swift release coordinate is tombstoned");
    }
    if (registry.findRelease(runtime.id(), scopeLc, nameLc, path.version()).isPresent()) {
      throw new SwiftExceptions.Conflict("Swift release already exists");
    }

    try (SwiftPublishLeaseManager.Lease lease = leases.acquire(
        leaseKey,
        () -> registry.findRelease(
            runtime.id(), scopeLc, nameLc, path.version()).isPresent())) {
      if (registry.findRelease(runtime.id(), scopeLc, nameLc, path.version()).isPresent()) {
        throw new SwiftExceptions.Conflict("Swift release already exists");
      }
      lease.assertHeld();
      SwiftArchiveInspector.InspectedArchive archive;
      try (var input = multipart.openArchive()) {
        archive = inspector.inspect(input);
      } catch (IOException e) {
        throw new SwiftExceptions.BadRequest("Unable to read source-archive", e);
      }
      try {
        List<SwiftComponentService.RepositoryUrlDraft> urls = repositoryUrls(metadata);
        SwiftRegistryDao.Release release = persistRelease(
            runtime,
            path.scope(),
            path.name(),
            path.version(),
            archive,
            metadataJson,
            sourceSignature,
            metadataSignature,
            normalizedSignatureFormat,
            SOURCE_HOSTED,
            urls,
            Instant.now(),
            actor,
            ip,
            lease);
        String location = releaseUrl(repositoryBaseUrl, release);
        return json(
            new SwiftPublishResponse("Package release successfully published", location),
            201,
            false)
            .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION)
            .withHeader("Location", location);
      } catch (DataIntegrityViolationException e) {
        throw new SwiftExceptions.Conflict("Swift release already exists");
      } finally {
        deleteQuietly(archive.file());
      }
    }
  }

  /**
   * Validates all header/path/repository conditions that do not require reading the multipart
   * body. Controllers call this before {@code getParts()} so invalid Expect: 100-continue uploads
   * can be rejected without accepting a potentially large archive.
   */
  public SwiftPath validatePublishRequest(
      RepositoryRuntime runtime, String rawPath, String rawQuery, String accept) {
    if (runtime == null || !runtime.isHosted()) {
      throw new SwiftExceptions.MethodNotAllowed(
          "Swift proxy and group repositories are read-only");
    }
    requireHostedWritable(runtime);
    SwiftRequestTarget target;
    try {
      target = paths.parseReleaseMetadata(rawPath, rawQuery);
    } catch (IllegalArgumentException e) {
      throw new SwiftExceptions.BadRequest(e.getMessage(), e);
    }
    SwiftPath path = target.path();
    if (path.kind() != SwiftPath.Kind.RELEASE_METADATA
        || rawPath == null
        || path.jsonAlias()) {
      throw new SwiftExceptions.MethodNotAllowed(
          "Swift publish requires /{scope}/{name}/{version}");
    }
    negotiate(accept, SwiftMediaTypes.Resource.JSON);
    String scopeLc = SwiftScope.key(path.scope());
    String nameLc = SwiftPackageName.key(path.name());
    if (registry.findTombstone(runtime.id(), scopeLc, nameLc, path.version()).isPresent()) {
      throw new SwiftExceptions.Conflict("This Swift release coordinate is tombstoned");
    }
    if (registry.findRelease(runtime.id(), scopeLc, nameLc, path.version()).isPresent()) {
      throw new SwiftExceptions.Conflict("Swift release already exists");
    }
    return path;
  }

  public MavenResponse login(RepositoryRuntime runtime) {
    if (runtime == null || !runtime.online()) {
      throw new SwiftExceptions.NotFound("Swift repository is offline");
    }
    return MavenResponse.noBody(200)
        .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION)
        .withHeader("Cache-Control", "no-store");
  }

  /**
   * Admin/UI upload entry point. It deliberately reuses the exact immutable publish validation and
   * persistence path used by SwiftPM instead of writing a ZIP as an untyped Raw asset.
   */
  public MavenResponse publishUpload(
      RepositoryRuntime runtime,
      String scope,
      String name,
      String version,
      InputStream archiveStream,
      String metadataJson,
      byte[] sourceSignature,
      byte[] metadataSignature,
      String signatureFormat,
      String repositoryBaseUrl,
      String actor,
      String ip) {
    if (runtime == null || !runtime.isHosted()) {
      throw new SwiftExceptions.MethodNotAllowed(
          "Swift UI/API upload requires a hosted repository");
    }
    requireHostedWritable(runtime);
    try {
      SwiftScope.require(scope);
      SwiftPackageName.require(name);
      SwiftVersions.require(version);
    } catch (IllegalArgumentException e) {
      throw new SwiftExceptions.BadRequest(e.getMessage(), e);
    }
    byte[] normalizedSourceSignature = boundedSignature(
        sourceSignature,
        SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES,
        "source-archive-signature");
    byte[] normalizedMetadataSignature = boundedSignature(
        metadataSignature,
        SwiftPublishLimits.MAX_METADATA_SIGNATURE_BYTES,
        "metadata-signature");
    String normalizedFormat = normalizeSignatureFormat(
        signatureFormat, normalizedSourceSignature != null);
    Map<String, Object> metadata = validateMetadata(
        metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson);
    String scopeLc = SwiftScope.key(scope);
    String nameLc = SwiftPackageName.key(name);
    if (registry.findTombstone(runtime.id(), scopeLc, nameLc, version).isPresent()
        || registry.findRelease(runtime.id(), scopeLc, nameLc, version).isPresent()) {
      throw new SwiftExceptions.Conflict("Swift release already exists or is tombstoned");
    }
    try (SwiftPublishLeaseManager.Lease lease = leases.acquire(
        coordinateLeaseKey(runtime.id(), scopeLc, nameLc, version),
        () -> registry.findRelease(runtime.id(), scopeLc, nameLc, version).isPresent())) {
      if (registry.findRelease(runtime.id(), scopeLc, nameLc, version).isPresent()) {
        throw new SwiftExceptions.Conflict("Swift release already exists");
      }
      lease.assertHeld();
      SwiftArchiveInspector.InspectedArchive archive = inspector.inspect(archiveStream);
      try {
        SwiftRegistryDao.Release release = persistRelease(
            runtime,
            scope,
            name,
            version,
            archive,
            writeJson(metadata),
            normalizedSourceSignature,
            normalizedMetadataSignature,
            normalizedFormat,
            SOURCE_HOSTED,
            repositoryUrls(metadata),
            Instant.now(),
            actor,
            ip,
            lease);
        String base = repositoryBaseUrl == null || repositoryBaseUrl.isBlank()
            ? "/repository/" + runtime.name() + "/"
            : repositoryBaseUrl;
        String location = releaseUrl(base, release);
        return json(
            new SwiftPublishResponse("Package release successfully published", location),
            201,
            false)
            .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION)
            .withHeader("Location", location);
      } catch (DataIntegrityViolationException e) {
        throw new SwiftExceptions.Conflict("Swift release already exists");
      } finally {
        deleteQuietly(archive.file());
      }
    }
  }

  /**
   * Restores a Nexus hosted release through the same validation and persistence path as a SwiftPM
   * publication. Migration retries are idempotent, while a tombstoned coordinate remains blocked.
   */
  public SwiftRegistryDao.Release restoreHostedReleaseForMigration(
      RepositoryRuntime runtime,
      String scope,
      String name,
      String version,
      InputStream archiveStream,
      String metadataJson,
      byte[] sourceSignature,
      byte[] metadataSignature,
      String signatureFormat,
      Instant publishedAt,
      String actor,
      String ip) {
    if (runtime == null || !runtime.isHosted()) {
      throw new SwiftExceptions.MethodNotAllowed(
          "Swift hosted data migration requires a hosted repository");
    }
    try {
      SwiftScope.require(scope);
      SwiftPackageName.require(name);
      SwiftVersions.require(version);
    } catch (IllegalArgumentException e) {
      throw new SwiftExceptions.BadRequest(e.getMessage(), e);
    }
    String scopeLc = SwiftScope.key(scope);
    String nameLc = SwiftPackageName.key(name);
    Optional<SwiftRegistryDao.Release> existing =
        registry.findRelease(runtime.id(), scopeLc, nameLc, version);
    if (existing.isPresent()) {
      return existing.get();
    }
    if (registry.findTombstone(runtime.id(), scopeLc, nameLc, version).isPresent()) {
      throw new SwiftExceptions.Conflict("This Swift release coordinate is tombstoned");
    }
    byte[] normalizedSourceSignature = boundedSignature(
        sourceSignature,
        SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES,
        "source-archive-signature");
    byte[] normalizedMetadataSignature = boundedSignature(
        metadataSignature,
        SwiftPublishLimits.MAX_METADATA_SIGNATURE_BYTES,
        "metadata-signature");
    String normalizedFormat = normalizeSignatureFormat(
        signatureFormat, normalizedSourceSignature != null);
    Map<String, Object> metadata = validateMetadata(
        metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson);
    String leaseKey = coordinateLeaseKey(runtime.id(), scopeLc, nameLc, version);
    try (SwiftPublishLeaseManager.Lease lease = leases.acquire(leaseKey)) {
      Optional<SwiftRegistryDao.Release> afterLease =
          registry.findRelease(runtime.id(), scopeLc, nameLc, version);
      if (afterLease.isPresent()) {
        return afterLease.get();
      }
      SwiftArchiveInspector.InspectedArchive archive = inspector.inspect(archiveStream);
      try {
        lease.assertHeld();
        return persistRelease(
            runtime,
            scope,
            name,
            version,
            archive,
            writeJson(metadata),
            normalizedSourceSignature,
            normalizedMetadataSignature,
            normalizedFormat,
            "NEXUS_MIGRATION",
            repositoryUrls(metadata),
            publishedAt == null ? Instant.now() : publishedAt,
            actor == null || actor.isBlank() ? "nexus-migration" : actor,
            ip,
            lease);
      } catch (DataIntegrityViolationException e) {
        return registry.findRelease(runtime.id(), scopeLc, nameLc, version)
            .orElseThrow(() -> new SwiftExceptions.Conflict(
                "Another replica restored this Swift release"));
      } finally {
        deleteQuietly(archive.file());
      }
    }
  }

  private MavenResponse releaseList(
      RepositoryRuntime runtime, SwiftPath request, String baseUrl, boolean headOnly) {
    RepositoryRuntime snapshot = currentGroupSnapshot(runtime);
    String scopeLc = SwiftScope.key(request.scope());
    String nameLc = SwiftPackageName.key(request.name());
    LinkedHashMap<String, VersionState> states = listedVersions(
        snapshot, scopeLc, nameLc, new LinkedHashSet<>());
    LinkedHashSet<String> versions = new LinkedHashSet<>();
    LinkedHashMap<String, String> unavailable = new LinkedHashMap<>();
    states.forEach((version, state) -> {
      if (state.available()) {
        versions.add(version);
      } else {
        unavailable.put(version, state.unavailableReason());
      }
    });
    if (versions.isEmpty() && unavailable.isEmpty()) {
      throw new SwiftExceptions.NotFound("Swift package was not found");
    }
    SwiftReleaseList body = SwiftReleaseList.listed(
        versions,
        unavailable,
        version -> releaseUrl(baseUrl, request.scope(), request.name(), version));
    MavenResponse response = json(body, 200, headOnly, releaseListRevisionState(snapshot))
        .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION);
    List<SwiftLink> links = new ArrayList<>();
    if (!versions.isEmpty()) {
      String latest = SwiftVersions.sortDescending(versions).getFirst();
      links.add(SwiftLink.of(
          releaseUrl(baseUrl, request.scope(), request.name(), latest), "latest-version"));
    }
    LinkedHashSet<String> repositoryUrls = new LinkedHashSet<>();
    collectReleaseListRepositoryUrls(
        snapshot,
        scopeLc,
        nameLc,
        repositoryUrls,
        new LinkedHashSet<>());
    for (String repositoryUrl : repositoryUrls) {
      links.add(SwiftLink.of(
          repositoryUrl,
          links.stream().noneMatch(link -> "canonical".equals(link.relation()))
              ? "canonical"
              : "alternate"));
    }
    if (!links.isEmpty()) {
      response.withHeader("Link", SwiftLinkHeader.render(links));
    }
    return response;
  }

  private void collectReleaseListRepositoryUrls(
      RepositoryRuntime runtime,
      String scopeLc,
      String nameLc,
      Set<String> urls,
      Set<Long> visiting) {
    if (runtime == null || !runtime.online() || !visiting.add(runtime.id())) {
      return;
    }
    try {
      if (runtime.isProxy()) {
        urls.add(SwiftGitHubClient.coordinates(scopeLc, nameLc).repositoryUrl());
        return;
      }
      if (runtime.isHosted()) {
        registry.listRepositoryUrls(runtime.id(), scopeLc, nameLc).stream()
            .map(SwiftRegistryDao.RepositoryUrl::normalizedUrl)
            .forEach(urls::add);
        return;
      }
      for (RepositoryRuntime member : runtime.members()) {
        collectReleaseListRepositoryUrls(member, scopeLc, nameLc, urls, visiting);
      }
    } finally {
      visiting.remove(runtime.id());
    }
  }

  private MavenResponse releaseMetadata(
      RepositoryRuntime runtime, SwiftPath request, String baseUrl, boolean headOnly) {
    ResolvedRelease resolved = resolveRelease(runtime, request, new LinkedHashSet<>());
    SwiftRegistryDao.Release release = resolved.release();
    Map<String, Object> metadata = readMetadata(release.metadataJson());
    SwiftReleaseSigning signing = signing(release);
    SwiftReleaseMetadata body = new SwiftReleaseMetadata(
        release.scopeDisplay() + "." + release.nameDisplay(),
        release.version(),
        List.of(SwiftReleaseResource.sourceArchive(release.archiveSha256(), signing)),
        metadata,
        release.publishedAt());
    MavenResponse response = json(body, 200, headOnly)
        .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION);
    // Group runtimes supplied by request routing are recoverable node-local cache entries. Resolve
    // current membership again before calculating navigation links so a cross-replica member
    // update cannot leak stale latest/predecessor/successor relations into fresh metadata.
    addReleaseLinks(response, currentGroupSnapshot(runtime), release, baseUrl);
    return response;
  }

  private MavenResponse manifest(
      RepositoryRuntime runtime,
      SwiftPath request,
      String requestedToolsVersion,
      String baseUrl,
      boolean headOnly) {
    ResolvedRelease resolved = resolveRelease(runtime, request, new LinkedHashSet<>());
    SwiftRegistryDao.Release release = resolved.release();
    String lookup = requestedToolsVersion == null ? "" : requestedToolsVersion;
    Optional<SwiftRegistryDao.Manifest> selected = registry.findManifest(release.id(), lookup);
    if (selected.isEmpty() && requestedToolsVersion != null) {
      return MavenResponse.noBody(303)
          .withHeader("Location", releaseUrl(baseUrl, release) + "/Package.swift")
          .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION);
    }
    SwiftRegistryDao.Manifest manifest = selected
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift package manifest was not found"));
    MavenResponse response = assets.serve(release.repositoryId(), manifest.assetId(), headOnly)
        .withHeader("Content-Disposition", "attachment; filename=\"" + manifest.filename() + "\"")
        .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION);
    List<SwiftLink> links = new ArrayList<>();
    for (SwiftRegistryDao.Manifest candidate : registry.listManifests(release.id())) {
      if (candidate.toolsVersion() == null || candidate.toolsVersion().isBlank()) {
        continue;
      }
      String declared = declaredToolsVersion(release.repositoryId(), candidate);
      links.add(new SwiftLink(
          URI.create(releaseUrl(baseUrl, release)
              + "/Package.swift?swift-version=" + candidate.toolsVersion()),
          "alternate",
          Map.of("filename", candidate.filename(), "swift-tools-version", declared)));
    }
    if (!links.isEmpty()) {
      response.withHeader("Link", SwiftLinkHeader.render(links));
    }
    return response;
  }

  private MavenResponse archive(
      RepositoryRuntime runtime, SwiftPath request, boolean headOnly) {
    ResolvedRelease resolved = resolveRelease(runtime, request, new LinkedHashSet<>());
    SwiftRegistryDao.Release release = resolved.release();
    byte[] sourceSignature = release.signatureFormat() != null
            && release.sourceSignatureAssetId() != null
        ? boundedSignature(
            assets.bytes(release.repositoryId(), release.sourceSignatureAssetId()),
            SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES,
            "Stored source-archive-signature")
        : null;
    MavenResponse response = assets.serve(release.repositoryId(), release.archiveAssetId(), headOnly)
        .withHeader("Accept-Ranges", "bytes")
        .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION)
        .withHeader("Digest", "sha-256=" + base64Sha256(release.archiveSha256()))
        .withHeader(
            "Content-Disposition",
            "attachment; filename=\"" + safeFilename(release.nameDisplay()) + "-"
                + release.version() + ".zip\"");
    if (sourceSignature != null) {
      response.withHeader("X-Swift-Package-Signature-Format", release.signatureFormat());
      response.withHeader(
          "X-Swift-Package-Signature",
          Base64.getEncoder().encodeToString(sourceSignature));
    }
    return response;
  }

  private MavenResponse identifiers(
      RepositoryRuntime runtime,
      String repositoryUrl,
      boolean headOnly,
      PermissionSubject subject) {
    RepositoryRuntime snapshot = currentGroupSnapshot(runtime);
    LinkedHashMap<String, String> identities = new LinkedHashMap<>();
    collectIdentifiers(
        snapshot,
        repositoryUrl,
        identities,
        new LinkedHashSet<>(),
        subject,
        false);
    if (identities.isEmpty()) {
      throw new SwiftExceptions.NotFound("No Swift package identity was found for this URL");
    }
    return json(new SwiftIdentifiers(new ArrayList<>(identities.values())), 200, headOnly)
        .withHeader("Content-Version", SwiftMediaTypes.CONTENT_VERSION);
  }

  private void collectIdentifiers(
      RepositoryRuntime runtime,
      String repositoryUrl,
      Map<String, String> identities,
      Set<Long> visiting,
      PermissionSubject subject,
      boolean requireReadPermission) {
    if (runtime == null
        || !runtime.online()
        || (requireReadPermission && !canReadIdentifiers(runtime, subject))
        || !visiting.add(runtime.id())) {
      return;
    }
    try {
      if (runtime.isGroup()) {
        for (RepositoryRuntime member : runtime.members()) {
          collectIdentifiers(member, repositoryUrl, identities, visiting, subject, true);
        }
        return;
      }
      if (runtime.isProxy()) {
        SwiftGitHubClient.coordinatesFromUrl(repositoryUrl).ifPresent(coordinates -> {
          try {
            String value = new SwiftPackageIdentity(
                coordinates.owner(), coordinates.repository()).value();
            identities.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
          } catch (IllegalArgumentException ignored) {
            // Some valid GitHub names cannot be represented by a Swift registry identity.
          }
        });
      }
      String normalized = normalizeRepositoryUrl(repositoryUrl);
      for (SwiftRegistryDao.PackageIdentity identity
          : registry.findIdentities(runtime.id(), normalized)) {
        String value = identity.scopeDisplay() + "." + identity.nameDisplay();
        identities.putIfAbsent(
            identity.scopeLc() + "." + identity.nameLc(), value);
      }
    } finally {
      visiting.remove(runtime.id());
    }
  }

  private boolean canReadIdentifiers(
      RepositoryRuntime runtime, PermissionSubject subject) {
    // RepositorySecurityFilter authorizes the externally addressed group. Identifiers are an
    // enumeration endpoint, so additionally apply that same READ decision to every member before
    // consulting member metadata or deriving a proxy identity.
    if (accessDecisions == null || subject == null) {
      return false;
    }
    return accessDecisions.decide(
        subject,
        new RepositoryPermission(
            runtime.name(), runtime.format(), "identifiers", PermissionAction.READ))
        .allowed();
  }

  private RepositoryRuntime currentGroupSnapshot(RepositoryRuntime runtime) {
    if (!runtime.isGroup()) {
      return runtime;
    }
    // The request router can supply a recoverable TTL-cached runtime. Resolve by id once so every
    // release-list/identifier aggregation pass (body, links, tombstones, and ETag state) traverses
    // one current, DB-backed membership snapshot across replicas.
    return runtimes.resolveById(runtime.id())
        .filter(RepositoryRuntime::isGroup)
        .filter(RepositoryRuntime::online)
        .filter(snapshot -> snapshot.format() == runtime.format())
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift group was not found"));
  }

  private Set<String> availableVersions(
      RepositoryRuntime runtime, String scopeLc, String nameLc, Set<Long> visiting) {
    LinkedHashSet<String> available = new LinkedHashSet<>();
    listedVersions(runtime, scopeLc, nameLc, visiting).forEach((version, state) -> {
      if (state.available()) {
        available.add(version);
      }
    });
    return available;
  }

  /**
   * Returns the first observed state for every version while traversing group members in order.
   * A tombstone is a real member result, not a miss, so a later member cannot revive that version.
   */
  private LinkedHashMap<String, VersionState> listedVersions(
      RepositoryRuntime runtime, String scopeLc, String nameLc, Set<Long> visiting) {
    LinkedHashMap<String, VersionState> states = new LinkedHashMap<>();
    if (runtime == null || !runtime.online() || !visiting.add(runtime.id())) {
      return states;
    }
    try {
      if (runtime.isHosted() || runtime.isProxy()) {
        List<SwiftRegistryDao.Tombstone> tombstones =
            registry.listTombstones(runtime.id(), scopeLc, nameLc);
        tombstones.forEach(tombstone ->
            states.putIfAbsent(
                tombstone.version(), VersionState.unavailable(tombstone.reason())));
        Set<String> available = runtime.isHosted()
            ? readyVersions(runtime.id(), scopeLc, nameLc)
            : proxyVersions(runtime, scopeLc, nameLc, tombstones);
        available.forEach(version -> states.putIfAbsent(version, VersionState.availableVersion()));
        return states;
      }
      SwiftExceptions.SwiftException upstreamFailure = null;
      for (RepositoryRuntime member : runtime.members()) {
        try {
          listedVersions(member, scopeLc, nameLc, visiting).forEach(states::putIfAbsent);
        } catch (SwiftExceptions.UpstreamRateLimited | SwiftExceptions.BadUpstream e) {
          if (upstreamFailure == null) {
            upstreamFailure = e;
          }
        }
      }
      if (states.isEmpty() && upstreamFailure != null) {
        throw upstreamFailure;
      }
      return states;
    } finally {
      visiting.remove(runtime.id());
    }
  }

  private Set<String> proxyVersions(
      RepositoryRuntime runtime,
      String scopeLc,
      String nameLc,
      List<SwiftRegistryDao.Tombstone> tombstones) {
    String cacheKey = "tags:" + scopeLc + "/" + nameLc;
    List<SwiftRegistryDao.ProxySource> cachedSources =
        registry.listProxySources(runtime.id(), scopeLc, nameLc);
    Set<String> tombstoned = tombstoneVersions(tombstones);
    Set<String> stale = persistedProxyVersions(cachedSources, tombstoned);
    Optional<SwiftRegistryDao.NegativeCache> negative =
        registry.findNegativeCache(runtime.id(), cacheKey);
    if (negative.isPresent() && negative.get().expiresAt().isAfter(Instant.now())) {
      if (!stale.isEmpty()) {
        return stale;
      }
      if (negative.get().statusCode() == 429) {
        throw new SwiftExceptions.UpstreamRateLimited(
            "GitHub tag metadata is temporarily rate limited",
            negative.get().retryAfter());
      }
      if (negative.get().statusCode() == 404 || negative.get().statusCode() == 410) {
        return Set.of();
      }
      throw new SwiftExceptions.BadUpstream(
          "GitHub tag metadata is temporarily unavailable (cached status "
              + negative.get().statusCode() + ")");
    }
    if (proxyMetadataFresh(cachedSources, runtime.metadataMaxAgeMinutesOrDefault())) {
      return stale;
    }
    try {
      List<SwiftRegistryDao.ProxySource> refreshed =
          refreshProxySources(runtime, scopeLc, nameLc, cacheKey);
      Set<String> refreshedTombstones = tombstoneVersions(
          registry.listTombstones(runtime.id(), scopeLc, nameLc));
      Set<String> versions = persistedProxyVersions(refreshed, refreshedTombstones);
      if (versions.isEmpty()) {
        rememberNotFound(runtime.id(), cacheKey);
      }
      return versions;
    } catch (SwiftExceptions.NotFound e) {
      rememberNotFound(runtime.id(), cacheKey);
      if (!stale.isEmpty()) {
        return stale;
      }
      return Set.of();
    } catch (SwiftExceptions.UpstreamRateLimited e) {
      rememberRateLimit(runtime.id(), cacheKey, e.retryAfter());
      if (!stale.isEmpty()) {
        return stale;
      }
      throw e;
    } catch (SwiftExceptions.BadUpstream e) {
      if (!stale.isEmpty()) {
        return stale;
      }
      throw e;
    }
  }

  private static Set<String> persistedProxyVersions(
      List<SwiftRegistryDao.ProxySource> sources,
      Set<String> tombstones) {
    LinkedHashSet<String> versions = new LinkedHashSet<>();
    if (sources != null) {
      for (SwiftRegistryDao.ProxySource source : sources) {
        if (!tombstones.contains(source.version())) {
          versions.add(source.version());
        }
      }
    }
    return versions;
  }

  private static Set<String> tombstoneVersions(
      List<SwiftRegistryDao.Tombstone> tombstones) {
    if (tombstones == null || tombstones.isEmpty()) {
      return Set.of();
    }
    return tombstones.stream()
        .map(SwiftRegistryDao.Tombstone::version)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static boolean proxyMetadataFresh(
      List<SwiftRegistryDao.ProxySource> sources, int ttlMinutes) {
    if (sources == null || sources.isEmpty()) {
      return false;
    }
    if (ttlMinutes < 0) {
      return true;
    }
    Instant newestCheck = sources.stream()
        .map(SwiftRegistryDao.ProxySource::lastCheckedAt)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);
    return newestCheck != null
        && newestCheck.plusSeconds(Math.max(0, ttlMinutes) * 60L).isAfter(Instant.now());
  }

  private Set<String> readyVersions(long repositoryId, String scopeLc, String nameLc) {
    return registry.listReleases(repositoryId, scopeLc, nameLc).stream()
        .filter(release -> SwiftRegistryDao.RELEASE_READY.equals(release.status()))
        .map(SwiftRegistryDao.Release::version)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private ResolvedRelease resolveRelease(
      RepositoryRuntime runtime, SwiftPath path, Set<Long> visiting) {
    if (runtime == null || !runtime.online() || !visiting.add(runtime.id())) {
      throw new SwiftExceptions.NotFound("Swift release was not found");
    }
    String scopeLc = SwiftScope.key(path.scope());
    String nameLc = SwiftPackageName.key(path.name());
    try {
      if (runtime.isHosted()) {
        SwiftRegistryDao.Release release = registry.findRelease(
                runtime.id(), scopeLc, nameLc, path.version())
            .filter(row -> SwiftRegistryDao.RELEASE_READY.equals(row.status()))
            .orElseThrow(() -> new SwiftExceptions.NotFound("Swift release was not found"));
        return new ResolvedRelease(runtime, release);
      }
      if (runtime.isProxy()) {
        return new ResolvedRelease(
            runtime, materializeProxy(runtime, path.scope(), path.name(), path.version()));
      }
      return resolveGroup(runtime, path, visiting, false);
    } finally {
      visiting.remove(runtime.id());
    }
  }

  private ResolvedRelease resolveGroup(
      RepositoryRuntime group,
      SwiftPath path,
      Set<Long> visiting,
      boolean retriedAfterConfigurationChange) {
    String scopeLc = SwiftScope.key(path.scope());
    String nameLc = SwiftPackageName.key(path.name());
    long configRevision = currentGroupConfigurationRevision(group.id());
    Optional<SwiftRegistryDao.GroupSourceBinding> existing =
        registry.findGroupSourceBinding(group.id(), scopeLc, nameLc, path.version());
    if (existing.isPresent() && existing.get().groupConfigRevision() == configRevision) {
      Optional<ResolvedRelease> bound = resolveBoundRelease(existing.get());
      if (bound.isPresent()) {
        return bound.get();
      }
    }
    // The runtime passed by the request router can be a recoverable node-local cache snapshot.
    // Read the shared revision first, then reload membership directly from MySQL. If membership
    // changes on either side of this read, the exact-revision write below is fenced and retried.
    RepositoryRuntime snapshot = runtimes.resolveById(group.id())
        .filter(RepositoryRuntime::isGroup)
        .filter(RepositoryRuntime::online)
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift group was not found"));
    SwiftExceptions.SwiftException upstreamFailure = null;
    for (RepositoryRuntime member : snapshot.members()) {
      VersionState state;
      try {
        state = listedVersions(member, scopeLc, nameLc, visiting).get(path.version());
      } catch (SwiftExceptions.UpstreamRateLimited | SwiftExceptions.BadUpstream e) {
        if (upstreamFailure == null) {
          upstreamFailure = e;
        }
        continue;
      }
      if (state == null) {
        continue;
      }
      if (!state.available()) {
        throw new SwiftExceptions.NotFound("Swift release was permanently deleted");
      }
      ResolvedRelease winner;
      try {
        winner = resolveRelease(member, path, visiting);
      } catch (SwiftExceptions.NotFound ignored) {
        // The advertised version disappeared between discovery and materialization. This is a
        // member miss, unlike the terminal tombstone state handled above.
        continue;
      } catch (SwiftExceptions.UpstreamRateLimited | SwiftExceptions.BadUpstream e) {
        if (upstreamFailure == null) {
          upstreamFailure = e;
        }
        continue;
      }
      SwiftRegistryDao.Release release = winner.release();
      boolean current = registry.upsertGroupSourceBindingIfCurrent(
          new SwiftRegistryDao.GroupSourceBinding(
              group.id(),
              scopeLc,
              nameLc,
              path.version(),
              member.id(),
              release.id(),
              release.revision(),
              configRevision,
              Instant.now()));
      if (current) {
        Optional<ResolvedRelease> canonical = registry.findGroupSourceBinding(
                group.id(), scopeLc, nameLc, path.version())
            .filter(binding -> binding.groupConfigRevision() == configRevision)
            .flatMap(this::resolveBoundRelease);
        if (canonical.isPresent()) {
          return canonical.get();
        }
        if (retriedAfterConfigurationChange) {
          throw new SwiftExceptions.Conflict(
              "Swift group source binding changed while resolving the release");
        }
        return resolveGroup(snapshot, path, visiting, true);
      }
      if (retriedAfterConfigurationChange) {
        throw new SwiftExceptions.Conflict(
            "Swift group configuration changed while resolving the release");
      }
      return resolveGroup(snapshot, path, visiting, true);
    }
    if (upstreamFailure != null) {
      throw upstreamFailure;
    }
    throw new SwiftExceptions.NotFound("Swift release was not found in group members");
  }

  private Optional<ResolvedRelease> resolveBoundRelease(
      SwiftRegistryDao.GroupSourceBinding binding) {
    return registry.findReleaseById(binding.memberReleaseId())
        .filter(row -> row.revision() == binding.memberRevision())
        .filter(row -> SwiftRegistryDao.RELEASE_READY.equals(row.status()))
        .flatMap(release -> runtimes.resolveById(release.repositoryId())
            .map(memberRuntime -> new ResolvedRelease(memberRuntime, release)));
  }

  private SwiftRegistryDao.Release materializeProxy(
      RepositoryRuntime runtime, String scope, String name, String version) {
    String scopeLc = SwiftScope.key(scope);
    String nameLc = SwiftPackageName.key(name);
    if (registry.findTombstone(runtime.id(), scopeLc, nameLc, version).isPresent()) {
      throw new SwiftExceptions.NotFound("Swift release was permanently deleted");
    }
    Optional<SwiftRegistryDao.Release> ready = registry.findRelease(
        runtime.id(), scopeLc, nameLc, version);
    if (ready.isPresent()) {
      return ready.get();
    }
    SwiftGitHubClient.Coordinates coordinates = SwiftGitHubClient.coordinates(scopeLc, nameLc);
    SwiftRegistryDao.ProxySource source = registry.findProxySource(
            runtime.id(), scopeLc, nameLc, version)
        .orElseGet(() -> refreshProxySources(
            runtime, scopeLc, nameLc, "tags:" + scopeLc + "/" + nameLc).stream()
            .filter(candidate -> candidate.version().equals(version))
            .findFirst()
            .orElseThrow(() -> new SwiftExceptions.NotFound("GitHub tag was not found")));
    if (source.releaseId() != null) {
      Optional<SwiftRegistryDao.Release> release = registry.findReleaseById(source.releaseId());
      if (release.isPresent()) {
        return release.get();
      }
    }

    String leaseKey = coordinateLeaseKey(runtime.id(), scopeLc, nameLc, version);
    try (SwiftPublishLeaseManager.Lease lease = leases.acquireForCoalescedRead(leaseKey)) {
      if (registry.findTombstone(runtime.id(), scopeLc, nameLc, version).isPresent()) {
        throw new SwiftExceptions.NotFound("Swift release was permanently deleted");
      }
      Optional<SwiftRegistryDao.Release> afterLease = registry.findRelease(
          runtime.id(), scopeLc, nameLc, version);
      if (afterLease.isPresent()) {
        return afterLease.get();
      }
      SwiftRegistryDao.ProxySource pinned = registry.findProxySource(
              runtime.id(), scopeLc, nameLc, version)
          .orElse(source);
      String archiveCacheKey = "archive:" + scopeLc + "/" + nameLc + "/" + version;
      throwIfNegativeCached(runtime.id(), archiveCacheKey);
      SwiftArchiveInspector.InspectedArchive archive;
      try {
        archive = github.archive(runtime, coordinates, pinned.commitSha());
      } catch (SwiftExceptions.UpstreamRateLimited e) {
        rememberRateLimit(runtime.id(), archiveCacheKey, e.retryAfter());
        throw e;
      } catch (SwiftExceptions.NotFound e) {
        rememberNotFound(runtime.id(), archiveCacheKey);
        throw e;
      }
      try {
        lease.assertHeld();
        Map<String, Object> metadata = Map.of(
            "repositoryURLs", List.of(coordinates.repositoryUrl()));
        SwiftRegistryDao.Release release = persistRelease(
            runtime,
            scope,
            name,
            version,
            archive,
            writeJson(metadata),
            null,
            null,
            null,
            SOURCE_PROXY,
            List.of(new SwiftComponentService.RepositoryUrlDraft(
                normalizeRepositoryUrl(coordinates.repositoryUrl()),
                coordinates.repositoryUrl())),
            Instant.now(),
            "swift-github-proxy",
            null,
            new SwiftComponentService.ProxyCompletion(
                pinned.commitSha(),
                Instant.now(),
                lease.key(),
                lease.owner(),
                lease.fencingToken()));
        return release;
      } catch (DataIntegrityViolationException e) {
        return registry.findRelease(runtime.id(), scopeLc, nameLc, version)
            .orElseThrow(() -> new SwiftExceptions.Conflict(
                "Another replica materialized this Swift release"));
      } finally {
        deleteQuietly(archive.file());
      }
    }
  }

  private List<SwiftRegistryDao.ProxySource> refreshProxySources(
      RepositoryRuntime runtime,
      String scopeLc,
      String nameLc,
      String cacheKey) {
    try (SwiftPublishLeaseManager.Lease lease = leases.acquire(
        tagRefreshLeaseKey(runtime.id(), scopeLc, nameLc))) {
      List<SwiftRegistryDao.ProxySource> current =
          registry.listProxySources(runtime.id(), scopeLc, nameLc);
      if (proxyMetadataFresh(current, runtime.metadataMaxAgeMinutesOrDefault())) {
        return current;
      }

      SwiftGitHubClient.Coordinates coordinates = SwiftGitHubClient.coordinates(scopeLc, nameLc);
      List<SwiftGitHubClient.Tag> tags = fetchProxyTags(runtime, coordinates, cacheKey);
      lease.assertHeld();
      Set<String> tombstones = tombstoneVersions(
          registry.listTombstones(runtime.id(), scopeLc, nameLc));
      Map<String, SwiftRegistryDao.ProxySource> existing = new LinkedHashMap<>();
      current.forEach(source -> existing.put(source.version(), source));
      LinkedHashMap<String, SwiftGitHubClient.Tag> observed = new LinkedHashMap<>();
      tags.stream()
          .filter(tag -> !tombstones.contains(tag.version()))
          .forEach(tag -> observed.putIfAbsent(tag.version(), tag));
      boolean inventoryChanged = !existing.keySet().equals(observed.keySet());
      long discoveryRevision = inventoryChanged
          ? registry.nextRepositoryRevision(runtime.id())
          : 0L;
      Instant now = Instant.now();
      List<SwiftRegistryDao.ProxySource> candidates = observed.values().stream()
          .map(tag -> {
            SwiftRegistryDao.ProxySource previous = existing.get(tag.version());
            return new SwiftRegistryDao.ProxySource(
                runtime.id(),
                scopeLc,
                nameLc,
                tag.version(),
                coordinates.repositoryUrl(),
                tag.tag(),
                tag.commitSha(),
                GENERATION_PROFILE,
                null,
                "DISCOVERED",
                null,
                null,
                previous == null ? discoveryRevision : previous.revision(),
                previous == null ? 0L : previous.observedCount(),
                now);
          })
          .toList();
      List<SwiftRegistryDao.ProxySource> bound = registry.replaceProxySources(
          runtime.id(), scopeLc, nameLc, candidates);
      observed.values().forEach(tag -> Optional.ofNullable(existing.get(tag.version()))
          .filter(previous -> !previous.commitSha().equalsIgnoreCase(tag.commitSha()))
          .ifPresent(previous -> auditMovedProxyTag(
              runtime, coordinates, tag, previous, now)));
      return bound;
    }
  }

  private void auditMovedProxyTag(
      RepositoryRuntime runtime,
      SwiftGitHubClient.Coordinates coordinates,
      SwiftGitHubClient.Tag observed,
      SwiftRegistryDao.ProxySource pinned,
      Instant observedAt) {
    LOGGER.warn(
        "Ignoring moved Swift proxy tag repository={} coordinate={}.{}@{} pinnedCommit={} observedCommit={}",
        runtime.name(), coordinates.owner(), coordinates.repository(), observed.version(),
        pinned.commitSha(), observed.commitSha());
    if (audit == null) {
      return;
    }
    try {
      audit.insert(new SecurityAuditDao.AuditLogRecord(
          LocalDateTime.now(),
          "swift-proxy",
          null,
          null,
          null,
          null,
          "PROXY",
          "/repository/" + runtime.name() + "/"
              + coordinates.owner() + "/" + coordinates.repository() + "/" + observed.version(),
          null,
          409,
          "CONFLICT",
          Map.of(
              "event", "swift_proxy_tag_moved",
              "repository", runtime.name(),
              "coordinate", coordinates.identity() + "@" + observed.version(),
              "upstreamTag", observed.tag(),
              "pinnedCommit", pinned.commitSha(),
              "observedCommit", observed.commitSha(),
              "observedAt", observedAt.toString())));
    } catch (RuntimeException ignored) {
      // Detection must preserve the immutable pinned release even if audit storage is unavailable.
    }
  }

  private List<SwiftGitHubClient.Tag> fetchProxyTags(
      RepositoryRuntime runtime,
      SwiftGitHubClient.Coordinates coordinates,
      String cacheKey) {
    throwIfNegativeCached(runtime.id(), GITHUB_TAGS_FAILURE_CACHE_KEY);
    throwIfNegativeCached(runtime.id(), cacheKey);
    try {
      return github.tags(runtime, coordinates);
    } catch (SwiftExceptions.UpstreamRateLimited e) {
      rememberRateLimit(runtime.id(), cacheKey, e.retryAfter());
      throw e;
    } catch (SwiftExceptions.NotFound e) {
      rememberNotFound(runtime.id(), cacheKey);
      throw e;
    } catch (SwiftExceptions.BadUpstream e) {
      rememberBadGateway(runtime.id(), GITHUB_TAGS_FAILURE_CACHE_KEY);
      throw e;
    }
  }

  private SwiftRegistryDao.Release persistRelease(
      RepositoryRuntime runtime,
      String scopeDisplay,
      String nameDisplay,
      String version,
      SwiftArchiveInspector.InspectedArchive inspected,
      String metadataJson,
      byte[] sourceSignature,
      byte[] metadataSignature,
      String signatureFormat,
      String sourceKind,
      List<SwiftComponentService.RepositoryUrlDraft> urls,
      Instant publishedAt,
      String actor,
      String ip,
      SwiftPublishLeaseManager.Lease hostedLease) {
    return persistRelease(
        runtime, scopeDisplay, nameDisplay, version, inspected, metadataJson,
        sourceSignature, metadataSignature, signatureFormat, sourceKind, urls,
        publishedAt, actor, ip, hostedLease, null);
  }

  private SwiftRegistryDao.Release persistRelease(
      RepositoryRuntime runtime,
      String scopeDisplay,
      String nameDisplay,
      String version,
      SwiftArchiveInspector.InspectedArchive inspected,
      String metadataJson,
      byte[] sourceSignature,
      byte[] metadataSignature,
      String signatureFormat,
      String sourceKind,
      List<SwiftComponentService.RepositoryUrlDraft> urls,
      Instant publishedAt,
      String actor,
      String ip,
      SwiftComponentService.ProxyCompletion proxyCompletion) {
    return persistRelease(
        runtime, scopeDisplay, nameDisplay, version, inspected, metadataJson,
        sourceSignature, metadataSignature, signatureFormat, sourceKind, urls,
        publishedAt, actor, ip, null, proxyCompletion);
  }

  private SwiftRegistryDao.Release persistRelease(
      RepositoryRuntime runtime,
      String scopeDisplay,
      String nameDisplay,
      String version,
      SwiftArchiveInspector.InspectedArchive inspected,
      String metadataJson,
      byte[] sourceSignature,
      byte[] metadataSignature,
      String signatureFormat,
      String sourceKind,
      List<SwiftComponentService.RepositoryUrlDraft> urls,
      Instant publishedAt,
      String actor,
      String ip,
      SwiftPublishLeaseManager.Lease hostedLease,
      SwiftComponentService.ProxyCompletion proxyCompletion) {
    String scopeLc = SwiftScope.key(scopeDisplay);
    String nameLc = SwiftPackageName.key(nameDisplay);
    String base = scopeLc + "/" + nameLc + "/" + version;
    String archivePath = base + ".zip";
    List<String> storedPaths = new ArrayList<>();
    try {
      AssetRecord archiveAsset = assets.stageFile(
          runtime,
          archivePath,
          inspected.file(),
          SwiftMediaTypes.ARCHIVE,
          Map.of(
              "swiftKind", "source-archive",
              "scope", scopeLc,
              "name", nameLc,
              "version", version,
              "sha256", inspected.sha256Hex(),
              "sourceKind", sourceKind),
          actor,
          ip);
      storedPaths.add(archiveAsset.path());

      List<SwiftComponentService.ManifestDraft> manifestDrafts = new ArrayList<>();
      for (SwiftArchiveInspector.ManifestEntry manifest : inspected.manifests()) {
        String manifestPath = base + "/" + manifest.filename();
        AssetRecord manifestAsset = assets.stageBytes(
            runtime,
            manifestPath,
            manifest.bytes(),
            SwiftMediaTypes.MANIFEST,
            Map.of(
                "swiftKind", "manifest",
                "swiftToolsVersion", manifest.lookupToolsVersion(),
                "declaredSwiftToolsVersion", manifest.declaredToolsVersion(),
                "sha256", manifest.sha256Hex()),
            actor,
            ip);
        storedPaths.add(manifestAsset.path());
        manifestDrafts.add(new SwiftComponentService.ManifestDraft(
            manifest.filename(),
            manifest.lookupToolsVersion(),
            manifestAsset.id(),
            manifest.sha256Hex()));
      }

      Long sourceSignatureAssetId = null;
      if (sourceSignature != null) {
        String path = ".swift/signatures/" + base + "/source.cms";
        AssetRecord signatureAsset = assets.stageBytes(
            runtime,
            path,
            sourceSignature,
            "application/octet-stream",
            Map.of("swiftKind", "source-signature", "signatureFormat", signatureFormat),
            actor,
            ip);
        storedPaths.add(signatureAsset.path());
        sourceSignatureAssetId = signatureAsset.id();
      }
      Long metadataSignatureAssetId = null;
      if (metadataSignature != null) {
        String path = ".swift/signatures/" + base + "/metadata.sig";
        AssetRecord signatureAsset = assets.stageBytes(
            runtime,
            path,
            metadataSignature,
            "application/octet-stream",
            Map.of("swiftKind", "metadata-signature"),
            actor,
            ip);
        storedPaths.add(signatureAsset.path());
        metadataSignatureAssetId = signatureAsset.id();
      }

      SwiftComponentService.Publication publication = new SwiftComponentService.Publication(
          scopeLc,
          scopeDisplay,
          nameLc,
          nameDisplay,
          version,
          publishedAt == null ? Instant.now() : publishedAt,
          metadataJson,
          inspected.sha256Hex(),
          archiveAsset.id(),
          signatureFormat,
          sourceSignatureAssetId,
          metadataSignatureAssetId,
          sourceKind,
          manifestDrafts,
          urls);
      if (proxyCompletion != null) {
        return components.publishProxy(runtime, publication, proxyCompletion);
      }
      return components.publishFenced(runtime, publication, hostedLease);
    } catch (RuntimeException e) {
      // Every path in this list is request-unique staging state. A concurrent winner can only
      // reference its promoted public assets, never another request's UUID path, so always unlink
      // this request's staging rows; blob ref-counting protects any deduplicated winner blob.
      for (int i = storedPaths.size() - 1; i >= 0; i--) {
        try {
          assets.delete(runtime, storedPaths.get(i));
        } catch (RuntimeException ignored) {
        }
      }
      throw e;
    }
  }

  private void addReleaseLinks(
      MavenResponse response,
      RepositoryRuntime requestedRuntime,
      SwiftRegistryDao.Release release,
      String baseUrl) {
    List<String> versions = new ArrayList<>(availableVersions(
        requestedRuntime, release.scopeLc(), release.nameLc(), new LinkedHashSet<>()));
    versions.sort(SwiftVersions.COMPARATOR);
    int index = versions.indexOf(release.version());
    List<SwiftLink> links = new ArrayList<>();
    if (!versions.isEmpty()) {
      links.add(SwiftLink.of(
          releaseUrl(baseUrl, release.scopeDisplay(), release.nameDisplay(), versions.getLast()),
          "latest-version"));
    }
    if (index > 0) {
      links.add(SwiftLink.of(
          releaseUrl(baseUrl, release.scopeDisplay(), release.nameDisplay(), versions.get(index - 1)),
          "predecessor-version"));
    }
    if (index >= 0 && index + 1 < versions.size()) {
      links.add(SwiftLink.of(
          releaseUrl(baseUrl, release.scopeDisplay(), release.nameDisplay(), versions.get(index + 1)),
          "successor-version"));
    }
    if (!links.isEmpty()) {
      response.withHeader("Link", SwiftLinkHeader.render(links));
    }
  }

  private SwiftReleaseSigning signing(SwiftRegistryDao.Release release) {
    if (release.signatureFormat() == null || release.sourceSignatureAssetId() == null) {
      return null;
    }
    byte[] sourceSignature = boundedSignature(
        assets.bytes(release.repositoryId(), release.sourceSignatureAssetId()),
        SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES,
        "Stored source-archive-signature");
    return new SwiftReleaseSigning(
        Base64.getEncoder().encodeToString(sourceSignature), release.signatureFormat());
  }

  private String declaredToolsVersion(long repositoryId, SwiftRegistryDao.Manifest manifest) {
    String source = new String(assets.bytes(repositoryId, manifest.assetId()), StandardCharsets.UTF_8);
    return SwiftToolsVersions.fromManifest(source).orElse(manifest.toolsVersion());
  }

  private Map<String, Object> validateMetadata(String json) {
    Map<String, Object> metadata;
    try {
      metadata = mapper.readValue(json == null || json.isBlank() ? "{}" : json, MAP);
    } catch (IOException e) {
      throw new SwiftExceptions.UnprocessableEntity("metadata must be a JSON object", e);
    }
    validateOptionalString(metadata, "description");
    if (metadata.containsKey("author")) {
      validateAuthor(metadata.get("author"));
    }
    Object urls = metadata.get("repositoryURLs");
    if (metadata.containsKey("repositoryURLs") && !(urls instanceof List<?>)) {
      throw new SwiftExceptions.UnprocessableEntity("metadata.repositoryURLs must be an array");
    }
    if (urls instanceof List<?> list) {
      if (list.size() > MAX_METADATA_URLS) {
        throw new SwiftExceptions.UnprocessableEntity("metadata contains too many repository URLs");
      }
      for (Object value : list) {
        if (!(value instanceof String text) || text.isBlank()
            || text.length() > MAX_REPOSITORY_URL_LENGTH) {
          throw new SwiftExceptions.UnprocessableEntity("metadata contains an invalid repository URL");
        }
        if (normalizeRepositoryUrl(text).length() > MAX_REPOSITORY_URL_LENGTH) {
          throw new SwiftExceptions.UnprocessableEntity("metadata contains an invalid repository URL");
        }
      }
    }
    validateOptionalUri(metadata, "readmeURL");
    validateOptionalUri(metadata, "licenseURL");
    Object publicationTime = metadata.get("originalPublicationTime");
    if (metadata.containsKey("originalPublicationTime")) {
      if (!(publicationTime instanceof String text)) {
        throw new SwiftExceptions.UnprocessableEntity(
            "metadata.originalPublicationTime must be an ISO-8601 timestamp");
      }
      try {
        OffsetDateTime.parse(text);
      } catch (DateTimeParseException e) {
        throw new SwiftExceptions.UnprocessableEntity(
            "metadata.originalPublicationTime must be an ISO-8601 timestamp", e);
      }
    }
    return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  private static void validateAuthor(Object value) {
    if (!(value instanceof Map<?, ?> author)) {
      throw new SwiftExceptions.UnprocessableEntity("metadata.author must be an object");
    }
    validateRequiredString(author, "name", "metadata.author.name");
    validateOptionalString(author, "description", "metadata.author.description");
    validateOptionalEmail(author, "email", "metadata.author.email");
    validateOptionalUri(author, "url", "metadata.author.url");
    if (author.containsKey("organization")) {
      Object organizationValue = author.get("organization");
      if (!(organizationValue instanceof Map<?, ?> organization)) {
        throw new SwiftExceptions.UnprocessableEntity(
            "metadata.author.organization must be an object");
      }
      validateRequiredString(
          organization, "name", "metadata.author.organization.name");
      validateOptionalString(
          organization, "description", "metadata.author.organization.description");
      validateOptionalEmail(
          organization, "email", "metadata.author.organization.email");
      validateOptionalUri(
          organization, "url", "metadata.author.organization.url");
    }
  }

  private static void validateOptionalString(Map<String, Object> metadata, String field) {
    validateOptionalString(metadata, field, "metadata." + field);
  }

  private static void validateOptionalString(Map<?, ?> object, String field, String qualified) {
    if (object.containsKey(field) && !(object.get(field) instanceof String)) {
      throw new SwiftExceptions.UnprocessableEntity(qualified + " must be a string");
    }
  }

  private static void validateRequiredString(Map<?, ?> object, String field, String qualified) {
    Object value = object.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new SwiftExceptions.UnprocessableEntity(qualified + " must be a non-empty string");
    }
  }

  private static void validateOptionalEmail(Map<?, ?> object, String field, String qualified) {
    if (!object.containsKey(field)) {
      return;
    }
    Object value = object.get(field);
    if (!(value instanceof String text) || text.length() > 320
        || !METADATA_EMAIL.matcher(text).matches()) {
      throw new SwiftExceptions.UnprocessableEntity(qualified + " must be an email address");
    }
  }

  private static void validateOptionalUri(Map<String, Object> metadata, String field) {
    if (!metadata.containsKey(field)) {
      return;
    }
    validateOptionalUri(metadata, field, "metadata." + field);
  }

  private static void validateOptionalUri(Map<?, ?> object, String field, String qualified) {
    if (!object.containsKey(field)) {
      return;
    }
    Object value = object.get(field);
    if (!(value instanceof String text) || text.isBlank()
        || text.length() > MAX_METADATA_URL_LENGTH) {
      throw new SwiftExceptions.UnprocessableEntity(qualified + " must be a URI");
    }
    try {
      URI uri = new URI(text);
      if (uri.getScheme() == null || uri.getScheme().isBlank() || uri.getUserInfo() != null
          || uri.getFragment() != null || credentialQuery(uri)) {
        throw new URISyntaxException(text, "absolute URI without user info required");
      }
    } catch (URISyntaxException e) {
      throw new SwiftExceptions.UnprocessableEntity(qualified + " must be a safe URI", e);
    }
  }

  private List<SwiftComponentService.RepositoryUrlDraft> repositoryUrls(
      Map<String, Object> metadata) {
    Object value = metadata.get("repositoryURLs");
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    LinkedHashMap<String, SwiftComponentService.RepositoryUrlDraft> result = new LinkedHashMap<>();
    for (Object raw : list) {
      String display = raw.toString();
      String normalized = normalizeRepositoryUrl(display);
      result.putIfAbsent(normalized, new SwiftComponentService.RepositoryUrlDraft(normalized, display));
    }
    return List.copyOf(result.values());
  }

  private static String normalizeRepositoryUrl(String raw) {
    Optional<SwiftGitHubClient.Coordinates> github = SwiftGitHubClient.coordinatesFromUrl(raw);
    if (github.isPresent()) {
      return github.get().repositoryUrl().toLowerCase(Locale.ROOT);
    }
    if (raw == null || raw.isBlank() || raw.length() > MAX_METADATA_URL_LENGTH
        || raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) {
      throw new SwiftExceptions.UnprocessableEntity("Invalid repository URL");
    }
    try {
      URI uri = new URI(raw.trim()).normalize();
      if (uri.getScheme() == null || uri.getScheme().isBlank() || uri.getUserInfo() != null
          || uri.getFragment() != null || uri.getQuery() != null) {
        throw new URISyntaxException(raw, "absolute URI without credentials or fragment required");
      }
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
      if (!REPOSITORY_URL_SCHEMES.contains(scheme) || host == null || host.isBlank()) {
        throw new URISyntaxException(raw, "supported network repository URI required");
      }
      String path = uri.getPath();
      if (path != null && path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      URI normalized = host == null
          ? uri
          : new URI(scheme, null, host, uri.getPort(), path, uri.getQuery(), null);
      return normalized.toASCIIString();
    } catch (URISyntaxException e) {
      throw new SwiftExceptions.UnprocessableEntity("Invalid repository URL", e);
    }
  }

  private static boolean credentialQuery(URI uri) {
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return false;
    }
    String decoded;
    try {
      decoded = URLDecoder.decode(query, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return true;
    }
    for (String pair : decoded.split("&")) {
      String name = pair.substring(0, pair.indexOf('=') < 0 ? pair.length() : pair.indexOf('='))
          .replace("-", "")
          .replace("_", "");
      if (name.contains("token") || name.contains("secret") || name.contains("password")
          || name.contains("credential") || name.contains("signature")
          || name.contains("apikey") || name.contains("authorization")
          || name.startsWith("xamz")) {
        return true;
      }
    }
    return false;
  }

  private SwiftRequestTarget parse(
      String rawPath, String rawQuery, boolean preferReleaseMetadata) {
    try {
      SwiftRequestTarget target = preferReleaseMetadata
          ? paths.parseReleaseMetadata(rawPath, rawQuery)
          : paths.parse(rawPath, rawQuery);
      if (target.path().kind() == SwiftPath.Kind.UNKNOWN) {
        throw new SwiftExceptions.NotFound("Swift registry resource was not found");
      }
      return target;
    } catch (SwiftExceptions.SwiftException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      throw new SwiftExceptions.BadRequest(e.getMessage(), e);
    }
  }

  private static boolean prefersReleaseMetadata(String accept) {
    if (accept == null || accept.isBlank()) {
      return false;
    }
    return SwiftMediaTypes.negotiate(accept, SwiftMediaTypes.Resource.JSON).accepted()
        && !SwiftMediaTypes.negotiate(accept, SwiftMediaTypes.Resource.ARCHIVE).accepted();
  }

  private static void negotiate(String accept, SwiftMediaTypes.Resource resource) {
    SwiftMediaTypes.Negotiation negotiation = SwiftMediaTypes.negotiate(accept, resource);
    if (negotiation.accepted()) {
      return;
    }
    if (negotiation.outcome() == SwiftMediaTypes.Outcome.INVALID_API_VERSION) {
      throw new SwiftExceptions.BadRequest("Invalid Swift Registry API version");
    }
    throw new SwiftExceptions.UnsupportedMediaType(
        negotiation.outcome() == SwiftMediaTypes.Outcome.UNSUPPORTED_API_VERSION
            ? "Unsupported Swift Registry API version"
            : "Unsupported media type for Swift Registry resource");
  }

  private MavenResponse json(Object value, int status, boolean headOnly) {
    return json(value, status, headOnly, null);
  }

  private MavenResponse json(
      Object value, int status, boolean headOnly, String validatorState) {
    byte[] bytes;
    try {
      bytes = mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed serializing Swift registry response", e);
    }
    String etag = sha256Hex(bytes, validatorState);
    return headOnly
        ? MavenResponse.noBody(status, bytes.length, SwiftMediaTypes.JSON, etag, null)
        : MavenResponse.ok(
            new ByteArrayInputStream(bytes), bytes.length, SwiftMediaTypes.JSON, etag, null)
            .withStatus(status);
  }

  private static String sha256Hex(byte[] bytes) {
    return sha256Hex(bytes, null);
  }

  private static String sha256Hex(byte[] bytes, String validatorState) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(bytes);
      if (validatorState != null) {
        digest.update((byte) 0);
        digest.update(validatorState.getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private String releaseListRevisionState(RepositoryRuntime runtime) {
    StringBuilder state = new StringBuilder();
    appendReleaseListRevisionState(runtime, state, new LinkedHashSet<>());
    return state.toString();
  }

  private void appendReleaseListRevisionState(
      RepositoryRuntime runtime, StringBuilder state, Set<Long> visiting) {
    if (runtime == null) {
      state.append("null");
      return;
    }
    state.append('[')
        .append(runtime.id()).append(':')
        .append(runtime.type()).append(':')
        .append(runtime.online()).append(':')
        .append(registry.currentRepositoryRevision(runtime.id()));
    if (!visiting.add(runtime.id())) {
      state.append(":cycle]");
      return;
    }
    try {
      for (RepositoryRuntime member : runtime.members()) {
        state.append('|');
        appendReleaseListRevisionState(member, state, visiting);
      }
    } finally {
      visiting.remove(runtime.id());
    }
    state.append(']');
  }

  private Map<String, Object> readMetadata(String json) {
    try {
      return mapper.readValue(json == null || json.isBlank() ? "{}" : json, MAP);
    } catch (IOException e) {
      throw new IllegalStateException("Stored Swift metadata is invalid", e);
    }
  }

  private String writeJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed serializing Swift metadata", e);
    }
  }

  private AssetRecord requiredStored(RepositoryRuntime runtime, String path) {
    return assets.find(runtime, path)
        .orElseThrow(() -> new IllegalStateException("Stored Swift asset is missing: " + path));
  }

  private static String normalizeSignatureFormat(String raw, boolean signed) {
    if (!signed) {
      if (raw != null && !raw.isBlank()) {
        throw new SwiftExceptions.BadRequest(
            "X-Swift-Package-Signature-Format requires source-archive-signature");
      }
      return null;
    }
    if (raw == null || !SIGNATURE_FORMAT.equalsIgnoreCase(raw.trim())) {
      throw new SwiftExceptions.UnprocessableEntity(
          "Only cms-1.0.0 source archive signatures are supported");
    }
    return SIGNATURE_FORMAT;
  }

  private static String releaseUrl(
      String baseUrl, String scope, String name, String version) {
    String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    return base + encode(scope) + "/" + encode(name) + "/" + encode(version);
  }

  private static String releaseUrl(String baseUrl, SwiftRegistryDao.Release release) {
    return releaseUrl(baseUrl, release.scopeDisplay(), release.nameDisplay(), release.version());
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String coordinateLeaseKey(
      long repositoryId, String scopeLc, String nameLc, String version) {
    return "swift:" + repositoryId + ":" + scopeLc + ":" + nameLc + ":" + version;
  }

  private static String tagRefreshLeaseKey(
      long repositoryId, String scopeLc, String nameLc) {
    return "swift-tags:" + repositoryId + ":" + scopeLc + ":" + nameLc;
  }

  private static void requireHostedWritable(RepositoryRuntime runtime) {
    if (!runtime.online()) {
      throw new SwiftExceptions.NotFound("Swift repository is offline");
    }
    if (WritePolicy.parse(runtime.writePolicy()) == WritePolicy.DENY) {
      throw new SwiftExceptions.Forbidden("Write policy DENY forbids Swift publication");
    }
  }

  private long currentGroupConfigurationRevision(long repositoryId) {
    long revision = registry.currentRepositoryRevision(repositoryId);
    if (revision > 0) {
      return revision;
    }
    // Older Swift groups can predate the shared revision row. Lazily creating it is safe because
    // bumpCacheVersion is atomic; a concurrent initializer simply fences this request and triggers
    // the fresh-runtime retry above.
    return registry.nextRepositoryRevision(repositoryId);
  }

  private void rememberNotFound(long repositoryId, String key) {
    Instant now = Instant.now();
    registry.putNegativeCache(new SwiftRegistryDao.NegativeCache(
        repositoryId, key, 404, null, now.plusSeconds(300), now));
  }

  private void rememberRateLimit(long repositoryId, String key, Instant retryAfter) {
    Instant now = Instant.now();
    Instant retry = retryAfter == null || !retryAfter.isAfter(now)
        ? now.plusSeconds(60)
        : retryAfter;
    registry.putNegativeCache(new SwiftRegistryDao.NegativeCache(
        repositoryId, key, 429, retry, retry, now));
  }

  private void rememberBadGateway(long repositoryId, String key) {
    Instant now = Instant.now();
    registry.putNegativeCache(new SwiftRegistryDao.NegativeCache(
        repositoryId,
        key,
        502,
        null,
        now.plusSeconds(BAD_GATEWAY_CACHE_SECONDS),
        now));
  }

  private void throwIfNegativeCached(long repositoryId, String key) {
    registry.findNegativeCache(repositoryId, key)
        .filter(entry -> entry.expiresAt().isAfter(Instant.now()))
        .ifPresent(entry -> {
          if (entry.statusCode() == 429) {
            throw new SwiftExceptions.UpstreamRateLimited(
                "GitHub upstream is temporarily rate limited", entry.retryAfter());
          }
          if (entry.statusCode() == 404 || entry.statusCode() == 410) {
            throw new SwiftExceptions.NotFound(
                "GitHub Swift package was not found (cached)");
          }
          throw new SwiftExceptions.BadUpstream(
              "GitHub upstream is temporarily unavailable (cached status "
                  + entry.statusCode() + ")");
        });
  }

  private static String base64Sha256(String hex) {
    return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
  }

  private static String safeFilename(String name) {
    return name == null ? "package" : name.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static byte[] emptyToNull(byte[] value) {
    return value == null || value.length == 0 ? null : value;
  }

  private static byte[] boundedSignature(byte[] value, int limit, String field) {
    byte[] normalized = emptyToNull(value);
    if (normalized != null && normalized.length > limit) {
      String size = limit % (1024 * 1024) == 0
          ? (limit / (1024 * 1024)) + " MiB"
          : (limit / 1024) + " KiB";
      throw new SwiftExceptions.ContentTooLarge(
          field + " exceeds the " + size + " size limit");
    }
    return normalized;
  }

  private static void deleteQuietly(java.nio.file.Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }

  private record ResolvedRelease(
      RepositoryRuntime sourceRuntime, SwiftRegistryDao.Release release) {}

  private record VersionState(boolean available, String unavailableReason) {
    private static VersionState availableVersion() {
      return new VersionState(true, null);
    }

    private static VersionState unavailable(String reason) {
      return new VersionState(false, reason);
    }
  }
}
