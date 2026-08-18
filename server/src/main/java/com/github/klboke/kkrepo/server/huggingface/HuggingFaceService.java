package com.github.klboke.kkrepo.server.huggingface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.ApiCacheEntry;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.ModelFile;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.ModelRevision;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.RevisionRef;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFaceContentIdentity;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFaceFileKind;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFaceHeaders;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFaceJsonTransformer;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFacePath;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFacePathParser;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProtocolCache;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/** Models-only Hugging Face Hub proxy with commit-pinned, Xet-safe local cache semantics. */
@Service
public class HuggingFaceService {
  private static final int MAX_ERROR_BYTES = 1024 * 1024;
  private static final int MAX_PATHS_INFO_BYTES = 1024 * 1024;
  private static final int MAX_PATHS_INFO_PATHS = 1_000;
  private static final Set<String> MODEL_QUERY = Set.of(
      "expand", "securityStatus", "blobs", "safetensors");
  private static final Set<String> TREE_QUERY = Set.of(
      "recursive", "expand", "cursor", "limit");
  private static final Set<String> REFS_QUERY = Set.of("include_pull_requests");

  private final HuggingFacePathParser pathParser = new HuggingFacePathParser();
  private final HuggingFaceJsonTransformer transformer = new HuggingFaceJsonTransformer();
  private final HuggingFaceRegistryDao registry;
  private final ComponentDao componentDao;
  private final HttpRemoteFetcher fetcher;
  private final RawProtocolCache cache;
  private final HuggingFaceComponentFactory components;
  private final HuggingFaceLeaseManager leases;
  private final ObjectMapper objectMapper;
  private final long maxFileBytes;
  // Commit-addressed files are immutable. This node-local snapshot is only a rebuildable hot
  // cache: the asset watermark is still checked by RawProtocolCache and the database remains the
  // authority for misses, fetch ownership, failures, cleanup, and every mutable ref binding.
  private final LocalCache<FileKey, ModelFile> readyFiles = LocalCacheFactory.standard()
      .<FileKey, ModelFile>builder("huggingface-ready-files")
      .maximumSize(100_000)
      .expireAfterAccess(Duration.ofSeconds(60))
      .build();
  private final LocalCache<Long, ComponentRecord> revisionComponents = LocalCacheFactory.standard()
      .<Long, ComponentRecord>builder("huggingface-revision-components")
      .maximumSize(100_000)
      .expireAfterAccess(Duration.ofSeconds(60))
      .build();

  public HuggingFaceService(
      HuggingFaceRegistryDao registry,
      ComponentDao componentDao,
      HttpRemoteFetcher fetcher,
      RawProtocolCache cache,
      HuggingFaceComponentFactory components,
      HuggingFaceLeaseManager leases,
      ObjectMapper objectMapper,
      @Value("${kkrepo.huggingface.max-file-bytes:1099511627776}") long maxFileBytes) {
    this.registry = registry;
    this.componentDao = componentDao;
    this.fetcher = fetcher;
    this.cache = cache;
    this.components = components;
    this.leases = leases;
    this.objectMapper = objectMapper;
    this.maxFileBytes = Math.max(1L, maxFileBytes);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      String repositoryBase,
      boolean headOnly) {
    requireProxy(runtime);
    HuggingFacePath path = pathParser.parse(rawPath);
    return switch (path.kind()) {
      case MODEL_INFO, REVISION_INFO, TREE, REFS -> metadata(
          runtime, path, normalizeQuery(path, rawQuery), null, repositoryBase, headOnly, false);
      case RESOLVE -> resolve(runtime, path, repositoryBase, headOnly);
      case ROOT -> MavenResponse.noBody(404);
      case PATHS_INFO -> throw new MavenExceptions.MethodNotAllowed(
          "Hugging Face paths-info requires POST");
      case XET_TOKEN -> throw new MavenExceptions.MavenNotFoundException(
          "Client-side Xet token routes are not exposed");
      case UNSUPPORTED -> throw new MavenExceptions.MavenNotFoundException(rawPath);
    };
  }

  public MavenResponse post(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      String repositoryBase,
      byte[] body,
      boolean headOnly) {
    requireProxy(runtime);
    HuggingFacePath path = pathParser.parse(rawPath);
    if (path.kind() != HuggingFacePath.Kind.PATHS_INFO) {
      throw new MavenExceptions.MethodNotAllowed(
          "POST is only supported for Hugging Face paths-info");
    }
    validatePathsInfoBody(body);
    return metadata(
        runtime, path, normalizeQuery(path, rawQuery), body, repositoryBase, headOnly, false);
  }

