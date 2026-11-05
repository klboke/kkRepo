package com.github.klboke.kkrepo.server.arch;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.arch.ArchDesc;
import com.github.klboke.kkrepo.protocol.arch.ArchMediaTypes;
import com.github.klboke.kkrepo.protocol.arch.ArchPackageFilename;
import com.github.klboke.kkrepo.protocol.arch.ArchPath;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.blob.BufferedUpload;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** pacman hosted, proxy and group repository behavior. */
@Service
public class ArchService {
  private final ComponentDao components;
  private final ArchAssetSupport assets;
  private final ArchPackageInspector inspector;
  private final ArchDatabase database;
  private final RawProxyService proxy;
  private final ArchSigningService signing;
  // Signatures are deterministic for a database set and key revision. This hot cache is
  // rebuildable and never decides correctness.
  private final com.github.klboke.kkrepo.cache.LocalCache<String, byte[]> signatureCache =
      com.github.klboke.kkrepo.cache.LocalCacheFactory.standard()
          .<String, byte[]>builder("arch-database-signatures")
          .maximumSize(256)
          .expireAfterAccess(java.time.Duration.ofMinutes(10))
          .build();

  public ArchService(
      ComponentDao components,
      ArchAssetSupport assets,
      ArchPackageInspector inspector,
      ArchDatabase database,
      RawProxyService proxy,
      ArchSigningService signing) {
    this.components = components;
    this.assets = assets;
    this.inspector = inspector;
    this.database = database;
    this.proxy = proxy;
    this.signing = signing;
  }

  public ArchSigningService.KeyStatus rotateKey(
      RepositoryRuntime runtime, String privateKey, String passphrase) {
    requireSignable(runtime);
    return status(signing.rotate(runtime, privateKey, passphrase));
  }

  public ArchSigningService.KeyStatus rotateGeneratedKey(RepositoryRuntime runtime) {
    requireSignable(runtime);
    return status(signing.rotateGenerated(runtime));
  }

  private static ArchSigningService.KeyStatus status(
      com.github.klboke.kkrepo.persistence.jdbc.api.ArchSigningKeyDao.SigningKey row) {
    return new ArchSigningService.KeyStatus(
        row.revision(), row.keyId(), row.fingerprint(), row.createdAt());
  }

  public ArchSigningService.KeyStatus keyStatus(RepositoryRuntime runtime) {
    requireArch(runtime);
    return signing.status(runtime);
  }

