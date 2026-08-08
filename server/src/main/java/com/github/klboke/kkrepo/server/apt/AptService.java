package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.apt.AptMediaTypes;
import com.github.klboke.kkrepo.protocol.apt.AptPackageControl;
import com.github.klboke.kkrepo.protocol.apt.AptPath;
import com.github.klboke.kkrepo.protocol.apt.AptPathParser;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Hosted and proxy APT repository behavior with atomically published signed snapshots. */
@Service
public class AptService {
  private final AptRegistryDao registry;
  private final AptRepositorySettings repositorySettings;
  private final AptDebPackageInspector inspector;
  private final AptComponentFactory components;
  private final AptAssetSupport assets;
  private final AptMetadataBuilder metadataBuilder;
  private final AptSigningService signing;
  private final AptLeaseManager leases;
  private final RawProxyService proxy;
  private final AptProxyProjectionService proxyProjection;
  private final AptPathParser paths = new AptPathParser();

  AptService(
      AptRegistryDao registry,
      AptRepositorySettings repositorySettings,
      AptDebPackageInspector inspector,
      AptComponentFactory components,
      AptAssetSupport assets,
      AptMetadataBuilder metadataBuilder,
      AptSigningService signing,
      AptLeaseManager leases,
      RawProxyService proxy,
      AptProxyProjectionService proxyProjection) {
    this.registry = registry;
    this.repositorySettings = repositorySettings;
    this.inspector = inspector;
    this.components = components;
    this.assets = assets;
    this.metadataBuilder = metadataBuilder;
    this.signing = signing;
    this.leases = leases;
    this.proxy = proxy;
    this.proxyProjection = proxyProjection;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    requireApt(runtime);
    AptRepositorySettings.Settings settings = repositorySettings.get(runtime);
    AptPath path = paths.parse(rawPath, settings.flat());
    if (path.kind() == AptPath.Kind.UNKNOWN || path.kind() == AptPath.Kind.ROOT) {
      throw new MavenExceptions.MavenNotFoundException(rawPath == null ? "" : rawPath);
    }
    return runtime.isHosted()
        ? getHosted(runtime, settings, path, headOnly)
        : getProxy(runtime, settings, path, headOnly);
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    requireHosted(runtime);
    AptPath requested = paths.parse(rawPath);
    if (requested.kind() != AptPath.Kind.PACKAGE) {
      throw new MavenExceptions.MethodNotAllowed("APT PUT only accepts Debian package paths");
    }
    PublishedPackage published = publishInternal(
        runtime, requested.filename(), body, null, null, actor, ip, requested.normalized());
    return MavenResponse.created().withHeader("Location", published.path());
  }

  public PublishedPackage publish(
      RepositoryRuntime runtime,
      String filename,
      InputStream body,
      String distribution,
      String component,
      String actor,
      String ip) {
    return publishInternal(runtime, filename, body, distribution, component, actor, ip, null);
  }

  private PublishedPackage publishInternal(
      RepositoryRuntime runtime,
      String filename,
      InputStream body,
      String distribution,
      String component,
      String actor,
      String ip,
      String expectedPath) {
    requireHosted(runtime);
    AptRepositorySettings.Settings settings = repositorySettings.get(runtime);
    String suite = chooseConfigured("distribution", distribution, settings.distribution());
    String section = chooseConfigured("component", component, settings.component());
    try (AptDebPackageInspector.InspectedPackage inspected = inspector.inspect(body, filename)) {
      return publishInspected(
          runtime, settings, inspected, suite, section, actor, ip, expectedPath, true);
    }
  }