  private MavenResponse metadata(
      RepositoryRuntime runtime,
      HuggingFacePath path,
      String query,
      byte[] requestBody,
      String repositoryBase,
      boolean headOnly,
      boolean forceRefresh) {
    String requestFingerprint = requestBody == null ? "" : sha256(requestBody);
    String cacheFingerprint = cacheFingerprint(path.rawPath(), query, requestFingerprint);
    String derivedPath = hiddenPath("derived", cacheFingerprint);
    Instant now = Instant.now();
    Optional<ApiCacheEntry> existing = registry.findApiCache(
        runtime.id(), path.rawPath(), query, requestFingerprint);
    if (!forceRefresh && existing.isPresent()) {
      ApiCacheEntry entry = existing.orElseThrow();
      if (entry.transformVersion() == HuggingFaceJsonTransformer.SCHEMA_VERSION
          && entry.expiresAt() != null && entry.expiresAt().isAfter(now)
          && cache.find(runtime, derivedPath).isPresent()) {
        return metadataResponse(
            cache.serve(runtime, derivedPath, headOnly, "inline"), entry, path, null);
      }
    }

    String remoteUrl = remoteUrl(runtime, path.rawPath(), query);
    String etag = existing.map(ApiCacheEntry::upstreamEtag).orElse(null);
    HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
        remoteUrl, etag, null, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withAccept("application/json")
        .withRepository(runtime);
    if (requestBody != null) {
      request = request.withBody(MediaType.APPLICATION_JSON_VALUE, requestBody);
    }
    try (HttpRemoteFetcher.Result result = fetcher.fetch(request)) {
      if (result.status() == 304 && existing.isPresent()
          && cache.find(runtime, derivedPath).isPresent()) {
        ApiCacheEntry refreshed = extend(existing.orElseThrow(), runtime, now);
        return metadataResponse(
            cache.serve(runtime, derivedPath, headOnly, "inline"), refreshed, path, result);
      }
      byte[] raw = readBounded(result.body(), HuggingFaceJsonTransformer.MAX_BYTES);
      if (result.status() < 200 || result.status() >= 300) {
        return upstreamError(result.status(), raw, result.contentType(), headOnly, result);
      }
      HuggingFaceJsonTransformer.Result transformed = transformer.transform(
          raw, remoteBase(runtime), repositoryBase);
      String rawPath = hiddenPath("raw", cacheFingerprint);
      RawProtocolCache.StoredAsset rawAsset = cache.storeHidden(
          runtime, rawPath, new ByteArrayInputStream(raw), jsonContentType(result.contentType()),
          Map.of(
              "huggingfaceRole", "raw-api",
              "upstreamEtag", value(result.etag()),
              "transformVersion", HuggingFaceJsonTransformer.SCHEMA_VERSION));
      RawProtocolCache.StoredAsset derivedAsset = cache.storeHidden(
          runtime, derivedPath, new ByteArrayInputStream(transformed.bytes()),
          MediaType.APPLICATION_JSON_VALUE,
          Map.of(
              "huggingfaceRole", "derived-api",
              "sourceSha256", rawAsset.sha256(),
              "transformVersion", HuggingFaceJsonTransformer.SCHEMA_VERSION,
              "removedXetHints", transformed.removedXetHints()));
      String nextLink = transformer.rewriteLink(
          result.header(HttpHeaders.LINK), remoteBase(runtime), repositoryBase);
      ApiCacheEntry stored = registry.upsertApiCache(new ApiCacheEntry(
          null, runtime.id(), path.rawPath(), query, requestFingerprint,
          rawAsset.assetId(), derivedAsset.assetId(), result.etag(), derivedAsset.sha256(),
          nextLink, HuggingFaceJsonTransformer.SCHEMA_VERSION, metadataExpiry(runtime, now), now));
      projectMetadata(runtime, path, transformed.sourceJson(), rawAsset.assetId(), result, now);
      return metadataResponse(
          cache.serve(runtime, derivedPath, headOnly, "inline"), stored, path, result);
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException(
          "Hugging Face metadata fetch failed: " + safeMessage(error));
    }
  }

