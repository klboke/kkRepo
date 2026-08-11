package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.core.DatabaseCompositeKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conan.ConanInfo;
import com.github.klboke.kkrepo.protocol.conan.ConanManifest;
import com.github.klboke.kkrepo.protocol.conan.ConanMediaTypes;
import com.github.klboke.kkrepo.protocol.conan.ConanPath;
import com.github.klboke.kkrepo.protocol.conan.ConanPathParser;
import com.github.klboke.kkrepo.protocol.conan.ConanReference;
import com.github.klboke.kkrepo.protocol.conan.ConanRequestTarget;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Conan 2 hosted, proxy, and group repository implementation. */
@Service
public class ConanService {
  private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024 * 1024;
  private static final int MAX_DISCOVERY_BYTES = 32 * 1024 * 1024;
  private static final int PAGE = 10_000;
  private static final java.time.Duration SESSION_TTL = java.time.Duration.ofHours(24);

  private final ConanRegistryDao registry;
  private final ConanAssetSupport assets;
  private final ConanComponentService components;
  private final ConanArchiveInspector archives;
  private final ConanLeaseManager leases;
  private final ConanAuthService auth;
  private final ConanRemoteClient remote;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final ConanPathParser paths = new ConanPathParser();
  private final String nodeId = UUID.randomUUID().toString();

  ConanService(
      ConanRegistryDao registry,
      ConanAssetSupport assets,
      ConanComponentService components,
      ConanArchiveInspector archives,
      ConanLeaseManager leases,
      ConanAuthService auth,
      ConanRemoteClient remote,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.registry = registry;
    this.assets = assets;
    this.components = components;
    this.archives = archives;
    this.leases = leases;
    this.auth = auth;
    this.remote = remote;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      String rawPath,
      String rawQuery,
      boolean headOnly,
      AuthenticatedSubject subject) {
    requireRuntime(runtime);
    // Nexus 3.94 does not register HEAD for Conan v1/v2 routes. Keep this explicit instead of
    // letting the generic repository controller synthesize a GET-shaped response.
    if (headOnly) {
      throw new ConanExceptions.NotFound("Conan HEAD routes are not available");
    }
    ConanRequestTarget target;
    try {
      target = paths.parse(rawPath, rawQuery);
    } catch (IllegalArgumentException invalid) {
      throw new ConanExceptions.BadRequest(invalid.getMessage(), invalid);
    }
    ConanPath path = target.path();
    return switch (path.kind()) {
      case PING -> MavenResponse.noBody(200)
          .withHeader(ConanMediaTypes.CAPABILITIES_HEADER, ConanMediaTypes.CAPABILITIES);
      case AUTHENTICATE -> plain(
          auth.issue(runtime.id(), subject), headOnly, ConanMediaTypes.TEXT);
      case CHECK_CREDENTIALS -> plain(
          requireSubject(subject).userId(), headOnly, ConanMediaTypes.TEXT);
      case UNKNOWN -> throw new ConanExceptions.NotFound("Unknown Conan route: " + rawPath);
      default -> switch (runtime.type()) {
        case HOSTED -> getHosted(runtime, target, headOnly);
        case PROXY -> getProxy(runtime, target, rawPath, rawQuery, headOnly);
        case GROUP -> getGroup(runtime, target, rawPath, rawQuery, headOnly, subject);
      };
    };
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      long contentLength,
      String contentType,
      String checksumSha1,
      boolean checksumDeploy,
      AuthenticatedSubject subject,
      String ip) {
    return put0(
        runtime, rawPath, body, contentLength, contentType, checksumSha1,
        checksumDeploy, subject, ip, false, Instant.now());
  }

  private MavenResponse put0(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      long contentLength,
      String contentType,
      String checksumSha1,
      boolean checksumDeploy,
      AuthenticatedSubject subject,
      String ip,
      boolean migration,
      Instant publishedAt) {
    requireRuntime(runtime);
    if (!runtime.isHosted() && !(migration && runtime.isProxy())) {
      throw new ConanExceptions.MethodNotAllowed("Conan uploads require a hosted repository");
    }
    if (checksumDeploy) {
      throw new ConanExceptions.NotFound("Conan repositories are not checksum-deploy stores");
    }
    if (contentLength > MAX_UPLOAD_BYTES) {
      throw new ConanExceptions.ContentTooLarge("Conan file exceeds the upload limit");
    }
    if (!migration && "DENY".equalsIgnoreCase(runtime.writePolicy())) {
      throw new ConanExceptions.Forbidden("Repository write policy forbids Conan uploads");
    }
    ConanPath path = paths.parse(rawPath);
    if (!path.fileResource()) {
      throw new ConanExceptions.MethodNotAllowed("Conan PUT accepts revision files only");
    }
    if (checksumSha1 == null || !checksumSha1.matches("(?i)[0-9a-f]{40}")) {
      throw new ConanExceptions.BadRequest("X-Checksum-Sha1 is required for Conan uploads");
    }
    AuthenticatedSubject actor = requireSubject(subject);
    if (metadataPath(path.filePath()) && resolveOwner(runtime.id(), path.reference()).isPresent()) {
      return putMetadata(
          runtime, path.reference(), path.filePath(), body, contentType, checksumSha1,
          actor.userId(), ip);
    }
    ensurePackageParent(runtime.id(), path.reference());
    String ownerKind = ownerKind(path.reference());
    String coordinate = ownerCoordinate(path.reference());
    String actorKey = DatabaseCompositeKey.of(actor.source(), actor.userId());
    Instant now = Instant.now();
    ConanRegistryDao.UploadSession session = registry.openUploadSession(
        new ConanRegistryDao.UploadSession(
            null, runtime.id(), ownerKind, coordinate, actorKey,
            ConanRegistryDao.SESSION_OPEN, nodeId, 0, null, now.plus(SESSION_TTL), now, now));
    ConanAssetSupport.Staged staged = null;
    try {
      staged = assets.stage(
          runtime, session.id(), path.filePath(), new MaxBytesInputStream(body, MAX_UPLOAD_BYTES),
          mediaType(contentType), actor.userId(), ip);
      verifySha1(checksumSha1, staged.blob().sha1());
      inspectArchive(runtime, staged, path.filePath());
      registry.upsertUploadFile(new ConanRegistryDao.UploadFile(
          null, session.id(), path.filePath(), staged.asset().id(), staged.blob().md5(),
          staged.blob().sha1(), staged.blob().sha256(), staged.blob().size(),
          mediaType(contentType), now, now));
      boolean manifestRequest = ConanManifest.FILE_NAME.equals(path.filePath());
      tryCommitSession(
          runtime, path.reference(), session.id(), actor.userId(), ip, manifestRequest,
          publishedAt);
      return MavenResponse.noBody(200);
    } catch (RuntimeException failure) {
      Optional<AssetRecord> current = staged == null
          ? Optional.empty() : assets.find(runtime, staged.path());
      if (staged != null && current.isPresent()
          && Objects.equals(
              current.orElseThrow().assetBlobId(), staged.asset().assetBlobId())) {
        // Keep valid staged files for resumable manifest-last uploads. Invalid checksum/archive
        // files are removed by the branches below before they can participate in a commit.
        if (failure instanceof ConanExceptions.BadRequest
            || failure instanceof ConanExceptions.ContentTooLarge) {
          assets.discard(runtime, staged);
        }
      }
      throw failure;
    }
  }

