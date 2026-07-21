package com.github.klboke.kkrepo.server.ansible;

import static com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao.TASK_COMPLETED;
import static com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao.TASK_FAILED;
import static com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao.TASK_WAITING;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPath;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyRequestTarget;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyVersions;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Galaxy v3 hosted, proxy, and group behavior behind the unified repository controller. */
@Service
public class AnsibleGalaxyService {
  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };
  private static final String VERSIONS_SENTINEL = "@versions";
  private static final String DISCOVERY_NAMESPACE = "@upstream";
  private static final String DISCOVERY_NAME = "@discovery";
  private static final int MAX_UPSTREAM_VERSION_PAGES = 100;
  private static final long MAX_METADATA_BYTES = 16L * 1024 * 1024;
  private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
  private static final Duration TASK_LEASE_DURATION = Duration.ofMinutes(5);
  private static final Duration LEASE_WAIT = Duration.ofSeconds(5);

  private final ObjectMapper objectMapper;
  private final AnsibleGalaxyRegistryDao registry;
  private final AnsibleGalaxyAssetSupport assets;
  private final AnsibleCollectionArchiveInspector inspector;
  private final HttpRemoteFetcher remoteFetcher;
  private final RepositoryRuntimeRegistry runtimes;
  private final AnsibleImportTaskLeaseManager taskLeases;
  private final AnsibleGalaxyPathParser pathParser = new AnsibleGalaxyPathParser();
  private final String nodeOwner = "ansible-" + UUID.randomUUID();

  public AnsibleGalaxyService(
      ObjectMapper objectMapper,
      AnsibleGalaxyRegistryDao registry,
      AnsibleGalaxyAssetSupport assets,
      AnsibleCollectionArchiveInspector inspector,
      HttpRemoteFetcher remoteFetcher,
      RepositoryRuntimeRegistry runtimes,
      AnsibleImportTaskLeaseManager taskLeases) {
    this.objectMapper = objectMapper;
    this.registry = registry;
    this.assets = assets;
    this.inspector = inspector;
    this.remoteFetcher = remoteFetcher;
    this.runtimes = runtimes;
    this.taskLeases = taskLeases;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      String repositoryBaseUrl,
      boolean headOnly,
      String requester) {
    requireRuntime(runtime);
    AnsibleGalaxyRequestTarget target;
    try {
      target = pathParser.parse(rawPath, rawQuery);
    } catch (IllegalArgumentException e) {
      throw new AnsibleGalaxyExceptions.BadRequest(e.getMessage(), e);
    }
    AnsibleGalaxyPath path = target.path();
    return switch (path.kind()) {
      case DISCOVERY -> jsonResponse(discovery(), 200, headOnly, null);
      case COLLECTION -> collectionResponse(
          runtime, path.namespace(), path.name(), repositoryBaseUrl, headOnly);
      case VERSION_LIST -> versionListResponse(
          runtime, path.namespace(), path.name(), repositoryBaseUrl,
          target.limit(), target.offset(), headOnly);
      case VERSION_DETAIL -> versionDetailResponse(
          runtime, path.namespace(), path.name(), path.version(), repositoryBaseUrl, headOnly);
      case IMPORT_TASK -> taskResponse(runtime, path.taskId(), requester, headOnly);
      case ARTIFACT -> artifactResponse(runtime, path.filename(), headOnly);
      case PUBLISH -> throw new AnsibleGalaxyExceptions.NotFound(
          "Ansible Galaxy resource was not found");
      case UNKNOWN -> throw new AnsibleGalaxyExceptions.NotFound(
          "Ansible Galaxy resource was not found");
    };
  }

  /** Standard ansible-galaxy multipart publication. */
  public MavenResponse publish(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      String repositoryBaseUrl,
      InputStream artifact,
      String filename,
      String expectedSha256,
      String actor,
      String ip,
      boolean headOnly) {
    validatePublishRequest(runtime, rawPath, rawQuery);
    requireExpectedSha(expectedSha256);
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspector.inspect(artifact);
    try {
      validateUploadIdentity(inspected, filename, expectedSha256);
      if (registry.findVersion(
          runtime.id(), inspected.namespace(), inspected.name(), inspected.version()).isPresent()) {
        throw new AnsibleGalaxyExceptions.Conflict(
            inspected.filename() + " cannot be updated because the collection version exists");
      }
      String taskId = UUID.randomUUID().toString();
      AssetRecord staged = assets.stageCollection(
          runtime, taskId, inspected.filename(), inspected.file(), actor, ip);
      Instant now = Instant.now();
      AnsibleGalaxyRegistryDao.ImportTask task;
      try {
        task = registry.createClaimedTask(
            new AnsibleGalaxyRegistryDao.ImportTask(
                taskId, runtime.id(), actor, TASK_WAITING, List.of(), null, null,
                inspected.namespace(), inspected.name(), inspected.version(), inspected.filename(),
                expectedSha256.toLowerCase(Locale.ROOT), inspected.sha256(), staged.id(), 0,
                null, null, 0L, now, null, null, now),
            nodeOwner, now.plus(TASK_LEASE_DURATION), now);
      } catch (RuntimeException e) {
        deleteStaging(runtime, taskId, inspected.filename());
        throw e;
      }
      processNewTask(runtime, task, inspected, actor, ip);
      String taskPath = "api/v3/imports/collections/" + taskId + "/";
      return jsonResponse(Map.of("task", taskPath), 202, headOnly, now)
          .withHeader("Location", normalizedBase(repositoryBaseUrl) + taskPath);
    } finally {
      AnsibleCollectionArchiveInspector.delete(inspected.file());
    }
  }

  /** Read-only validation that must run before a controller consumes a multipart body. */
  public void validatePublishRequest(
      RepositoryRuntime runtime, String rawPath, String rawQuery) {
    requireHostedWritable(runtime);
    AnsibleGalaxyRequestTarget target;
    try {
      target = pathParser.parse(rawPath, rawQuery);
    } catch (IllegalArgumentException e) {
      throw new AnsibleGalaxyExceptions.BadRequest(e.getMessage(), e);
    }
    if (target.path().kind() != AnsibleGalaxyPath.Kind.PUBLISH) {
      throw new AnsibleGalaxyExceptions.MethodNotAllowed(
          "Unsupported Ansible Galaxy POST path");
    }
  }

  /** Nexus-compatible raw PUT to the long collection artifact path. */
  public MavenResponse putArtifact(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream artifact,
      String actor,
      String ip) {
    requireHostedWritable(runtime);
    AnsibleGalaxyPath uploadPath = pathParser.parse(rawPath);
    if (uploadPath.kind() != AnsibleGalaxyPath.Kind.ARTIFACT) {
      throw new AnsibleGalaxyExceptions.MethodNotAllowed(
          "Unsupported Ansible Galaxy PUT path");
    }
    String filename = uploadPath.filename();
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspector.inspect(artifact);
    try {
      validateUploadIdentity(inspected, filename, null);
      persistCollection(runtime, inspected, "HOSTED", Map.of(), actor, ip, false);
      return MavenResponse.created();
    } finally {
      AnsibleCollectionArchiveInspector.delete(inspected.file());
    }
  }

  AnsibleGalaxyRegistryDao.CollectionVersion restoreCollectionForMigration(
      RepositoryRuntime runtime,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      Instant publishedAt,
      String actor,
      String ip) {
    requireRuntime(runtime);
    if (runtime.isGroup()) {
      throw new IllegalArgumentException(
          "Ansible Galaxy group repositories do not own migrated collection blobs");
    }
    return persistCollection(
        runtime, inspected, runtime.isProxy() ? "MIGRATION_PROXY" : "MIGRATION_HOSTED",
        Map.of(), actor, ip, true, publishedAt);
  }

  /** Claims and resumes one durable task after a node crash or lease expiry. */
  void recoverTask(AnsibleGalaxyRegistryDao.ImportTask candidate) {
    RepositoryRuntime runtime = runtimes.resolveById(candidate.repositoryId()).orElse(null);
    if (runtime == null || runtime.format() != RepositoryFormat.ANSIBLEGALAXY || !runtime.isHosted()) {
      return;
    }
    Instant now = Instant.now();
    Optional<AnsibleGalaxyRegistryDao.ImportTask> claimed = registry.claimTask(
        candidate.taskId(), nodeOwner, now.plus(TASK_LEASE_DURATION), now);
    if (claimed.isEmpty()) return;
    AnsibleGalaxyRegistryDao.ImportTask task = claimed.get();
    if (task.stagingAssetId() == null) {
      finishFailed(task, "missing_staging_artifact", "Staged collection artifact is missing");
      return;
    }
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = null;
    boolean terminal = false;
    try (InputStream input = assets.open(runtime.id(), task.stagingAssetId())) {
      inspected = inspector.inspect(input);
      validateRecoveredTask(task, inspected);
      terminal = finishSuccessfulTask(runtime, task, inspected, task.requester(), null, true);
    } catch (RuntimeException | IOException e) {
      terminal = finishFailed(task, errorCode(e), safeDetail(e));
    } finally {
      if (inspected != null) AnsibleCollectionArchiveInspector.delete(inspected.file());
      if (terminal) deleteStaging(runtime, task);
    }
  }

  private MavenResponse collectionResponse(
      RepositoryRuntime runtime,
      String namespace,
      String name,
      String baseUrl,
      boolean headOnly) {
    List<String> versions = listVersionNames(runtime, namespace, name, new HashSet<>());
    if (versions.isEmpty()) {
      throw new AnsibleGalaxyExceptions.NotFound("Collection not found");
    }
    String highest = AnsibleGalaxyVersions.sortDescending(versions).getFirst();
    AnsibleGalaxyRegistryDao.CollectionVersion detail =
        resolveExact(runtime, namespace, name, highest, new HashSet<>());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("href", collectionUrl(baseUrl, namespace, name));
    body.put("namespace", namespace);
    body.put("name", name);
    body.put("highest_version", Map.of(
        "href", versionUrl(baseUrl, namespace, name, highest),
        "version", highest));
    body.put("versions_url", versionsUrl(baseUrl, namespace, name));
    body.put("created_at", iso(detail.createdAt()));
    body.put("updated_at", iso(detail.updatedAt()));
    body.put("metadata", collectionMetadata(detail));
    return jsonResponse(body, 200, headOnly, detail.updatedAt());
  }

  private MavenResponse versionListResponse(
      RepositoryRuntime runtime,
      String namespace,
      String name,
      String baseUrl,
      int limit,
      int offset,
      boolean headOnly) {
    List<String> all = AnsibleGalaxyVersions.sortDescending(
        listVersionNames(runtime, namespace, name, new HashSet<>()));
    if (all.isEmpty()) {
      throw new AnsibleGalaxyExceptions.NotFound("Collection not found");
    }
    int from = Math.min(offset, all.size());
    int to = Math.min(all.size(), from + limit);
    List<Map<String, Object>> data = all.subList(from, to).stream()
        .map(version -> Map.<String, Object>of(
            "version", version,
            "href", versionUrl(baseUrl, namespace, name, version)))
        .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("meta", Map.of("count", all.size()));
    body.put("data", data);
    if (to < all.size()) {
      body.put("links", Map.of("next", clientPaginationPath(
          versionsUrl(baseUrl, namespace, name), limit, to)));
    } else {
      body.put("links", Map.of("next", ""));
    }
    return jsonResponse(body, 200, headOnly, null);
  }

  static String clientPaginationPath(String versionsUrl, int limit, int offset) {
    String path = URI.create(versionsUrl).getRawPath();
    if (path == null || path.isBlank() || !path.startsWith("/")) {
      throw new IllegalArgumentException("Ansible Galaxy versions URL must contain an absolute path");
    }
    return path + "?limit=" + limit + "&offset=" + offset;
  }

  private MavenResponse versionDetailResponse(
      RepositoryRuntime runtime,
      String namespace,
      String name,
      String version,
      String baseUrl,
      boolean headOnly) {
    AnsibleGalaxyRegistryDao.CollectionVersion resolved =
        resolveExact(runtime, namespace, name, version, new HashSet<>());
    return jsonResponse(versionDetail(resolved, baseUrl, namespace, name),
        200, headOnly, resolved.updatedAt());
  }

  private MavenResponse taskResponse(
      RepositoryRuntime runtime, String taskId, String requester, boolean headOnly) {
    AnsibleGalaxyRegistryDao.ImportTask task = registry.findTask(taskId)
        .filter(value -> value.repositoryId() == runtime.id())
        .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound("Import task was not found"));
    if (task.requester() != null && !task.requester().equals(requester)) {
      throw new AnsibleGalaxyExceptions.NotFound("Import task was not found");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", task.taskId());
    body.put("state", task.state().toLowerCase(Locale.ROOT));
    body.put("started_at", iso(task.startedAt()));
    body.put("finished_at", iso(task.finishedAt()));
    body.put("messages", task.messages());
    if (TASK_FAILED.equals(task.state())) {
      body.put("error", Map.of(
          "code", task.errorCode() == null ? "UNKNOWN" : task.errorCode(),
          "description", task.errorDetail() == null
              ? "Collection import failed" : task.errorDetail()));
    }
    return jsonResponse(body, 200, headOnly, task.updatedAt())
        .withHeader("Cache-Control", "no-store");
  }

  private MavenResponse artifactResponse(
      RepositoryRuntime runtime, String filename, boolean headOnly) {
    AnsibleGalaxyRegistryDao.CollectionVersion version =
        resolveArtifact(runtime, filename, new HashSet<>());
    return assets.serve(version.repositoryId(), version.artifactAssetId(), headOnly)
        .withHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
  }

  private List<String> listVersionNames(
      RepositoryRuntime runtime, String namespace, String name, Set<Long> visiting) {
    if (!visiting.add(runtime.id())) {
      throw new AnsibleGalaxyExceptions.BadRequest("Ansible group contains a cycle");
    }
    try {
      if (runtime.isHosted()) {
        return registry.listVersionNames(runtime.id(), namespace, name).stream()
            .distinct().toList();
      }
      if (runtime.isProxy()) {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        registry.listVersionNames(runtime.id(), namespace, name).forEach(versions::add);
        try {
          versions.addAll(fetchProxyVersionNames(runtime, namespace, name));
        } catch (AnsibleGalaxyExceptions.NotFound e) {
          // Preserve already materialized immutable proxy versions.
        }
        return List.copyOf(versions);
      }
      LinkedHashSet<String> versions = new LinkedHashSet<>();
      for (RepositoryRuntime member : safeMembers(runtime)) {
        try {
          listVersionNames(member, namespace, name, visiting).forEach(versions::add);
        } catch (AnsibleGalaxyExceptions.NotFound ignored) {
        }
      }
      return List.copyOf(versions);
    } finally {
      visiting.remove(runtime.id());
    }
  }

  private AnsibleGalaxyRegistryDao.CollectionVersion resolveExact(
      RepositoryRuntime runtime,
      String namespace,
      String name,
      String version,
      Set<Long> visiting) {
    if (!visiting.add(runtime.id())) {
      throw new AnsibleGalaxyExceptions.BadRequest("Ansible group contains a cycle");
    }
    try {
      if (runtime.isHosted()) {
        return registry.findVersion(runtime.id(), namespace, name, version)
            .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
                "Collection version was not found"));
      }
      if (runtime.isProxy()) {
        Optional<AnsibleGalaxyRegistryDao.CollectionVersion> cached =
            registry.findVersion(runtime.id(), namespace, name, version);
        if (cached.isPresent()) return cached.get();
        Map<String, Object> detail = fetchProxyDocument(
            runtime, namespace, name, version,
            proxyV3Url(runtime, "collections/" + encode(namespace) + "/" + encode(name)
                + "/versions/" + encode(version) + "/"));
        validateUpstreamDetail(namespace, name, version, detail);
        AnsibleGalaxyRegistryDao.ProxyVersionState state = registry.findProxyState(
                runtime.id(), namespace, name, version)
            .orElseThrow(() -> new AnsibleGalaxyExceptions.BadUpstream(
                "Upstream collection state was not persisted"));
        return materializeProxy(runtime, state);
      }

      long groupRevision = currentGroupRevision(runtime.id());
      Optional<AnsibleGalaxyRegistryDao.GroupBinding> existing = registry.findGroupBinding(
          runtime.id(), namespace, name, version);
      Optional<AnsibleGalaxyRegistryDao.CollectionVersion> existingVersion = existing.flatMap(
          binding -> currentGroupBindingVersion(runtime, binding, groupRevision));
      if (existingVersion.isPresent()) return existingVersion.get();
      for (RepositoryRuntime member : safeMembers(runtime)) {
        try {
          AnsibleGalaxyRegistryDao.CollectionVersion candidate =
              resolveExact(member, namespace, name, version, visiting);
          long memberRevision = registry.currentRepositoryRevision(member.id());
          registry.bindGroupSourceIfCurrent(new AnsibleGalaxyRegistryDao.GroupBinding(
              runtime.id(), namespace, name, version, member.id(), candidate.id(), memberRevision,
              groupRevision, candidate.artifactSha256(), Instant.now(), Instant.now()));
          return registry.findGroupBinding(runtime.id(), namespace, name, version)
              .flatMap(binding -> currentGroupBindingVersion(runtime, binding, groupRevision))
              .orElse(candidate);
        } catch (AnsibleGalaxyExceptions.NotFound ignored) {
        }
      }
      throw new AnsibleGalaxyExceptions.NotFound(
          "Collection version was not found in group members");
    } finally {
      visiting.remove(runtime.id());
    }
  }

  private AnsibleGalaxyRegistryDao.CollectionVersion resolveArtifact(
      RepositoryRuntime runtime, String filename, Set<Long> visiting) {
    if (!visiting.add(runtime.id())) {
      throw new AnsibleGalaxyExceptions.BadRequest("Ansible group contains a cycle");
    }
    try {
      if (runtime.isHosted()) {
        return registry.findVersionByArtifactFilename(runtime.id(), filename)
            .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
                "Collection artifact was not found"));
      }
      if (runtime.isProxy()) {
        Optional<AnsibleGalaxyRegistryDao.CollectionVersion> cached =
            registry.findVersionByArtifactFilename(runtime.id(), filename);
        if (cached.isPresent()) return cached.get();
        AnsibleGalaxyRegistryDao.ProxyVersionState state =
            registry.findProxyStateByArtifactFilename(runtime.id(), filename)
                .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
                    "Collection artifact metadata was not requested before download"));
        return materializeProxy(runtime, state);
      }
      long groupRevision = currentGroupRevision(runtime.id());
      Optional<AnsibleGalaxyRegistryDao.GroupBinding> binding =
          registry.findGroupBindingByArtifactFilename(runtime.id(), filename);
      Optional<AnsibleGalaxyRegistryDao.CollectionVersion> boundArtifact = binding.flatMap(
          current -> currentGroupBindingVersion(runtime, current, groupRevision));
      if (boundArtifact.isPresent()) return boundArtifact.get();
      for (RepositoryRuntime member : safeMembers(runtime)) {
        try {
          AnsibleGalaxyRegistryDao.CollectionVersion candidate =
              resolveArtifact(member, filename, visiting);
          registry.bindGroupSourceIfCurrent(new AnsibleGalaxyRegistryDao.GroupBinding(
              runtime.id(), candidate.namespaceLc(), candidate.nameLc(),
              candidate.versionNormalized(), member.id(), candidate.id(),
              registry.currentRepositoryRevision(member.id()), groupRevision,
              candidate.artifactSha256(), Instant.now(), Instant.now()));
          return registry.findGroupBinding(
                  runtime.id(), candidate.namespaceLc(), candidate.nameLc(),
                  candidate.versionNormalized())
              .flatMap(current -> currentGroupBindingVersion(runtime, current, groupRevision))
              .orElse(candidate);
        } catch (AnsibleGalaxyExceptions.NotFound ignored) {
        }
      }
      throw new AnsibleGalaxyExceptions.NotFound(
          "Collection artifact was not found in group members");
    } finally {
      visiting.remove(runtime.id());
    }
  }

  AnsibleGalaxyRegistryDao.CollectionVersion materializeProxy(
      RepositoryRuntime runtime, AnsibleGalaxyRegistryDao.ProxyVersionState state) {
    Optional<AnsibleGalaxyRegistryDao.CollectionVersion> existing = registry.findVersion(
        runtime.id(), state.namespaceLc(), state.nameLc(), state.versionNormalized());
    if (existing.isPresent()) return existing.get();
    if (state.upstreamDownloadUrl() == null || state.artifactSha256() == null) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream version metadata does not provide an artifact download and SHA-256");
    }
    String leaseKey = leaseKey(runtime.id(), "artifact", state.namespaceLc(), state.nameLc(),
        state.versionNormalized());
    AnsibleGalaxyRegistryDao.Lease lease = acquireLease(leaseKey);
    try {
      existing = registry.findVersion(
          runtime.id(), state.namespaceLc(), state.nameLc(), state.versionNormalized());
      if (existing.isPresent()) return existing.get();
      HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request.get(state.upstreamDownloadUrl())
          .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
          // galaxy.ansible.com returns 406 for a narrowed binary Accept value even though the
          // eventual signed S3 response is application/gzip. Match ansible-galaxy and permit the
          // upstream redirect chain to negotiate its artifact representation.
          .withAccept("*/*")
          // Galaxy commonly redirects signed downloads to S3/CDN origins. Authorization is
          // retained only on the configured upstream origin and stripped on every cross-origin
          // hop; OutboundRequestPolicy still validates and DNS-pins each redirect, while the
          // metadata SHA-256 below provides the final integrity boundary.
          .withRepositoryAllowingUnsignedRedirects(runtime, true, Set.of("*"));
      try (HttpRemoteFetcher.Result result = remoteFetcher.fetch(request)) {
        if (result.status() < 200 || result.status() >= 300 || result.body() == null) {
          throw new AnsibleGalaxyExceptions.BadUpstream(
              "Upstream artifact request returned HTTP " + result.status());
        }
        AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspector.inspect(result.body());
        try {
          if (!state.artifactSha256().equalsIgnoreCase(inspected.sha256())) {
            throw new AnsibleGalaxyExceptions.BadUpstream(
                "Upstream artifact SHA-256 does not match version metadata");
          }
          if (!state.namespaceLc().equals(inspected.namespace())
              || !state.nameLc().equals(inspected.name())
              || !state.versionNormalized().equals(inspected.version())
              || (state.artifactFilename() != null
                  && !state.artifactFilename().equals(inspected.filename()))) {
            throw new AnsibleGalaxyExceptions.BadUpstream(
                "Upstream artifact identity does not match version metadata");
          }
          return persistCollection(
              runtime, inspected, "PROXY", state.upstreamIdentity(), "proxy", null, true);
        } finally {
          AnsibleCollectionArchiveInspector.delete(inspected.file());
        }
      } catch (IOException e) {
        throw new AnsibleGalaxyExceptions.BadUpstream(
            "Failed downloading upstream Ansible collection", e);
      }
    } finally {
      registry.releaseLease(lease.leaseKey(), lease.owner(), lease.fencingToken());
    }
  }

  Map<String, Object> fetchProxyDocument(
      RepositoryRuntime runtime,
      String namespace,
      String name,
      String stateKey,
      String relativePath) {
    requireProxyRemote(runtime);
    Instant now = Instant.now();
    Optional<AnsibleGalaxyRegistryDao.ProxyVersionState> existing = registry.findProxyState(
        runtime.id(), namespace, name, stateKey);
    if (existing.isPresent()) {
      AnsibleGalaxyRegistryDao.ProxyVersionState cached = existing.get();
      if (cached.negativeStatus() != null && cached.negativeExpiresAt() != null
          && cached.negativeExpiresAt().isAfter(now)) {
        throw new AnsibleGalaxyExceptions.NotFound("Upstream collection resource was not found");
      }
      if (cached.cacheUntil() != null && cached.cacheUntil().isAfter(now)
          && !cached.upstreamIdentity().isEmpty()) {
        return cached.upstreamIdentity();
      }
    }

    String leaseKey = leaseKey(runtime.id(), "metadata", namespace, name, stateKey);
    Optional<AnsibleGalaxyRegistryDao.Lease> acquired = registry.tryAcquireLease(
        leaseKey, nodeOwner, now.plus(LEASE_DURATION));
    if (acquired.isEmpty()) {
      return awaitProxyDocument(runtime, namespace, name, stateKey, now);
    }
    AnsibleGalaxyRegistryDao.Lease lease = acquired.get();
    try {
      existing = registry.findProxyState(runtime.id(), namespace, name, stateKey);
      if (existing.isPresent() && existing.get().cacheUntil() != null
          && existing.get().cacheUntil().isAfter(Instant.now())
          && !existing.get().upstreamIdentity().isEmpty()) {
        return existing.get().upstreamIdentity();
      }
      String url = remoteUrl(runtime, relativePath);
      HttpRemoteFetcher.Request request = HttpRemoteFetcher.Request.get(url)
          .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
          .withAccept("application/json")
          .withRepository(runtime, true);
      if (existing.isPresent()) {
        request = request.withConditional(
            existing.get().metadataEtag(), parseInstant(existing.get().metadataLastModified()));
      }
      try (HttpRemoteFetcher.Result result = remoteFetcher.fetch(request)) {
        int status = result.status();
        Instant checkedAt = Instant.now();
        Instant cacheUntil = checkedAt.plus(metadataTtl(runtime));
        if (status == 304 && existing.isPresent() && !existing.get().upstreamIdentity().isEmpty()) {
          AnsibleGalaxyRegistryDao.ProxyVersionState cached = existing.get();
          registry.upsertProxyState(new AnsibleGalaxyRegistryDao.ProxyVersionState(
              cached.repositoryId(), cached.namespaceLc(), cached.nameLc(), cached.versionNormalized(),
              cached.artifactFilename(), cached.upstreamHref(), cached.upstreamDownloadUrl(),
              cached.artifactSha256(), cached.metadataEtag(), cached.metadataLastModified(),
              cacheUntil, checkedAt, null, null, cached.upstreamIdentity(),
              registry.nextRepositoryRevision(runtime.id()), checkedAt));
          return cached.upstreamIdentity();
        }
        if (status == 404 || status == 410) {
          registry.upsertProxyState(new AnsibleGalaxyRegistryDao.ProxyVersionState(
              runtime.id(), namespace, name, stateKey, null, null, null, null,
              result.etag(), instantText(result.lastModified()), null, checkedAt, status,
              checkedAt.plus(negativeTtl(runtime)), Map.of(),
              registry.nextRepositoryRevision(runtime.id()), checkedAt));
          throw new AnsibleGalaxyExceptions.NotFound("Upstream collection resource was not found");
        }
        if (status < 200 || status >= 300 || result.body() == null) {
          throw new AnsibleGalaxyExceptions.BadUpstream(
              "Upstream Galaxy metadata request returned HTTP " + status);
        }
        Map<String, Object> document = projectProxyDocument(
            stateKey, readJsonBounded(result.body()));
        if (!stateKey.startsWith("@")) {
          validateUpstreamDetail(namespace, name, stateKey, document);
        }
        UpstreamArtifact artifact = upstreamArtifact(runtime, url, stateKey, document);
        if (existing.isPresent() && !stateKey.startsWith("@")
            && existing.get().artifactSha256() != null && artifact.sha256() != null
            && !existing.get().artifactSha256().equalsIgnoreCase(artifact.sha256())) {
          throw new AnsibleGalaxyExceptions.BadUpstream(
              "Upstream changed the SHA-256 of an immutable Ansible collection version");
        }
        registry.upsertProxyState(new AnsibleGalaxyRegistryDao.ProxyVersionState(
            runtime.id(), namespace, name, stateKey, artifact.filename(), artifact.href(),
            artifact.downloadUrl(), artifact.sha256(), result.etag(),
            instantText(result.lastModified()), cacheUntil, checkedAt, null, null, document,
            registry.nextRepositoryRevision(runtime.id()), checkedAt));
        return document;
      } catch (IOException e) {
        if (existing.isPresent() && !existing.get().upstreamIdentity().isEmpty()) {
          return existing.get().upstreamIdentity();
        }
        throw new AnsibleGalaxyExceptions.BadUpstream(
            "Failed fetching upstream Galaxy metadata", e);
      }
    } finally {
      registry.releaseLease(lease.leaseKey(), lease.owner(), lease.fencingToken());
    }
  }

  List<String> fetchProxyVersionNames(
      RepositoryRuntime runtime, String namespace, String name) {
    LinkedHashSet<String> versions = new LinkedHashSet<>();
    Set<String> visitedPages = new HashSet<>();
    String next = proxyV3Url(runtime, "collections/" + encode(namespace) + "/" + encode(name)
        + "/versions/?limit=1000");
    for (int page = 0; next != null && page < MAX_UPSTREAM_VERSION_PAGES; page++) {
      if (!visitedPages.add(next)) {
        throw new AnsibleGalaxyExceptions.BadUpstream(
            "Upstream Galaxy version pagination contains a cycle");
      }
      String stateKey = page == 0 ? VERSIONS_SENTINEL : pageStateKey(next);
      Map<String, Object> document = fetchProxyDocument(
          runtime, namespace, name, stateKey, next);
      for (Map<String, Object> item : resultItems(document)) {
        String version = text(item.get("version"));
        if (!AnsibleGalaxyVersions.isValid(version)) {
          throw new AnsibleGalaxyExceptions.BadUpstream(
              "Upstream Galaxy version list contains an invalid semantic version");
        }
        versions.add(version);
      }
      String link = nextLink(document);
      next = link == null ? null : resolveUpstreamUrl(runtime, next, link);
    }
    if (next != null) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy version pagination exceeds the safety limit");
    }
    return List.copyOf(versions);
  }

  String proxyV3Url(RepositoryRuntime runtime, String suffix) {
    Map<String, Object> discovery = null;
    String discoveryUrl = remoteUrl(runtime, "");
    try {
      discovery = fetchProxyDocument(
          runtime, DISCOVERY_NAMESPACE, DISCOVERY_NAME, "@v3-root", "");
    } catch (AnsibleGalaxyExceptions.GalaxyException ignored) {
      // Nexus proxy repositories and some Galaxy installations expose discovery at /api/ only.
    }
    String advertised = discovery == null
        ? null : text(map(discovery.get("available_versions")).get("v3"));
    if (advertised == null || advertised.isBlank()) {
      discovery = fetchProxyDocument(
          runtime, DISCOVERY_NAMESPACE, DISCOVERY_NAME, "@v3-api", "api/");
      discoveryUrl = remoteUrl(runtime, "api/");
      advertised = text(map(discovery.get("available_versions")).get("v3"));
    }
    if (advertised == null || advertised.isBlank() || advertised.length() > 2048) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy discovery does not advertise available_versions.v3");
    }
    URI v3Base;
    try {
      // available_versions values are relative to the discovery document. Public Galaxy, for
      // example, discovers at /api/ and advertises "v3/"; resolving against the configured
      // remote root would incorrectly request /v3/.
      v3Base = URI.create(discoveryUrl).resolve(advertised);
    } catch (RuntimeException e) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy discovery returned an invalid v3 URL", e);
    }
    if (!("http".equalsIgnoreCase(v3Base.getScheme())
        || "https".equalsIgnoreCase(v3Base.getScheme()))
        || v3Base.getHost() == null || v3Base.getUserInfo() != null
        || v3Base.getFragment() != null || v3Base.getQuery() != null) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy discovery returned an unsafe v3 URL");
    }
    return URI.create(ensureSlash(v3Base.toASCIIString())).resolve(suffix).toASCIIString();
  }

  Map<String, Object> awaitProxyDocument(
      RepositoryRuntime runtime,
      String namespace,
      String name,
      String stateKey,
      Instant started) {
    while (Duration.between(started, Instant.now()).compareTo(LEASE_WAIT) < 0) {
      try {
        Thread.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AnsibleGalaxyExceptions.ServiceUnavailable(
            "Interrupted while waiting for proxy metadata revalidation");
      }
      Optional<AnsibleGalaxyRegistryDao.ProxyVersionState> refreshed = registry.findProxyState(
          runtime.id(), namespace, name, stateKey);
      if (refreshed.isPresent()) {
        Instant observedAt = Instant.now();
        if (refreshed.get().negativeStatus() != null
            && refreshed.get().negativeExpiresAt() != null
            && refreshed.get().negativeExpiresAt().isAfter(observedAt)) {
          throw new AnsibleGalaxyExceptions.NotFound("Upstream collection resource was not found");
        }
        if (refreshed.get().cacheUntil() != null
            && refreshed.get().cacheUntil().isAfter(observedAt)
            && !refreshed.get().upstreamIdentity().isEmpty()) {
          return refreshed.get().upstreamIdentity();
        }
      }
    }
    throw new AnsibleGalaxyExceptions.ServiceUnavailable(
        "Another replica is revalidating this Galaxy resource");
  }

  private AnsibleGalaxyRegistryDao.CollectionVersion persistCollection(
      RepositoryRuntime runtime,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      String sourceKind,
      Map<String, Object> upstreamIdentity,
      String actor,
      String ip,
      boolean allowExistingSameArtifact) {
    return persistCollection(
        runtime, inspected, sourceKind, upstreamIdentity, actor, ip,
        allowExistingSameArtifact, null);
  }

  private AnsibleGalaxyRegistryDao.CollectionVersion persistCollection(
      RepositoryRuntime runtime,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      String sourceKind,
      Map<String, Object> upstreamIdentity,
      String actor,
      String ip,
      boolean allowExistingSameArtifact,
      Instant publishedAt) {
    Optional<AnsibleGalaxyRegistryDao.CollectionVersion> existing = registry.findVersion(
        runtime.id(), inspected.namespace(), inspected.name(), inspected.version());
    if (existing.isPresent()) {
      if (allowExistingSameArtifact
          && existing.get().artifactSha256().equalsIgnoreCase(inspected.sha256())) {
        return existing.get();
      }
      throw new AnsibleGalaxyExceptions.Conflict(
          inspected.filename() + " cannot be updated because the collection version exists");
    }
    String coordinateLease = leaseKey(runtime.id(), "publish", inspected.namespace(),
        inspected.name(), inspected.version());
    AnsibleGalaxyRegistryDao.Lease lease = acquireLease(coordinateLease);
    String artifactPath = AnsibleGalaxyPathParser.ARTIFACT_BASE + inspected.filename();
    boolean createdAsset = false;
    try {
      existing = registry.findVersion(
          runtime.id(), inspected.namespace(), inspected.name(), inspected.version());
      if (existing.isPresent()) {
        if (allowExistingSameArtifact
            && existing.get().artifactSha256().equalsIgnoreCase(inspected.sha256())) {
          return existing.get();
        }
        throw new AnsibleGalaxyExceptions.Conflict(
            inspected.filename() + " cannot be updated because the collection version exists");
      }
      Instant now = Instant.now();
      Instant publicationTime = publishedAt == null ? now : publishedAt;
      Map<String, Object> componentAttributes = new LinkedHashMap<>();
      componentAttributes.put("ansibleNamespace", inspected.namespace());
      componentAttributes.put("ansibleCollection", inspected.name());
      componentAttributes.put("ansibleVersion", inspected.version());
      componentAttributes.put("artifactSha256", inspected.sha256());
      componentAttributes.put("requiresAnsible", inspected.requiresAnsible());
      ComponentRecord component = new ComponentRecord(
          null, runtime.id(), RepositoryFormat.ANSIBLEGALAXY, inspected.namespace(),
          inspected.name(), inspected.version(), "ansible-collection",
          PersistenceHashes.componentCoordinateHash(
              inspected.namespace(), inspected.name(), inspected.version()),
          immutableMap(componentAttributes), now);
      AnsibleGalaxyAssetSupport.StoredCollection storedCollection = assets.storeCollection(
          runtime, artifactPath, inspected.file(), Map.of(), actor, ip, component);
      AssetRecord asset = storedCollection.asset();
      createdAsset = storedCollection.created();
      AssetBlobRecord blob = assets.requiredBlob(asset);
      if (!inspected.sha256().equalsIgnoreCase(blob.sha256()) || inspected.size() != blob.size()) {
        throw new IllegalStateException("Persisted Ansible artifact digest changed");
      }
      long revision = registry.nextRepositoryRevision(runtime.id());
      Map<String, Object> metadata = new LinkedHashMap<>(inspected.metadata());
      String requiresAnsible = inspected.requiresAnsible();
      if ((requiresAnsible == null || requiresAnsible.isBlank()) && upstreamIdentity != null) {
        requiresAnsible = text(upstreamIdentity.get("requires_ansible"));
      }
      AnsibleGalaxyRegistryDao.CollectionVersion candidate =
          new AnsibleGalaxyRegistryDao.CollectionVersion(
              null, runtime.id(), asset.componentId(), asset.id(), inspected.namespace(),
              inspected.namespace(), inspected.name(), inspected.name(), inspected.version(),
              inspected.version(), inspected.filename(), inspected.sha256(), inspected.size(),
              immutableMap(metadata), inspected.dependencies(), requiresAnsible, sourceKind, revision,
              AnsibleGalaxyRegistryDao.VERSION_READY, publicationTime, now, now);
      try {
        return registry.insertVersion(candidate);
      } catch (DuplicateKeyException e) {
        Optional<AnsibleGalaxyRegistryDao.CollectionVersion> winner = registry.findVersion(
            runtime.id(), inspected.namespace(), inspected.name(), inspected.version());
        if (allowExistingSameArtifact && winner.isPresent()
            && winner.get().artifactSha256().equalsIgnoreCase(inspected.sha256())) {
          return winner.get();
        }
        throw new AnsibleGalaxyExceptions.Conflict(
            inspected.filename() + " cannot be updated because the collection version exists");
      }
    } catch (RuntimeException e) {
      if (createdAsset && registry.findVersion(
          runtime.id(), inspected.namespace(), inspected.name(), inspected.version()).isEmpty()) {
        assets.delete(runtime, artifactPath);
      }
      throw e;
    } finally {
      registry.releaseLease(lease.leaseKey(), lease.owner(), lease.fencingToken());
    }
  }

  private void processNewTask(
      RepositoryRuntime runtime,
      AnsibleGalaxyRegistryDao.ImportTask claimed,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      String actor,
      String ip) {
    boolean terminal = false;
    try {
      terminal = finishSuccessfulTask(runtime, claimed, inspected, actor, ip, false);
    } catch (RuntimeException e) {
      terminal = finishFailed(claimed, errorCode(e), safeDetail(e));
      if (terminal) {
        throw e;
      }
    } finally {
      if (terminal) deleteStaging(runtime, claimed);
    }
  }

  private boolean finishSuccessfulTask(
      RepositoryRuntime runtime,
      AnsibleGalaxyRegistryDao.ImportTask task,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      String actor,
      String ip,
      boolean allowExistingSameArtifact) {
    try (AnsibleImportTaskLeaseManager.Lease lease =
             taskLeases.monitor(task, TASK_LEASE_DURATION)) {
      AnsibleGalaxyRegistryDao.CollectionVersion version = persistCollection(
          runtime, inspected, "HOSTED", Map.of(), actor, ip, allowExistingSameArtifact);
      lease.assertHeld();
      Instant finished = Instant.now();
      List<Map<String, Object>> messages = List.of(Map.of(
          "level", "INFO",
          "message", "Imported collection " + version.namespaceDisplay() + "."
              + version.nameDisplay() + ":" + version.versionOriginal()));
      return registry.finishTask(
          task.taskId(), task.leaseOwner(), task.fencingToken(), TASK_COMPLETED, messages,
          null, null, version.namespaceLc(), version.nameLc(), version.versionNormalized(),
          version.artifactFilename(), version.artifactSha256(), finished);
    }
  }

  private boolean finishFailed(
      AnsibleGalaxyRegistryDao.ImportTask task, String code, String detail) {
    Instant finished = Instant.now();
    return registry.finishTask(
        task.taskId(), task.leaseOwner(), task.fencingToken(), TASK_FAILED,
        List.of(Map.of("level", "ERROR", "message", detail)), code, detail,
        task.namespaceLc(), task.nameLc(), task.versionNormalized(), task.artifactFilename(),
        task.actualSha256(), finished);
  }

  private void deleteStaging(
      RepositoryRuntime runtime, AnsibleGalaxyRegistryDao.ImportTask task) {
    deleteStaging(runtime, task.taskId(), task.artifactFilename());
  }

  private void deleteStaging(
      RepositoryRuntime runtime, String taskId, String artifactFilename) {
    if (artifactFilename == null) return;
    try {
      assets.delete(runtime, ".ansible/staging/" + taskId + "/" + artifactFilename);
    } catch (RuntimeException ignored) {
      // A periodic blob reconcile can reclaim an already-unlinked or concurrently cleaned staging row.
    }
  }

  private void validateRecoveredTask(
      AnsibleGalaxyRegistryDao.ImportTask task,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected) {
    if (task.expectedSha256() != null
        && !task.expectedSha256().equalsIgnoreCase(inspected.sha256())) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Recovered artifact SHA-256 does not match the import task");
    }
    if (task.namespaceLc() != null && !task.namespaceLc().equals(inspected.namespace())
        || task.nameLc() != null && !task.nameLc().equals(inspected.name())
        || task.versionNormalized() != null
            && !task.versionNormalized().equals(inspected.version())
        || task.artifactFilename() != null
            && !task.artifactFilename().equals(inspected.filename())) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Recovered artifact identity does not match the import task");
    }
  }

  private void validateUploadIdentity(
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      String filename,
      String expectedSha256) {
    if (filename == null || !filename.equals(inspected.filename())) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Artifact filename must be " + inspected.filename());
    }
    if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(inspected.sha256())) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Multipart sha256 does not match the collection artifact");
    }
  }

  static void validateUpstreamDetail(
      String namespace, String name, String version, Map<String, Object> detail) {
    Map<String, Object> namespaceObject = map(detail.get("namespace"));
    Map<String, Object> collectionObject = map(detail.get("collection"));
    Map<String, Object> artifact = map(detail.get("artifact"));
    if (!namespace.equals(text(namespaceObject.get("name")))
        || !name.equals(text(collectionObject.get("name")))
        || !version.equals(text(detail.get("version")))
        || !validSha(text(artifact.get("sha256")))) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream collection version metadata has an invalid identity or SHA-256");
    }
  }

  private UpstreamArtifact upstreamArtifact(
      RepositoryRuntime runtime,
      String requestUrl,
      String stateKey,
      Map<String, Object> document) {
    if (stateKey.startsWith("@")) return new UpstreamArtifact(null, null, null, null);
    Map<String, Object> artifact = map(document.get("artifact"));
    String sha = text(artifact.get("sha256"));
    String filename = text(artifact.get("filename"));
    if (filename == null) {
      filename = AnsibleGalaxyPathParser.canonicalFilename(
          text(map(document.get("namespace")).get("name")),
          text(map(document.get("collection")).get("name")),
          text(document.get("version")));
    }
    String download = text(document.get("download_url"));
    String href = text(document.get("href"));
    return new UpstreamArtifact(
        filename,
        resolveUpstreamUrl(runtime, requestUrl, href),
        resolveUpstreamUrl(runtime, requestUrl, download),
        sha);
  }

  String resolveUpstreamUrl(
      RepositoryRuntime runtime, String requestUrl, String candidate) {
    if (candidate == null || candidate.isBlank()) return null;
    try {
      URI parsed = URI.create(candidate);
      if (parsed.isAbsolute()) return parsed.toASCIIString();
      URI request = URI.create(requestUrl);
      if (candidate.startsWith("/")) {
        return URI.create(request.getScheme() + "://" + request.getRawAuthority())
            .resolve(candidate).toASCIIString();
      }
      return request.resolve(candidate).toASCIIString();
    } catch (IllegalArgumentException e) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy metadata contains an invalid URL", e);
    }
  }

  private Map<String, Object> versionDetail(
      AnsibleGalaxyRegistryDao.CollectionVersion version,
      String baseUrl,
      String exposedNamespace,
      String exposedName) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("namespace", Map.of("name", exposedNamespace));
    body.put("collection", Map.of(
        "name", exposedName,
        "href", collectionUrl(baseUrl, exposedNamespace, exposedName)));
    body.put("name", exposedName);
    body.put("version", version.versionNormalized());
    body.put("href", versionUrl(baseUrl, exposedNamespace, exposedName, version.versionNormalized()));
    body.put("download_url", artifactUrl(baseUrl, version.artifactFilename()));
    Map<String, Object> artifact = new LinkedHashMap<>();
    artifact.put("filename", version.artifactFilename());
    artifact.put("size", version.artifactSize());
    artifact.put("sha256", version.artifactSha256());
    body.put("artifact", artifact);
    body.put("metadata", versionMetadata(version));
    if (version.requiresAnsible() != null) body.put("requires_ansible", version.requiresAnsible());
    body.put("signatures", signatures(version));
    return body;
  }

  Map<String, Object> collectionMetadata(
      AnsibleGalaxyRegistryDao.CollectionVersion version) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    copyMetadataValue(version.metadata(), metadata, "license");
    copyMetadataValue(version.metadata(), metadata, "description");
    copyMetadataValue(version.metadata(), metadata, "authors");
    copyMetadataValue(version.metadata(), metadata, "tags");
    return metadata;
  }

  Map<String, Object> versionMetadata(
      AnsibleGalaxyRegistryDao.CollectionVersion version) {
    Map<String, Object> metadata = new LinkedHashMap<>(collectionMetadata(version));
    metadata.put("dependencies", version.dependencies());
    copyMetadataValue(version.metadata(), metadata, "repository");
    copyMetadataValue(version.metadata(), metadata, "documentation");
    copyMetadataValue(version.metadata(), metadata, "homepage");
    copyMetadataValue(version.metadata(), metadata, "issues");
    return metadata;
  }

  List<?> signatures(AnsibleGalaxyRegistryDao.CollectionVersion version) {
    return registry.listSignatures(version.id()).stream()
        .map(signature -> Map.<String, Object>of(
            "sha256", signature.sha256(),
            "key_fingerprint", signature.keyFingerprint() == null
                ? "" : signature.keyFingerprint()))
        .toList();
  }

  /**
   * Reduces upstream documents to the bounded fields needed for protocol decisions. Complete
   * version documents can include multi-megabyte content/file indexes; those remain upstream (or
   * in the cached collection artifact blob) and are never copied into a database JSON column.
   */
  static Map<String, Object> projectProxyDocument(
      String stateKey, Map<String, Object> document) {
    if (stateKey != null && stateKey.startsWith("@v3-")) {
      String v3 = boundedUpstreamText(
          map(document.get("available_versions")).get("v3"), 2048, "v3 discovery URL");
      return v3 == null ? Map.of() : Map.of("available_versions", Map.of("v3", v3));
    }
    if (VERSIONS_SENTINEL.equals(stateKey)
        || (stateKey != null && stateKey.startsWith("@versions-"))) {
      List<Map<String, Object>> items = resultItems(document);
      if (items.size() > 1000) {
        throw new AnsibleGalaxyExceptions.BadUpstream(
            "Upstream Galaxy version page exceeds the safe item limit");
      }
      List<Map<String, Object>> versions = new ArrayList<>(items.size());
      for (Map<String, Object> item : items) {
        String version = boundedUpstreamText(item.get("version"), 128, "version");
        versions.add(Map.of("version", version == null ? "" : version));
      }
      Map<String, Object> projected = new LinkedHashMap<>();
      projected.put("data", List.copyOf(versions));
      String next = nextLink(document);
      if (next != null) projected.put("links", Map.of("next", next));
      return immutableMap(projected);
    }

    Map<String, Object> projected = new LinkedHashMap<>();
    String namespace = boundedUpstreamText(
        map(document.get("namespace")).get("name"), 64, "namespace");
    String collection = boundedUpstreamText(
        map(document.get("collection")).get("name"), 64, "collection");
    String version = boundedUpstreamText(document.get("version"), 128, "version");
    if (namespace != null) projected.put("namespace", Map.of("name", namespace));
    if (collection != null) projected.put("collection", Map.of("name", collection));
    if (version != null) projected.put("version", version);
    copyBoundedUpstreamText(document, projected, "href", 4096);
    copyBoundedUpstreamText(document, projected, "download_url", 4096);
    copyBoundedUpstreamText(document, projected, "requires_ansible", 255);
    Map<String, Object> artifact = map(document.get("artifact"));
    Map<String, Object> projectedArtifact = new LinkedHashMap<>();
    copyBoundedUpstreamText(artifact, projectedArtifact, "filename", 255);
    copyBoundedUpstreamText(artifact, projectedArtifact, "sha256", 64);
    if (!projectedArtifact.isEmpty()) projected.put("artifact", immutableMap(projectedArtifact));
    return immutableMap(projected);
  }

  private static void copyBoundedUpstreamText(
      Map<String, Object> source, Map<String, Object> target, String key, int maxLength) {
    String value = boundedUpstreamText(source.get(key), maxLength, key);
    if (value != null) target.put(key, value);
  }

  static String boundedUpstreamText(Object raw, int maxLength, String label) {
    if (raw == null) return null;
    if (!(raw instanceof String value)) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy " + label + " has an invalid type");
    }
    if (value.length() > maxLength
        || value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy " + label + " exceeds the safe limit");
    }
    return value;
  }

  static void copyMetadataValue(
      Map<String, Object> source, Map<String, Object> target, String key) {
    Object value = source.get(key);
    if (value == null) return;
    if ((key.equals("authors") || key.equals("license")) && value instanceof Collection<?> list) {
      target.put(key, list.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse(""));
    } else {
      target.put(key, value);
    }
  }

  Map<String, Object> discovery() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("available_versions", Map.of("v3", AnsibleGalaxyPathParser.V3_BASE));
    body.put("server_version", "kkrepo");
    body.put("description", "kkRepo Ansible Galaxy v3 repository");
    return body;
  }

  MavenResponse jsonResponse(
      Object value, int status, boolean headOnly, Instant lastModified) {
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new IllegalStateException("Failed serializing Galaxy v3 response", e);
    }
    String etag = HexFormat.of().formatHex(sha256().digest(bytes));
    if (headOnly) {
      return MavenResponse.noBody(status, bytes.length, "application/json", etag, lastModified);
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "application/json", etag, lastModified)
        .withStatus(status);
  }

  Map<String, Object> readJsonBounded(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[32 * 1024];
    long total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (read == 0) continue;
      total += read;
      if (total > MAX_METADATA_BYTES) {
        throw new AnsibleGalaxyExceptions.BadUpstream(
            "Upstream Galaxy metadata exceeds the safe limit");
      }
      output.write(buffer, 0, read);
    }
    try {
      return objectMapper.readValue(output.toByteArray(), JSON_OBJECT);
    } catch (RuntimeException | IOException e) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy metadata is not a JSON object", e);
    }
  }

  static List<Map<String, Object>> resultItems(Map<String, Object> document) {
    Object raw = document.containsKey("data") ? document.get("data") : document.get("results");
    if (!(raw instanceof List<?> values)) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy version list has no data/results array");
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (Object value : values) {
      if (!(value instanceof Map<?, ?> map)) {
        throw new AnsibleGalaxyExceptions.BadUpstream(
            "Upstream Galaxy version list contains an invalid item");
      }
      results.add(map(map));
    }
    return List.copyOf(results);
  }

  static String nextLink(Map<String, Object> document) {
    Object value = map(document.get("links")).get("next");
    if (value == null) value = document.get("next");
    String next = text(value);
    if (next == null || next.isBlank()) return null;
    if (next.length() > 4096 || next.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Upstream Galaxy pagination returned an invalid next link");
    }
    return next;
  }

  static String pageStateKey(String pageUrl) {
    return "@versions-" + HexFormat.of().formatHex(
        sha256().digest(pageUrl.getBytes(StandardCharsets.UTF_8)));
  }

  static String ensureSlash(String value) {
    return value.endsWith("/") ? value : value + "/";
  }

  private AnsibleGalaxyRegistryDao.Lease acquireLease(String key) {
    Instant started = Instant.now();
    while (Duration.between(started, Instant.now()).compareTo(LEASE_WAIT) < 0) {
      Optional<AnsibleGalaxyRegistryDao.Lease> lease = registry.tryAcquireLease(
          key, nodeOwner, Instant.now().plus(LEASE_DURATION));
      if (lease.isPresent()) return lease.get();
      try {
        Thread.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AnsibleGalaxyExceptions.ServiceUnavailable(
            "Interrupted while waiting for Ansible registry coordination");
      }
    }
    throw new AnsibleGalaxyExceptions.ServiceUnavailable(
        "Another replica is processing this collection coordinate");
  }

  static List<RepositoryRuntime> safeMembers(RepositoryRuntime runtime) {
    return runtime.members() == null
        ? List.of()
        : runtime.members().stream()
            .filter(member -> member != null && member.online())
            .toList();
  }

  static Optional<RepositoryRuntime> directMember(
      RepositoryRuntime group, long repositoryId) {
    return safeMembers(group).stream().filter(member -> member.id() == repositoryId).findFirst();
  }

  private Optional<AnsibleGalaxyRegistryDao.CollectionVersion> currentGroupBindingVersion(
      RepositoryRuntime group,
      AnsibleGalaxyRegistryDao.GroupBinding binding,
      long groupRevision) {
    if (binding.groupConfigRevision() != groupRevision) return Optional.empty();
    Optional<RepositoryRuntime> member = directMember(group, binding.memberRepositoryId());
    if (member.isEmpty()
        || registry.currentRepositoryRevision(member.get().id()) != binding.memberRevision()) {
      return Optional.empty();
    }
    return registry.findVersionById(binding.memberVersionId())
        .filter(version -> version.repositoryId() == member.get().id())
        .filter(version -> binding.artifactSha256().equals(version.artifactSha256()));
  }

  private long currentGroupRevision(long repositoryId) {
    long revision = registry.currentRepositoryRevision(repositoryId);
    return revision == 0 ? registry.nextRepositoryRevision(repositoryId) : revision;
  }

  static void requireRuntime(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.ANSIBLEGALAXY) {
      throw new AnsibleGalaxyExceptions.NotFound("Repository is not Ansible Galaxy format");
    }
    if (!runtime.online()) {
      throw new AnsibleGalaxyExceptions.NotFound("Ansible Galaxy repository is offline");
    }
  }

  static void requireHostedWritable(RepositoryRuntime runtime) {
    requireRuntime(runtime);
    if (!runtime.isHosted()) {
      // Nexus does not mount either Ansible upload route on proxy/group recipes, so these
      // requests are indistinguishable from an unknown repository resource.
      throw new AnsibleGalaxyExceptions.NotFound(
          "Ansible Galaxy resource was not found");
    }
    if (runtime.blobStoreId() == null) {
      throw new AnsibleGalaxyExceptions.ServiceUnavailable(
          "Hosted Ansible repository has no blob store");
    }
    if (WritePolicy.parse(runtime.writePolicy()) == WritePolicy.DENY) {
      throw new AnsibleGalaxyExceptions.Forbidden(
          "Write policy DENY forbids collection publication");
    }
  }

  static void requireProxyRemote(RepositoryRuntime runtime) {
    if (!runtime.isProxy() || runtime.proxyRemoteUrl() == null
        || runtime.proxyRemoteUrl().isBlank()) {
      throw new AnsibleGalaxyExceptions.BadUpstream(
          "Ansible proxy repository has no remote URL");
    }
  }

  static void requireExpectedSha(String sha256) {
    if (!validSha(sha256)) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Multipart sha256 must contain 64 hexadecimal characters");
    }
  }

  static boolean validSha(String value) {
    return value != null && value.matches("[0-9a-fA-F]{64}");
  }

  static String remoteUrl(RepositoryRuntime runtime, String relativePath) {
    String base = runtime.proxyRemoteUrl().trim();
    if (!base.endsWith("/")) base += "/";
    return URI.create(base).resolve(relativePath).toASCIIString();
  }

  static Duration metadataTtl(RepositoryRuntime runtime) {
    int minutes = runtime.metadataMaxAgeMinutes() == null
        ? 60 : Math.max(1, runtime.metadataMaxAgeMinutes());
    return Duration.ofMinutes(minutes);
  }

  static Duration negativeTtl(RepositoryRuntime runtime) {
    int minutes = runtime.metadataMaxAgeMinutes() == null
        ? 5 : Math.max(1, Math.min(15, runtime.metadataMaxAgeMinutes()));
    return Duration.ofMinutes(minutes);
  }

  static String leaseKey(
      long repositoryId, String operation, String namespace, String name, String version) {
    return "ansible:" + repositoryId + ":" + operation + ":" + namespace + ":" + name
        + ":" + version;
  }

  static String collectionUrl(String baseUrl, String namespace, String name) {
    return normalizedBase(baseUrl) + "api/v3/collections/" + encode(namespace) + "/"
        + encode(name) + "/";
  }

  static String versionsUrl(String baseUrl, String namespace, String name) {
    return collectionUrl(baseUrl, namespace, name) + "versions/";
  }

  static String versionUrl(
      String baseUrl, String namespace, String name, String version) {
    return versionsUrl(baseUrl, namespace, name) + encode(version) + "/";
  }

  static String artifactUrl(String baseUrl, String filename) {
    return normalizedBase(baseUrl) + AnsibleGalaxyPathParser.ARTIFACT_BASE + encode(filename);
  }

  static String normalizedBase(String baseUrl) {
    return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
  }

  static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static String iso(Instant value) {
    return value == null ? null : value.toString();
  }

  static String instantText(Instant value) {
    return value == null ? null : value.toString();
  }

  static Instant parseInstant(String value) {
    try {
      return value == null ? null : Instant.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static String errorCode(Throwable failure) {
    return failure instanceof AnsibleGalaxyExceptions.GalaxyException galaxy
        ? galaxy.code() : "import_failed";
  }

  static String safeDetail(Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) return "Collection import failed";
    String sanitized = message.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    return sanitized.length() <= 2048 ? sanitized : sanitized.substring(0, 2048);
  }

  static String text(Object value) {
    return value instanceof String string ? string : value == null ? null : String.valueOf(value);
  }

  static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> raw)) return Map.of();
    return map(raw);
  }

  static Map<String, Object> map(Map<?, ?> raw) {
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  static Map<String, Object> immutableMap(Map<String, Object> map) {
    return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record UpstreamArtifact(
      String filename, String href, String downloadUrl, String sha256) {
  }
}