  private MavenResponse resolve(
      RepositoryRuntime runtime,
      HuggingFacePath requestPath,
      String repositoryBase,
      boolean headOnly) {
    RevisionBinding binding = resolveRevision(runtime, requestPath, repositoryBase);
    FileKey fileKey = new FileKey(
        runtime.id(), requestPath.repoId(), binding.commit(), requestPath.filePath());
    ModelFile localReady = readyFiles.getIfPresent(fileKey);
    String canonicalPath = canonicalFilePath(
        requestPath.repoId(), binding.commit(), requestPath.filePath());
    if (localReady != null && ready(runtime, canonicalPath, localReady)) {
      rememberProjection(runtime, requestPath, localReady, binding);
      return fileResponse(runtime, canonicalPath, localReady, binding.commit(), headOnly);
    }
    if (localReady != null) readyFiles.invalidate(fileKey);
    ModelRevision revision = registry.findRevision(
            runtime.id(), requestPath.repoId(), binding.commit())
        .orElseGet(() -> ensureRevision(
            runtime, requestPath.repoId(), binding.commit(), requestPath.revision(), null,
            Instant.now()));
    ModelFile file = registry.findFile(
            runtime.id(), requestPath.repoId(), binding.commit(), requestPath.filePath())
        .orElseGet(() -> registry.upsertFileMetadata(new ModelFile(
            null, revision.id(), runtime.id(), requestPath.repoId(), binding.commit(),
            requestPath.filePath(), null, revision.componentId(), null, null, null, null, null,
            contentTypeFor(requestPath.filePath()),
            HuggingFaceFileKind.classify(requestPath.filePath()).name(),
            HuggingFaceRegistryDao.FILE_DISCOVERED, 0L, null, null, Instant.now())));
    if (HuggingFaceRegistryDao.FILE_FAILED.equals(file.state())
        && file.nextAttemptAt() != null && file.nextAttemptAt().isAfter(Instant.now())) {
      throw new MavenExceptions.BadUpstreamException(
          "Hugging Face upstream fetch is temporarily backed off after "
              + firstNonBlank(file.failureCode(), "a prior failure"));
    }
    if (ready(runtime, canonicalPath, file)) {
      readyFiles.put(fileKey, file);
      rememberProjection(runtime, requestPath, file, binding);
      return fileResponse(runtime, canonicalPath, file, binding.commit(), headOnly);
    }

    String fetchKey = requestPath.repoId() + "\u0000" + binding.commit() + "\u0000"
        + requestPath.filePath();
    Optional<HuggingFaceLeaseManager.Lease> acquired = leases.acquireUnlessCompleted(
        runtime.id(), fetchKey,
        () -> registry.findFile(
                runtime.id(), requestPath.repoId(), binding.commit(), requestPath.filePath())
            .map(candidate -> ready(runtime, canonicalPath, candidate)).orElse(false));
    if (acquired.isEmpty()) {
      ModelFile completed = registry.findFile(
              runtime.id(), requestPath.repoId(), binding.commit(), requestPath.filePath())
          .orElseThrow(() -> new MavenExceptions.BadUpstreamException(
              "Hugging Face fetch winner completed without publishing file state"));
      readyFiles.put(fileKey, completed);
      rememberProjection(runtime, requestPath, completed, binding);
      return fileResponse(runtime, canonicalPath, completed, binding.commit(), headOnly);
    }

    try (HuggingFaceLeaseManager.Lease lease = acquired.orElseThrow()) {
      lease.assertHeld();
      if (!registry.markFileFetching(file.id(), lease.fencingToken(), Instant.now())) {
        ModelFile completed = registry.findFile(
                runtime.id(), requestPath.repoId(), binding.commit(), requestPath.filePath())
            .orElseThrow();
        if (ready(runtime, canonicalPath, completed)) {
          readyFiles.put(fileKey, completed);
          rememberProjection(runtime, requestPath, completed, binding);
          return fileResponse(runtime, canonicalPath, completed, binding.commit(), headOnly);
        }
        throw new MavenExceptions.BadUpstreamException(
            "Hugging Face file state changed while acquiring fetch ownership");
      }
      MavenResponse populated = populateFile(
          runtime, requestPath, binding, revision, file, fileKey, canonicalPath, lease, headOnly);
      return populated;
    } catch (RuntimeException error) {
      registry.markFileFailed(
          file.id(), acquired.orElseThrow().fencingToken(), failureCode(error),
          Instant.now().plus(30, ChronoUnit.SECONDS), Instant.now());
      throw error;
    }
  }