  PublishedPackage restoreHostedPackageForMigration(
      RepositoryRuntime runtime,
      AptDebPackageInspector.InspectedPackage inspected,
      String distribution,
      String component,
      String actor,
      String ip,
      String expectedPath) {
    requireHosted(runtime);
    AptRepositorySettings.Settings settings = repositorySettings.get(runtime);
    String suite = chooseConfigured("distribution", distribution, settings.distribution());
    String section = chooseConfigured("component", component, settings.component());
    // savePackage marks the suite dirty. Migration deliberately leaves that state unpublished
    // until an administrator imports the source signing key and requests an explicit rebuild.
    return publishInspected(
        runtime, settings, inspected, suite, section, actor, ip, expectedPath, false);
  }

  private PublishedPackage publishInspected(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      AptDebPackageInspector.InspectedPackage inspected,
      String suite,
      String section,
      String actor,
      String ip,
      String expectedPath,
      boolean publishSnapshot) {
    AptPackageControl control = inspected.control();
    if (!"all".equals(control.architecture())
        && !settings.architectures().contains(control.architecture())) {
      throw new MavenExceptions.BadRequestException(
          "APT package architecture is not enabled: " + control.architecture());
    }
    String poolPath = poolPath(control, inspected.filename());
    if (expectedPath != null && !poolPath.equals(expectedPath)) {
      throw new MavenExceptions.BadRequestException(
          "APT package must be uploaded at its canonical path: " + poolPath);
    }
    String coordinateLease = coordinateLease(runtime, suite, section, control);
    try (AptLeaseManager.Lease lease = leases.acquire(coordinateLease)) {
      lease.assertHeld();
      Optional<AptRegistryDao.PackageRecord> existing = registry.findPackage(
          runtime.id(), suite, section, control.packageName(), control.version(),
          control.architecture());
      enforceWritePolicy(runtime, existing.isPresent());
      existing.filter(row -> row.path().equals(poolPath))
          .filter(row -> !row.sha256().equalsIgnoreCase(inspected.sha256()))
          .ifPresent(row -> {
            throw new MavenExceptions.WritePolicyDenied(
                "APT package paths are immutable; upload a new filename or version");
          });
      registry.findPackageByPath(runtime.id(), poolPath).ifPresent(row -> {
        if (!sameCoordinate(row, suite, section, control)) {
          throw new MavenExceptions.WritePolicyDenied(
              "APT package path is already bound to another coordinate: " + poolPath);
        }
      });
      Instant now = Instant.now();
      ComponentRecord projected = components.component(
          runtime, suite, section, control, inspected.filename(), poolPath, now);
      LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("aptDistribution", suite);
      attributes.put("aptComponent", section);
      attributes.put("aptArchitecture", control.architecture());
      attributes.put("aptPackage", control.packageName());
      attributes.put("aptVersion", control.version());
      attributes.put("aptMd5", inspected.md5());
      attributes.put("aptSha1", inspected.sha1());
      attributes.put("aptSha256", inspected.sha256());
      attributes.put("aptSize", inspected.size());
      AssetRecord asset = assets.storePackage(
          runtime,
          poolPath,
          components.browsePath(suite, section, control, inspected.filename()),
          inspected.file(),
          Map.copyOf(attributes),
          actor,
          ip,
          projected);
      lease.assertHeld();
      AptRegistryDao.PackageRecord stored = registry.savePackage(new AptRegistryDao.PackageRecord(
          null,
          runtime.id(),
          suite,
          section,
          control.architecture(),
          control.packageName(),
          control.version(),
          sourcePackage(control),
          inspected.filename(),
          poolPath,
          objectFields(control.fields()),
          inspected.md5(),
          inspected.sha1(),
          inspected.sha256(),
          inspected.size(),
          asset.id(),
          asset.componentId(),
          AptRegistryDao.SOURCE_HOSTED,
          0,
          now,
          existing.map(AptRegistryDao.PackageRecord::createdAt).orElse(now),
          now));
      existing.filter(row -> row.assetId() != null)
          .filter(row -> !row.assetId().equals(stored.assetId()))
          .ifPresent(row -> assets.retirePackageProjection(row.assetId()));
      lease.assertHeld();
      if (publishSnapshot) {
        publishPending(runtime, settings, suite);
      }
      return new PublishedPackage(
          stored.path(), stored.packageName(), stored.version(), stored.architecture(),
          stored.sha256(), stored.size());
    }
  }

