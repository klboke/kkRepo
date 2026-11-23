package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.protocol.npm.NpmPathParser;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Staged npm publication.
 *
 * <p>Promotion replays the normal npm publish writer, which writes the packument and all tarball
 * attachments and preserves npm revision semantics.
 */
@Component
final class NpmStagingContentAdapter implements StagingContentAdapter {
  private final NpmPathParser parser = new NpmPathParser();
  private final RawHostedService hosted;
  private final NpmHostedService npmHosted;
  private final StagingDao staging;
  private final AssetDao assets;
  private final BlobStorageRegistry blobStorageRegistry;
  private final ObjectMapper objectMapper;

  NpmStagingContentAdapter(RawHostedService hosted, NpmHostedService npmHosted) {
    this(hosted, npmHosted, null, null, null, new ObjectMapper());
  }

  @org.springframework.beans.factory.annotation.Autowired
  NpmStagingContentAdapter(
      RawHostedService hosted,
      NpmHostedService npmHosted,
      StagingDao staging,
      AssetDao assets,
      BlobStorageRegistry blobStorageRegistry,
      ObjectMapper objectMapper) {
    this.hosted = hosted;
    this.npmHosted = npmHosted;
    this.staging = staging;
    this.assets = assets;
    this.blobStorageRegistry = blobStorageRegistry;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.NPM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      NpmPath path = parser.parse(rawPath);
      if (path.kind() == NpmPath.Kind.PACKAGE_ROOT) {
        return Optional.of(path.packageId().id());
      }
      return Optional.empty();
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    NpmPath path = parser.parse(requestedPath);
    if (path.kind() != NpmPath.Kind.PACKAGE_ROOT) {
      throw new NpmExceptions.MethodNotAllowed(
          "Staged npm writes require a package publish path");
    }
    String logicalPath = path.packageId().id();
    AssetBlobRecord blob = hosted.storeStagedBlob(
        runtime, logicalPath, body, contentType, Map.of(), actor, ip);
    return new StagedUpload(blob, logicalPath, contentType, Map.of());
  }

  @Override
  public boolean generatedMetadataPreferred(RepositoryRuntime runtime, String rawPath) {
    try {
      return parser.parse(rawPath).kind() == NpmPath.Kind.PACKAGE_ROOT;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime,
      long sessionId,
      String rawPath,
      boolean headOnly,
      String repositoryBaseUrl) {
    if (staging == null || assets == null || blobStorageRegistry == null
        || repositoryBaseUrl == null || repositoryBaseUrl.isBlank()) {
      return Optional.empty();
    }
    NpmPath path;
    try {
      path = parser.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.kind() != NpmPath.Kind.PACKAGE_ROOT) return Optional.empty();
    Optional<StagingItemRecord> staged = staging.findItem(
        sessionId, runtime.id(), com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes
            .pathHash(path.packageId().id()));
    if (staged.isEmpty() || staged.get().action().equals(StagingDao.ITEM_DELETE)
        || staged.get().assetBlobId() == null) {
      return Optional.empty();
    }
    try {
      Map<String, Object> incoming = readJson(staged.get());
      if (incoming.isEmpty()) return Optional.empty();
      Map<String, Object> document = incoming;
      try {
        MavenResponse live = npmHosted.get(runtime, path, repositoryBaseUrl, false);
        if (live.body() != null) {
          Map<String, Object> existing = objectMapper.readValue(
              live.body().readAllBytes(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
          document = NpmMetadata.overlay(existing, incoming);
        }
      } catch (NpmExceptions.NpmNotFoundException ignored) {
        // staging-only package
      }
      NpmMetadata.rewriteTarballUrls(document, path.packageId(), repositoryBaseUrl);
      byte[] body = objectMapper.writeValueAsBytes(document);
      String etag = sha1(body);
      if (headOnly) {
        return Optional.of(MavenResponse.noBody(
            200, body.length, "application/json", etag, Instant.now()));
      }
      return Optional.of(MavenResponse.ok(
          new ByteArrayInputStream(body), body.length, "application/json", etag, Instant.now()));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private Map<String, Object> readJson(StagingItemRecord item) {
    Optional<AssetBlobRecord> blob = assets.findBlobById(item.assetBlobId());
    if (blob.isEmpty()) return Map.of();
    try (InputStream body = blobStorageRegistry.forBlobStoreId(blob.get().blobStoreId())
        .get(BlobReferenceCodec.reference(
            blob.get().blobRef(), blob.get().objectKey(), blob.get().sha256(), blob.get().size()))
        .orElseThrow()) {
      return objectMapper.readValue(
          body.readAllBytes(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String sha1(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    NpmPath parsed = parser.parse(path);
    return switch (parsed.kind()) {
      case PACKAGE_ROOT -> npmHosted.deletePackage(
          runtime, parsed.packageId(), parsed.revision()).status() != 404;
      case TARBALL -> npmHosted.deleteTarball(
          runtime, parsed.packageId(), parsed.tarballName(), parsed.revision()).status() != 404;
      case DIST_TAG -> false;
      default -> false;
    };
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    NpmPath path = parser.parse(item.logicalPath());
    if (path.kind() != NpmPath.Kind.PACKAGE_ROOT) {
      throw new NpmExceptions.MethodNotAllowed("Unsupported staged npm path");
    }
    try (InputStream body = source.open()) {
      npmHosted.putPackage(
          runtime, path.packageId(), path.revision(), body, actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