  private MavenResponse populateFile(
      RepositoryRuntime runtime,
      HuggingFacePath requested,
      RevisionBinding binding,
      ModelRevision revision,
      ModelFile file,
      FileKey fileKey,
      String canonicalPath,
      HuggingFaceLeaseManager.Lease lease,
      boolean headOnly) {
    String remotePath = requested.repoId() + "/resolve/" + encodeRevision(binding.commit())
        + "/" + encodeFilePath(requested.filePath());
    String remoteUrl = remoteUrl(runtime, remotePath, "");
    HttpRemoteFetcher.Request getRequest = new HttpRemoteFetcher.Request(
        remoteUrl, null, null, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepositoryAllowingUnsignedRedirects(runtime, true, Set.of("*"));
    try (HttpRemoteFetcher.Result result = fetcher.fetch(getRequest)) {
      if (result.status() < 200 || result.status() >= 300) {
        markFetchFailed(file.id(), lease.fencingToken(), "UPSTREAM_GET_" + result.status());
        byte[] errorBody = readBounded(result.body(), MAX_ERROR_BYTES);
        return upstreamError(
            result.status(), errorBody, result.contentType(), headOnly, result);
      }
      String responseCommit = clean(result.header(HuggingFaceHeaders.REPO_COMMIT));
      if (responseCommit != null && !responseCommit.equalsIgnoreCase(binding.commit())) {
        throw new MavenExceptions.BadUpstreamException(
            "Hugging Face resolve commit did not match the pinned revision");
      }
      Long linkedSize = firstNonNull(
          file.expectedSize(), parseNonNegativeLong(result.header(HuggingFaceHeaders.LINKED_SIZE)));
      if (linkedSize == null && result.contentLength() > 0) linkedSize = result.contentLength();
      String xetHash = firstNonBlank(
          file.xetHash(), clean(result.header(HuggingFaceHeaders.XET_HASH)));
      HuggingFaceContentIdentity identity = HuggingFaceContentIdentity.fromResolveHeaders(
          file.gitOid(), file.lfsSha256(), linkedSize, xetHash,
          result.header(HuggingFaceHeaders.LINKED_ETAG), result.etag());
      if (identity.expectedSize() != null && identity.expectedSize() > maxFileBytes) {
        throw new MavenExceptions.BadUpstreamException(
            "Hugging Face model file exceeds the configured size limit");
      }
      Instant metadataUpdatedAt = Instant.now();
      String resolvedContentType = contentTypeFor(
          requested.filePath(), result.contentType(), file.contentType());
      ModelFile enriched = new ModelFile(
          file.id(), revision.id(), runtime.id(), requested.repoId(), binding.commit(),
          requested.filePath(), file.assetId(), revision.componentId(), identity.gitOid(),
          identity.linkedSha256(), identity.xetHash(), identity.expectedSize(),
          file.internalSha256(), resolvedContentType,
          file.fileKind(), HuggingFaceRegistryDao.FILE_FETCHING, lease.fencingToken(),
          null, null, metadataUpdatedAt);
      if (!registry.updateFetchingFileMetadata(
          enriched.id(), lease.fencingToken(), enriched.gitOid(), enriched.lfsSha256(),
          enriched.xetHash(), enriched.expectedSize(), enriched.contentType(), enriched.fileKind(),
          metadataUpdatedAt)) {
        throw new MavenExceptions.BadUpstreamException(
            "Hugging Face fetch lease was superseded before content download");
      }
      lease.assertHeld();
      ComponentRecord component = revisionComponent(runtime, revision);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("huggingfaceRole", "model-file");
      attributes.put("repoId", requested.repoId());
      attributes.put("commit", binding.commit());
      attributes.put("filePath", requested.filePath());
      attributes.put("fileKind", enriched.fileKind());
      if (identity.gitOid() != null) attributes.put("gitOid", identity.gitOid());
      if (identity.linkedSha256() != null) attributes.put("lfsSha256", identity.linkedSha256());
      if (identity.expectedSize() != null) attributes.put("expectedSize", identity.expectedSize());
      attributes.put("private", revision.privateModel());
      attributes.put("gated", revision.gated());
      if (revision.libraryName() != null) attributes.put("library", revision.libraryName());
      if (revision.pipelineTag() != null) attributes.put("pipeline", revision.pipelineTag());
      if (revision.license() != null) attributes.put("license", revision.license());
      if (requested.revision() != null) attributes.put("requestedRef", requested.revision());
      long readLimit = identity.expectedSize() == null
          ? maxFileBytes : Math.min(maxFileBytes, identity.expectedSize());
      RawProtocolCache.StoredAsset stored = cache.storeVerifiedImmutable(
          runtime, canonicalPath, new LimitedInputStream(result.body(), readLimit),
          enriched.contentType(), attributes, component,
          components.fileBrowsePath(requested.repoId(), binding.commit(), requested.filePath()),
          identity.expectedSize(), identity.linkedSha256(),
          identity.linkedSha256() == null ? identity.gitOid() : null);
      try {
        identity.verify(stored.sha256(), stored.size());
        lease.assertHeld();
        Instant publishedAt = Instant.now();
        if (!registry.markFileReady(
            enriched.id(), lease.fencingToken(), stored.assetId(), component.id(), stored.sha256(),
            stored.contentType(), publishedAt)) {
          throw new MavenExceptions.BadUpstreamException(
              "Hugging Face fetch lease was superseded before publication");
        }
        ModelFile ready = new ModelFile(
            enriched.id(), enriched.revisionId(), enriched.repositoryId(), enriched.repoId(),
            enriched.commitHash(), enriched.path(), stored.assetId(), component.id(),
            enriched.gitOid(), enriched.lfsSha256(), enriched.xetHash(), enriched.expectedSize(),
            stored.sha256(), stored.contentType(), enriched.fileKind(),
            HuggingFaceRegistryDao.FILE_READY, lease.fencingToken(), null, null, publishedAt);
        readyFiles.put(fileKey, ready);
        rememberProjection(runtime, requested, ready, binding);
        MavenResponse response = cache.serveStored(runtime, stored, headOnly, "attachment");
        return decorateFileResponse(response, ready, binding.commit());
      } catch (RuntimeException error) {
        stored.discardBody();
        throw error;
      }
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException(
          "Hugging Face model file fetch failed: " + safeMessage(error));
    }
  }

  private RevisionBinding resolveRevision(
      RepositoryRuntime runtime, HuggingFacePath path, String repositoryBase) {
    if (HuggingFacePathParser.isCommit(path.revision())) {
      return new RevisionBinding(path.revision().toLowerCase(Locale.ROOT), 0L);
    }
    Instant now = Instant.now();
    Optional<RevisionRef> cached = registry.findRef(runtime.id(), path.repoId(), path.revision())
        .filter(ref -> ref.expiresAt() != null && ref.expiresAt().isAfter(now));
    if (cached.isPresent()) {
      RevisionRef ref = cached.orElseThrow();
      return new RevisionBinding(ref.commitHash(), ref.generation());
    }
    HuggingFacePath revisionInfo = pathParser.parse(
        "api/models/" + path.repoId() + "/revision/" + encodeRevision(path.revision()));
    metadata(runtime, revisionInfo, "", null, repositoryBase, true, true);
    RevisionRef resolved = registry.findRef(runtime.id(), path.repoId(), path.revision())
        .orElseThrow(() -> new MavenExceptions.BadUpstreamException(
            "Hugging Face revision response did not contain a commit hash"));
    return new RevisionBinding(resolved.commitHash(), resolved.generation());
  }

  private void projectMetadata(
      RepositoryRuntime runtime,
      HuggingFacePath path,
      JsonNode json,
      long rawAssetId,
      HttpRemoteFetcher.Result result,
      Instant now) {
    if (path.kind() == HuggingFacePath.Kind.MODEL_INFO
        || path.kind() == HuggingFacePath.Kind.REVISION_INFO) {
      JsonNode model = json.isArray() ? json.path(0) : json;
      String commit = text(model, "sha");
      if (!HuggingFacePathParser.isCommit(commit)) {
        commit = clean(result.header(HuggingFaceHeaders.REPO_COMMIT));
      }
      if (!HuggingFacePathParser.isCommit(commit)) return;
      String requestedRef = path.kind() == HuggingFacePath.Kind.MODEL_INFO ? "main" : path.revision();
      ensureRevision(runtime, path.repoId(), commit, requestedRef, model, now, rawAssetId);
      if (!HuggingFacePathParser.isCommit(requestedRef)) {
        registry.upsertRef(new RevisionRef(
            runtime.id(), path.repoId(), requestedRef, commit.toLowerCase(Locale.ROOT), 0L,
            metadataExpiry(runtime, now), now, now));
      }
      projectFileEntries(runtime, path.repoId(), commit, json.path("siblings"), now);
      return;
    }
    if (path.kind() == HuggingFacePath.Kind.TREE
        || path.kind() == HuggingFacePath.Kind.PATHS_INFO) {
      String commit = path.revision();
      if (!HuggingFacePathParser.isCommit(commit)) {
        commit = registry.findRef(runtime.id(), path.repoId(), path.revision())
            .map(RevisionRef::commitHash)
            .orElse(clean(result.header(HuggingFaceHeaders.REPO_COMMIT)));
      }
      if (!HuggingFacePathParser.isCommit(commit)) return;
      ensureRevision(runtime, path.repoId(), commit, path.revision(), null, now);
      projectFileEntries(runtime, path.repoId(), commit, json, now);
      return;
    }
    if (path.kind() == HuggingFacePath.Kind.REFS && json != null && json.isObject()) {
      for (String family : Set.of("branches", "tags", "converts", "pullRequests")) {
        JsonNode refs = json.path(family);
        if (!refs.isArray()) continue;
        for (JsonNode ref : refs) {
          String commit = firstNonBlank(text(ref, "targetCommit"), text(ref, "commit"));
          if (!HuggingFacePathParser.isCommit(commit)) continue;
          String name = firstNonBlank(text(ref, "name"), text(ref, "ref"));
          if (name == null) continue;
          String canonicalCommit = commit.toLowerCase(Locale.ROOT);
          ensureRevision(runtime, path.repoId(), canonicalCommit, name, null, now);
          registry.upsertRef(new RevisionRef(
              runtime.id(), path.repoId(), name, canonicalCommit, 0L,
              metadataExpiry(runtime, now), now, now));
          String fullRef = text(ref, "ref");
          if (fullRef != null && !fullRef.equals(name)) {
            registry.upsertRef(new RevisionRef(
                runtime.id(), path.repoId(), fullRef, canonicalCommit, 0L,
                metadataExpiry(runtime, now), now, now));
          }
        }
      }
    }
  }

  private void projectFileEntries(
      RepositoryRuntime runtime, String repoId, String commit, JsonNode entries, Instant now) {
    if (entries == null || !entries.isArray()) return;
    ModelRevision revision = ensureRevision(runtime, repoId, commit, null, null, now);
    int processed = 0;
    for (JsonNode entry : entries) {
      if (++processed > 250_000) {
        throw new MavenExceptions.BadUpstreamException("Hugging Face tree exceeds entry limit");
      }
      if (!"file".equalsIgnoreCase(text(entry, "type"))) continue;
      String filePath = text(entry, "path");
      if (!validFilePath(repoId, commit, filePath)) continue;
      JsonNode lfs = entry.path("lfs");
      String lfsSha = lfs.isObject() ? firstNonBlank(text(lfs, "oid"), text(lfs, "sha256")) : null;
      if (lfsSha != null && lfsSha.startsWith("sha256:")) lfsSha = lfsSha.substring(7);
      Long size = lfs.isObject() && lfs.path("size").canConvertToLong()
          ? lfs.path("size").longValue()
          : entry.path("size").canConvertToLong() ? entry.path("size").longValue() : null;
      registry.upsertFileMetadata(new ModelFile(
          null, revision.id(), runtime.id(), repoId, commit.toLowerCase(Locale.ROOT), filePath,
          null, revision.componentId(), text(entry, "oid"), lfsSha,
          firstNonBlank(text(entry, "xetHash"), text(entry, "xet_hash")), size, null,
          contentTypeFor(filePath), HuggingFaceFileKind.classify(filePath).name(),
          HuggingFaceRegistryDao.FILE_DISCOVERED, 0L, null, null, now));
    }
  }

  private ModelRevision ensureRevision(
      RepositoryRuntime runtime,
      String repoId,
      String commit,
      String requestedRef,
      JsonNode model,
      Instant now) {
    return ensureRevision(runtime, repoId, commit, requestedRef, model, now, null);
  }

  private ModelRevision ensureRevision(
      RepositoryRuntime runtime,
      String repoId,
      String commit,
      String requestedRef,
      JsonNode model,
      Instant now,
      Long rawMetadataAssetId) {
    String canonicalCommit = commit.toLowerCase(Locale.ROOT);
    Optional<ModelRevision> existing = registry.findRevision(runtime.id(), repoId, canonicalCommit);
    boolean privateModel = model != null && model.path("private").asBoolean(false);
    boolean gated = model != null && gated(model.path("gated"));
    boolean effectivePrivate = model != null && model.has("private")
        ? privateModel : existing.map(ModelRevision::privateModel).orElse(false);
    boolean effectiveGated = model != null && model.has("gated")
        ? gated : existing.map(ModelRevision::gated).orElse(false);
    String library = model == null ? null : text(model, "library_name");
    String pipeline = model == null ? null : text(model, "pipeline_tag");
    String license = model == null ? null : license(model);
    ComponentRecord candidate = components.component(
        runtime, repoId, canonicalCommit, requestedRef,
        effectivePrivate,
        effectiveGated,
        firstNonBlank(library, existing.map(ModelRevision::libraryName).orElse(null)),
        firstNonBlank(pipeline, existing.map(ModelRevision::pipelineTag).orElse(null)),
        firstNonBlank(license, existing.map(ModelRevision::license).orElse(null)), now);
    long componentId = componentDao.upsertReturningId(candidate);
    ComponentRecord persistedComponent = new ComponentRecord(
        componentId, candidate.repositoryId(), candidate.format(), candidate.namespace(),
        candidate.name(), candidate.version(), candidate.kind(), candidate.coordinateHash(),
        candidate.attributes(), candidate.lastUpdatedAt());
    ModelRevision persistedRevision = registry.upsertRevision(new ModelRevision(
        existing.map(ModelRevision::id).orElse(null), runtime.id(), repoId, canonicalCommit,
        componentId, firstNonNull(rawMetadataAssetId,
            existing.map(ModelRevision::rawMetadataAssetId).orElse(null)),
        model == null ? existing.map(ModelRevision::author).orElse(null) : text(model, "author"),
        model == null ? existing.map(ModelRevision::committedAt).orElse(null)
            : parseInstant(firstNonBlank(text(model, "lastModified"), text(model, "createdAt"))),
        effectivePrivate,
        effectiveGated,
        firstNonBlank(library, existing.map(ModelRevision::libraryName).orElse(null)),
        firstNonBlank(pipeline, existing.map(ModelRevision::pipelineTag).orElse(null)),
        firstNonBlank(license, existing.map(ModelRevision::license).orElse(null)),
        existing.map(ModelRevision::observedAt).orElse(now), now));
    revisionComponents.put(componentId, persistedComponent);
    return persistedRevision;
  }

  private ComponentRecord revisionComponent(RepositoryRuntime runtime, ModelRevision revision) {
    ComponentRecord record = revisionComponents.getIfPresent(revision.componentId());
    if (record == null) {
      record = componentDao.findById(revision.componentId()).orElseThrow();
      revisionComponents.put(revision.componentId(), record);
    }
    if (record.repositoryId() != runtime.id() || record.format() != RepositoryFormat.HUGGINGFACE) {
      throw new IllegalStateException("Hugging Face revision component belongs to another repository");
    }
    return record;
  }

  private boolean ready(RepositoryRuntime runtime, String canonicalPath, ModelFile file) {
    return file != null && HuggingFaceRegistryDao.FILE_READY.equals(file.state())
        && file.assetId() != null && cache.find(runtime, canonicalPath).isPresent();
  }

  private MavenResponse fileResponse(
      RepositoryRuntime runtime,
      String canonicalPath,
      ModelFile file,
      String commit,
      boolean headOnly) {
    return decorateFileResponse(
        cache.serve(runtime, canonicalPath, headOnly, "attachment"), file, commit);
  }

  private MavenResponse decorateFileResponse(
      MavenResponse response, ModelFile file, String commit) {
    response = response.withContentType(
        contentTypeFor(file.path(), response.contentType(), file.contentType()));
    response.withHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
        .withHeader(HttpHeaders.CONTENT_DISPOSITION, inlineFileDisposition(file.path()))
        .withHeader(HuggingFaceHeaders.REPO_COMMIT, commit);
    String linkedEtag = firstNonBlank(file.lfsSha256(), file.gitOid());
    if (linkedEtag != null) {
      response.withHeader(HuggingFaceHeaders.LINKED_ETAG, "\"" + linkedEtag + "\"");
    }
    if (file.expectedSize() != null) {
      response.withHeader(HuggingFaceHeaders.LINKED_SIZE, Long.toString(file.expectedSize()));
    }
    return response;
  }

  private void rememberProjection(
      RepositoryRuntime runtime,
      HuggingFacePath requested,
      ModelFile file,
      RevisionBinding binding) {
    if (HuggingFacePathParser.isCommit(requested.revision())
        && requested.revision().equalsIgnoreCase(binding.commit())) {
      return;
    }
    registry.upsertRouteProjection(new HuggingFaceRegistryDao.RouteProjection(
        runtime.id(), requested.rawPath(), file.id(), requested.revision(), binding.generation(),
        Instant.now()));
  }

  private MavenResponse metadataResponse(
      MavenResponse response,
      ApiCacheEntry entry,
      HuggingFacePath path,
      HttpRemoteFetcher.Result upstream) {
    response = response.withEtag(entry.derivedEtag());
    if (entry.nextLink() != null && !entry.nextLink().isBlank()) {
      response.withHeader(HttpHeaders.LINK, entry.nextLink());
    }
    if (path.revision() != null && HuggingFacePathParser.isCommit(path.revision())) {
      response.withHeader(HuggingFaceHeaders.REPO_COMMIT, path.revision().toLowerCase(Locale.ROOT));
    }
    copySafeUpstreamHeaders(response, upstream);
    return response;
  }

  private ApiCacheEntry extend(ApiCacheEntry existing, RepositoryRuntime runtime, Instant now) {
    return registry.upsertApiCache(new ApiCacheEntry(
        existing.id(), existing.repositoryId(), existing.route(), existing.query(),
        existing.requestFingerprint(), existing.rawAssetId(), existing.derivedAssetId(),
        existing.upstreamEtag(), existing.derivedEtag(), existing.nextLink(),
        existing.transformVersion(), metadataExpiry(runtime, now), now));
  }

  private MavenResponse upstreamError(
      int status,
      byte[] bytes,
      String contentType,
      boolean headOnly,
      HttpRemoteFetcher.Result upstream) {
    byte[] safe = bytes == null || bytes.length > MAX_ERROR_BYTES ? new byte[0] : bytes;
    MavenResponse response = headOnly || safe.length == 0
        ? MavenResponse.noBody(status, safe.length, jsonContentType(contentType), null, null)
        : MavenResponse.ok(
            new ByteArrayInputStream(safe), safe.length, jsonContentType(contentType), null, null)
            .withStatus(status);
    copySafeUpstreamHeaders(response, upstream);
    return response;
  }

  private static void copySafeUpstreamHeaders(
      MavenResponse response, HttpRemoteFetcher.Result upstream) {
    if (upstream == null) return;
    upstream.headers().forEach((name, value) -> {
      if (HuggingFaceHeaders.passthrough(name) && value != null) response.withHeader(name, value);
    });
  }

  private String normalizeQuery(HuggingFacePath path, String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) return "";
    if (rawQuery.length() > 8_192 || rawQuery.indexOf('#') >= 0
        || rawQuery.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
      throw new MavenExceptions.MavenNotFoundException("Invalid Hugging Face query");
    }
    Set<String> allowed = switch (path.kind()) {
      case MODEL_INFO, REVISION_INFO -> MODEL_QUERY;
      case TREE, PATHS_INFO -> TREE_QUERY;
      case REFS -> REFS_QUERY;
      default -> Set.of();
    };
    String[] pairs = rawQuery.split("&", -1);
    if (pairs.length > 16) throw new MavenExceptions.MavenNotFoundException("Too many query parameters");
    for (String pair : pairs) {
      if (pair.isBlank()) continue;
      int equals = pair.indexOf('=');
      String rawName = equals < 0 ? pair : pair.substring(0, equals);
      String name;
      try {
        name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
      } catch (RuntimeException error) {
        throw new MavenExceptions.MavenNotFoundException("Invalid Hugging Face query encoding");
      }
      if (!allowed.contains(name)) {
        throw new MavenExceptions.MavenNotFoundException(
            "Unsupported Hugging Face query parameter: " + name);
      }
    }
    return rawQuery;
  }