  public MavenResponse delete(
      RepositoryRuntime runtime, String rawPath, String reason, boolean enforcePolicy) {
    requireHosted(runtime);
    if (enforcePolicy && !WritePolicy.parse(runtime.writePolicy()).checkDeleteAllowed()) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy forbids deletion");
    }
    AptPath path = paths.parse(rawPath);
    if (path.kind() != AptPath.Kind.PACKAGE) {
      throw new MavenExceptions.MethodNotAllowed("Only APT package assets can be deleted");
    }
    AptRegistryDao.PackageRecord existing = registry.findPackageByPath(runtime.id(), path.normalized())
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    AptPackageControl control = packageControl(existing);
    try (AptLeaseManager.Lease lease = leases.acquire(
        coordinateLease(runtime, existing.distribution(), existing.component(), control))) {
      lease.assertHeld();
      AptRegistryDao.PackageRecord removed = registry.deletePackage(
          runtime.id(), existing.distribution(), existing.component(), existing.packageName(),
          existing.version(), existing.architecture(), reason, Instant.now())
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.normalized()));
      assets.retirePackageProjection(removed.assetId());
      lease.assertHeld();
      publishPending(runtime, repositorySettings.get(runtime), removed.distribution());
      // Retain the package blob for old signed snapshots. Automated grace-period cleanup is
      // intentionally deferred until snapshot/package reference retention is implemented.
      return MavenResponse.noBody(204);
    }
  }

  public MavenResponse publicKey(RepositoryRuntime runtime, boolean headOnly) {
    AptSigningService.SigningMaterial key = signing.active(runtime);
    List<AptRegistryDao.SigningKey> retained = registry.listSigningKeys(runtime.id(), 2);
    StringBuilder armor = new StringBuilder();
    for (AptRegistryDao.SigningKey row : retained) {
      if (!row.publicKey().isBlank()) {
        if (!armor.isEmpty() && armor.charAt(armor.length() - 1) != '\n') armor.append('\n');
        armor.append(row.publicKey());
      }
    }
    if (armor.isEmpty()) armor.append(key.publicArmor());
    byte[] bytes = armor.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String etag = '"' + sha256(bytes) + '"';
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, AptMediaTypes.PGP_KEYS, etag, null);
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, AptMediaTypes.PGP_KEYS, etag, null);
  }

  public AptRegistryDao.SigningKey rotateKey(
      RepositoryRuntime runtime, String privateKeyArmor, String passphrase) {
    requireApt(runtime);
    AptRegistryDao.SigningKey key = signing.rotate(runtime, privateKeyArmor, passphrase);
    republishForKeyRotation(runtime);
    return key;
  }

  public AptRegistryDao.SigningKey rotateGeneratedKey(RepositoryRuntime runtime) {
    requireApt(runtime);
    AptRegistryDao.SigningKey key = signing.rotateGenerated(runtime);
    republishForKeyRotation(runtime);
    return key;
  }

  private void republishForKeyRotation(RepositoryRuntime runtime) {
    AptRepositorySettings.Settings settings = repositorySettings.get(runtime);
    if (runtime.isHosted() || settings.resign()) {
      for (String distribution : registry.listDistributions(runtime.id())) {
        registry.markSuiteDirty(runtime.id(), distribution, Instant.now());
        publishPending(runtime, settings, distribution);
      }
    }
  }

  public void rebuild(RepositoryRuntime runtime, String distribution) {
    requireApt(runtime);
    AptRepositorySettings.Settings settings = repositorySettings.get(runtime);
    String suite = distribution == null || distribution.isBlank()
        ? settings.distribution() : distribution.trim();
    registry.markSuiteDirty(runtime.id(), suite, Instant.now());
    publishPending(runtime, settings, suite);
  }

  public Status status(RepositoryRuntime runtime) {
    requireApt(runtime);
    List<SuiteStatus> suites = registry.listSuites(runtime.id()).stream()
        .map(state -> new SuiteStatus(
            state.distribution(), state.desiredRevision(), state.publishedRevision(),
            state.signingKeyRevision(), state.lastPublishedAt(), state.lastError(),
            state.lastErrorAt()))
        .toList();
    KeyStatus key = registry.findActiveSigningKey(runtime.id())
        .map(row -> new KeyStatus(
            row.revision(), row.keyId(), row.fingerprint(), row.createdAt()))
        .orElse(null);
    List<ProxyStatus> proxies = registry.listProxyDistributions(runtime.id()).stream()
        .map(row -> new ProxyStatus(
            row.distribution(), row.releaseIdentity(), row.indices().size(),
            row.signatureVerified(), row.observedAt()))
        .toList();
    return new Status(suites, key, proxies);
  }

  private MavenResponse getHosted(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      AptPath path,
      boolean headOnly) {
    if (path.kind() == AptPath.Kind.PUBLIC_KEY) return publicKey(runtime, headOnly);
    if (path.kind() == AptPath.Kind.PACKAGE) {
      return assets.serve(runtime, path.normalized(), headOnly)
          .withHeader("Cache-Control", "public, max-age=31536000, immutable");
    }
    enforceDistribution(settings, path);
    String distribution = path.distribution() == null ? settings.distribution() : path.distribution();
    AptRegistryDao.SuiteState state = registry.ensureSuite(
        runtime.id(), distribution, Instant.now());
    if (registry.findPublishedSnapshot(runtime.id(), distribution).isEmpty()
        && state.desiredRevision() == state.publishedRevision()) {
      registry.markSuiteDirty(runtime.id(), distribution, Instant.now());
      state = registry.findSuite(runtime.id(), distribution).orElseThrow();
    }
    if (state.desiredRevision() != state.publishedRevision()) {
      publishPending(runtime, settings, distribution);
    }
    AptRegistryDao.Snapshot snapshot = registry.findPublishedSnapshot(runtime.id(), distribution)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.normalized()));
    String hidden = snapshot.manifest().get(path.normalized());
    if (hidden == null && path.kind() == AptPath.Kind.BY_HASH) {
      hidden = registry.listSnapshots(runtime.id(), distribution, 100).stream()
          .map(AptRegistryDao.Snapshot::manifest)
          .map(manifest -> manifest.get(path.normalized()))
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .orElse(null);
    }
    if (hidden == null) throw new MavenExceptions.MavenNotFoundException(path.normalized());
    MavenResponse response = assets.serve(runtime, hidden, headOnly);
    return response.withHeader(
        "Cache-Control",
        path.kind() == AptPath.Kind.BY_HASH
            ? "public, max-age=31536000, immutable"
            : "public, max-age=0, must-revalidate");
  }

  private MavenResponse getProxy(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      AptPath path,
      boolean headOnly) {
    enforceDistribution(settings, path);
    if (settings.resign()) {
      return getResignedProxy(runtime, settings, path, headOnly);
    }
    String remote = RemoteUrlBuilder.repositoryPathString(
        runtime.proxyRemoteUrl(), path.normalized());
    MavenResponse response = path.metadata()
        ? proxy.getMetadataFromUrlUnindexed(runtime, path.normalized(), remote, headOnly)
        : proxy.getPinnedAssetFromUrlUnindexed(runtime, path.normalized(), remote, headOnly);
    if (path.kind() == AptPath.Kind.BY_HASH || path.kind() == AptPath.Kind.PACKAGE) {
      response.withHeader("Cache-Control", "public, max-age=31536000, immutable");
    }
    proxyProjection.observePassthrough(runtime, settings, path);
    return response;
  }

  private MavenResponse getResignedProxy(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      AptPath path,
      boolean headOnly) {
    if (path.kind() == AptPath.Kind.PUBLIC_KEY) return publicKey(runtime, headOnly);
    if (path.kind() == AptPath.Kind.PACKAGE) {
      AptRegistryDao.PackageRecord projected = registry.findPackageByPath(
              runtime.id(), path.normalized())
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.normalized()));
      Object upstreamField = projected.controlFields().get("Filename");
      String upstreamPath = upstreamField == null
          ? path.normalized() : upstreamField.toString();
      String remote = RemoteUrlBuilder.repositoryPathString(
          runtime.proxyRemoteUrl(), upstreamPath);
      MavenResponse response = proxy.getPinnedAssetFromUrlUnindexed(
          runtime, path.normalized(), remote, headOnly);
      proxyProjection.verifyAndBindKnownPackage(runtime, path.normalized());
      return response.withHeader("Cache-Control", "public, max-age=31536000, immutable");
    }
    String distribution = path.distribution() == null ? settings.distribution() : path.distribution();
    AptRegistryDao.ProxyDistribution upstream = registry.findProxyDistribution(
        runtime.id(), distribution).orElse(null);
    int metadataTtlMinutes = runtime.metadataMaxAgeMinutesOrDefault();
    Instant refreshBefore = metadataTtlMinutes > 0
        ? Instant.now().minusSeconds(metadataTtlMinutes * 60L) : Instant.EPOCH;
    if (upstream == null || metadataTtlMinutes == 0
        || (metadataTtlMinutes > 0
            && (upstream.updatedAt() == null || upstream.updatedAt().isBefore(refreshBefore)))) {
      proxyProjection.refreshForResign(runtime, settings, distribution);
      publishPending(runtime, settings, distribution);
    }
    AptRegistryDao.Snapshot snapshot = registry.findPublishedSnapshot(runtime.id(), distribution)
        .orElseGet(() -> {
          proxyProjection.refreshForResign(runtime, settings, distribution);
          publishPending(runtime, settings, distribution);
          return registry.findPublishedSnapshot(runtime.id(), distribution)
              .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
                  "APT re-signing has no verified cached snapshot yet: " + distribution));
        });
    String hidden = snapshot.manifest().get(path.normalized());
    if (hidden == null) throw new MavenExceptions.MavenNotFoundException(path.normalized());
    return assets.serve(runtime, hidden, headOnly);
  }

  private void publishPending(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      String distribution) {
    for (int attempt = 0; attempt < 4; attempt++) {
      AptRegistryDao.SuiteState before = registry.findSuite(runtime.id(), distribution)
          .orElseGet(() -> registry.ensureSuite(runtime.id(), distribution, Instant.now()));
      if (before.desiredRevision() == before.publishedRevision()
          && registry.findPublishedSnapshot(runtime.id(), distribution).isPresent()) return;
      try (AptLeaseManager.Lease lease = leases.acquire(
          "apt:publish:" + runtime.id() + ":" + distribution)) {
        lease.assertHeld();
        AptRegistryDao.SuiteState state = registry.findSuite(runtime.id(), distribution).orElseThrow();
        if (state.desiredRevision() == state.publishedRevision()
            && registry.findPublishedSnapshot(runtime.id(), distribution).isPresent()) return;
        try {
          AptSigningService.SigningMaterial key = signing.active(runtime);
          AptMetadataBuilder.BuiltSnapshot built = metadataBuilder.build(
              runtime, settings, state, key);
          lease.assertHeld();
          boolean published = registry.publishSnapshot(
              new AptRegistryDao.Snapshot(
                  runtime.id(), distribution, state.desiredRevision(),
                  built.signingKeyRevision(), built.manifest(), built.releaseSha256(),
                  built.createdAt()),
              lease.owner(), lease.fencingToken());
          if (published) return;
        } catch (RuntimeException error) {
          registry.recordBuildFailure(
              runtime.id(), distribution, state.desiredRevision(), error.getMessage(), Instant.now());
          throw error;
        }
      }
    }
    throw new MavenExceptions.WritePolicyDenied(
        "APT suite changed repeatedly during publication; retry the request");
  }

  private static String poolPath(AptPackageControl control, String filename) {
    String source = sourcePackage(control);
    String prefix = source.startsWith("lib") && source.length() >= 4
        ? source.substring(0, 4) : source.substring(0, 1);
    return "pool/" + prefix + "/" + source + "/" + filename;
  }

  private static String sourcePackage(AptPackageControl control) {
    return control.sourcePackageName();
  }

  private static Map<String, Object> objectFields(Map<String, String> fields) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    fields.forEach(result::put);
    return Map.copyOf(result);
  }

  private static AptPackageControl packageControl(AptRegistryDao.PackageRecord record) {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    record.controlFields().forEach((name, value) -> {
      if (value != null) fields.put(name, value.toString());
    });
    return AptPackageControl.from(
        new com.github.klboke.kkrepo.protocol.apt.AptDeb822.Stanza(fields));
  }

  private static boolean sameCoordinate(
      AptRegistryDao.PackageRecord row,
      String distribution,
      String component,
      AptPackageControl control) {
    return row.distribution().equals(distribution)
        && row.component().equals(component)
        && row.packageName().equals(control.packageName())
        && row.version().equals(control.version())
        && row.architecture().equals(control.architecture());
  }

  private static void enforceWritePolicy(RepositoryRuntime runtime, boolean exists) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied("Repository write policy is DENY");
    }
    if (policy == WritePolicy.ALLOW_ONCE && exists) {
      throw new MavenExceptions.WritePolicyDenied("APT package coordinate already exists");
    }
  }

  private static void enforceDistribution(
      AptRepositorySettings.Settings settings, AptPath path) {
    if (settings.enforceDistribution() && path.distribution() != null
        && !path.distribution().equals(settings.distribution())) {
      throw new MavenExceptions.MavenNotFoundException(path.normalized());
    }
  }

  private static String chooseConfigured(String field, String requested, String configured) {
    if (requested == null || requested.isBlank()) return configured;
    String value = requested.trim();
    if (!value.equals(configured)) {
      throw new MavenExceptions.BadRequestException(
          "APT " + field + " does not match repository configuration: " + configured);
    }
    return value;
  }

  private static String coordinateLease(
      RepositoryRuntime runtime,
      String distribution,
      String component,
      AptPackageControl control) {
    return "apt:coordinate:" + runtime.id() + ":" + HexFormat.of().formatHex(
        PersistenceHashes.sha256(
            distribution, component, control.packageName(), control.version(),
            control.architecture()));
  }

  private static void requireApt(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.APT || runtime.isGroup()) {
      throw new MavenExceptions.MethodNotAllowed("Repository is not an APT hosted/proxy repository");
    }
  }

  private static void requireHosted(RepositoryRuntime runtime) {
    requireApt(runtime);
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("APT upload is only valid on hosted repositories");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  public record PublishedPackage(
      String path,
      String packageName,
      String version,
      String architecture,
      String sha256,
      long size) { }

  public record Status(
      List<SuiteStatus> suites,
      KeyStatus activeKey,
      List<ProxyStatus> proxyDistributions) { }

  public record SuiteStatus(
      String distribution,
      long desiredRevision,
      long publishedRevision,
      int signingKeyRevision,
      Instant lastPublishedAt,
      String lastError,
      Instant lastErrorAt) { }

  public record KeyStatus(
      int revision,
      String keyId,
      String fingerprint,
      Instant createdAt) { }

  public record ProxyStatus(
      String distribution,
      String releaseIdentity,
      int indexCount,
      boolean signatureVerified,
      Instant observedAt) { }
}
