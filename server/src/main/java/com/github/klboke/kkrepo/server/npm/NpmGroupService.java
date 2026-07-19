package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class NpmGroupService {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final NpmHostedService hosted;
  private final NpmProxyService proxy;
  private final ObjectMapper mapper;
  private final NpmGroupPackumentCache packumentCache;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final BlobStorageRegistry blobStorageRegistry;
  private final NpmAssetWriter writer;

  public NpmGroupService(
      NpmHostedService hosted,
      NpmProxyService proxy,
      ObjectMapper mapper,
      NpmGroupPackumentCache packumentCache,
      GroupMemberAssetCache groupMemberAssetCache,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.mapper = mapper;
    this.packumentCache = packumentCache;
    this.groupMemberAssetCache = groupMemberAssetCache;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
  }

  public MavenResponse get(RepositoryRuntime group, NpmPath path, String repositoryBaseUrl, boolean headOnly) {
    return get(group, path, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  public MavenResponse get(
      RepositoryRuntime group,
      NpmPath path,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    if (!group.isGroup()) {
      throw new IllegalStateException("NpmGroupService.get called on non-group " + group.name());
    }
    if (group.members().isEmpty()) {
      throw new NpmExceptions.NpmNotFoundException(path.rawPath());
    }
    return switch (path.kind()) {
      case PACKAGE_ROOT, PACKAGE_VERSION -> getMergedPackageRoot(group, path.packageId(), repositoryBaseUrl, headOnly,
          variant);
      case TARBALL -> firstWin(group, path, repositoryBaseUrl, headOnly);
      case DIST_TAGS -> getMergedDistTags(group, path.packageId(), headOnly);
      default -> throw new NpmExceptions.NpmNotFoundException(path.rawPath());
    };
  }

  private MavenResponse getMergedPackageRoot(
      RepositoryRuntime group,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    PackageRootContent content = getOrBuildPackageRoot(group, packageId, repositoryBaseUrl, variant, Instant.now());
    return packageRootResponse(
        packageId, repositoryBaseUrl, content.packageRoot(), content.lastModified(), headOnly,
        variant, content.minimumReleaseAgeValidUntil());
  }

  private PackageRootContent getOrBuildPackageRoot(
      RepositoryRuntime group,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      NpmPackumentVariant variant,
      Instant now) {
    if (packumentCache != null) {
      Optional<CachedAssetMetadata> cached = packumentCache.findFresh(group, packageId, variant, now);
      if (cached.isPresent()) {
        Optional<PackageRootContent> loaded = loadCachedPackageRoot(group, packageId, variant, cached.get());
        if (loaded.isPresent()) {
          return loaded.get();
        }
      }
    }

    MergedPackageRoot merged = mergePackageRoot(
        group,
        packageId,
        repositoryBaseUrl,
        containsMinimumReleaseAgeProxy(group),
        variant);
    Instant lastModified = now;
    if (packumentCache != null && packumentCache.enabled()) {
      NpmAssetWriter.Stored stored = storePackageRoot(
          group, packageId, variant, merged.bytes(), now,
          merged.minimumReleaseAgeValidUntil());
      lastModified = stored.asset().lastUpdatedAt();
    }
    return new PackageRootContent(
        merged.packageRoot(), lastModified, merged.minimumReleaseAgeValidUntil());
  }

  private Optional<PackageRootContent> loadCachedPackageRoot(
      RepositoryRuntime group,
      NpmPackageId packageId,
      NpmPackumentVariant variant,
      CachedAssetMetadata cached) {
    AssetBlobRecord blob = cached.toBlobRecord();
    if (blob == null) {
      return Optional.empty();
    }
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    try (InputStream in = storage.get(BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size())).orElse(null)) {
      if (in == null) {
        return Optional.empty();
      }
      return Optional.of(new PackageRootContent(
          mapper.readValue(in, MAP_TYPE),
          cached.lastUpdatedAt(),
          packumentCache.minimumReleaseAgeValidUntil(cached)));
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed reading cached npm group package root "
              + group.name() + "/" + variant.cachePath(packageId), e);
    }
  }

  private NpmAssetWriter.Stored storePackageRoot(
      RepositoryRuntime group,
      NpmPackageId packageId,
      NpmPackumentVariant variant,
      byte[] bytes,
      Instant now,
      Instant minimumReleaseAgeValidUntil) {
    long blobStoreId = requireBlobStore(group);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    return writer.writePackageRootCache(
        group,
        storage,
        blobStoreId,
        packageId,
        variant,
        bytes,
        "group",
        group.name(),
        Map.of(),
        packumentCache.freshAttributes(group, now, variant, minimumReleaseAgeValidUntil),
        variant == NpmPackumentVariant.FULL);
  }

  private MergedPackageRoot mergePackageRoot(
      RepositoryRuntime group,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      boolean policyAware,
      NpmPackumentVariant variant) {
    List<Map<String, Object>> roots = new ArrayList<>();
    Instant minimumReleaseAgeValidUntil = null;
    for (RepositoryRuntime member : group.members()) {
      try {
        MavenResponse response = dispatch(member,
            new NpmPath(NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId, null, null, null, null),
            repositoryBaseUrl, false, variant);
        try (InputStream body = response.body()) {
          if (body != null) {
            roots.add(mapper.readValue(body, MAP_TYPE));
            if (policyAware) {
              Object rawValidUntil = response.internalAttribute(
                  NpmProxyService.POLICY_VALID_UNTIL_CONTEXT);
              Instant memberValidUntil = rawValidUntil instanceof Instant instant
                  ? instant
                  : null;
              if (memberValidUntil != null
                  && (minimumReleaseAgeValidUntil == null
                      || memberValidUntil.isBefore(minimumReleaseAgeValidUntil))) {
                minimumReleaseAgeValidUntil = memberValidUntil;
              }
            }
          }
        }
      } catch (NpmExceptions.NpmNotFoundException | NpmExceptions.BadUpstreamException
          | NpmExceptions.MethodNotAllowed ignored) {
        // member miss/down; try the next member
      } catch (IOException e) {
        throw new IllegalStateException("Failed reading npm metadata from group member", e);
      }
    }
    if (roots.isEmpty()) {
      throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
    }
    Map<String, Object> merged = roots.size() == 1 ? roots.get(0) : NpmMetadata.merge(roots);
    return new MergedPackageRoot(
        merged, NpmResponseSupport.write(mapper, merged), minimumReleaseAgeValidUntil);
  }

  private MavenResponse packageRootResponse(
      NpmPackageId packageId,
      String repositoryBaseUrl,
      Map<String, Object> packageRoot,
      Instant lastModified,
      boolean headOnly,
      NpmPackumentVariant variant,
      Instant minimumReleaseAgeValidUntil) {
    byte[] bytes = NpmPackumentResponseWriter.write(
        mapper, packageRoot, null, null, variant, packageId, repositoryBaseUrl);
    MavenResponse response = headOnly
        ? MavenResponse.noBody(200, bytes.length, NpmResponseSupport.JSON, null, lastModified)
        : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
            NpmResponseSupport.JSON, null, lastModified);
    return response.withInternalAttribute(
        NpmProxyService.POLICY_VALID_UNTIL_CONTEXT, minimumReleaseAgeValidUntil);
  }

  private MavenResponse getMergedDistTags(RepositoryRuntime group, NpmPackageId packageId, boolean headOnly) {
    PackageRootContent packageRoot = getOrBuildPackageRoot(
        group, packageId, group.name(), NpmPackumentVariant.FULL, Instant.now());
    byte[] bytes = NpmResponseSupport.write(mapper, NpmMetadata.distTags(packageRoot.packageRoot()));
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, NpmResponseSupport.JSON, null, packageRoot.lastModified());
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        NpmResponseSupport.JSON, null, packageRoot.lastModified());
  }

  private MavenResponse firstWin(
      RepositoryRuntime group,
      NpmPath path,
      String repositoryBaseUrl,
      boolean headOnly) {
    NpmExceptions.ReleaseAgeDenied releaseAgeDenied = null;
    NexusCacheType cacheType = NexusCacheType.CONTENT;
    String cachePath = groupMemberCachePath(path);
    Optional<Long> cachedMemberId = groupMemberAssetCache == null
        ? Optional.empty()
        : groupMemberAssetCache.get(group, cachePath, cacheType);
    if (cachedMemberId.isPresent()) {
      RepositoryRuntime cachedMember = group.members().stream()
          .filter(member -> member.id() == cachedMemberId.get())
          .findFirst()
          .orElse(null);
      if (cachedMember != null) {
        try {
          return dispatch(cachedMember, path, repositoryBaseUrl, headOnly);
        } catch (NpmExceptions.ReleaseAgeDenied denied) {
          releaseAgeDenied = denied;
          groupMemberAssetCache.evict(group, cachePath, cacheType);
        } catch (NpmExceptions.NpmNotFoundException | NpmExceptions.BadUpstreamException
            | NpmExceptions.MethodNotAllowed ignored) {
          groupMemberAssetCache.evict(group, cachePath, cacheType);
        }
      }
    }
    for (RepositoryRuntime member : group.members()) {
      try {
        MavenResponse response = dispatch(member, path, repositoryBaseUrl, headOnly);
        if (groupMemberAssetCache != null) {
          groupMemberAssetCache.put(group, cachePath, cacheType, member.id());
        }
        return response;
      } catch (NpmExceptions.ReleaseAgeDenied denied) {
        if (releaseAgeDenied == null) {
          releaseAgeDenied = denied;
        }
      } catch (NpmExceptions.NpmNotFoundException ignored) {
      } catch (NpmExceptions.BadUpstreamException e) {
        // Nexus group repositories only return successful member responses; keep probing.
      } catch (NpmExceptions.MethodNotAllowed ignored) {
      }
    }
    if (releaseAgeDenied != null) {
      throw releaseAgeDenied;
    }
    throw new NpmExceptions.NpmNotFoundException(path.rawPath());
  }

  private static boolean containsMinimumReleaseAgeProxy(RepositoryRuntime runtime) {
    if (runtime == null) {
      return false;
    }
    if (runtime.minimumReleaseAgeEnabled()) {
      return true;
    }
    for (RepositoryRuntime member : runtime.members()) {
      if (containsMinimumReleaseAgeProxy(member)) {
        return true;
      }
    }
    return false;
  }

  private static String groupMemberCachePath(NpmPath path) {
    if (path.kind() == NpmPath.Kind.TARBALL && path.packageId() != null && path.tarballName() != null) {
      return path.packageId().tarballPath(path.tarballName());
    }
    return path.rawPath();
  }

  private MavenResponse dispatch(
      RepositoryRuntime member,
      NpmPath path,
      String repositoryBaseUrl,
      boolean headOnly) {
    return dispatch(member, path, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  private MavenResponse dispatch(
      RepositoryRuntime member,
      NpmPath path,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    return switch (member.type()) {
      case HOSTED -> variant == NpmPackumentVariant.FULL
          ? hosted.get(member, path, repositoryBaseUrl, headOnly)
          : hosted.get(member, path, repositoryBaseUrl, headOnly, variant);
      case PROXY -> variant == NpmPackumentVariant.FULL
          ? proxy.get(member, path, repositoryBaseUrl, headOnly)
          : proxy.get(member, path, repositoryBaseUrl, headOnly, variant);
      case GROUP -> variant == NpmPackumentVariant.FULL
          ? get(member, path, repositoryBaseUrl, headOnly)
          : get(member, path, repositoryBaseUrl, headOnly, variant);
    };
  }

  private static long requireBlobStore(RepositoryRuntime runtime) {
    if (runtime.blobStoreId() == null) {
      throw new IllegalStateException("Repository has no blob store: " + runtime.name());
    }
    return runtime.blobStoreId();
  }

  private record MergedPackageRoot(
      Map<String, Object> packageRoot,
      byte[] bytes,
      Instant minimumReleaseAgeValidUntil) {
  }

  private record PackageRootContent(
      Map<String, Object> packageRoot,
      Instant lastModified,
      Instant minimumReleaseAgeValidUntil) {
  }
}
