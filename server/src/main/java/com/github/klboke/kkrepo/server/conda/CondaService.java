package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaMediaTypes;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.luben.zstd.Zstd;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Hosted, proxy, and group implementation of a Nexus-style Conda channel repository. */
@Service
public class CondaService {
  private static final long MAX_UPSTREAM_METADATA_BYTES = 256L * 1024 * 1024;
  private static final int ZSTD_FRAME_HEADER_BYTES = 18;
  private static final List<String> PROXY_REPODATA_PREFERENCE = List.of(
      "repodata.json.zst", "repodata.json.bz2", "repodata.json");

  private final CondaRegistryDao registry;
  private final CondaArchiveInspector inspector;
  private final CondaMetadataCodec metadata;
  private final CondaComponentFactory components;
  private final CondaAssetSupport assets;
  private final CondaLeaseManager leases;
  private final RawProxyService proxy;
  private final CondaProxyInventoryScheduler proxyInventories;
  private final CondaMetadataBuildLimiter metadataBuilds;
  private final CondaPublishLimiter publications;
  private final TransactionTemplate transactions;
  private final CondaPathParser paths = new CondaPathParser();

  @Autowired
  public CondaService(
      CondaRegistryDao registry,
      CondaArchiveInspector inspector,
      CondaMetadataCodec metadata,
      CondaComponentFactory components,
      CondaAssetSupport assets,
      CondaLeaseManager leases,
      RawProxyService proxy,
      CondaProxyInventoryScheduler proxyInventories,
      CondaMetadataBuildLimiter metadataBuilds,
      CondaPublishLimiter publications,
      PlatformTransactionManager transactionManager) {
    this(
        registry, inspector, metadata, components, assets, leases, proxy, proxyInventories,
        metadataBuilds,
        publications,
        new TransactionTemplate(transactionManager));
  }

  CondaService(
      CondaRegistryDao registry,
      CondaArchiveInspector inspector,
      CondaMetadataCodec metadata,
      CondaComponentFactory components,
      CondaAssetSupport assets,
      CondaLeaseManager leases,
      RawProxyService proxy) {
    this(
        registry, inspector, metadata, components, assets, leases, proxy,
        null,
        new CondaMetadataBuildLimiter(), new CondaPublishLimiter(),
        (TransactionTemplate) null);
  }

  CondaService(
      CondaRegistryDao registry,
      CondaArchiveInspector inspector,
      CondaMetadataCodec metadata,
      CondaComponentFactory components,
      CondaAssetSupport assets,
      CondaLeaseManager leases,
      RawProxyService proxy,
      CondaProxyInventoryScheduler proxyInventories,
      CondaMetadataBuildLimiter metadataBuilds,
      CondaPublishLimiter publications,
      TransactionTemplate transactions) {
    this.registry = registry;
    this.inspector = inspector;
    this.metadata = metadata;
    this.components = components;
    this.assets = assets;
    this.leases = leases;
    this.proxy = proxy;
    this.proxyInventories = proxyInventories;
    this.metadataBuilds = metadataBuilds;
    this.publications = publications;
    this.transactions = transactions;
  }

  public MavenResponse get(
      RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    requireRuntime(runtime);
    CondaPath path = paths.parse(rawPath);
    return switch (path.kind()) {
      case ROOT -> root(runtime, headOnly);
      case REPODATA -> repodata(runtime, path, headOnly);
      // Serving the full snapshot is a valid compatibility fallback until the optional
      // current-repodata reduction pass is available.
      case CURRENT_REPODATA -> repodata(runtime, path, headOnly);
      case CHANNELDATA -> channeldata(runtime, path, headOnly);
      case NOTICES -> notices(runtime, path, headOnly);
      case PACKAGE -> packageResponse(runtime, path, headOnly);
      case SHARDED_REPODATA, UNKNOWN -> throw notFound(path.rawPath());
    };
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    return publications.execute(() -> putWithinCapacity(
        runtime, rawPath, body, contentType, actor, ip));
  }

  private MavenResponse putWithinCapacity(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    requireHosted(runtime);
    CondaPath path = paths.parse(rawPath);
    if (!path.packageFile()) {
      throw new MavenExceptions.MethodNotAllowed("Conda PUT accepts package archive paths only");
    }
    CondaArchiveInspector.InspectedPackage inspected =
        inspector.inspect(body, path.filename(), path.subdir());
    try {
      publishHosted(
          runtime, path, inspected, contentType, actor, ip, Instant.now(), false);
      return MavenResponse.created();
    } finally {
      CondaArchiveInspector.delete(inspected.file());
    }
  }

  CondaRegistryDao.PackageRecord restoreHostedPackageForMigration(
      RepositoryRuntime runtime,
      CondaPath path,
      CondaArchiveInspector.InspectedPackage inspected,
      String contentType,
      String actor,
      String ip,
      Instant publishedAt) {
    requireHosted(runtime);
    if (path == null || !path.packageFile()
        || !path.filename().equals(inspected.filename())) {
      throw new IllegalArgumentException("Invalid Conda migration package path");
    }
    return publishHosted(
        runtime,
        path,
        inspected,
        contentType,
        actor,
        ip,
        publishedAt == null ? Instant.now() : publishedAt,
        true);
  }

  private CondaRegistryDao.PackageRecord publishHosted(
      RepositoryRuntime runtime,
      CondaPath path,
      CondaArchiveInspector.InspectedPackage inspected,
      String contentType,
      String actor,
      String ip,
      Instant indexedAt,
      boolean migration) {
    Optional<AssetRecord> preliminary = assets.find(runtime, path.canonicalPath());
    if (!migration) {
      enforceWritePolicy(runtime, path.canonicalPath(), preliminary.isPresent());
    }
    Instant now = Instant.now();
    LinkedHashMap<String, Object> publicMetadata = new LinkedHashMap<>(inspected.metadata());
    publicMetadata.remove("base_url");
    publicMetadata.remove("download_url");
    publicMetadata.put("subdir", path.subdir());
    publicMetadata.put("md5", inspected.md5());
    publicMetadata.put("sha256", inspected.sha256());
    publicMetadata.put("size", inspected.size());
    ComponentRecord component = components.component(
        runtime, path.channel(), path.subdir(), inspected.name(), inspected.version(),
        inspected.build(), inspected.buildNumber(), path.filename(), now);
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("condaChannel", path.channel());
    attributes.put("condaSubdir", path.subdir());
    attributes.put("condaName", inspected.name());
    attributes.put("condaVersion", inspected.version());
    attributes.put("condaBuild", inspected.build());
    attributes.put("condaBuildNumber", inspected.buildNumber());
    attributes.put("md5", inspected.md5());
    attributes.put("sha256", inspected.sha256());
    String browsePath = components.browsePath(
        path.channel(), path.subdir(), inspected.name(), inspected.version(), path.filename());
    String mediaType = packageMediaType(path.filename(), contentType);
    CondaAssetSupport.StagedAsset staged = assets.stage(
        runtime, path.canonicalPath(), inspected.file(), mediaType, attributes, actor, ip,
        inspected.sha256(), inspected.size());
    boolean[] created = {false};
    try (CondaLeaseManager.Lease lease = leases.acquire(
        leaseKey(runtime, "package", path.canonicalPath()))) {
      try {
        return transactionally(() -> {
          lease.assertHeld();
          Optional<AssetRecord> existingAsset = assets.find(runtime, path.canonicalPath());
          Optional<CondaRegistryDao.PackageRecord> existingRecord = registry.findPackage(
              runtime.id(), path.channel(), path.subdir(), path.filename());
          if (migration && existingRecord.isPresent()) {
            CondaRegistryDao.PackageRecord existing = existingRecord.orElseThrow();
            AssetBlobRecord existingBlob = assets.blob(runtime, path.canonicalPath());
            if (existingAsset.isPresent()
                && inspected.sha256().equalsIgnoreCase(existing.sha256())
                && inspected.sha256().equalsIgnoreCase(existingBlob.sha256())
                && inspected.size() == existing.size()
                && inspected.size() == existingBlob.size()) {
              return existing;
            }
            throw new IllegalStateException(
                "Existing Conda migration package differs from " + path.canonicalPath());
          }
          if (!migration) {
            enforceWritePolicy(runtime, path.canonicalPath(), existingAsset.isPresent());
          }
          AssetRecord asset = assets.promote(
              runtime, path.canonicalPath(), browsePath, staged, mediaType, actor, ip, component);
          created[0] = existingAsset.isEmpty();
          lease.assertHeld();
          CondaRegistryDao.PackageRecord record = registry.saveHostedPackage(
              new CondaRegistryDao.PackageRecord(
                  null,
                  runtime.id(),
                  path.channel(),
                  path.subdir(),
                  path.filename(),
                  inspected.name(),
                  inspected.version(),
                  inspected.build(),
                  inspected.buildNumber(),
                  inspected.archiveFormat(),
                  publicMetadata,
                  metadata.fingerprint(publicMetadata),
                  inspected.md5(),
                  inspected.sha256(),
                  inspected.size(),
                  asset.id(),
                  asset.componentId(),
                  CondaRegistryDao.SOURCE_HOSTED,
                  0,
                  indexedAt,
                  now));
          ensureNoarch(runtime.id(), path.channel(), record.revision(), now);
          return record;
        });
      } catch (RuntimeException e) {
        if (transactions == null && created[0]
            && assets.find(runtime, path.canonicalPath()).isPresent()) {
          assets.delete(runtime, path.canonicalPath());
        }
        throw e;
      }
    } finally {
      assets.discard(runtime, staged);
    }
  }