  private void validatePathsInfoBody(byte[] body) {
    if (body == null || body.length == 0 || body.length > MAX_PATHS_INFO_BYTES) {
      throw new MavenExceptions.MavenNotFoundException("Invalid Hugging Face paths-info body");
    }
    try {
      JsonNode json = objectMapper.readTree(body);
      JsonNode paths = json == null ? null : json.path("paths");
      if (paths == null || !paths.isArray() || paths.isEmpty()
          || paths.size() > MAX_PATHS_INFO_PATHS) {
        throw new MavenExceptions.MavenNotFoundException(
            "Hugging Face paths-info requires 1 to " + MAX_PATHS_INFO_PATHS + " paths");
      }
      for (JsonNode path : paths) {
        if (!path.isTextual() || !validFilePath("model", "0".repeat(40), path.textValue())) {
          throw new MavenExceptions.MavenNotFoundException(
              "Hugging Face paths-info contains an invalid path");
        }
      }
    } catch (IOException error) {
      throw new MavenExceptions.MavenNotFoundException("Invalid Hugging Face paths-info JSON");
    }
  }

  private boolean validFilePath(String repoId, String revision, String filePath) {
    if (filePath == null || filePath.isBlank()) return false;
    String synthetic = repoId + "/resolve/" + encodeRevision(revision) + "/"
        + encodeFilePath(filePath);
    return pathParser.parse(synthetic).kind() == HuggingFacePath.Kind.RESOLVE;
  }