  public MavenResponse publicKey(RepositoryRuntime runtime, boolean headOnly) {
    requireArch(runtime);
    byte[] bytes = signing.publicKeyArmor(runtime)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String etag = sha1(bytes);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, ArchMediaTypes.PGP_KEYS, etag, null);
    }
    return MavenResponse.ok(new java.io.ByteArrayInputStream(bytes), bytes.length,
        ArchMediaTypes.PGP_KEYS, etag, null);
  }

  public Published publishSignature(
      RepositoryRuntime runtime,
      String fileName,
      InputStream body,
      String actor,
      String ip) {
    requireHosted(runtime);
    String base = fileName.substring(0, fileName.length() - 4);
    if (!ArchPackageFilename.looksLikePackage(base)) {
      throw new MavenExceptions.BadRequestException(
          "Only package signatures (*.pkg.tar.*.sig) can be uploaded");
    }
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied(
          "Repository write policy forbids signature publication");
    }
    if (policy == WritePolicy.ALLOW_ONCE && assets.find(runtime, fileName).isPresent()) {
      throw new MavenExceptions.WritePolicyDenied(
          "Repository write policy forbids overwriting " + fileName);
    }
    try (InputStream input = body) {
      byte[] bytes = input.readNBytes(1024 * 1024);
      if (bytes.length >= 1024 * 1024) {
        throw new MavenExceptions.BadRequestException("Package signature is too large");
      }
      assets.storeSignature(runtime, fileName, bytes, actor, ip);
      return new Published(fileName);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read package signature", error);
    }
  }

  public Published publish(
      RepositoryRuntime runtime,
      String fileName,
      InputStream body,
      String actor,
      String ip) {
    requireHosted(runtime);
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied(
          "Repository write policy forbids package publication");
    }
    boolean replacing = assets.find(runtime, fileName).isPresent();
    if (policy == WritePolicy.ALLOW_ONCE && replacing) {
      throw new MavenExceptions.WritePolicyDenied(
          "Repository write policy forbids overwriting " + fileName);
    }
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "arch-", ".pkg.tar");
      BufferedUpload.Digests digests =
          BufferedUpload.copy(body, temp, BufferedUpload.Checksums.WITH_SHA512);
      ArchPackageInspector.Inspected inspected =
          inspector.inspect(temp, fileName, digests.size(), digests.md5(), digests.sha256());
      assets.store(runtime, inspected, temp, fileName, digests.md5(), digests.sha1(),
          digests.sha256(), digests.size(), actor, ip);
      // A package replacement invalidates any signature uploaded for the previous bytes.
      if (replacing) {
        assets.delete(runtime, fileName + ".sig");
      }
      return new Published(fileName);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read pacman package", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  public MavenResponse get(RepositoryRuntime runtime, ArchPath path, boolean headOnly) {
    if (!runtime.online()) {
      throw new MavenExceptions.BadUpstreamException("Repository is offline");
    }
    return switch (runtime.type()) {
      case HOSTED -> hostedGet(runtime, path, headOnly);
      case PROXY -> proxyGet(runtime, path, headOnly);
      case GROUP -> groupGet(runtime, path, headOnly);
    };
  }

  public MavenResponse delete(RepositoryRuntime runtime, ArchPath path, boolean enforcePolicy) {
    requireHosted(runtime);
    if (path.kind() != ArchPath.Kind.PACKAGE && path.kind() != ArchPath.Kind.SIGNATURE) {
      throw new MavenExceptions.MethodNotAllowed(
          "Only pacman package or signature files can be deleted");
    }
    if (enforcePolicy && !WritePolicy.parse(runtime.writePolicy()).checkDeleteAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy forbids deletion");
    }
    if (assets.find(runtime, path.fileName()).isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    }
    assets.delete(runtime, path.fileName());
    if (path.kind() == ArchPath.Kind.PACKAGE) {
      assets.delete(runtime, path.fileName() + ".sig");
    }
    return MavenResponse.noBody(204);
  }

  private MavenResponse hostedGet(
      RepositoryRuntime runtime, ArchPath path, boolean headOnly) {
    return switch (path.kind()) {
      case ROOT -> info("Arch Linux repository", headOnly);
      case DATABASE, DATABASE_TAR, FILES, FILES_TAR -> generated(
          runtime, path, headOnly);
      case PACKAGE -> assets.serve(runtime, path.fileName(), headOnly);
      case SIGNATURE -> path.databaseSignature()
          ? generatedSignature(runtime, path, headOnly)
          : assets.serve(runtime, path.fileName(), headOnly);
      case PUBLIC_KEY -> publicKey(runtime, headOnly);
      default -> throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    };
  }

  private MavenResponse proxyGet(
      RepositoryRuntime runtime, ArchPath path, boolean headOnly) {
    if (path.kind() == ArchPath.Kind.ROOT) {
      return info("Arch Linux proxy repository", headOnly);
    }
    if (path.kind() == ArchPath.Kind.UNKNOWN) {
      throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    }
    return proxy.getAsset(runtime, path.fileName(), headOnly);
  }

  private MavenResponse groupGet(
      RepositoryRuntime runtime, ArchPath path, boolean headOnly) {
    if (path.kind() == ArchPath.Kind.ROOT) {
      return info("Arch Linux group repository", headOnly);
    }
    if (path.kind() == ArchPath.Kind.PUBLIC_KEY) {
      return publicKey(runtime, headOnly);
    }
    if (path.generated()) {
      boolean files = path.kind() == ArchPath.Kind.FILES
          || path.kind() == ArchPath.Kind.FILES_TAR;
      byte[] bytes = mergedDatabase(runtime, path.fileName(), files, new HashSet<>());
      return databaseResponse(bytes, headOnly);
    }
    if (path.databaseSignature()) {
      return mergedSignature(runtime, path, headOnly);
    }
    if (path.kind() == ArchPath.Kind.UNKNOWN) {
      throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    }
    return routePackage(runtime, path, headOnly, new HashSet<>());
  }

  /** Builds and signs a database from live components plus staged package projections. */
  MavenResponse stagedDatabase(
      RepositoryRuntime runtime,
      ArchPath path,
      List<com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord> staged,
      java.util.Set<String> deletedFilenames,
      boolean headOnly) {
    List<com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord> merged =
        new ArrayList<>(components.listByRepositoryId(runtime.id()));
    if (deletedFilenames != null && !deletedFilenames.isEmpty()) {
      merged.removeIf(component -> deletedFilenames.contains(
          String.valueOf(component.attributes().get("archFileName"))));
    }
    merged.addAll(staged);
    boolean files = path.kind() == ArchPath.Kind.FILES || path.kind() == ArchPath.Kind.FILES_TAR;
    byte[] bytes = database.build(merged, files);
    return path.databaseSignature()
        ? signatureResponse(runtime, bytes, headOnly)
        : databaseResponse(bytes, headOnly);
  }

  private MavenResponse generated(
      RepositoryRuntime runtime, ArchPath path, boolean headOnly) {
    boolean files = path.kind() == ArchPath.Kind.FILES || path.kind() == ArchPath.Kind.FILES_TAR;
    byte[] bytes = database.build(components.listByRepositoryId(runtime.id()), files);
    return databaseResponse(bytes, headOnly);
  }

  private MavenResponse generatedSignature(
      RepositoryRuntime runtime, ArchPath path, boolean headOnly) {
    boolean files = path.fileName().contains(".files");
    byte[] bytes = database.build(components.listByRepositoryId(runtime.id()), files);
    return signatureResponse(runtime, bytes, headOnly);
  }

  private MavenResponse mergedSignature(
      RepositoryRuntime runtime, ArchPath path, boolean headOnly) {
    boolean files = path.fileName().contains(".files");
    String dbName = path.fileName().substring(
        0, path.fileName().length() - ".sig".length());
    byte[] bytes = mergedDatabase(runtime, dbName, files, new HashSet<>());
    return signatureResponse(runtime, bytes, headOnly);
  }

  private MavenResponse signatureResponse(
      RepositoryRuntime runtime, byte[] databaseBytes, boolean headOnly) {
    var material = signing.active(runtime)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
            "No Arch Linux signing key is configured"));
    String cacheKey = runtime.id() + ":" + material.revision() + ":" + sha1(databaseBytes);
    byte[] signature = signatureCache.get(cacheKey,
        ignored -> signing.sign(databaseBytes, material, java.time.Instant.EPOCH));
    String etag = sha1(signature);
    if (headOnly) {
      return MavenResponse.noBody(200, signature.length, ArchMediaTypes.SIGNATURE, etag, null);
    }
    return MavenResponse.ok(new java.io.ByteArrayInputStream(signature), signature.length,
        ArchMediaTypes.SIGNATURE, etag, null);
  }

  private byte[] mergedDatabase(
      RepositoryRuntime group, String requestedName, boolean files, Set<Long> resolving) {
    if (!resolving.add(group.id())) {
      throw new MavenExceptions.BadUpstreamException("Arch Linux group cycle detected");
    }
    String memberDatabase = files ? dbAlias(requestedName) : requestedName;
    Map<String, ArchDesc> merged = new LinkedHashMap<>();
    try {
      for (RepositoryRuntime member : group.members()) {
        if (!member.online() || member.format() != RepositoryFormat.ARCH) continue;
        List<ArchDesc> descs;
        try {
          descs = memberDescriptions(member, memberDatabase, files, resolving);
        } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException e) {
          continue;
        }
        for (ArchDesc desc : descs) {
          if (desc.name() == null || desc.version() == null) continue;
          merged.putIfAbsent(desc.name() + "|" + (desc.architecture() == null ? ""
              : desc.architecture()), desc);
        }
      }
    } finally {
      resolving.remove(group.id());
    }
    return database.buildDescs(new ArrayList<>(merged.values()), files);
  }

  private List<ArchDesc> memberDescriptions(
      RepositoryRuntime member, String requestedName, boolean files, Set<Long> resolving) {
    return switch (member.type()) {
      case HOSTED -> components.listByRepositoryId(member.id()).stream()
          .filter(component -> component.format() == RepositoryFormat.ARCH)
          .map(ArchDatabase::desc)
          .filter(java.util.Objects::nonNull)
          .toList();
      case PROXY -> {
        MavenResponse response = proxy.getAsset(member, requestedName, false);
        try (InputStream body = response.body()) {
          if (body == null) throw new MavenExceptions.MavenNotFoundException(requestedName);
          byte[] bytes = body.readNBytes(64 * 1024 * 1024 + 1);
          if (bytes.length > 64 * 1024 * 1024) {
            throw new MavenExceptions.BadUpstreamException("Member database is too large");
          }
          yield database.parse(bytes, requestedName);
        } catch (IOException error) {
          throw new MavenExceptions.BadUpstreamException("Unable to read member database", error);
        }
      }
      case GROUP -> {
        byte[] bytes = mergedDatabase(member, requestedName, files, resolving);
        yield database.parse(bytes, requestedName);
      }
    };
  }

  private MavenResponse routePackage(
      RepositoryRuntime group, ArchPath path, boolean headOnly, Set<Long> resolving) {
    if (!resolving.add(group.id())) {
      throw new MavenExceptions.BadUpstreamException("Arch Linux group cycle detected");
    }
    MavenExceptions.BadUpstreamException last = null;
    try {
      for (RepositoryRuntime member : group.members()) {
        if (!member.online() || member.format() != RepositoryFormat.ARCH) continue;
        try {
          if (member.type() == RepositoryType.GROUP) {
            return routePackage(member, path, headOnly, resolving);
          }
          if (member.type() == RepositoryType.HOSTED) {
            return assets.serve(member, path.fileName(), headOnly);
          }
          return proxy.getAsset(member, path.fileName(), headOnly);
        } catch (MavenExceptions.MavenNotFoundException ignored) {
          // Next member.
        } catch (MavenExceptions.BadUpstreamException error) {
          last = error;
        } finally {
          resolving.remove(member.id());
        }
      }
      if (last != null) throw last;
      throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    } finally {
      resolving.remove(group.id());
    }
  }

  private static String dbAlias(String requestedName) {
    if (requestedName == null) return "core.db";
    if (requestedName.endsWith(".files")) {
      return requestedName.substring(0, requestedName.length() - ".files".length()) + ".db";
    }
    if (requestedName.endsWith(".files.tar.gz")) {
      return requestedName.substring(0, requestedName.length() - ".files.tar.gz".length())
          + ".db";
    }
    if (requestedName.endsWith(".files.tar.xz")) {
      return requestedName.substring(0, requestedName.length() - ".files.tar.xz".length())
          + ".db";
    }
    if (requestedName.endsWith(".files.tar.zst")) {
      return requestedName.substring(0, requestedName.length() - ".files.tar.zst".length())
          + ".db";
    }
    return requestedName;
  }

  private static MavenResponse databaseResponse(byte[] bytes, boolean headOnly) {
    String etag = sha1(bytes);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, ArchMediaTypes.DATABASE_GZIP, etag, null);
    }
    return MavenResponse.ok(new java.io.ByteArrayInputStream(bytes), bytes.length,
        ArchMediaTypes.DATABASE_GZIP, etag, null);
  }

  private static MavenResponse info(String message, boolean headOnly) {
    byte[] bytes = (message + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (headOnly) return MavenResponse.noBody(200, bytes.length, "text/plain", null, null);
    return MavenResponse.ok(new java.io.ByteArrayInputStream(bytes), bytes.length, "text/plain",
        null, null);
  }

  private static String sha1(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void requireArch(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.ARCH) {
      throw new MavenExceptions.MethodNotAllowed(
          "Operation is only valid on Arch Linux repositories");
    }
  }

  private static void requireSignable(RepositoryRuntime runtime) {
    requireArch(runtime);
    if (runtime.type() == RepositoryType.PROXY) {
      throw new MavenExceptions.MethodNotAllowed(
          "Proxy repositories pass through upstream signatures; import a key on a hosted or group repository");
    }
  }

  private static void requireHosted(RepositoryRuntime runtime) {
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Pacman publication is only available on hosted repositories");
    }
    if (!runtime.online()) {
      throw new MavenExceptions.BadUpstreamException("Repository is offline");
    }
  }

  public record Published(String path) {
  }
}