  public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
    return deletePackage(runtime, rawPath, "client-delete", true);
  }

  /**
   * Administrative and cleanup deletion may be called from a generic browse transaction. The
   * registry and asset mutations intentionally join that transaction so cleanup's locked
   * revalidation and deletion stay atomic. Coordinate lease operations use independent durable
   * transactions and the lease remains held through outer transaction completion.
   */
  public MavenResponse deleteAdministrative(
      RepositoryRuntime runtime, String rawPath, String reason) {
    return deletePackage(runtime, rawPath, reason, false);
  }

  private MavenResponse deletePackage(
      RepositoryRuntime runtime, String rawPath, String reason, boolean enforceWritePolicy) {
    requireHosted(runtime);
    CondaPath path = paths.parse(rawPath);
    if (!path.packageFile()) {
      throw new MavenExceptions.MethodNotAllowed("Conda DELETE accepts package archive paths only");
    }
    if (enforceWritePolicy && !WritePolicy.parse(runtime.writePolicy()).checkDeleteAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy forbids deletion");
    }
    try (CondaLeaseManager.Lease lease = leases.acquire(
        leaseKey(runtime, "package", path.canonicalPath()))) {
      return transactionally(() -> {
        lease.assertHeld();
        Optional<CondaRegistryDao.PackageRecord> removed = registry.tombstoneAndDeletePackage(
            runtime.id(), path.channel(), path.subdir(), path.filename(),
            reason == null || reason.isBlank() ? "administrative-delete" : reason, 0,
            Instant.now());
        if (removed.isEmpty()) return MavenResponse.noBody(404);
        assets.delete(runtime, path.canonicalPath());
        lease.assertHeld();
        return MavenResponse.noBody(204);
      });
    }
  }

  private MavenResponse repodata(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    if (runtime.isProxy()) {
      return proxyRepodata(runtime, path, headOnly);
    }
    RepositoryRuntime transparentProxy = singleProxyMember(runtime);
    if (transparentProxy != null) {
      return proxyRepodata(transparentProxy, path, headOnly);
    }
    if (runtime.isGroup()) {
      List<RepositoryRuntime> members = concreteMembers(runtime, new LinkedHashSet<>());
      if (members.stream().anyMatch(RepositoryRuntime::isProxy)) {
        return mergedGroupRepodata(runtime, path, members, headOnly);
      }
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      List<RepositoryRuntime> members = preparedConcreteMembers(
          runtime, path.channel(), path.subdir(), new LinkedHashSet<>());
      String identity = repodataIdentity(runtime, members, path.channel(), path.subdir());
      String generatedPath = generatedMetadataPath(path, identity);
      Optional<CachedAssetMetadata> cached = assets.findInternal(runtime, generatedPath);
      if (cached.isPresent()) {
        return assets.serveInternal(runtime, generatedPath, headOnly);
      }
      MavenResponse built = metadataBuilds.execute(generatedPath, () -> buildRepodata(
          runtime, path, members, identity, generatedPath, headOnly));
      if (built != null) {
        return built;
      }
    }
    throw new MavenExceptions.BadUpstreamException(
        "Conda repository changed repeatedly while generating " + path.rawPath());
  }

  /**
   * Serves JSON repodata from a compact shared backing object when the upstream publishes one.
   * The decoded representation is streamed and never persisted as a second, much larger S3 blob.
   */
  private MavenResponse proxyRepodata(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    if (path.encoding() == CondaPath.Encoding.JSON
        && (path.kind() == CondaPath.Kind.REPODATA
            || path.kind() == CondaPath.Kind.CURRENT_REPODATA)) {
      return decodedProxyRepodata(runtime, path, headOnly);
    }
    return proxyRepodataVerbatim(runtime, path, headOnly);
  }

  private MavenResponse decodedProxyRepodata(
      RepositoryRuntime runtime, CondaPath requested, boolean headOnly) {
    for (String suffix : List.of(".zst", ".bz2")) {
      CondaPath compact = paths.parse(metadataPath(
          requested.channel(), requested.subdir(), requested.filename() + suffix));
      try {
        // Request a response body for HEAD as well so zstd's frame header can provide the decoded
        // Content-Length without materializing the JSON representation.
        MavenResponse source = proxyRepodataVerbatim(runtime, compact, false);
        return decodedRepodataResponse(source, compact.encoding(), headOnly);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Older channels may publish only bzip2 or the uncompressed JSON representation.
      }
    }
    return proxyRepodataVerbatim(runtime, requested, headOnly);
  }

  private MavenResponse decodedRepodataResponse(
      MavenResponse source, CondaPath.Encoding sourceEncoding, boolean headOnly) {
    InputStream sourceBody = boundedBody(source, MAX_UPSTREAM_METADATA_BYTES);
    try {
      BufferedInputStream replayable = new BufferedInputStream(sourceBody);
      replayable.mark(ZSTD_FRAME_HEADER_BYTES);
      byte[] frameHeader = replayable.readNBytes(ZSTD_FRAME_HEADER_BYTES);
      replayable.reset();
      long decodedLength = decodedRepodataLength(sourceEncoding, frameHeader);
      InputStream decoded = metadata.decodeRepodata(replayable, sourceEncoding);
      PushbackInputStream checked = new PushbackInputStream(
          new LimitedInputStream(decoded, MAX_UPSTREAM_METADATA_BYTES), 1);
      int firstByte = checked.read();
      if (firstByte < 0) {
        checked.close();
        throw new IOException("decoded repodata is empty");
      }
      checked.unread(firstByte);
      String etag = decodedRepodataEtag(source, sourceEncoding);
      if (headOnly) {
        checked.close();
        return MavenResponse.noBody(
            200, decodedLength, CondaMediaTypes.JSON, etag, source.lastModified());
      }
      return MavenResponse.ok(
          checked, decodedLength, CondaMediaTypes.JSON, etag, source.lastModified());
    } catch (IOException error) {
      closeQuietly(sourceBody);
      throw new MavenExceptions.BadUpstreamException(
          "Failed decoding compact Conda repodata", error);
    }
  }

  private static long decodedRepodataLength(
      CondaPath.Encoding sourceEncoding, byte[] frameHeader) {
    if (sourceEncoding != CondaPath.Encoding.ZSTD) return -1;
    long size = Zstd.getFrameContentSize(frameHeader);
    if (size > MAX_UPSTREAM_METADATA_BYTES) {
      throw new MavenExceptions.BadUpstreamException("Conda upstream metadata is too large");
    }
    return size > 0 ? size : -1;
  }

  private static String decodedRepodataEtag(
      MavenResponse source, CondaPath.Encoding sourceEncoding) {
    String identity = source.etag();
    if (identity == null || identity.isBlank()) {
      identity = source.contentLength() + ":" + source.lastModified();
    }
    return sha256(("decoded-json:" + sourceEncoding + ":" + identity)
        .getBytes(StandardCharsets.UTF_8));
  }

  /** Nexus-compatible proxy path: cache and serve the requested upstream representation verbatim. */
  private MavenResponse proxyRepodataVerbatim(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    String localPath = proxyMetadataLocalPath(
        path.channel(), path.subdir(), path.filename());
    if (proxyRawMetadataCurrent(runtime, localPath)) {
      return assets.serveInternal(runtime, localPath, headOnly);
    }
    Optional<CondaLeaseManager.Lease> acquired = leases.acquireUnlessCompleted(
        leaseKey(runtime, "raw-metadata", path.canonicalPath()),
        () -> proxyRawMetadataCurrent(runtime, localPath));
    if (acquired.isEmpty()) {
      return assets.serveInternal(runtime, localPath, headOnly);
    }
    try (CondaLeaseManager.Lease ignored = acquired.orElseThrow()) {
      if (proxyRawMetadataCurrent(runtime, localPath)) {
        return assets.serveInternal(runtime, localPath, headOnly);
      }
      return proxy.getMetadataFromUrlUnindexed(
          runtime,
          localPath,
          remoteUrl(runtime, path.canonicalPath()),
          headOnly);
    }
  }

  /**
   * Merges mixed-group metadata from replayable member snapshots without synchronously importing
   * an entire proxy index into MySQL. This follows Nexus' group cold path: raw member repodata is
   * the merge input, while the searchable/browse projection is rebuilt independently in the
   * background. The generated group asset and its distributed lease remain the shared truth
   * across replicas.
   */
  private MavenResponse mergedGroupRepodata(
      RepositoryRuntime group,
      CondaPath path,
      List<RepositoryRuntime> members,
    boolean headOnly) {
    for (int attempt = 0; attempt < 3; attempt++) {
      List<GroupRepodataMember> prepared = prepareGroupRepodataMembers(
          members, path.channel(), path.subdir());
      String identity = preparedGroupRepodataIdentity(group, path, prepared);
      String generatedPath = generatedMetadataPath(path, identity);
      if (assets.findInternal(group, generatedPath).isPresent()) {
        return assets.serveInternal(group, generatedPath, headOnly);
      }
      MavenResponse built = metadataBuilds.execute(generatedPath, () ->
          buildMergedGroupRepodata(
              group, path, prepared, identity, generatedPath, headOnly));
      if (built != null) {
        return built;
      }
    }
    throw new MavenExceptions.BadUpstreamException(
        "Conda group members changed repeatedly while generating " + path.rawPath());
  }

  private List<GroupRepodataMember> prepareGroupRepodataMembers(
      List<RepositoryRuntime> members, String channel, String subdir) {
    ArrayList<GroupRepodataMember> prepared = new ArrayList<>();
    MavenExceptions.BadUpstreamException upstreamFailure = null;
    boolean hasNonProxyMember = false;
    for (RepositoryRuntime member : members) {
      if (!member.isProxy()) {
        hasNonProxyMember = true;
        Optional<CondaRegistryDao.ChannelState> state = registry.findChannelState(
            member.id(), channel, subdir);
        prepared.add(new GroupRepodataMember(
            member, "revision:" + state.map(CondaRegistryDao.ChannelState::revision).orElse(0L),
            null, 0, CondaPath.Encoding.NONE, state.isPresent()));
        continue;
      }
      try {
        GroupProxyRepodata source = prepareGroupProxyRepodata(member, channel, subdir);
        prepared.add(new GroupRepodataMember(
            member, "blob:" + cachedBlobIdentity(source.cached()), source.localPath(),
            source.rawSize(), source.encoding(), true));
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // A member does not have to expose every channel/subdir present in another member.
      } catch (MavenExceptions.BadUpstreamException failure) {
        upstreamFailure = failure;
      }
    }
    if (prepared.isEmpty() && !hasNonProxyMember && upstreamFailure != null) {
      throw upstreamFailure;
    }
    return List.copyOf(prepared);
  }

  /**
   * Selects a replayable proxy snapshot for a group merge. Existing fresh snapshots are reused in
   * compact-first order; a cold member requests zstd, then bzip2, and only falls back to the large
   * JSON representation when the upstream does not publish either compressed form.
   */
  private GroupProxyRepodata prepareGroupProxyRepodata(
      RepositoryRuntime member, String channel, String subdir) {
    for (String filename : PROXY_REPODATA_PREFERENCE) {
      String localPath = proxyMetadataLocalPath(channel, subdir, filename);
      if (proxyRawMetadataCurrent(member, localPath)) {
        return groupProxyRepodata(member, localPath, filename);
      }
    }
    for (String filename : PROXY_REPODATA_PREFERENCE) {
      String localPath = proxyMetadataLocalPath(channel, subdir, filename);
      CondaPath sourcePath = paths.parse(metadataPath(channel, subdir, filename));
      try {
        MavenResponse refreshed = proxyRepodataVerbatim(member, sourcePath, true);
        closeQuietly(refreshed.body());
        return groupProxyRepodata(member, localPath, filename);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Conda channels may omit newer compressed representations; try the next one.
      }
    }
    throw new MavenExceptions.MavenNotFoundException(
        metadataPath(channel, subdir, "repodata.json"));
  }

  private GroupProxyRepodata groupProxyRepodata(
      RepositoryRuntime member, String localPath, String filename) {
    CachedAssetMetadata cached = assets.findInternal(member, localPath)
        .filter(value -> value.blob() != null)
        .orElseThrow(() -> new MavenExceptions.BadUpstreamException(
            "Conda proxy metadata cache is unavailable"));
    CondaPath.Encoding encoding = paths.parse("noarch/" + filename).encoding();
    long rawSize = encoding == CondaPath.Encoding.JSON ? cached.blob().size() : -1;
    return new GroupProxyRepodata(localPath, rawSize, encoding, cached);
  }

  private MavenResponse buildMergedGroupRepodata(
      RepositoryRuntime group,
      CondaPath path,
      List<GroupRepodataMember> members,
      String identity,
      String generatedPath,
      boolean headOnly) {
    Optional<CondaLeaseManager.Lease> acquired = leases.acquireUnlessCompleted(
        leaseKey(group, "metadata", generatedPath),
        () -> assets.findInternal(group, generatedPath).isPresent());
    if (acquired.isEmpty()) {
      return assets.serveInternal(group, generatedPath, headOnly);
    }
    try (CondaLeaseManager.Lease lease = acquired.orElseThrow()) {
      if (assets.findInternal(group, generatedPath).isPresent()) {
        return assets.serveInternal(group, generatedPath, headOnly);
      }
      if (!currentGroupRepodataIdentity(group, path, members).equals(identity)) {
        return null;
      }
      try (GroupRepodataProjection projection = openGroupRepodataProjection(
               path.channel(), path.subdir(), members)) {
        if (!projection.available() && !"noarch".equals(path.subdir())) {
          throw notFound(path.rawPath());
        }
        try (CondaMetadataCodec.RenderedFile rendered = metadata.renderMergedRepodataFile(
            path.subdir(), projection.sources(), projection.tombstones(), path.encoding())) {
          lease.assertHeld();
          if (!currentGroupRepodataIdentity(group, path, members).equals(identity)) {
            return null;
          }
          assets.storeGenerated(
              group,
              generatedPath,
              rendered.path(),
              rendered.contentType(),
              Map.of(
                  "condaGenerated", true,
                  "condaRevisionIdentity", identity,
                  "condaChannel", path.channel(),
                  "condaSubdir", path.subdir(),
                  "condaEncoding", path.encoding().name()));
          lease.assertHeld();
        }
      }
      return assets.serveInternal(group, generatedPath, headOnly);
    }
  }

  private GroupRepodataProjection openGroupRepodataProjection(
      String channel, String subdir, List<GroupRepodataMember> members) {
    ArrayList<AutoCloseable> resources = new ArrayList<>();
    ArrayList<CondaMetadataCodec.MergeSource> sources = new ArrayList<>();
    LinkedHashMap<String, CondaRegistryDao.Tombstone> tombstones = new LinkedHashMap<>();
    boolean available = false;
    try {
      for (GroupRepodataMember member : members) {
        available |= member.available();
        if (member.runtime().isProxy()) {
          sources.add(CondaMetadataCodec.MergeSource.raw(member.rawSize(), () -> {
            InputStream stored = boundedBody(
                assets.serveInternal(member.runtime(), member.proxyLocalPath(), false),
                MAX_UPSTREAM_METADATA_BYTES);
            return new LimitedInputStream(
                metadata.decodeRepodata(stored, member.sourceEncoding()),
                MAX_UPSTREAM_METADATA_BYTES);
          }));
        } else {
          CondaMetadataCodec.RecordSource database = (archiveFormat, visitor) -> {
            try {
              registry.visitPackages(
                  member.runtime().id(), channel, subdir, archiveFormat, record -> {
                    try {
                      visitor.accept(record);
                    } catch (IOException error) {
                      throw new UncheckedIOException(error);
                    }
                  });
            } catch (UncheckedIOException error) {
              throw error.getCause();
            }
          };
          CondaMetadataCodec.RecordSourceFile snapshot = metadata.snapshotRecordSource(
              subdir, database);
          resources.add(snapshot);
          sources.add(CondaMetadataCodec.MergeSource.records(snapshot.records()));
          for (CondaRegistryDao.Tombstone tombstone :
              registry.listTombstones(member.runtime().id(), channel, subdir)) {
            tombstones.putIfAbsent(tombstone.filename(), tombstone);
          }
        }
      }
      return new GroupRepodataProjection(
          List.copyOf(sources), List.copyOf(tombstones.values()), available, resources);
    } catch (RuntimeException error) {
      closeGroupResources(resources);
      throw error;
    }
  }

  private String preparedGroupRepodataIdentity(
      RepositoryRuntime group, CondaPath path, List<GroupRepodataMember> members) {
    StringBuilder value = groupRepodataIdentityPrefix(group, path);
    for (GroupRepodataMember member : members) {
      value.append(member.runtime().id()).append(':')
          .append(member.runtime().type()).append(':')
          .append(member.identity()).append('\n');
    }
    return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private String currentGroupRepodataIdentity(
      RepositoryRuntime group, CondaPath path, List<GroupRepodataMember> members) {
    StringBuilder value = groupRepodataIdentityPrefix(group, path);
    for (GroupRepodataMember member : members) {
      String identity;
      if (member.runtime().isProxy()) {
        identity = assets.findInternal(member.runtime(), member.proxyLocalPath())
            .filter(cached -> cached.blob() != null)
            .map(CondaService::cachedBlobIdentity)
            .map(digest -> "blob:" + digest)
            .orElse("missing");
      } else {
        identity = "revision:" + registry.findChannelState(
                member.runtime().id(), path.channel(), path.subdir())
            .map(CondaRegistryDao.ChannelState::revision)
            .orElse(0L);
      }
      value.append(member.runtime().id()).append(':')
          .append(member.runtime().type()).append(':')
          .append(identity).append('\n');
    }
    return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static StringBuilder groupRepodataIdentityPrefix(
      RepositoryRuntime group, CondaPath path) {
    return new StringBuilder()
        .append(groupConfigurationRevision(group)).append('\n')
        .append(path.channel()).append('\n')
        .append(path.subdir()).append('\n');
  }

  private static String cachedBlobIdentity(CachedAssetMetadata cached) {
    if (cached == null || cached.blob() == null) {
      throw new MavenExceptions.BadUpstreamException(
          "Conda proxy metadata cache has no blob");
    }
    String identity = cached.blob().sha256();
    if (identity == null || identity.isBlank()) identity = cached.blob().sha1();
    if (identity == null || identity.isBlank()) {
      throw new MavenExceptions.BadUpstreamException(
          "Conda proxy metadata cache has no content identity");
    }
    return identity.toLowerCase(Locale.ROOT);
  }

  private static void closeGroupResources(List<? extends AutoCloseable> resources) {
    for (int index = resources.size() - 1; index >= 0; index--) {
      try {
        resources.get(index).close();
      } catch (Exception ignored) {
      }
    }
  }

  private MavenResponse buildRepodata(
      RepositoryRuntime runtime,
      CondaPath path,
      List<RepositoryRuntime> members,
      String identity,
      String generatedPath,
      boolean headOnly) {
    Optional<CondaLeaseManager.Lease> acquired = leases.acquireUnlessCompleted(
        leaseKey(runtime, "metadata", generatedPath),
        () -> assets.findInternal(runtime, generatedPath).isPresent());
    if (acquired.isEmpty()) {
      return assets.serveInternal(runtime, generatedPath, headOnly);
    }
    try (CondaLeaseManager.Lease lease = acquired.orElseThrow()) {
      if (assets.findInternal(runtime, generatedPath).isPresent()) {
        return assets.serveInternal(runtime, generatedPath, headOnly);
      }
      if (!repodataIdentity(runtime, members, path.channel(), path.subdir()).equals(identity)) {
        return null;
      }
      RepodataProjection projection = repodataProjection(
          runtime, members, path.channel(), path.subdir());
      if (!projection.available() && !"noarch".equals(path.subdir())) {
        throw notFound(path.rawPath());
      }
      try (CondaMetadataCodec.RecordSourceFile snapshot =
               metadata.snapshotRecordSource(path.subdir(), projection.records());
           CondaMetadataCodec.RenderedFile rendered = metadata.renderRepodataFile(
               path.subdir(), snapshot.records(), projection.tombstones(), path.encoding())) {
        lease.assertHeld();
        if (!repodataIdentity(runtime, members, path.channel(), path.subdir()).equals(identity)) {
          return null;
        }
        assets.storeGenerated(
            runtime,
            generatedPath,
            rendered.path(),
            rendered.contentType(),
            Map.of(
                "condaGenerated", true,
                "condaRevisionIdentity", identity,
                "condaChannel", path.channel(),
                "condaSubdir", path.subdir(),
                "condaEncoding", path.encoding().name()));
        lease.assertHeld();
      }
      return assets.serveInternal(runtime, generatedPath, headOnly);
    }
  }

  private RepodataProjection repodataProjection(
      RepositoryRuntime runtime,
      List<RepositoryRuntime> members,
      String channel,
      String subdir) {
    List<Long> memberIds = members.stream().map(RepositoryRuntime::id).toList();
    boolean group = runtime.isGroup();
    boolean available = "noarch".equals(subdir);
    LinkedHashMap<String, CondaRegistryDao.Tombstone> tombstones = new LinkedHashMap<>();
    for (RepositoryRuntime member : members) {
      Optional<CondaRegistryDao.ChannelState> state = registry.findChannelState(
          member.id(), channel, subdir);
      available |= state.isPresent();
      for (CondaRegistryDao.Tombstone tombstone :
          registry.listTombstones(member.id(), channel, subdir)) {
        tombstones.putIfAbsent(tombstone.filename(), tombstone);
      }
    }
    Set<String> activeTombstoneNames = registry.findPreferredPackageFilenames(
        memberIds, channel, subdir, tombstones.keySet());
    List<CondaRegistryDao.Tombstone> visibleTombstones = tombstones.values().stream()
        .filter(tombstone -> !activeTombstoneNames.contains(tombstone.filename()))
        .toList();
    CondaMetadataCodec.RecordSource records = (archiveFormat, visitor) -> {
      try {
        java.util.function.Consumer<CondaRegistryDao.PackageRecord> consumer = record -> {
          try {
            visitor.accept(record);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };
        if (group) {
          registry.visitPreferredPackages(
              memberIds, channel, subdir, archiveFormat, consumer);
        } else if (!memberIds.isEmpty()) {
          registry.visitPackages(
              memberIds.getFirst(), channel, subdir, archiveFormat, consumer);
        }
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    };
    return new RepodataProjection(records, visibleTombstones, available);
  }

  private MavenResponse channeldata(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    if (runtime.isProxy()) {
      try {
        return proxyJsonMetadata(runtime, path.channel(), "channeldata.json", headOnly);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // A valid Conda channel need not publish channeldata; synthesize it from known repodata.
      }
    }
    return materializedChanneldata(runtime, path.channel(), headOnly);
  }

  private MavenResponse notices(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    if (runtime.isProxy()) {
      try {
        return proxyJsonMetadata(runtime, path.channel(), "notices.json", headOnly);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Notices are optional.
      }
    }
    return rendered(metadata.emptyNotices(), headOnly, Instant.EPOCH);
  }

  private MavenResponse materializedChanneldata(
      RepositoryRuntime runtime, String channel, boolean headOnly) {
    List<RepositoryRuntime> members = concreteMembers(runtime, new LinkedHashSet<>());
    for (int attempt = 0; attempt < 3; attempt++) {
      long revision = registry.currentRepositoryRevision(runtime.id());
      String generatedPath = generatedChanneldataPath(channel, revision);
      if (assets.findInternal(runtime, generatedPath).isPresent()) {
        return assets.serveInternal(runtime, generatedPath, headOnly);
      }
      MavenResponse built = metadataBuilds.execute(generatedPath, () -> buildChanneldata(
          runtime, channel, members, revision, generatedPath, headOnly));
      if (built != null) return built;
    }
    throw new MavenExceptions.BadUpstreamException(
        "Conda repository changed repeatedly while generating channeldata.json");
  }

  private MavenResponse buildChanneldata(
      RepositoryRuntime runtime,
      String channel,
      List<RepositoryRuntime> members,
      long revision,
      String generatedPath,
      boolean headOnly) {
    Optional<CondaLeaseManager.Lease> acquired = leases.acquireUnlessCompleted(
        leaseKey(runtime, "metadata", generatedPath),
        () -> assets.findInternal(runtime, generatedPath).isPresent());
    if (acquired.isEmpty()) return assets.serveInternal(runtime, generatedPath, headOnly);
    try (CondaLeaseManager.Lease lease = acquired.orElseThrow()) {
      if (assets.findInternal(runtime, generatedPath).isPresent()) {
        return assets.serveInternal(runtime, generatedPath, headOnly);
      }
      if (registry.currentRepositoryRevision(runtime.id()) != revision) return null;
      CondaMetadataCodec.ChannelRecordSource source = channelRecordSource(
          runtime, members, channel);
      try (CondaMetadataCodec.RenderedFile rendered = metadata.renderChanneldataFile(source)) {
        lease.assertHeld();
        if (registry.currentRepositoryRevision(runtime.id()) != revision) return null;
        assets.storeGenerated(
            runtime,
            generatedPath,
            rendered.path(),
            rendered.contentType(),
            Map.of(
                "condaGenerated", true,
                "condaRevision", revision,
                "condaChannel", channel,
                "condaMetadataKind", "channeldata"));
        lease.assertHeld();
      }
      return assets.serveInternal(runtime, generatedPath, headOnly);
    }
  }

  private CondaMetadataCodec.ChannelRecordSource channelRecordSource(
      RepositoryRuntime runtime, List<RepositoryRuntime> members, String channel) {
    List<Long> memberIds = members.stream().map(RepositoryRuntime::id).toList();
    return visitor -> {
      try {
        java.util.function.Consumer<CondaRegistryDao.PackageRecord> consumer = record -> {
          try {
            visitor.accept(record);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };
        if (runtime.isGroup()) {
          registry.visitPreferredPackagesByChannel(memberIds, channel, consumer);
        } else if (!memberIds.isEmpty()) {
          registry.visitPackagesByChannel(memberIds.getFirst(), channel, consumer);
        }
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    };
  }

  private MavenResponse proxyJsonMetadata(
      RepositoryRuntime runtime, String channel, String filename, boolean headOnly) {
    String remotePath = metadataPath(channel, null, filename);
    String localPath = proxyMetadataLocalPath(channel, null, filename);
    MavenResponse refreshed = proxy.getMetadataFromUrlUnindexed(
        runtime, localPath, remoteUrl(runtime, remotePath), true);
    closeQuietly(refreshed.body());
    CachedAssetMetadata raw = assets.findInternal(runtime, localPath)
        .filter(value -> value.blob() != null)
        .orElseThrow(() -> new MavenExceptions.BadUpstreamException(
            "Conda upstream metadata was not cached: " + remotePath));
    String identity = raw.blob().sha256() == null
        ? raw.blob().sha1()
        : raw.blob().sha256();
    if (identity == null || identity.isBlank()) {
      throw new MavenExceptions.BadUpstreamException(
          "Conda upstream metadata has no content identity: " + remotePath);
    }
    String generatedPath = generatedUpstreamJsonPath(channel, filename, identity);
    if (assets.findInternal(runtime, generatedPath).isPresent()) {
      return assets.serveInternal(runtime, generatedPath, headOnly);
    }
    return metadataBuilds.execute(generatedPath, () -> buildProxyJsonMetadata(
        runtime, localPath, generatedPath, channel, filename, identity, headOnly));
  }

  private MavenResponse buildProxyJsonMetadata(
      RepositoryRuntime runtime,
      String localPath,
      String generatedPath,
      String channel,
      String filename,
      String identity,
      boolean headOnly) {
    Optional<CondaLeaseManager.Lease> acquired = leases.acquireUnlessCompleted(
        leaseKey(runtime, "metadata", generatedPath),
        () -> assets.findInternal(runtime, generatedPath).isPresent());
    if (acquired.isEmpty()) return assets.serveInternal(runtime, generatedPath, headOnly);
    try (CondaLeaseManager.Lease lease = acquired.orElseThrow()) {
      if (assets.findInternal(runtime, generatedPath).isPresent()) {
        return assets.serveInternal(runtime, generatedPath, headOnly);
      }
      MavenResponse raw = assets.serveInternal(runtime, localPath, false);
      try (InputStream input = new LimitedInputStream(raw.body(), MAX_UPSTREAM_METADATA_BYTES);
           CondaMetadataCodec.RenderedFile rendered = metadata.sanitizeJsonFile(input)) {
        lease.assertHeld();
        assets.storeGenerated(
            runtime,
            generatedPath,
            rendered.path(),
            rendered.contentType(),
            Map.of(
                "condaGenerated", true,
                "condaChannel", channel,
                "condaMetadataKind", filename,
                "condaSourceSha256", identity));
        lease.assertHeld();
      } catch (IOException e) {
        throw new MavenExceptions.BadUpstreamException(
            "Failed reading Conda upstream metadata: " + filename, e);
      }
      return assets.serveInternal(runtime, generatedPath, headOnly);
    }
  }

  private MavenResponse packageResponse(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hostedPackage(runtime, path, headOnly);
      case PROXY -> proxyPackage(runtime, path, headOnly);
      case GROUP -> groupPackage(runtime, path, headOnly);
    };
  }

  private MavenResponse hostedPackage(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    registry.findPackage(runtime.id(), path.channel(), path.subdir(), path.filename())
        .orElseThrow(() -> notFound(path.rawPath()));
    return assets.serve(runtime, path.canonicalPath(), headOnly);
  }

  private MavenResponse proxyPackage(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    Optional<CondaRegistryDao.PackageRecord> known = registry.findPackage(
        runtime.id(), path.channel(), path.subdir(), path.filename());
    if (known.isPresent()) {
      CondaRegistryDao.PackageRecord record = known.orElseThrow();
      bindCachedPackageIfNeeded(runtime, path, record);
      boolean inventoryCurrent = proxyInventoryCurrent(
          runtime, path.channel(), path.subdir());
      if (!inventoryCurrent) {
        scheduleProxyInventory(runtime, path.channel(), path.subdir());
        if (proxyInventories == null) {
          // The focused-test constructor runs the projection synchronously. Do not retain the
          // record captured before that refresh; a same-name package can have been republished.
          record = registry.findPackage(
                  runtime.id(), path.channel(), path.subdir(), path.filename())
              .orElseThrow(() -> notFound(path.rawPath()));
        }
      }
      try {
        return proxyPackage(runtime, path, record, headOnly);
      } catch (MavenExceptions.BadUpstreamException staleChecksum) {
        if (inventoryCurrent) throw staleChecksum;
        // Package names are normally immutable. If an upstream republishes one while the
        // deferred inventory projection is pending, refresh once and retry with the new digest.
        // This exceptional recovery keeps the normal cold path free of repodata parsing.
        ensureProxyInventory(runtime, path.channel(), path.subdir());
        CondaRegistryDao.PackageRecord refreshed = registry.findPackage(
                runtime.id(), path.channel(), path.subdir(), path.filename())
            .orElseThrow(() -> notFound(path.rawPath()));
        if (samePackageContent(record, refreshed)) throw staleChecksum;
        bindCachedPackageIfNeeded(runtime, path, refreshed);
        return proxyPackage(runtime, path, refreshed, headOnly);
      }
    }

    if (proxyInventories == null) {
      // Focused unit tests use the legacy constructor; production always supplies the deferred
      // scheduler. Preserve synchronous projection behavior for those deterministic fixtures.
      ensureProxyInventory(runtime, path.channel(), path.subdir());
      CondaRegistryDao.PackageRecord record = registry.findPackage(
              runtime.id(), path.channel(), path.subdir(), path.filename())
          .orElseThrow(() -> notFound(path.rawPath()));
      return proxyPackage(runtime, path, record, headOnly);
    }

    try {
      return proxyCanonicalPackage(runtime, path, headOnly);
    } catch (MavenExceptions.MavenNotFoundException canonicalMissing) {
      // Non-standard channels can relocate packages with info.base_url. Resolve that uncommon
      // case through the full inventory before reporting the upstream 404.
      ensureProxyInventory(runtime, path.channel(), path.subdir());
      CondaRegistryDao.PackageRecord record = registry.findPackage(
              runtime.id(), path.channel(), path.subdir(), path.filename())
          .orElseThrow(() -> canonicalMissing);
      return proxyPackage(runtime, path, record, headOnly);
    }
  }

  private MavenResponse proxyPackage(
      RepositoryRuntime runtime,
      CondaPath path,
      CondaRegistryDao.PackageRecord record,
      boolean headOnly) {
    ComponentRecord component = components.component(
        runtime, record.channel(), record.subdir(), record.name(), record.version(), record.build(),
        record.buildNumber(), record.filename(), record.updatedAt());
    String remoteUrl = remotePackageUrl(runtime, path);
    MavenResponse response = proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        runtime,
        path.canonicalPath(),
        remoteUrl,
        component,
        components.browsePath(
            record.channel(), record.subdir(), record.name(), record.version(), record.filename()),
        headOnly);
    try {
      verifyCachedPackage(runtime, path.canonicalPath(), record);
      return response;
    } catch (RuntimeException e) {
      closeQuietly(response.body());
      throw e;
    }
  }

  private MavenResponse groupPackage(
      RepositoryRuntime group, CondaPath path, boolean headOnly) {
    RepositoryRuntime transparentProxy = singleProxyMember(group);
    if (transparentProxy != null) {
      return proxyPackage(transparentProxy, path, headOnly);
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      Optional<CondaRegistryDao.GroupSourceBinding> candidate = registry.findGroupSourceBinding(
          group.id(), path.channel(), path.subdir(), path.filename());
      if (candidate.isPresent()) {
        MavenResponse bound = boundGroupPackage(
            group, path, candidate.orElseThrow(), headOnly);
        if (bound != null) return bound;
      } else {
        MavenResponse direct = directGroupPackage(group, path, headOnly);
        if (direct != null) return direct;
      }
      List<RepositoryRuntime> members = preparedConcreteMembers(
          group, path.channel(), path.subdir(), new LinkedHashSet<>());
      List<Long> memberIds = members.stream().map(RepositoryRuntime::id).toList();
      CondaRegistryDao.PackageRecord selected = registry.findPreferredPackage(
              memberIds, path.channel(), path.subdir(), path.filename())
          .orElseThrow(() -> notFound(path.rawPath()));
      CondaRegistryDao.GroupSourceBinding binding = bindGroupPackage(group, selected);
      MavenResponse resolved = boundGroupPackage(group, path, binding, headOnly);
      if (resolved != null) return resolved;
    }
    throw new MavenExceptions.BadUpstreamException(
        "Conda group source changed while resolving " + path.canonicalPath());
  }

  /**
   * Resolves the normal canonical package path in member order before falling back to a full
   * inventory projection. This lets a client install immediately after a cold group metadata
   * request; unusual channels that relocate packages through info.base_url still use the slower
   * compatibility fallback below when every canonical member lookup misses.
   */
  private MavenResponse directGroupPackage(
      RepositoryRuntime group, CondaPath path, boolean headOnly) {
    for (RepositoryRuntime member : concreteMembers(group, new LinkedHashSet<>())) {
      if (member.isHosted()) {
        Optional<CondaRegistryDao.PackageRecord> record = registry.findPackage(
            member.id(), path.channel(), path.subdir(), path.filename());
        if (record.isEmpty()) continue;
        bindGroupPackage(group, record.orElseThrow());
        return assets.serve(member, path.canonicalPath(), headOnly);
      }
      if (!member.isProxy()) continue;
      Optional<CondaRegistryDao.PackageRecord> known = registry.findPackage(
          member.id(), path.channel(), path.subdir(), path.filename());
      try {
        if (known.isPresent()) {
          MavenResponse response = proxyPackage(member, path, headOnly);
          bindGroupPackage(group, registry.findPackage(
                  member.id(), path.channel(), path.subdir(), path.filename())
              .orElse(known.orElseThrow()));
          return response;
        }
        return proxyCanonicalPackage(member, path, headOnly);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Continue in member order. If every canonical path misses, the existing inventory-based
        // path below can still resolve a non-standard info.base_url relocation.
      }
    }
    return null;
  }

  private CondaRegistryDao.GroupSourceBinding bindGroupPackage(
      RepositoryRuntime group, CondaRegistryDao.PackageRecord record) {
    Instant now = Instant.now();
    long memberRevision = registry.findChannelState(
            record.repositoryId(), record.channel(), record.subdir())
        .map(CondaRegistryDao.ChannelState::revision)
        .orElse(record.revision());
    CondaRegistryDao.GroupSourceBinding binding = new CondaRegistryDao.GroupSourceBinding(
        group.id(), record.channel(), record.subdir(), record.filename(), record.repositoryId(),
        memberRevision, contentIdentity(record), groupConfigurationRevision(group), now, now);
    registry.upsertGroupSourceBinding(binding);
    return binding;
  }

  private MavenResponse boundGroupPackage(
      RepositoryRuntime group,
      CondaPath path,
      CondaRegistryDao.GroupSourceBinding binding,
      boolean headOnly) {
    RepositoryRuntime member = containedRuntime(group, binding.memberRepositoryId());
    if (member == null || member.isGroup()) return null;
    if (binding.groupConfigRevision() != groupConfigurationRevision(group)) return null;
    if (member.isProxy()) {
      // Refresh before validating the pinned revision. Serving through proxyPackage(record, ...)
      // below deliberately skips a second inventory refresh, closing the metadata/bytes window.
      ensureProxyInventory(member, path.channel(), path.subdir());
    }
    long memberRevision = registry.findChannelState(
            member.id(), path.channel(), path.subdir())
        .map(CondaRegistryDao.ChannelState::revision)
        .orElse(0L);
    if (memberRevision != binding.memberRevision()) return null;
    Optional<CondaRegistryDao.PackageRecord> record = registry.findPackage(
        member.id(), path.channel(), path.subdir(), path.filename());
    if (record.isEmpty()
        || !contentIdentity(record.orElseThrow()).equals(binding.sha256())) {
      return null;
    }
    return member.isProxy()
        ? proxyPackage(member, path, record.orElseThrow(), headOnly)
        : assets.serve(member, path.canonicalPath(), headOnly);
  }

  private void ensureProxyInventory(
      RepositoryRuntime runtime, String channel, String subdir) {
    if (proxyInventoryCurrent(runtime, channel, subdir)) {
      return;
    }
    metadataBuilds.execute(leaseKey(runtime, "inventory-capacity", channel + "/" + subdir), () -> {
      ensureProxyInventoryWithinCapacity(runtime, channel, subdir);
      return null;
    });
  }

  private void scheduleProxyInventory(
      RepositoryRuntime runtime, String channel, String subdir) {
    if (proxyInventories == null) {
      ensureProxyInventory(runtime, channel, subdir);
      return;
    }
    String coordinate = runtime.id() + ":" + channel + ":" + subdir;
    proxyInventories.schedule(coordinate, () -> ensureProxyInventory(runtime, channel, subdir));
  }

  private void ensureProxyInventoryWithinCapacity(
      RepositoryRuntime runtime, String channel, String subdir) {
    String coordinate = channel + "/" + subdir;
    if (proxyInventoryCurrent(runtime, channel, subdir)) {
      return;
    }
    Optional<CondaLeaseManager.Lease> acquired = leases.acquireUnlessCompleted(
        leaseKey(runtime, "repodata", coordinate),
        () -> proxyInventoryCurrent(runtime, channel, subdir));
    if (acquired.isEmpty()) {
      return;
    }
    try (CondaLeaseManager.Lease lease = acquired.orElseThrow()) {
      if (proxyInventoryCurrent(runtime, channel, subdir)) {
        return;
      }
      GroupProxyRepodata source;
      try {
        source = prepareGroupProxyRepodata(runtime, channel, subdir);
      } catch (MavenExceptions.MavenNotFoundException e) {
        if (!subdir.equals("noarch")) throw e;
        transactionally(() -> {
          lease.assertHeld();
          registry.replaceProxyPackages(
              runtime.id(), channel, subdir, sha256(new byte[0]), null, List.of(), Instant.now());
          lease.assertHeld();
          return null;
        });
        return;
      }
      CachedAssetMetadata snapshot = source.cached();
      String metadataSha256 = snapshot.blob() == null ? null : snapshot.blob().sha256();
      Optional<CondaRegistryDao.ChannelState> current = registry.findChannelState(
          runtime.id(), channel, subdir);
      if (metadataSha256 != null && current.isPresent()
          && metadataSha256.equalsIgnoreCase(current.orElseThrow().metadataSha256())) {
        return;
      }
      Instant now = Instant.now();
      MavenResponse cached = assets.serveInternal(runtime, source.localPath(), false);
      try (InputStream stored = boundedBody(cached, MAX_UPSTREAM_METADATA_BYTES);
           InputStream body = new LimitedInputStream(
               metadata.decodeRepodata(stored, source.encoding()),
               MAX_UPSTREAM_METADATA_BYTES)) {
        try (CondaMetadataCodec.ProxyInventoryFile inventory = metadata.parseRepodataFile(
            body, metadataSha256, runtime.id(), channel, subdir, now)) {
          transactionally(() -> {
            lease.assertHeld();
            long revision = registry.replaceProxyPackages(
                runtime.id(),
                channel,
                subdir,
                inventory.metadataSha256(),
                inventory.packageBaseUrl(),
                inventory.records(),
                now);
            ensureNoarch(runtime.id(), channel, revision, now);
            lease.assertHeld();
            return null;
          });
        }
      } catch (IOException e) {
        throw new MavenExceptions.BadUpstreamException(
            "Failed reading Conda upstream metadata", e);
      }
    }
  }

  private boolean proxyInventoryCurrent(
      RepositoryRuntime runtime, String channel, String subdir) {
    for (String filename : PROXY_REPODATA_PREFERENCE) {
      if (proxyInventoryCurrent(
          runtime, channel, subdir, proxyMetadataLocalPath(channel, subdir, filename))) {
        return true;
      }
    }
    return false;
  }

  private boolean proxyInventoryCurrent(
      RepositoryRuntime runtime, String channel, String subdir, String localPath) {
    Optional<CondaRegistryDao.ChannelState> state = registry.findChannelState(
        runtime.id(), channel, subdir);
    Optional<CachedAssetMetadata> cached = assets.findInternal(runtime, localPath);
    if (state.isEmpty() || cached.isEmpty() || cached.orElseThrow().blob() == null
        || state.orElseThrow().metadataSha256() == null
        || !state.orElseThrow().metadataSha256().equalsIgnoreCase(
            cached.orElseThrow().blob().sha256())) {
      return false;
    }
    Instant verified = cached.orElseThrow().lastUpdatedAt();
    int maxAgeMinutes = runtime.metadataMaxAgeMinutesOrDefault();
    return verified != null && (maxAgeMinutes < 0
        || verified.plusSeconds(maxAgeMinutes * 60L).isAfter(Instant.now()));
  }

  private boolean proxyRawMetadataCurrent(RepositoryRuntime runtime, String localPath) {
    Optional<CachedAssetMetadata> cached = assets.findInternal(runtime, localPath);
    if (cached.isEmpty() || cached.orElseThrow().blob() == null) return false;
    Instant verified = cached.orElseThrow().lastUpdatedAt();
    int maxAgeMinutes = runtime.metadataMaxAgeMinutesOrDefault();
    return verified != null && (maxAgeMinutes < 0
        || verified.plusSeconds(maxAgeMinutes * 60L).isAfter(Instant.now()));
  }

  private void bindCachedPackageIfNeeded(
      RepositoryRuntime runtime,
      CondaPath path,
      CondaRegistryDao.PackageRecord record) {
    Optional<AssetRecord> cached = assets.find(runtime, path.canonicalPath());
    if (cached.isEmpty() || cached.orElseThrow().componentId() != null) return;
    AssetBlobRecord blob = assets.blob(runtime, path.canonicalPath());
    if (!validPackageBlob(blob, record)) {
      assets.delete(runtime, path.canonicalPath());
      return;
    }
    ComponentRecord component = components.component(
        runtime,
        record.channel(),
        record.subdir(),
        record.name(),
        record.version(),
        record.build(),
        record.buildNumber(),
        record.filename(),
        record.updatedAt());
    assets.bindCachedPackage(
        runtime,
        cached.orElseThrow(),
        blob,
        component,
        components.browsePath(
            record.channel(), record.subdir(), record.name(), record.version(), record.filename()));
  }

  /**
   * Stores a canonical proxy package with its Nexus-style component and Browse path in the same
   * transaction. Only filenames that cannot be projected use the asynchronous full-repodata
   * compatibility fallback.
   */
  private MavenResponse proxyCanonicalPackage(
      RepositoryRuntime runtime, CondaPath path, boolean headOnly) {
    Optional<CondaComponentFactory.ProjectedPackage> projected =
        components.projectPackagePath(runtime, path, Instant.now());
    if (projected.isEmpty()) {
      MavenResponse response = proxy.getPinnedAssetFromUrlUnindexed(
          runtime,
          path.canonicalPath(),
          remoteUrl(runtime, path.canonicalPath()),
          headOnly);
      scheduleProxyInventory(runtime, path.channel(), path.subdir());
      return response;
    }
    CondaComponentFactory.ProjectedPackage value = projected.orElseThrow();
    bindCachedPackageIfNeeded(runtime, path, value);
    return proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        runtime,
        path.canonicalPath(),
        remoteUrl(runtime, path.canonicalPath()),
        value.component(),
        value.browsePath(),
        headOnly);
  }

  private void bindCachedPackageIfNeeded(
      RepositoryRuntime runtime,
      CondaPath path,
      CondaComponentFactory.ProjectedPackage projected) {
    Optional<AssetRecord> cached = assets.find(runtime, path.canonicalPath());
    if (cached.isEmpty() || cached.orElseThrow().componentId() != null) return;
    assets.bindCachedPackage(
        runtime,
        cached.orElseThrow(),
        assets.blob(runtime, path.canonicalPath()),
        projected.component(),
        projected.browsePath());
  }

  private static String proxyMetadataLocalPath(
      String channel, String subdir, String filename) {
    return ".conda/upstream/" + channelHash(channel) + "/"
        + (subdir == null ? "channel" : subdir) + "/" + filename;
  }

  private void verifyCachedPackage(
      RepositoryRuntime runtime, String path, CondaRegistryDao.PackageRecord expected) {
    AssetBlobRecord blob = assets.blob(runtime, path);
    if (validPackageBlob(blob, expected)) return;
    assets.delete(runtime, path);
    throw new MavenExceptions.BadUpstreamException(
        "Conda upstream checksum mismatch for " + path);
  }

  private static boolean validPackageBlob(
      AssetBlobRecord blob, CondaRegistryDao.PackageRecord expected) {
    boolean valid = blob != null && blob.size() == expected.size();
    if (expected.sha256() != null) {
      valid &= blob != null && expected.sha256().equalsIgnoreCase(blob.sha256());
    } else if (expected.md5() != null) {
      valid &= blob != null && expected.md5().equalsIgnoreCase(blob.md5());
    } else {
      valid = false;
    }
    return valid;
  }

  private List<RepositoryRuntime> preparedConcreteMembers(
      RepositoryRuntime runtime, String channel, String subdir, Set<Long> visiting) {
    if (runtime == null || !runtime.online() || !visiting.add(runtime.id())) {
      return List.of();
    }
    try {
      if (runtime.isProxy()) {
        ensureProxyInventory(runtime, channel, subdir);
        return List.of(runtime);
      }
      if (!runtime.isGroup()) {
        return List.of(runtime);
      }
      LinkedHashMap<Long, RepositoryRuntime> concrete = new LinkedHashMap<>();
      MavenExceptions.BadUpstreamException upstreamFailure = null;
      for (RepositoryRuntime member : safeMembers(runtime)) {
        if (member == null || !member.online() || member.format() != RepositoryFormat.CONDA) {
          continue;
        }
        try {
          for (RepositoryRuntime resolved :
              preparedConcreteMembers(member, channel, subdir, visiting)) {
            concrete.putIfAbsent(resolved.id(), resolved);
          }
        } catch (MavenExceptions.MavenNotFoundException ignored) {
          // A proxy member may not publish every nested channel/subdir exposed by another
          // member. Treat that member as empty so a group can still project the available
          // hosted records. Direct proxy requests continue to return the upstream 404.
        } catch (MavenExceptions.BadUpstreamException e) {
          upstreamFailure = e;
        }
      }
      if (concrete.isEmpty() && upstreamFailure != null) {
        throw upstreamFailure;
      }
      return List.copyOf(concrete.values());
    } finally {
      visiting.remove(runtime.id());
    }
  }

  private List<RepositoryRuntime> concreteMembers(
      RepositoryRuntime runtime, Set<Long> visiting) {
    if (runtime == null || !runtime.online() || !visiting.add(runtime.id())) {
      return List.of();
    }
    try {
      if (!runtime.isGroup()) return List.of(runtime);
      LinkedHashMap<Long, RepositoryRuntime> concrete = new LinkedHashMap<>();
      for (RepositoryRuntime member : safeMembers(runtime)) {
        if (member == null || !member.online() || member.format() != RepositoryFormat.CONDA) {
          continue;
        }
        for (RepositoryRuntime resolved : concreteMembers(member, visiting)) {
          concrete.putIfAbsent(resolved.id(), resolved);
        }
      }
      return List.copyOf(concrete.values());
    } finally {
      visiting.remove(runtime.id());
    }
  }

  /** A one-member group is protocol-equivalent to that member and needs no metadata merge. */
  private RepositoryRuntime singleProxyMember(RepositoryRuntime runtime) {
    if (runtime == null || !runtime.isGroup()) return null;
    List<RepositoryRuntime> members = concreteMembers(runtime, new LinkedHashSet<>());
    return members.size() == 1 && members.getFirst().isProxy() ? members.getFirst() : null;
  }

  private RepositoryRuntime containedRuntime(RepositoryRuntime group, long repositoryId) {
    if (group == null) return null;
    for (RepositoryRuntime member : safeMembers(group)) {
      if (member == null || !member.online()) continue;
      if (member.id() == repositoryId) return member;
      RepositoryRuntime nested = member.isGroup() ? containedRuntime(member, repositoryId) : null;
      if (nested != null) return nested;
    }
    return null;
  }

  private void ensureNoarch(long repositoryId, String channel, long revision, Instant now) {
    registry.ensureChannelState(new CondaRegistryDao.ChannelState(
        repositoryId, channel, "noarch", null, null, revision, now, now));
  }

  private MavenResponse root(RepositoryRuntime runtime, boolean headOnly) {
    String body = "<!doctype html><html><body><h1>Conda channel "
        + escapeHtml(runtime.name())
        + "</h1><p>Use this URL as a Conda channel.</p></body></html>";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return headOnly
        ? MavenResponse.noBody(200, bytes.length, "text/html;charset=UTF-8", sha256(bytes), null)
        : MavenResponse.ok(
            new ByteArrayInputStream(bytes), bytes.length, "text/html;charset=UTF-8", sha256(bytes),
            null);
  }

  private static MavenResponse rendered(
      CondaMetadataCodec.Rendered rendered, boolean headOnly, Instant modified) {
    return headOnly
        ? MavenResponse.noBody(
            200, rendered.body().length, rendered.contentType(), rendered.etag(), modified)
        : MavenResponse.ok(
            new ByteArrayInputStream(rendered.body()), rendered.body().length,
            rendered.contentType(), rendered.etag(), modified);
  }

  private static InputStream boundedBody(MavenResponse response, long maxBytes) {
    if (response.contentLength() > maxBytes) {
      closeQuietly(response.body());
      throw new MavenExceptions.BadUpstreamException("Conda upstream metadata is too large");
    }
    InputStream body = response.body();
    if (body == null) {
      throw new MavenExceptions.BadUpstreamException("Conda upstream metadata is empty");
    }
    return new LimitedInputStream(body, maxBytes);
  }

  private static void enforceWritePolicy(
      RepositoryRuntime runtime, String path, boolean exists) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (!policy.checkCreateAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy is DENY");
    }
    if (exists && !policy.checkUpdateAllowed()) {
      throw new MavenExceptions.WritePolicyDenied(
          "Write policy ALLOW_ONCE forbids overwriting " + path);
    }
  }

  private static String packageMediaType(String filename, String requestType) {
    if (requestType != null && !requestType.isBlank()
        && !"application/octet-stream".equalsIgnoreCase(requestType)) {
      return requestType;
    }
    return filename.endsWith(".conda")
        ? CondaMediaTypes.CONDA_PACKAGE
        : CondaMediaTypes.TARBZ2_PACKAGE;
  }

  private static String metadataPath(String channel, String subdir, String filename) {
    StringBuilder path = new StringBuilder();
    if (channel != null && !channel.isBlank()) path.append(channel).append('/');
    if (subdir != null && !subdir.isBlank()) path.append(subdir).append('/');
    return path.append(filename).toString();
  }

  private static String remoteUrl(RepositoryRuntime runtime, String path) {
    if (runtime.proxyRemoteUrl() == null || runtime.proxyRemoteUrl().isBlank()) {
      throw new MavenExceptions.BadUpstreamException("Conda proxy remote URL is missing");
    }
    return RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), path);
  }

  private String remotePackageUrl(RepositoryRuntime runtime, CondaPath path) {
    String baseUrl = registry.findChannelState(
            runtime.id(), path.channel(), path.subdir())
        .map(CondaRegistryDao.ChannelState::packageBaseUrl)
        .filter(value -> !value.isBlank())
        .orElse(null);
    if (baseUrl == null) return remoteUrl(runtime, path.canonicalPath());
    try {
      URI repodata = URI.create(remoteUrl(
          runtime, metadataPath(path.channel(), path.subdir(), "repodata.json")));
      URI resolvedBase = repodata.resolve(baseUrl);
      String directory = resolvedBase.toString();
      if (!directory.endsWith("/")) directory += "/";
      URI packageUrl = URI.create(directory).resolve(
          RemoteUrlBuilder.encodePath(path.filename()));
      if (packageUrl.isOpaque()
          || !("http".equalsIgnoreCase(packageUrl.getScheme())
              || "https".equalsIgnoreCase(packageUrl.getScheme()))
          || packageUrl.getHost() == null
          || packageUrl.getRawUserInfo() != null
          || packageUrl.getRawQuery() != null
          || packageUrl.getRawFragment() != null) {
        throw new IllegalArgumentException("unsupported package URL");
      }
      return packageUrl.toString();
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.BadUpstreamException(
          "Conda upstream package base URL is invalid", e);
    }
  }

  private static String leaseKey(RepositoryRuntime runtime, String kind, String coordinate) {
    return "conda:" + runtime.id() + ":" + kind + ":" + channelHash(coordinate);
  }

  private String repodataIdentity(
      RepositoryRuntime runtime,
      List<RepositoryRuntime> members,
      String channel,
      String subdir) {
    StringBuilder value = new StringBuilder()
        .append(runtime.id()).append('\n')
        .append(runtime.type()).append('\n')
        .append(channel).append('\n')
        .append(subdir).append('\n');
    for (RepositoryRuntime member : members) {
      Optional<CondaRegistryDao.ChannelState> state = registry.findChannelState(
          member.id(), channel, subdir);
      value.append(member.id()).append(':')
          .append(member.type()).append(':')
          .append(member.online()).append(':')
          .append(state.map(CondaRegistryDao.ChannelState::revision).orElse(0L))
          .append('\n');
    }
    return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String generatedMetadataPath(CondaPath path, String identity) {
    String filename = switch (path.encoding()) {
      case BZIP2 -> "repodata.json.bz2";
      case ZSTD -> "repodata.json.zst";
      case MSGPACK_ZSTD -> "repodata_shards.msgpack.zst";
      default -> "repodata.json";
    };
    return ".conda/generated/" + channelHash(path.channel()) + "/" + path.subdir()
        + "/" + identity + "/" + filename;
  }

  private static String generatedChanneldataPath(String channel, long revision) {
    return ".conda/generated/" + channelHash(channel) + "/channel/" + revision
        + "/channeldata.json";
  }

  private static String generatedUpstreamJsonPath(
      String channel, String filename, String identity) {
    return ".conda/generated/" + channelHash(channel) + "/channel/upstream-"
        + identity + "/" + filename;
  }

  private static String channelHash(String value) {
    return HexFormat.of().formatHex(PersistenceHashes.sha256(value == null ? "" : value));
  }

  private static String contentIdentity(CondaRegistryDao.PackageRecord record) {
    if (record.sha256() != null) return record.sha256().toLowerCase(Locale.ROOT);
    if (record.md5() != null) return "md5:" + record.md5().toLowerCase(Locale.ROOT);
    throw new MavenExceptions.BadUpstreamException(
        "Conda package is missing a checksum: " + record.filename());
  }

  private static boolean samePackageContent(
      CondaRegistryDao.PackageRecord left, CondaRegistryDao.PackageRecord right) {
    return left.size() == right.size() && contentIdentity(left).equals(contentIdentity(right));
  }

  private static long groupConfigurationRevision(RepositoryRuntime group) {
    StringBuilder topology = new StringBuilder();
    appendGroupTopology(group, topology, new LinkedHashSet<>());
    byte[] digest = PersistenceHashes.sha256(topology.toString());
    long revision = 0;
    for (int index = 0; index < Long.BYTES; index++) {
      revision = (revision << 8) | (digest[index] & 0xffL);
    }
    revision &= Long.MAX_VALUE;
    return revision == 0 ? 1 : revision;
  }

  private static void appendGroupTopology(
      RepositoryRuntime runtime, StringBuilder topology, Set<Long> visiting) {
    if (runtime == null || !visiting.add(runtime.id())) return;
    topology.append(runtime.id()).append(':')
        .append(runtime.type()).append(':')
        .append(runtime.online()).append('[');
    for (RepositoryRuntime member : safeMembers(runtime)) {
      appendGroupTopology(member, topology, visiting);
      topology.append(',');
    }
    topology.append(']');
    visiting.remove(runtime.id());
  }

  private static List<RepositoryRuntime> safeMembers(RepositoryRuntime runtime) {
    return runtime.members() == null ? List.of() : runtime.members();
  }

  private <T> T transactionally(Supplier<T> work) {
    if (transactions == null) return work.get();
    return transactions.execute(ignored -> work.get());
  }

  private static void requireRuntime(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.CONDA) {
      throw new MavenExceptions.MethodNotAllowed("Repository is not Conda format");
    }
    if (!runtime.online()) throw notFound(runtime.name());
  }

  private static void requireHosted(RepositoryRuntime runtime) {
    requireRuntime(runtime);
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("Conda proxy and group repositories are read-only");
    }
  }

  private static MavenExceptions.MavenNotFoundException notFound(String path) {
    return new MavenExceptions.MavenNotFoundException(path == null ? "Conda resource not found" : path);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String escapeHtml(String value) {
    return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;");
  }

  private static void closeQuietly(InputStream input) {
    if (input == null) return;
    try {
      input.close();
    } catch (IOException ignored) {
    }
  }

  private record RepodataProjection(
      CondaMetadataCodec.RecordSource records,
      List<CondaRegistryDao.Tombstone> tombstones,
      boolean available) { }

  private record GroupRepodataMember(
      RepositoryRuntime runtime,
      String identity,
      String proxyLocalPath,
      long rawSize,
      CondaPath.Encoding sourceEncoding,
      boolean available) { }

  private record GroupProxyRepodata(
      String localPath,
      long rawSize,
      CondaPath.Encoding encoding,
      CachedAssetMetadata cached) { }

  private record GroupRepodataProjection(
      List<CondaMetadataCodec.MergeSource> sources,
      List<CondaRegistryDao.Tombstone> tombstones,
      boolean available,
      List<AutoCloseable> resources) implements AutoCloseable {
    @Override
    public void close() {
      closeGroupResources(resources);
    }
  }

  private static final class LimitedInputStream extends FilterInputStream {
    private final long limit;
    private long read;

    private LimitedInputStream(InputStream input, long limit) {
      super(input);
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) count(1);
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = super.read(buffer, offset, length);
      if (count > 0) count(count);
      return count;
    }

    private void count(long bytes) throws IOException {
      read += bytes;
      if (read > limit) {
        throw new IOException("Conda upstream metadata is too large");
      }
    }
  }

}