  private static String cacheFingerprint(String route, String query, String request) {
    return sha256((route + "\u0000" + query + "\u0000" + request)
        .getBytes(StandardCharsets.UTF_8));
  }

  private record FileKey(long repositoryId, String repoId, String commit, String path) {
  }

  private static String hiddenPath(String role, String fingerprint) {
    return ".kkrepo/huggingface/" + role + "/" + fingerprint + ".json";
  }

  private static String canonicalFilePath(String repoId, String commit, String filePath) {
    return repoId + "/resolve/" + commit.toLowerCase(Locale.ROOT) + "/" + filePath;
  }

  private static String remoteUrl(RepositoryRuntime runtime, String path, String query) {
    String base = remoteBase(runtime);
    return base + "/" + path + (query == null || query.isBlank() ? "" : "?" + query);
  }

  private static String remoteBase(RepositoryRuntime runtime) {
    String value = runtime.proxyRemoteUrl();
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Hugging Face proxy remote URL is required");
    }
    value = value.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String encodeRevision(String value) {
    return encodeSegment(value).replace("%2F", "%2F");
  }

  private static String encodeFilePath(String value) {
    return java.util.Arrays.stream(value.split("/", -1))
        .map(HuggingFaceService::encodeSegment)
        .collect(java.util.stream.Collectors.joining("/"));
  }