  /** Internal publication entry used by the permission-checked Components upload adapter. */
  public MavenResponse putInternal(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      long contentLength,
      String contentType,
      String checksumSha1,
      String actor,
      String ip) {
    String user = actor == null || actor.isBlank() ? "component-upload" : actor;
    return put(
        runtime,
        rawPath,
        body,
        contentLength,
        contentType,
        checksumSha1,
        false,
        new AuthenticatedSubject("INTERNAL", user, null, null, null),
        ip);
  }

  /** Replays one verified Nexus file into a durable manifest-gated migration session. */
  public MavenResponse putMigration(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      long contentLength,
      String contentType,
      String checksumSha1,
      String actor,
      String ip,
      Instant publishedAt) {
    String user = actor == null || actor.isBlank() ? "nexus-migration" : actor;
    return put0(
        runtime,
        rawPath,
        body,
        contentLength,
        contentType,
        checksumSha1,
        false,
        new AuthenticatedSubject("MIGRATION", user, null, null, null),
        ip,
        true,
        publishedAt == null ? Instant.now() : publishedAt);
  }

  public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
    requireRuntime(runtime);
    if (!runtime.isHosted()) {
      throw new ConanExceptions.MethodNotAllowed("Conan delete requires a hosted repository");
    }
    if ("DENY".equalsIgnoreCase(runtime.writePolicy())) {
      throw new ConanExceptions.Forbidden("Repository write policy forbids Conan deletion");
    }
    ConanPath path = paths.parse(rawPath);
    ConanRegistryDao.DeletedCoordinate deleted = transactionally(() -> {
      ConanRegistryDao.DeletedCoordinate result = switch (path.kind()) {
        case RECIPE -> registry.deleteCoordinate(new ConanRegistryDao.DeleteTarget(
            coordinate(runtime.id(), path.reference()), null, null, null),
            "conan-client-delete", Instant.now());
        case RECIPE_REVISION -> registry.deleteCoordinate(new ConanRegistryDao.DeleteTarget(
            coordinate(runtime.id(), path.reference()), path.reference().recipeRevision(), null,
            null), "conan-client-delete", Instant.now());
        case PACKAGES -> registry.deleteAllPackages(
            coordinate(runtime.id(), path.reference()), path.reference().recipeRevision(),
            "conan-client-delete", Instant.now());
        case PACKAGE -> registry.deleteCoordinate(new ConanRegistryDao.DeleteTarget(
            coordinate(runtime.id(), path.reference()), path.reference().recipeRevision(),
            path.reference().packageId(), null), "conan-client-delete", Instant.now());
        case PACKAGE_REVISION -> registry.deleteCoordinate(new ConanRegistryDao.DeleteTarget(
            coordinate(runtime.id(), path.reference()), path.reference().recipeRevision(),
            path.reference().packageId(), path.reference().packageRevision()),
            "conan-client-delete", Instant.now());
        default -> throw new ConanExceptions.MethodNotAllowed(
            "Conan DELETE does not support " + path.kind());
      };
      result.assetIds().forEach(assetId -> assets.deleteByAssetId(runtime, assetId));
      result.componentIds().forEach(components::deleteIfNoAssets);
      return result;
    });
    if (!deleted.deleted()) throw new ConanExceptions.NotFound("Conan coordinate was not found");
    return MavenResponse.noBody(200);
  }

  /** Cleanup adapter: removes one complete Conan recipe component and all RREV/PREV files. */
  public int deleteComponentForCleanup(
      RepositoryRuntime runtime, long componentId, String actor) {
    requireRuntime(runtime);
    if (runtime.isGroup()) {
      throw new ConanExceptions.MethodNotAllowed("Conan groups do not own cleanup content");
    }
    ConanRegistryDao.Recipe recipe = registry.findRecipeByComponent(runtime.id(), componentId)
        .orElseThrow(() -> new ConanExceptions.NotFound(
            "Conan cleanup component was not found: " + componentId));
    ConanRegistryDao.DeletedCoordinate deleted = transactionally(() -> {
      ConanRegistryDao.DeletedCoordinate result = registry.deleteCoordinate(
          new ConanRegistryDao.DeleteTarget(recipe.coordinate(), null, null, null),
          "cleanup policy delete by " + (actor == null ? "system" : actor),
          Instant.now());
      result.assetIds().forEach(assetId -> assets.deleteByAssetId(runtime, assetId));
      result.componentIds().forEach(components::deleteIfNoAssets);
      return result;
    });
    return deleted.assetIds().size();
  }

  private MavenResponse getHosted(
      RepositoryRuntime runtime, ConanRequestTarget target, boolean headOnly) {
    ConanPath path = target.path();
    return switch (path.kind()) {
      case RECIPE_SEARCH -> json(Map.of(
          "results", registry.searchRecipes(
                  runtime.id(), target.searchPattern(), target.ignoreCase(), null, PAGE).stream()
              .map(ConanService::reference)
              .sorted()
              .toList()), headOnly, null);
      case RECIPE_REVISIONS -> recipeRevisions(runtime.id(), path.reference(), headOnly);
      case RECIPE_LATEST -> recipeLatest(runtime.id(), path.reference(), headOnly);
      case RECIPE_FILES -> fileList(runtime.id(), path.reference(), headOnly);
      case RECIPE_FILE -> file(runtime, path.reference(), path.filePath(), headOnly);
      case PACKAGE_SEARCH -> packageSearch(
          runtime.id(), path.reference(), target.listOnly(), headOnly);
      case PACKAGE_REVISIONS -> packageRevisions(runtime.id(), path.reference(), headOnly);
      case PACKAGE_LATEST -> packageLatest(runtime.id(), path.reference(), headOnly);
      case PACKAGE_FILES -> fileList(runtime.id(), path.reference(), headOnly);
      case PACKAGE_FILE -> file(runtime, path.reference(), path.filePath(), headOnly);
      default -> throw new ConanExceptions.NotFound("Unsupported Conan resource: " + path.kind());
    };
  }

  private MavenResponse getProxy(
      RepositoryRuntime runtime,
      ConanRequestTarget target,
      String rawPath,
      String rawQuery,
      boolean headOnly) {
    if (target.path().fileResource()) {
      return proxyFile(runtime, target.path(), rawPath, headOnly);
    }
    ConanRemoteClient.Discovery discovery;
    try {
      discovery = remote.discovery(runtime, rawPath, rawQuery);
    } catch (MavenExceptions.MavenNotFoundException missing) {
      throw new ConanExceptions.NotFound(target.path().rawPath());
    } catch (MavenExceptions.BadUpstreamException upstream) {
      throw new ConanExceptions.BadUpstream(upstream.getMessage(), upstream);
    }
    projectDiscovery(runtime, target.path(), discovery.bytes());
    return bytes(
        discovery.bytes(), headOnly,
        discovery.contentType() == null ? ConanMediaTypes.JSON : discovery.contentType(),
        discovery.lastModified());
  }

  private MavenResponse getGroup(
      RepositoryRuntime runtime,
      ConanRequestTarget target,
      String rawPath,
      String rawQuery,
      boolean headOnly,
      AuthenticatedSubject subject) {
    ConanPath.Kind kind = target.path().kind();
    if (kind == ConanPath.Kind.RECIPE_FILES || kind == ConanPath.Kind.RECIPE_FILE
        || kind == ConanPath.Kind.PACKAGE_FILES || kind == ConanPath.Kind.PACKAGE_FILE) {
      return groupBound(runtime, target, rawPath, rawQuery, headOnly, subject);
    }
    if (kind == ConanPath.Kind.RECIPE_LATEST || kind == ConanPath.Kind.PACKAGE_LATEST) {
      return firstMember(runtime, rawPath, rawQuery, headOnly, subject);
    }
    if (kind == ConanPath.Kind.RECIPE_SEARCH || kind == ConanPath.Kind.PACKAGE_SEARCH
        || kind == ConanPath.Kind.RECIPE_REVISIONS
        || kind == ConanPath.Kind.PACKAGE_REVISIONS) {
      return mergeMembers(runtime, target, rawPath, rawQuery, headOnly, subject);
    }
    throw new ConanExceptions.NotFound("Unsupported Conan group resource: " + kind);
  }

  private MavenResponse recipeRevisions(
      long repositoryId, ConanReference reference, boolean headOnly) {
    ConanRegistryDao.Recipe recipe = recipe(repositoryId, reference);
    List<Map<String, Object>> revisions = registry.listRecipeRevisions(
            recipe.id(), null, PAGE).stream()
        .sorted(Comparator.comparing(ConanRegistryDao.RecipeRevision::publishedAt).reversed()
            .thenComparing(ConanRegistryDao.RecipeRevision::id, Comparator.reverseOrder()))
        .map(value -> revision(value.revision(), value.publishedAt()))
        .toList();
    return json(Map.of(
        "reference", reference.recipe(),
        "revisions", revisions), headOnly, recipe.updatedAt());
  }

  private MavenResponse recipeLatest(
      long repositoryId, ConanReference reference, boolean headOnly) {
    ConanRegistryDao.Recipe recipe = recipe(repositoryId, reference);
    ConanRegistryDao.RecipeRevision revision = registry.findLatestRecipeRevision(recipe.id())
        .orElseThrow(() -> new ConanExceptions.NotFound(reference.recipe()));
    return json(revision(revision.revision(), revision.publishedAt()), headOnly,
        revision.publishedAt());
  }

  private MavenResponse packageRevisions(
      long repositoryId, ConanReference reference, boolean headOnly) {
    ResolvedOwner owner = resolvePackage(repositoryId, reference, false);
    List<Map<String, Object>> revisions = registry.listPackageRevisions(
            owner.conanPackage().id(), null, PAGE).stream()
        .sorted(Comparator.comparing(ConanRegistryDao.PackageRevision::publishedAt).reversed()
            .thenComparing(ConanRegistryDao.PackageRevision::id, Comparator.reverseOrder()))
        .map(value -> revision(value.revision(), value.publishedAt()))
        .toList();
    return json(Map.of(
        "packageReference", reference.packageReference(),
        "revisions", revisions), headOnly, owner.recipeRevision().publishedAt());
  }

  private MavenResponse packageLatest(
      long repositoryId, ConanReference reference, boolean headOnly) {
    ResolvedOwner owner = resolvePackage(repositoryId, reference, false);
    ConanRegistryDao.PackageRevision revision = registry.findLatestPackageRevision(
            owner.conanPackage().id())
        .orElseThrow(() -> new ConanExceptions.NotFound(reference.packageReference()));
    return json(revision(revision.revision(), revision.publishedAt()), headOnly,
        revision.publishedAt());
  }

  private MavenResponse fileList(
      long repositoryId, ConanReference reference, boolean headOnly) {
    ResolvedOwner owner = resolveOwner(repositoryId, reference)
        .orElseThrow(() -> new ConanExceptions.NotFound(ownerCoordinate(reference)));
    LinkedHashMap<String, Object> files = new LinkedHashMap<>();
    registry.listFiles(owner.ownerKind(), owner.ownerId(), null, PAGE).stream()
        .sorted(Comparator.comparing(ConanRegistryDao.RevisionFile::path))
        .forEach(file -> files.put(file.path(), Map.of()));
    if (files.isEmpty()) throw new ConanExceptions.NotFound(ownerCoordinate(reference));
    return json(Map.of("files", files), headOnly, owner.publishedAt());
  }

  private MavenResponse file(
      RepositoryRuntime runtime, ConanReference reference, String path, boolean headOnly) {
    ResolvedOwner owner = resolveOwner(runtime.id(), reference)
        .orElseThrow(() -> new ConanExceptions.NotFound(ownerCoordinate(reference)));
    ConanRegistryDao.RevisionFile file = registry.findFile(
            owner.ownerKind(), owner.ownerId(), path)
        .filter(value -> value.assetId() != null)
        .orElseThrow(() -> new ConanExceptions.NotFound(path));
    return assets.serve(runtime, reference, file.path(), headOnly);
  }

  private MavenResponse packageSearch(
      long repositoryId, ConanReference reference, boolean listOnly, boolean headOnly) {
    ConanRegistryDao.Recipe recipe = recipe(repositoryId, reference);
    ConanRegistryDao.RecipeRevision revision = reference.recipeRevision() == null
        ? registry.findLatestRecipeRevision(recipe.id()).orElseThrow(
            () -> new ConanExceptions.NotFound(reference.recipe()))
        : registry.findRecipeRevision(recipe.id(), reference.recipeRevision()).orElseThrow(
            () -> new ConanExceptions.NotFound(reference.recipeWithRevision()));
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    registry.listPackages(revision.id(), null, PAGE).stream()
        .sorted(Comparator.comparing(ConanRegistryDao.Package::packageId))
        .forEach(pkg -> result.put(pkg.packageId(), packageSearchEntry(pkg, listOnly)));
    return json(result, headOnly, revision.publishedAt());
  }

  private MavenResponse putMetadata(
      RepositoryRuntime runtime,
      ConanReference reference,
      String path,
      InputStream body,
      String contentType,
      String checksumSha1,
      String actor,
      String ip) {
    ConanAssetSupport.Staged staged = assets.stageProxy(
        runtime, path, new MaxBytesInputStream(body, MAX_UPLOAD_BYTES), mediaType(contentType),
        "hosted-metadata");
    try {
      verifySha1(checksumSha1, staged.blob().sha1());
      try (ConanLeaseManager.Lease lease = leases.acquire(runtime.id(), ownerCoordinate(reference))) {
        transactionally(() -> {
          lease.assertHeld();
          ResolvedOwner owner = resolveOwner(runtime.id(), reference)
              .orElseThrow(() -> new ConanExceptions.NotFound(ownerCoordinate(reference)));
          AssetRecord asset = assets.promote(
              runtime, reference, path, staged, mediaType(contentType), actor, ip,
              components.component(runtime, reference, Instant.now()));
          registry.upsertMetadataFile(owner.ownerKind(), owner.ownerId(), fileCommit(
              path, asset.id(), staged.blob(), mediaType(contentType), runtime.id()), runtime.id());
          return null;
        });
      }
      return MavenResponse.noBody(200);
    } finally {
      assets.discard(runtime, staged);
    }
  }

  private boolean tryCommitSession(
      RepositoryRuntime runtime,
      ConanReference reference,
      long sessionId,
      String actor,
      String ip,
      boolean failIfIncomplete,
      Instant publishedAt) {
    List<ConanRegistryDao.UploadFile> snapshot = registry.listUploadFiles(sessionId);
    ConanRegistryDao.UploadFile manifestFile = snapshot.stream()
        .filter(file -> ConanManifest.FILE_NAME.equals(file.path()))
        .findFirst().orElse(null);
    if (manifestFile == null) return false;
    ValidatedUpload validated = validateUpload(runtime, reference, snapshot, manifestFile,
        failIfIncomplete);
    if (validated == null) return false;
    try (ConanLeaseManager.Lease lease = leases.acquire(runtime.id(), ownerCoordinate(reference))) {
      List<Long> stagingAssetIds = new ArrayList<>();
      transactionally(() -> {
        lease.assertHeld();
        List<ConanRegistryDao.UploadFile> current = registry.listUploadFiles(sessionId);
        ConanRegistryDao.UploadFile currentManifest = current.stream()
            .filter(file -> ConanManifest.FILE_NAME.equals(file.path()))
            .findFirst().orElseThrow(() -> new ConanExceptions.Conflict(
                "Conan upload session changed before commit"));
        ValidatedUpload finalValidation = validateUpload(
            runtime, reference, current, currentManifest, true);
        if (!registry.beginSessionCommit(sessionId, lease.fencingToken(), lease.expiresAt())) {
          throw new ConanExceptions.Busy("Conan upload session is already being committed");
        }
        ComponentRecord component = components.component(runtime, reference, Instant.now());
        List<ConanRegistryDao.FileCommit> committedFiles = new ArrayList<>(current.size());
        Long componentId = null;
        for (ConanRegistryDao.UploadFile upload : current) {
          AssetRecord stagingAsset = assets.find(runtime,
                  com.github.klboke.kkrepo.protocol.conan.ConanPaths.stagingPath(
                      sessionId, upload.path()))
              .orElseThrow(() -> new ConanExceptions.Conflict(
                  "Conan staged file disappeared: " + upload.path()));
          AssetBlobRecord blob = assets.blob(stagingAsset.id());
          if (!Objects.equals(blob.sha256(), upload.sha256()) || blob.size() != upload.size()) {
            throw new ConanExceptions.Conflict("Conan staged file changed: " + upload.path());
          }
          ConanAssetSupport.Staged staged = new ConanAssetSupport.Staged(
              stagingAsset.path(), stagingAsset, blob);
          AssetRecord finalAsset = assets.promote(
              runtime, reference, upload.path(), staged, upload.contentType(), actor, ip, component);
          if (componentId == null) componentId = finalAsset.componentId();
          committedFiles.add(fileCommit(
              upload.path(), finalAsset.id(), blob, upload.contentType(), runtime.id()));
          stagingAssetIds.add(stagingAsset.id());
        }
        ConanInfo info = finalValidation.info();
        try {
          ConanRegistryDao.RevisionCommit commit = new ConanRegistryDao.RevisionCommit(
              coordinate(runtime.id(), reference), componentId, ownerKind(reference),
              reference.recipeRevision(), reference.packageId(), reference.packageRevision(),
              info == null ? Map.of() : info.settings(),
              info == null ? Map.of() : info.options(),
              info == null ? Map.of() : info.requires(),
              currentManifest.sha256(),
              runtime.isProxy() ? ConanRegistryDao.SOURCE_PROXY : ConanRegistryDao.SOURCE_HOSTED,
              runtime.isProxy()
                  ? ConanRegistryDao.STATUS_DISCOVERED : ConanRegistryDao.STATUS_COMMITTED,
              publishedAt == null ? Instant.now() : publishedAt,
              committedFiles);
          if (runtime.isProxy()) {
            registry.restoreRevision(commit);
          } else {
            registry.commitRevision(commit);
          }
        } catch (IllegalStateException conflict) {
          throw new ConanExceptions.Conflict(conflict.getMessage(), conflict);
        }
        registry.deleteUploadSession(sessionId);
        lease.assertHeld();
        return null;
      });
      stagingAssetIds.forEach(assetId -> assets.deleteByAssetId(runtime, assetId));
      return true;
    }
  }

  private ValidatedUpload validateUpload(
      RepositoryRuntime runtime,
      ConanReference reference,
      List<ConanRegistryDao.UploadFile> files,
      ConanRegistryDao.UploadFile manifestFile,
      boolean failIfIncomplete) {
    byte[] manifestBytes = assets.readStaged(
        runtime,
        com.github.klboke.kkrepo.protocol.conan.ConanPaths.stagingPath(
            manifestFile.sessionId(), manifestFile.path()),
        ConanManifest.MAX_BYTES);
    ConanManifest manifest;
    try {
      manifest = ConanManifest.parse(manifestBytes);
    } catch (IllegalArgumentException invalid) {
      throw new ConanExceptions.BadRequest(invalid.getMessage(), invalid);
    }
    Map<String, ConanRegistryDao.UploadFile> byPath = new LinkedHashMap<>();
    files.forEach(file -> byPath.put(file.path(), file));
    Map<String, String> actualManifest = manifestEntries(runtime, files);
    if (!actualManifest.equals(manifest.md5ByPath())) {
      if (!failIfIncomplete && !actualManifest.keySet().containsAll(manifest.md5ByPath().keySet())) {
        return null;
      }
      Set<String> missing = new LinkedHashSet<>(manifest.md5ByPath().keySet());
      missing.removeAll(actualManifest.keySet());
      Set<String> unexpected = new LinkedHashSet<>(actualManifest.keySet());
      unexpected.removeAll(manifest.md5ByPath().keySet());
      List<String> mismatched = manifest.md5ByPath().keySet().stream()
          .filter(actualManifest::containsKey)
          .filter(path -> !manifest.md5ByPath().get(path)
              .equalsIgnoreCase(actualManifest.get(path)))
          .toList();
      throw new ConanExceptions.BadRequest(
          "Conan manifest does not match uploaded content"
              + (missing.isEmpty() ? "" : "; missing=" + String.join(",", missing))
              + (unexpected.isEmpty() ? "" : "; unexpected=" + String.join(",", unexpected))
              + (mismatched.isEmpty() ? "" : "; checksum=" + String.join(",", mismatched)));
    }
    String required = reference.packageId() == null ? "conanfile.py" : ConanInfo.FILE_NAME;
    boolean hasRequiredArchive = reference.packageId() == null
        || files.stream().anyMatch(file -> archives.packageArchive(file.path()));
    boolean hasRequiredFile = reference.packageId() == null
        ? actualManifest.containsKey(required)
        : byPath.containsKey(required);
    if (!hasRequiredArchive || !hasRequiredFile) {
      if (!failIfIncomplete) return null;
      String requirement = reference.packageId() == null
          ? required : required + " and a conan_package.(tgz|txz|tzst) archive";
      throw new ConanExceptions.BadRequest("Conan revision requires " + requirement);
    }
    ConanInfo info = null;
    if (reference.packageId() != null) {
      ConanRegistryDao.UploadFile infoFile = byPath.get(ConanInfo.FILE_NAME);
      byte[] infoBytes = assets.readStaged(
          runtime,
          com.github.klboke.kkrepo.protocol.conan.ConanPaths.stagingPath(
              infoFile.sessionId(), infoFile.path()),
          ConanInfo.MAX_BYTES);
      try {
        info = ConanInfo.parse(infoBytes);
      } catch (IllegalArgumentException invalid) {
        throw new ConanExceptions.BadRequest(invalid.getMessage(), invalid);
      }
    }
    return new ValidatedUpload(manifest, info);
  }

  private Map<String, String> manifestEntries(
      RepositoryRuntime runtime, List<ConanRegistryDao.UploadFile> files) {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    for (ConanRegistryDao.UploadFile file : files) {
      if (ConanManifest.FILE_NAME.equals(file.path()) || metadataPath(file.path())) continue;
      if (!archives.archive(file.path())) {
        putManifestEntry(result, file.path(), file.md5());
        continue;
      }
      String prefix = file.path().startsWith("conan_sources.") ? "export_source/" : "";
      MavenResponse response = assets.openStaged(
          runtime,
          com.github.klboke.kkrepo.protocol.conan.ConanPaths.stagingPath(
              file.sessionId(), file.path()));
      try (InputStream body = response.body()) {
        Map<String, String> archived = archives.manifestEntries(
            body,
            file.size(),
            file.path(),
            prefix,
            ConanManifest.MAX_ENTRIES - result.size());
        archived.forEach((path, checksum) -> putManifestEntry(result, path, checksum));
      } catch (IOException failure) {
        throw new ConanExceptions.BadRequest(
            "Unable to validate Conan manifest archive " + file.path(), failure);
      }
    }
    return Map.copyOf(result);
  }

  private static void putManifestEntry(
      Map<String, String> entries, String path, String checksum) {
    if (entries.size() >= ConanManifest.MAX_ENTRIES
        || entries.putIfAbsent(path, checksum.toLowerCase(Locale.ROOT)) != null) {
      throw new ConanExceptions.BadRequest("Duplicate or excessive Conan manifest entry: " + path);
    }
  }

  private MavenResponse proxyFile(
      RepositoryRuntime runtime, ConanPath path, String rawPath, boolean headOnly) {
    Optional<ResolvedOwner> owner = resolveOwner(runtime.id(), path.reference());
    if (owner.isPresent()) {
      Optional<ConanRegistryDao.RevisionFile> cached = registry.findFile(
          owner.get().ownerKind(), owner.get().ownerId(), path.filePath());
      if (cached.filter(file -> file.assetId() != null).isPresent()) {
        return assets.serve(runtime, path.reference(), path.filePath(), headOnly);
      }
    }
    String leaseKey = DatabaseCompositeKey.of(
        ownerCoordinate(path.reference()), path.filePath());
    try (ConanLeaseManager.Lease lease = leases.acquire(runtime.id(), leaseKey)) {
      owner = resolveOwner(runtime.id(), path.reference());
      if (owner.isPresent() && registry.findFile(
          owner.get().ownerKind(), owner.get().ownerId(), path.filePath())
          .filter(file -> file.assetId() != null).isPresent()) {
        return assets.serve(runtime, path.reference(), path.filePath(), headOnly);
      }
      ConanAssetSupport.Staged staged;
      try (HttpRemoteFetcher.Result result = remote.fetchFile(runtime, rawPath)) {
        if (result.status() == 404 || result.status() == 410) {
          throw new ConanExceptions.NotFound(path.filePath());
        }
        if (result.status() < 200 || result.status() >= 300) {
          throw new ConanExceptions.BadUpstream(
              "Conan upstream returned " + result.status());
        }
        staged = assets.stageProxy(
            runtime, path.filePath(), result.body(), mediaType(result.contentType()),
            runtime.proxyRemoteUrl());
        String upstreamSha1 = result.header("X-Checksum-Sha1");
        if (upstreamSha1 != null && !upstreamSha1.isBlank()) {
          verifySha1(upstreamSha1, staged.blob().sha1());
        }
      } catch (IOException e) {
        throw new ConanExceptions.BadUpstream("Unable to fetch Conan upstream file", e);
      }
      try {
        inspectArchive(runtime, staged, path.filePath());
        ConanInfo info = null;
        if (ConanInfo.FILE_NAME.equals(path.filePath())) {
          info = ConanInfo.parse(assets.readStaged(runtime, staged.path(), ConanInfo.MAX_BYTES));
        }
        ConanInfo projectedInfo = info;
        transactionally(() -> {
          lease.assertHeld();
          ComponentRecord component = components.component(runtime, path.reference(), Instant.now());
          AssetRecord finalAsset = assets.promote(
              runtime, path.reference(), path.filePath(), staged,
              mediaType(staged.blob().contentType()), "conan-proxy", runtime.proxyRemoteUrl(),
              component);
          ConanRegistryDao.CommittedRevision discovered = registry.recordDiscoveredRevision(
              new ConanRegistryDao.RevisionCommit(
                  coordinate(runtime.id(), path.reference()), finalAsset.componentId(),
                  ownerKind(path.reference()), path.reference().recipeRevision(),
                  path.reference().packageId(), path.reference().packageRevision(),
                  projectedInfo == null ? Map.of() : projectedInfo.settings(),
                  projectedInfo == null ? Map.of() : projectedInfo.options(),
                  projectedInfo == null ? Map.of() : projectedInfo.requires(),
                  null, ConanRegistryDao.SOURCE_PROXY, ConanRegistryDao.STATUS_DISCOVERED,
                  Instant.EPOCH, List.of()));
          long expectedRevision = registry.currentRepositoryRevision(runtime.id());
          registry.bindDiscoveredFile(
              ownerKind(path.reference()), discovered.ownerId(),
              fileCommit(path.filePath(), finalAsset.id(), staged.blob(),
                  finalAsset.contentType(), runtime.id()),
              expectedRevision);
          return null;
        });
      } finally {
        assets.discard(runtime, staged);
      }
      return assets.serve(runtime, path.reference(), path.filePath(), headOnly);
    }
  }

  private void projectDiscovery(
      RepositoryRuntime runtime, ConanPath path, byte[] bytes) {
    try {
      JsonNode root = mapper.readTree(bytes);
      switch (path.kind()) {
        case RECIPE_LATEST -> recordDiscovered(
            runtime, path.reference().recipeRevision(requiredText(root, "revision")), null,
            parseTime(root.path("time").asText(null)));
        case RECIPE_FILES -> recordDiscovered(runtime, path.reference(), null, Instant.EPOCH);
        case PACKAGE_LATEST -> recordDiscovered(
            runtime,
            path.reference().packageCoordinate(
                path.reference().packageId(), requiredText(root, "revision")),
            null,
            parseTime(root.path("time").asText(null)));
        case PACKAGE_FILES -> recordDiscovered(runtime, path.reference(), null, Instant.EPOCH);
        default -> {
          // Search and revision lists remain bounded upstream snapshots. Exact latest/file-list
          // observations establish durable identities without guessing which list entry is latest.
        }
      }
    } catch (ConanExceptions.ConanException failure) {
      throw failure;
    } catch (Exception invalid) {
      throw new ConanExceptions.BadUpstream("Broken Conan upstream JSON", invalid);
    }
  }

  private void recordDiscovered(
      RepositoryRuntime runtime, ConanReference reference, ConanInfo info, Instant publishedAt) {
    registry.recordDiscoveredRevision(new ConanRegistryDao.RevisionCommit(
        coordinate(runtime.id(), reference), null, ownerKind(reference),
        reference.recipeRevision(), reference.packageId(), reference.packageRevision(),
        info == null ? Map.of() : info.settings(), info == null ? Map.of() : info.options(),
        info == null ? Map.of() : info.requires(), null, ConanRegistryDao.SOURCE_PROXY,
        ConanRegistryDao.STATUS_DISCOVERED,
        publishedAt == null ? Instant.now() : publishedAt, List.of()));
  }

  private MavenResponse groupBound(
      RepositoryRuntime group,
      ConanRequestTarget target,
      String rawPath,
      String rawQuery,
      boolean headOnly,
      AuthenticatedSubject subject) {
    ConanReference reference = target.path().reference();
    String kind = ownerKind(reference);
    String coordinate = ownerCoordinate(reference);
    long groupRevision = registry.currentRepositoryRevision(group.id());
    Optional<ConanRegistryDao.GroupBinding> existing = registry.findGroupBinding(
        group.id(), kind, coordinate);
    if (existing.isPresent()) {
      ConanRegistryDao.GroupBinding binding = existing.orElseThrow();
      Optional<RepositoryRuntime> member = findMember(group, binding.memberRepositoryId());
      if (member.isPresent()
          && binding.groupConfigRevision() == groupRevision
          && registry.currentRepositoryRevision(binding.memberRepositoryId())
              == binding.memberRevision()) {
        return get(member.orElseThrow(), rawPath, rawQuery, headOnly, subject);
      }
    }
    ConanExceptions.NotFound missing = null;
    for (RepositoryRuntime member : group.members()) {
      if (!eligibleMember(member)) continue;
      try {
        MavenResponse response = get(member, rawPath, rawQuery, headOnly, subject);
        ResolvedOwner owner = resolveOwner(member.id(), reference)
            .orElseThrow(() -> new ConanExceptions.NotFound(coordinate));
        long memberRevision = registry.currentRepositoryRevision(member.id());
        boolean bound = registry.upsertGroupBindingIfCurrent(new ConanRegistryDao.GroupBinding(
            group.id(), kind, coordinate, member.id(), owner.ownerId(), memberRevision,
            groupRevision, null, Instant.now(), Instant.now()));
        if (!bound) {
          response.closeBodyIfOpen();
          throw new ConanExceptions.Busy("Conan group membership changed during resolution");
        }
        return response;
      } catch (ConanExceptions.NotFound | MavenExceptions.MavenNotFoundException notFound) {
        missing = new ConanExceptions.NotFound(coordinate);
      }
    }
    throw missing == null ? new ConanExceptions.NotFound(coordinate) : missing;
  }

  private MavenResponse firstMember(
      RepositoryRuntime group,
      String rawPath,
      String rawQuery,
      boolean headOnly,
      AuthenticatedSubject subject) {
    for (RepositoryRuntime member : group.members()) {
      if (!eligibleMember(member)) continue;
      try {
        return get(member, rawPath, rawQuery, headOnly, subject);
      } catch (ConanExceptions.NotFound | MavenExceptions.MavenNotFoundException ignored) {
      }
    }
    throw new ConanExceptions.NotFound(rawPath);
  }

  private MavenResponse mergeMembers(
      RepositoryRuntime group,
      ConanRequestTarget target,
      String rawPath,
      String rawQuery,
      boolean headOnly,
      AuthenticatedSubject subject) {
    LinkedHashMap<String, JsonNode> values = new LinkedHashMap<>();
    String arrayField = switch (target.path().kind()) {
      case RECIPE_SEARCH -> "results";
      case RECIPE_REVISIONS, PACKAGE_REVISIONS -> "revisions";
      default -> null;
    };
    for (RepositoryRuntime member : group.members()) {
      if (!eligibleMember(member)) continue;
      try {
        MavenResponse response = get(member, rawPath, rawQuery, false, subject);
        JsonNode root = readJsonResponse(response);
        if (arrayField == null) {
          root.fields().forEachRemaining(entry -> values.putIfAbsent(entry.getKey(), entry.getValue()));
        } else {
          for (JsonNode item : root.path(arrayField)) {
            String key = item.isTextual() ? item.asText() : item.path("revision").asText();
            values.putIfAbsent(key, item);
          }
        }
      } catch (ConanExceptions.NotFound | MavenExceptions.MavenNotFoundException ignored) {
      }
    }
    Object result;
    if (arrayField == null) {
      LinkedHashMap<String, Object> map = new LinkedHashMap<>();
      values.forEach((key, value) -> map.put(key, mapper.convertValue(value, Object.class)));
      result = map;
    } else {
      result = Map.of(arrayField, values.values().stream()
          .map(value -> mapper.convertValue(value, Object.class)).toList());
    }
    return json(result, headOnly, null);
  }

  private JsonNode readJsonResponse(MavenResponse response) {
    try (InputStream body = response.body()) {
      if (body == null || response.contentLength() > MAX_DISCOVERY_BYTES) {
        throw new ConanExceptions.BadUpstream("Conan member response is too large");
      }
      byte[] bytes = body.readNBytes(MAX_DISCOVERY_BYTES + 1);
      if (bytes.length > MAX_DISCOVERY_BYTES) {
        throw new ConanExceptions.BadUpstream("Conan member response is too large");
      }
      return mapper.readTree(bytes);
    } catch (IOException invalid) {
      throw new ConanExceptions.BadUpstream("Unable to merge Conan member response", invalid);
    }
  }

  private Optional<ResolvedOwner> resolveOwner(long repositoryId, ConanReference reference) {
    if (reference == null || reference.recipeRevision() == null) return Optional.empty();
    Optional<ConanRegistryDao.Recipe> recipe = registry.findRecipe(coordinate(repositoryId, reference));
    if (recipe.isEmpty()) return Optional.empty();
    Optional<ConanRegistryDao.RecipeRevision> revision = registry.findRecipeRevision(
        recipe.orElseThrow().id(), reference.recipeRevision());
    if (revision.isEmpty()) return Optional.empty();
    if (reference.packageId() == null) {
      ConanRegistryDao.RecipeRevision value = revision.orElseThrow();
      return Optional.of(new ResolvedOwner(
          ConanRegistryDao.OWNER_RECIPE, value.id(), recipe.orElseThrow(), value,
          null, null, value.publishedAt()));
    }
    if (reference.packageRevision() == null) return Optional.empty();
    Optional<ConanRegistryDao.Package> pkg = registry.findPackage(
        revision.orElseThrow().id(), reference.packageId());
    if (pkg.isEmpty()) return Optional.empty();
    Optional<ConanRegistryDao.PackageRevision> prev = registry.findPackageRevision(
        pkg.orElseThrow().id(), reference.packageRevision());
    return prev.map(value -> new ResolvedOwner(
        ConanRegistryDao.OWNER_PACKAGE, value.id(), recipe.orElseThrow(), revision.orElseThrow(),
        pkg.orElseThrow(), value, value.publishedAt()));
  }

  private ResolvedOwner resolvePackage(
      long repositoryId, ConanReference reference, boolean requirePrev) {
    ConanRegistryDao.Recipe recipe = recipe(repositoryId, reference);
    ConanRegistryDao.RecipeRevision revision = registry.findRecipeRevision(
            recipe.id(), reference.recipeRevision())
        .orElseThrow(() -> new ConanExceptions.NotFound(reference.recipeWithRevision()));
    ConanRegistryDao.Package pkg = registry.findPackage(revision.id(), reference.packageId())
        .orElseThrow(() -> new ConanExceptions.NotFound(reference.packageReference()));
    ConanRegistryDao.PackageRevision prev = null;
    if (requirePrev) {
      prev = registry.findPackageRevision(pkg.id(), reference.packageRevision())
          .orElseThrow(() -> new ConanExceptions.NotFound(reference.packageReference()));
    }
    return new ResolvedOwner(
        ConanRegistryDao.OWNER_PACKAGE, prev == null ? pkg.id() : prev.id(), recipe, revision,
        pkg, prev, prev == null ? revision.publishedAt() : prev.publishedAt());
  }

  private ConanRegistryDao.Recipe recipe(long repositoryId, ConanReference reference) {
    return registry.findRecipe(coordinate(repositoryId, reference))
        .orElseThrow(() -> new ConanExceptions.NotFound(reference.recipe()));
  }

  private void ensurePackageParent(long repositoryId, ConanReference reference) {
    if (reference.packageId() == null) return;
    ConanRegistryDao.Recipe recipe = registry.findRecipe(coordinate(repositoryId, reference))
        .orElseThrow(() -> new ConanExceptions.NotFound(reference.recipeWithRevision()));
    registry.findRecipeRevision(recipe.id(), reference.recipeRevision())
        .orElseThrow(() -> new ConanExceptions.NotFound(reference.recipeWithRevision()));
  }

  private void inspectArchive(
      RepositoryRuntime runtime, ConanAssetSupport.Staged staged, String path) {
    if (!archives.archive(path)) return;
    MavenResponse response = assets.openStaged(runtime, staged.path());
    try (InputStream body = response.body()) {
      archives.inspect(body, staged.blob().size(), path);
    } catch (IOException failure) {
      throw new ConanExceptions.BadRequest("Unable to inspect Conan archive", failure);
    }
  }

  private MavenResponse json(Object value, boolean headOnly, Instant modified) {
    try {
      return bytes(mapper.writeValueAsBytes(value), headOnly, ConanMediaTypes.JSON, modified);
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to encode Conan response", impossible);
    }
  }

  private MavenResponse plain(String value, boolean headOnly, String contentType) {
    return bytes(value.getBytes(StandardCharsets.UTF_8), headOnly, contentType, null);
  }

  private static MavenResponse bytes(
      byte[] bytes, boolean headOnly, String contentType, Instant modified) {
    String etag = sha1(bytes);
    return headOnly
        ? MavenResponse.noBody(200, bytes.length, contentType, etag, modified)
        : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, contentType, etag, modified);
  }

  private static Map<String, Object> revision(String value, Instant time) {
    return Map.of("revision", value, "time", OffsetDateTime.ofInstant(
        time == null ? Instant.EPOCH : time, ZoneOffset.UTC).toString());
  }

  static Map<String, Object> packageSearchEntry(
      ConanRegistryDao.Package pkg, boolean listOnly) {
    return Map.of("content", listOnly ? "" : formatConanInfo(pkg));
  }

  private static String formatConanInfo(ConanRegistryDao.Package pkg) {
    if (pkg.settings().isEmpty() && pkg.options().isEmpty() && pkg.requires().isEmpty()) {
      return "";
    }
    StringBuilder value = new StringBuilder();
    appendSection(value, "settings", pkg.settings());
    appendSection(value, "options", pkg.options());
    appendSection(value, "requires", pkg.requires());
    return value.toString();
  }

  private static void appendSection(
      StringBuilder output, String name, Map<String, String> values) {
    output.append('[').append(name).append("]\n");
    values.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(entry -> output.append(entry.getKey()).append('=').append(entry.getValue())
            .append('\n'));
    output.append('\n');
  }

  private static ConanRegistryDao.FileCommit fileCommit(
      String path,
      Long assetId,
      AssetBlobRecord blob,
      String contentType,
      Long sourceRepositoryId) {
    return new ConanRegistryDao.FileCommit(
        path, assetId, blob.md5(), blob.sha1(), blob.sha256(), blob.size(), contentType,
        sourceRepositoryId);
  }

  private static ConanRegistryDao.RecipeCoordinate coordinate(
      long repositoryId, ConanReference reference) {
    return new ConanRegistryDao.RecipeCoordinate(
        repositoryId, reference.name(), reference.version(), reference.user(), reference.channel());
  }

  private static String ownerKind(ConanReference reference) {
    return reference.packageId() == null
        ? ConanRegistryDao.OWNER_RECIPE : ConanRegistryDao.OWNER_PACKAGE;
  }

  private static String ownerCoordinate(ConanReference reference) {
    String value = reference.packageReference();
    return value == null ? reference.recipeWithRevision() : value;
  }

  private static String reference(ConanRegistryDao.Recipe recipe) {
    String value = recipe.name() + "/" + recipe.version();
    if (recipe.user() != null) value += "@" + recipe.user();
    if (recipe.channel() != null) value += "/" + recipe.channel();
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    if (value == null || value.isBlank()) {
      throw new ConanExceptions.BadUpstream("Conan upstream omitted " + field);
    }
    return value;
  }

  private static Instant parseTime(String value) {
    if (value == null || value.isBlank()) return Instant.now();
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (RuntimeException ignored) {
      return Instant.now();
    }
  }

  private static String mediaType(String value) {
    return value == null || value.isBlank() ? ConanMediaTypes.BINARY : value;
  }

  private static boolean metadataPath(String path) {
    return path != null && (path.equals("metadata") || path.startsWith("metadata/"));
  }

  private static void verifySha1(String expected, String actual) {
    if (actual == null || !expected.equalsIgnoreCase(actual)) {
      throw new ConanExceptions.BadRequest("Conan X-Checksum-Sha1 mismatch");
    }
  }

  private static String sha1(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-1 is unavailable", impossible);
    }
  }

  private static AuthenticatedSubject requireSubject(AuthenticatedSubject subject) {
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw new ConanExceptions.Unauthorized("Logged user needed!");
    }
    return subject;
  }

  private static boolean eligibleMember(RepositoryRuntime member) {
    return member != null && member.online() && member.format() == RepositoryFormat.CONAN;
  }

  private static Optional<RepositoryRuntime> findMember(
      RepositoryRuntime group, long repositoryId) {
    for (RepositoryRuntime member : group.members()) {
      if (member != null && member.id() == repositoryId) return Optional.of(member);
      if (member != null && member.isGroup()) {
        Optional<RepositoryRuntime> nested = findMember(member, repositoryId);
        if (nested.isPresent()) return nested;
      }
    }
    return Optional.empty();
  }

  private static void requireRuntime(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.CONAN || !runtime.online()) {
      throw new ConanExceptions.NotFound("Conan repository is unavailable");
    }
  }

  private <T> T transactionally(Supplier<T> callback) {
    return transactions.execute(ignored -> callback.get());
  }

  private record ValidatedUpload(ConanManifest manifest, ConanInfo info) {}

  private record ResolvedOwner(
      String ownerKind,
      long ownerId,
      ConanRegistryDao.Recipe recipe,
      ConanRegistryDao.RecipeRevision recipeRevision,
      ConanRegistryDao.Package conanPackage,
      ConanRegistryDao.PackageRevision packageRevision,
      Instant publishedAt) {}

  private static final class MaxBytesInputStream extends FilterInputStream {
    private final long max;
    private long read;

    private MaxBytesInputStream(InputStream input, long max) {
      super(input);
      this.max = max;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) account(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int value = super.read(bytes, offset, length);
      if (value > 0) account(value);
      return value;
    }

    private void account(long value) {
      read += value;
      if (read > max) {
        throw new ConanExceptions.ContentTooLarge("Conan file exceeds the upload limit");
      }
    }
  }
}
