package com.github.klboke.kkrepo.server.goartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.goartifact.GoVersions;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GoHostedService {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final GoAssetWriter writer;
  private final GoModuleArchiveInspector archiveInspector;
  private final AssetMetadataCache assetMetadataCache;
  private final ObjectMapper objectMapper;
  private final ArtifactDownloadPolicy downloadPolicy;

  public GoHostedService(
      AssetDao assetDao,
      ComponentDao componentDao,
      BlobStorageRegistry blobStorageRegistry,
      GoAssetWriter writer,
      GoModuleArchiveInspector archiveInspector,
      AssetMetadataCache assetMetadataCache,
      ObjectMapper objectMapper) {
    this(
        assetDao,
        componentDao,
        blobStorageRegistry,
        writer,
        archiveInspector,
        assetMetadataCache,
        objectMapper,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public GoHostedService(
      AssetDao assetDao,
      ComponentDao componentDao,
      BlobStorageRegistry blobStorageRegistry,
      GoAssetWriter writer,
      GoModuleArchiveInspector archiveInspector,
      AssetMetadataCache assetMetadataCache,
      ObjectMapper objectMapper,
      ArtifactDownloadPolicy downloadPolicy) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.archiveInspector = archiveInspector;
    this.assetMetadataCache = assetMetadataCache;
    this.objectMapper = objectMapper;
    this.downloadPolicy = downloadPolicy;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    ensureHosted(runtime);
    GoPath path = parse(rawPath);
    return switch (path.kind()) {
      case LIST -> list(runtime, path, headOnly);
      case LATEST -> latest(runtime, path, headOnly);
      case PACKAGE, INFO, MODULE -> serveAsset(runtime, path, headOnly);
    };
  }

  public Published publish(
      RepositoryRuntime runtime,
      String rawUploadPath,
      InputStream body,
      String createdBy,
      String createdByIp) {
    ensureHosted(runtime);
    String version = uploadVersion(rawUploadPath);
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied("Write policy DENY forbids Go module upload");
    }

    Path archive = null;
    try {
      archive = copyArchive(body);
      GoModuleArchiveInspector.Inspected inspected = archiveInspector.inspect(archive, version);
      Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      GoPath modulePath = GoPath.versioned(inspected.module(), version, GoAssetKind.MODULE);
      GoPath infoPath = GoPath.versioned(inspected.module(), version, GoAssetKind.INFO);
      GoPath archivePath = GoPath.versioned(inspected.module(), version, GoAssetKind.PACKAGE);
      byte[] info = GoResponses.infoBytes(objectMapper, version, publishedAt);
      GoAssetWriter.ReleaseStored stored = writer.writeHostedRelease(
          runtime,
          blobStorage(runtime),
          requireBlobStore(runtime),
          modulePath,
          inspected.goMod(),
          infoPath,
          info,
          archivePath,
          archive,
          createdBy,
          createdByIp,
          policy == WritePolicy.ALLOW);
      return new Published(
          inspected.module(), version, stored.archive().asset().path(), publishedAt);
    } catch (IllegalArgumentException error) {
      throw new MavenExceptions.LayoutPolicyViolation(error.getMessage());
    } finally {
      TempBlobFiles.deleteQuietly(archive);
    }
  }

  MavenResponse list(RepositoryRuntime runtime, GoPath path, boolean headOnly) {
    String body = String.join("\n", listVersions(runtime, path));
    // Nexus derives hosted lists from component rows and exposes no validators for them.
    return GoResponses.text(body, null, headOnly);
  }

  List<String> listVersions(RepositoryRuntime runtime, GoPath path) {
    ensureHosted(runtime);
    List<String> candidates = componentDao
        .listVersionsByName(runtime.id(), RepositoryFormat.GO, path.module()).stream()
        .filter(GoVersions::isCanonical)
        .toList();
    if (candidates.isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    // A module containing only pseudo-versions is still a successful, empty Go version list.
    return GoVersions.listVersions(candidates);
  }

  MavenResponse latest(RepositoryRuntime runtime, GoPath path, boolean headOnly) {
    List<ComponentRecord> components = components(runtime, path.module());
    GoVersions.Candidate latest = GoVersions.latest(components.stream()
            .filter(component -> component.version() != null)
            .map(component -> new GoVersions.Candidate(
                component.version(), component.lastUpdatedAt()))
            .toList())
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path()));
    // @latest reuses the selected .info bytes but is advertised as text/plain and exposes the
    // stored object's validators, unlike a direct .info response.
    return serveAsset(
        runtime,
        GoPath.versioned(path.module(), latest.version(), GoAssetKind.INFO),
        headOnly,
        true).withContentType("text/plain");
  }

  MavenResponse serveAsset(RepositoryRuntime runtime, GoPath path, boolean headOnly) {
    return serveAsset(runtime, path, headOnly, false);
  }

  private MavenResponse serveAsset(
      RepositoryRuntime runtime,
      GoPath path,
      boolean headOnly,
      boolean exposeInfoValidators) {
    CachedAssetMetadata snapshot = assetMetadataCache.find(
            runtime.id(),
            path.path(),
            () -> AssetMetadataCache.Loaded.from(
                assetDao.findAssetByPath(runtime.id(), path.path()), assetDao))
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path()));
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) throw new MavenExceptions.MavenNotFoundException(path.path());
    if (downloadPolicy != null) {
      downloadPolicy.beforeReadFromRepository(snapshot.assetId(), blob.id(), snapshot.repositoryId());
    }
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    boolean exposeValidators = path.kind() != GoAssetKind.INFO || exposeInfoValidators;
    String etag = exposeValidators && blob.sha256() != null ? blob.sha256() : null;
    Instant lastModified = exposeValidators ? snapshot.lastUpdatedAt() : null;
    if (headOnly) {
      return MavenResponse.noBody(
          200, blob.size(), path.contentType(), etag, lastModified);
    }
    return MavenResponse.ok(
        () -> storage.get(BlobReferenceCodec.reference(
                blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path())),
        blob.size(), path.contentType(), etag, lastModified);
  }

  private List<ComponentRecord> components(RepositoryRuntime runtime, String module) {
    return componentDao.listByName(runtime.id(), module).stream()
        .filter(component -> component.format() == RepositoryFormat.GO)
        // Nexus datastore imports used the generic "package" component kind before native Go
        // hosted support existed. The coordinate is the stable identity, so do not make listing
        // depend on an implementation-specific component kind.
        .filter(component -> component.version() != null && GoVersions.isCanonical(component.version()))
        .toList();
  }

  private Path copyArchive(InputStream body) {
    if (body == null) throw new MavenExceptions.LayoutPolicyViolation("Go module archive is required");
    Path temp = null;
    try {
      temp = Files.createTempFile("kkrepo-go-hosted-", ".zip");
      long total = 0;
      try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = body.read(buffer)) >= 0;) {
          if (read == 0) continue;
          total += read;
          if (total > GoModuleArchiveInspector.MAX_COMPRESSED_BYTES) {
            throw new MavenExceptions.LayoutPolicyViolation(
                "Go module archive exceeds 500 MiB");
          }
          output.write(buffer, 0, read);
        }
      }
      if (total == 0) throw new MavenExceptions.LayoutPolicyViolation("Go module archive is empty");
      return temp;
    } catch (IOException error) {
      TempBlobFiles.deleteQuietly(temp);
      throw new MavenExceptions.BadRequestException("Unable to read Go module archive", error);
    } catch (RuntimeException error) {
      TempBlobFiles.deleteQuietly(temp);
      throw error;
    }
  }

  private static String uploadVersion(String rawUploadPath) {
    String path = rawUploadPath == null ? "" : rawUploadPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isBlank() || path.contains("/") || !path.endsWith(".zip")) {
      throw new MavenExceptions.LayoutPolicyViolation(
          "Go hosted upload path must be <version>.zip");
    }
    try {
      return GoVersions.requireCanonical(path.substring(0, path.length() - ".zip".length()));
    } catch (IllegalArgumentException error) {
      throw new MavenExceptions.LayoutPolicyViolation(error.getMessage());
    }
  }

  private GoPath parse(String rawPath) {
    try {
      return GoPath.parse(rawPath);
    } catch (IllegalArgumentException error) {
      throw new MavenExceptions.MavenNotFoundException(error.getMessage());
    }
  }

  private void ensureHosted(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.GO || !runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Operation is only valid on hosted Go repositories");
    }
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long blobStoreId = runtime.blobStoreId();
    if (blobStoreId == null) {
      throw new IllegalStateException(
          "Go repository " + runtime.name() + " has no blob store assigned");
    }
    return blobStoreId;
  }

  public record Published(
      String module,
      String version,
      String archivePath,
      Instant publishedAt) {
  }
}