  private static String encodeSegment(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20").replace("%2f", "%2F");
  }

  private static Instant metadataExpiry(RepositoryRuntime runtime, Instant now) {
    int minutes = runtime.metadataMaxAgeMinutesOrDefault();
    return minutes < 0 ? now.plus(3650, ChronoUnit.DAYS) : now.plus(minutes, ChronoUnit.MINUTES);
  }

  private static String text(JsonNode node, String field) {
    if (node == null) return null;
    JsonNode value = node.path(field);
    return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
  }

  private static String license(JsonNode model) {
    String direct = text(model, "license");
    if (direct != null) return direct;
    JsonNode tags = model.path("tags");
    if (tags.isArray()) {
      for (JsonNode tag : tags) {
        if (tag.isTextual() && tag.textValue().startsWith("license:")) {
          return tag.textValue().substring("license:".length());
        }
      }
    }
    return null;
  }

  private static boolean gated(JsonNode value) {
    return value != null && ((value.isBoolean() && value.booleanValue())
        || (value.isTextual() && !value.textValue().equalsIgnoreCase("false")));
  }

  private static Instant parseInstant(String value) {
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Long parseNonNegativeLong(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      long parsed = Long.parseLong(value.trim());
      return parsed < 0 ? null : parsed;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    if (input == null) return new byte[0];
    byte[] bytes = input.readNBytes(limit + 1);
    if (bytes.length > limit) throw new IOException("upstream response exceeds size limit");
    return bytes;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String jsonContentType(String value) {
    return value == null || value.isBlank() ? MediaType.APPLICATION_JSON_VALUE : value;
  }

  private static String contentTypeFor(String path, String... candidates) {
    String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON_VALUE;
    if (lower.endsWith(".md")) return "text/markdown";
    if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN_VALUE;
    if (candidates != null) {
      for (String candidate : candidates) {
        if (candidate != null && !candidate.isBlank()) return candidate;
      }
    }
    return MediaType.APPLICATION_OCTET_STREAM_VALUE;
  }

  private static String inlineFileDisposition(String path) {
    String value = path == null ? "" : path;
    int slash = value.lastIndexOf('/');
    String filename = slash < 0 ? value : value.substring(slash + 1);
    if (filename.isBlank()) filename = "download";
    return ContentDisposition.inline()
        .filename(filename, StandardCharsets.UTF_8)
        .build()
        .toString();
  }

  private static String clean(String value) {
    String result = HuggingFaceHeaders.unquote(value);
    return result == null || result.isBlank() ? null : result.trim();
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static <T> T firstNonNull(T first, T second) {
    return first != null ? first : second;
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private static String safeMessage(Throwable error) {
    String value = error.getMessage();
    if (value == null || value.isBlank()) return error.getClass().getSimpleName();
    return value.length() <= 256 ? value : value.substring(0, 256);
  }

  private static String failureCode(Throwable error) {
    if (error instanceof IllegalArgumentException) return "IDENTITY_MISMATCH";
    if (error instanceof MavenExceptions.BadUpstreamException) return "UPSTREAM_FAILURE";
    return "FETCH_FAILURE";
  }

  private void markFetchFailed(long fileId, long fencingToken, String failureCode) {
    Instant now = Instant.now();
    registry.markFileFailed(
        fileId, fencingToken, failureCode, now.plus(30, ChronoUnit.SECONDS), now);
  }

  private static void requireProxy(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.HUGGINGFACE || !runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Hugging Face Models is available only as a proxy repository");
    }
  }

  private record RevisionBinding(String commit, long generation) {
  }

  private static final class LimitedInputStream extends FilterInputStream {
    private final long limit;
    private long count;

    private LimitedInputStream(InputStream input, long limit) {
      super(input);
      this.limit = Math.max(0, limit);
    }

    @Override
    public int read() throws IOException {
      if (count >= limit) {
        if (super.read() < 0) return -1;
        throw new IOException("Hugging Face file exceeds configured size limit");
      }
      int value = super.read();
      if (value >= 0) count++;
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (count >= limit) {
        if (super.read() < 0) return -1;
        throw new IOException("Hugging Face file exceeds configured size limit");
      }
      int allowed = (int) Math.min(length, limit - count);
      int read = super.read(buffer, offset, allowed);
      if (read > 0) count += read;
      return read;
    }
  }
}
