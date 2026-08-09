package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.apt.AptPath;
import com.github.klboke.kkrepo.protocol.apt.AptPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AptServiceTest {

  @Test
  void publishesCanonicalHostedPackageAndAtomicallyPublishesSnapshot() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", null);
    byte[] archive = archive("demo", "1.0-1", "amd64");
    fixture.readyToPublish(runtime, "stable", 1);
    AssetRecord asset = asset(runtime, 10L, 20L, 30L,
        "pool/d/demo/demo_1.0-1_amd64.deb");
    when(fixture.assets.storePackage(
        eq(runtime), anyString(), anyString(), any(), any(), eq("alice"),
        eq("127.0.0.1"), any())).thenReturn(asset);
    AtomicReference<AptRegistryDao.PackageRecord> stored = new AtomicReference<>();
    when(fixture.registry.savePackage(any())).thenAnswer(invocation -> {
      AptRegistryDao.PackageRecord row = invocation.getArgument(0);
      stored.set(row);
      return row;
    });

    MavenResponse response = fixture.service.put(
        runtime,
        "pool/d/demo/demo_1.0-1_amd64.deb",
        new ByteArrayInputStream(archive),
        "application/octet-stream",
        "alice",
        "127.0.0.1");

    assertEquals(201, response.status());
    assertEquals("pool/d/demo/demo_1.0-1_amd64.deb", response.headers().get("Location"));
    AptRegistryDao.PackageRecord row = stored.get();
    assertEquals("demo", row.packageName());
    assertEquals("stable", row.distribution());
    assertEquals(sha256(archive), row.sha256());
    assertEquals(10L, row.assetId());
    verify(fixture.metadataBuilder).build(
        eq(runtime), eq(fixture.hostedSettings), any(), eq(fixture.key));
    verify(fixture.registry).publishSnapshot(any(), eq("owner"), eq(9L));
  }

  @Test
  void publishesThroughComponentApiAndRetiresReplacedProjection() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", null);
    byte[] archive = archive("libdemo", "2.0", "amd64");
    String path = "pool/libd/libdemo/libdemo_2.0_amd64.deb";
    String digest = sha256(archive);
    AptRegistryDao.PackageRecord existing = record(
        runtime, "stable", "main", "libdemo", "2.0", "amd64", path, digest, 5L, 6L);
    when(fixture.registry.findPackage(
        runtime.id(), "stable", "main", "libdemo", "2.0", "amd64"))
        .thenReturn(Optional.of(existing));
    when(fixture.registry.findPackageByPath(runtime.id(), path)).thenReturn(Optional.of(existing));
    when(fixture.assets.storePackage(
        eq(runtime), eq(path), anyString(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(asset(runtime, 10L, 20L, 30L, path));
    when(fixture.registry.savePackage(any())).thenAnswer(invocation -> invocation.getArgument(0));
    fixture.readyToPublish(runtime, "stable", 2);

    AptService.PublishedPackage published = fixture.service.publish(
        runtime, "libdemo_2.0_amd64.deb", new ByteArrayInputStream(archive),
        " stable ", " main ", "alice", "ip");
    assertEquals(path, published.path());
    assertEquals("libdemo", published.packageName());
    verify(fixture.assets).retirePackageProjection(5L);
  }

  @Test
  void rejectsUnsafeUploadsConfigurationMismatchesAndWritePolicyViolations() throws Exception {
    RepositoryRuntime hosted = runtime(RepositoryType.HOSTED, "ALLOW", null);
    byte[] archive = archive("demo", "1.0", "amd64");
    Fixture fixture = new Fixture();
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> fixture.service.put(
        hosted, "dists/stable/Release", new ByteArrayInputStream(archive), null, "a", "i"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> fixture.service.put(
        hosted, "pool/w/wrong/wrong.deb", new ByteArrayInputStream(archive), null, "a", "i"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> fixture.service.publish(
        hosted, "demo.deb", new ByteArrayInputStream(archive), "testing", null, "a", "i"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> fixture.service.publish(
        hosted, "demo.deb", new ByteArrayInputStream(archive), null, "contrib", "a", "i"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> fixture.service.publish(
        hosted, "demo.deb", new ByteArrayInputStream(
            archive("demo", "1.0", "arm64")), null, null, "a", "i"));

    Fixture deny = new Fixture();
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> deny.service.publish(
        runtime(RepositoryType.HOSTED, "DENY", null), "demo.deb",
        new ByteArrayInputStream(archive), null, null, "a", "i"));

    Fixture once = new Fixture();
    RepositoryRuntime allowOnce = runtime(RepositoryType.HOSTED, "ALLOW_ONCE", null);
    AptRegistryDao.PackageRecord existing = record(
        allowOnce, "stable", "main", "demo", "1.0", "amd64",
        "pool/d/demo/demo.deb", sha256(archive), 1L, 2L);
    when(once.registry.findPackage(
        allowOnce.id(), "stable", "main", "demo", "1.0", "amd64"))
        .thenReturn(Optional.of(existing));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> once.service.publish(
        allowOnce, "demo.deb", new ByteArrayInputStream(archive), null, null, "a", "i"));

    Fixture immutable = new Fixture();
    when(immutable.registry.findPackage(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record(
            hosted, "stable", "main", "demo", "1.0", "amd64",
            "pool/d/demo/demo_1.0_amd64.deb", "0".repeat(64), 1L, 2L)));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> immutable.service.publish(
        hosted, "demo.deb", new ByteArrayInputStream(archive), null, null, "a", "i"));

    Fixture collision = new Fixture();
    when(collision.registry.findPackageByPath(anyLong(), anyString()))
        .thenReturn(Optional.of(record(
            hosted, "testing", "main", "other", "1.0", "amd64",
            "pool/d/demo/demo_1.0_amd64.deb", sha256(archive), 1L, 2L)));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> collision.service.publish(
        hosted, "demo.deb", new ByteArrayInputStream(archive), null, null, "a", "i"));

    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> fixture.service.publish(
        runtime(RepositoryType.PROXY, "ALLOW", 60), "demo.deb",
        new ByteArrayInputStream(archive), null, null, "a", "i"));
  }

  @Test
  void servesHostedPackagesMetadataByHashAndRetainedPublicKeys() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", null);
    MavenResponse served = MavenResponse.noBody(200);
    when(fixture.assets.serve(eq(runtime), anyString(), anyBoolean())).thenReturn(served);

    MavenResponse packageResponse = fixture.service.get(
        runtime, "pool/d/demo/demo_1.0_amd64.deb", true);
    assertEquals("public, max-age=31536000, immutable",
        packageResponse.headers().get("Cache-Control"));

    AptRegistryDao.SuiteState state = suite(runtime, "stable", 1, 1);
    String release = "dists/stable/Release";
    AptRegistryDao.Snapshot snapshot = snapshot(runtime, "stable", 1,
        Map.of(release, ".apt/release"));
    when(fixture.registry.findSuite(runtime.id(), "stable")).thenReturn(Optional.of(state));
    when(fixture.registry.ensureSuite(eq(runtime.id()), eq("stable"), any())).thenReturn(state);
    when(fixture.registry.findPublishedSnapshot(runtime.id(), "stable"))
        .thenReturn(Optional.of(snapshot));
    MavenResponse metadata = fixture.service.get(runtime, release, false);
    assertEquals("public, max-age=0, must-revalidate",
        metadata.headers().get("Cache-Control"));
    verify(fixture.assets).serve(runtime, ".apt/release", false);
    verify(fixture.registry, never()).ensureSuite(eq(runtime.id()), eq("stable"), any());
    verify(fixture.registry, never()).findSuite(runtime.id(), "stable");

    String byHash = "dists/stable/main/binary-amd64/by-hash/SHA256/" + "a".repeat(64);
    when(fixture.registry.findPublishedSnapshot(runtime.id(), "stable"))
        .thenReturn(Optional.of(snapshot(runtime, "stable", 2, Map.of())));
    when(fixture.registry.listSnapshots(runtime.id(), "stable", 100))
        .thenReturn(List.of(snapshot(runtime, "stable", 1, Map.of(byHash, ".apt/by-hash"))));
    MavenResponse immutable = fixture.service.get(runtime, byHash, false);
    assertEquals("public, max-age=31536000, immutable",
        immutable.headers().get("Cache-Control"));

    AptRegistryDao.SigningKey oldKey = signingKey(runtime, 1, "old-key");
    AptRegistryDao.SigningKey newKey = signingKey(runtime, 2, "new-key\n");
    when(fixture.registry.listSigningKeys(runtime.id(), 2)).thenReturn(List.of(newKey, oldKey));
    MavenResponse key = fixture.service.get(runtime, "gpg.key", false);
    String keyBody = new String(key.body().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals("new-key\nold-key", keyBody);
    assertEquals("application/pgp-keys", key.contentType());
    MavenResponse head = fixture.service.get(runtime, "gpg.key", true);
    assertEquals(key.contentLength(), head.contentLength());
    assertTrue(!head.hasBody());

    when(fixture.registry.listSigningKeys(runtime.id(), 2)).thenReturn(List.of());
    assertTrue(new String(fixture.service.publicKey(runtime, false).body().readAllBytes(),
        StandardCharsets.UTF_8).contains("public"));
  }

  @Test
  void rejectsUnknownMissingAndWrongDistributionHostedReads() {
    Fixture fixture = new Fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", null);
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime, "", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime, "unknown/path", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime, "dists/testing/Release", false));

    when(fixture.registry.ensureSuite(eq(runtime.id()), eq("stable"), any()))
        .thenReturn(suite(runtime, "stable", 1, 1));
    when(fixture.registry.findPublishedSnapshot(runtime.id(), "stable"))
        .thenReturn(Optional.of(snapshot(runtime, "stable", 1, Map.of())));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime, "dists/stable/Release", false));
  }

  @Test
  void deletesHostedPackageAndRepublishesWhileEnforcingDeletePolicy() {
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", null);
    Fixture fixture = new Fixture();
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.delete(runtime, "dists/stable/Release", "manual", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.delete(
            runtime, "pool/d/demo/demo_1.0_amd64.deb", "manual", false));

    String path = "pool/d/demo/demo_1.0_amd64.deb";
    AptRegistryDao.PackageRecord row = record(
        runtime, "stable", "main", "demo", "1.0", "amd64", path,
        "a".repeat(64), 10L, 20L);
    when(fixture.registry.findPackageByPath(runtime.id(), path)).thenReturn(Optional.of(row));
    when(fixture.registry.deletePackage(
        eq(runtime.id()), eq("stable"), eq("main"), eq("demo"), eq("1.0"),
        eq("amd64"), eq("manual"), any())).thenReturn(Optional.of(row));
    fixture.readyToPublish(runtime, "stable", 2);
    assertEquals(204, fixture.service.delete(runtime, path, "manual", false).status());
    verify(fixture.assets).retirePackageProjection(10L);

    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> fixture.service.delete(
        runtime(RepositoryType.HOSTED, "DENY", null), path, "manual", true));
  }

  @Test
  void rotatesKeysRebuildsAndReportsDurableStatus() {
    Fixture fixture = new Fixture();
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", null);
    AptRegistryDao.SigningKey key = signingKey(runtime, 2, "public");
    when(fixture.signing.rotate(runtime, "private", "pass")).thenReturn(key);
    when(fixture.signing.rotateGenerated(runtime)).thenReturn(key);
    when(fixture.registry.listDistributions(runtime.id())).thenReturn(List.of("stable"));
    AptRegistryDao.Snapshot snapshot = snapshot(runtime, "stable", 1, Map.of());
    when(fixture.registry.findSuite(runtime.id(), "stable"))
        .thenReturn(Optional.of(suite(runtime, "stable", 1, 1)));
    when(fixture.registry.findPublishedSnapshot(runtime.id(), "stable"))
        .thenReturn(Optional.of(snapshot));

    assertEquals(key, fixture.service.rotateKey(runtime, "private", "pass"));
    assertEquals(key, fixture.service.rotateGeneratedKey(runtime));
    fixture.service.rebuild(runtime, " stable ");
    fixture.service.rebuild(runtime, " ");
    verify(fixture.registry, times(4)).markSuiteDirty(eq(runtime.id()), eq("stable"), any());

    AptRegistryDao.SuiteState state = new AptRegistryDao.SuiteState(
        runtime.id(), "stable", 3, Instant.EPOCH, 2, 2, Instant.EPOCH,
        "failure", Instant.EPOCH, Instant.EPOCH);
    AptRegistryDao.ProxyDistribution proxy = new AptRegistryDao.ProxyDistribution(
        runtime.id(), "stable", "release", Map.of("Packages", new AptRegistryDao.ProxyIndex(
            "a".repeat(64), 1)), true, Instant.EPOCH, Instant.EPOCH);
    when(fixture.registry.listSuites(runtime.id())).thenReturn(List.of(state));
    when(fixture.registry.findActiveSigningKey(runtime.id())).thenReturn(Optional.of(key));
    when(fixture.registry.listProxyDistributions(runtime.id())).thenReturn(List.of(proxy));
    AptService.Status status = fixture.service.status(runtime);
    assertEquals(1, status.suites().size());
    assertEquals("failure", status.suites().get(0).lastError());
    assertEquals(2, status.activeKey().revision());
    assertEquals(1, status.proxyDistributions().get(0).indexCount());

    when(fixture.registry.findActiveSigningKey(runtime.id())).thenReturn(Optional.empty());
    assertNull(fixture.service.status(runtime).activeKey());
  }

  @Test
  void servesPassthroughAndResignedProxyRepositories() {
    RepositoryRuntime proxyRuntime = runtime(RepositoryType.PROXY, "ALLOW", 60);
    Fixture passthrough = new Fixture();
    when(passthrough.repositorySettings.get(proxyRuntime)).thenReturn(passthrough.proxySettings);
    MavenResponse response = MavenResponse.noBody(200);
    when(passthrough.proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(), anyString(), anyBoolean())).thenReturn(response);
    when(passthrough.proxy.getPinnedAssetFromUrlUnindexed(
        eq(proxyRuntime), anyString(), anyString(), anyBoolean())).thenReturn(response);
    assertEquals(200, passthrough.service.get(
        proxyRuntime, "dists/stable/Release", false).status());
    MavenResponse packageResponse = passthrough.service.get(
        proxyRuntime, "pool/d/demo/demo_1.0_amd64.deb", true);
    assertEquals("public, max-age=31536000, immutable",
        packageResponse.headers().get("Cache-Control"));
    verify(passthrough.proxyProjection, times(2)).observePassthrough(
        eq(proxyRuntime), eq(passthrough.proxySettings), any(AptPath.class));

    Fixture resign = new Fixture();
    AptRepositorySettings.Settings resignSettings = new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, true, null,
        "kkRepo", "kkRepo");
    when(resign.repositorySettings.get(proxyRuntime)).thenReturn(resignSettings);
    String packagePath = "pool/d/demo/demo_1.0_amd64.deb";
    AptRegistryDao.PackageRecord projected = record(
        proxyRuntime, "stable", "main", "demo", "1.0", "amd64", packagePath,
        "a".repeat(64), 10L, 20L);
    when(resign.registry.findPackageByPath(proxyRuntime.id(), packagePath))
        .thenReturn(Optional.of(projected));
    when(resign.proxy.getPinnedAssetFromUrlUnindexed(
        eq(proxyRuntime), eq(packagePath), anyString(), eq(false))).thenReturn(response);
    assertEquals(200, resign.service.get(proxyRuntime, packagePath, false).status());
    verify(resign.proxyProjection).verifyAndBindKnownPackage(proxyRuntime, packagePath);

    AptRegistryDao.Snapshot snapshot = snapshot(proxyRuntime, "stable", 1,
        Map.of("dists/stable/Release", ".apt/proxy/Release"));
    when(resign.registry.findProxyDistribution(proxyRuntime.id(), "stable"))
        .thenReturn(Optional.empty());
    when(resign.registry.findSuite(proxyRuntime.id(), "stable"))
        .thenReturn(Optional.of(suite(proxyRuntime, "stable", 1, 1)));
    when(resign.registry.findPublishedSnapshot(proxyRuntime.id(), "stable"))
        .thenReturn(Optional.of(snapshot));
    when(resign.assets.serve(proxyRuntime, ".apt/proxy/Release", false)).thenReturn(response);
    assertEquals(200, resign.service.get(
        proxyRuntime, "dists/stable/Release", false).status());
    verify(resign.proxyProjection).refreshForResign(proxyRuntime, resignSettings, "stable");
  }

  @Test
  void rejectsNullWrongFormatAndGroupRuntimes() {
    Fixture fixture = new Fixture();
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(null, "gpg.key", false));
    RepositoryRuntime raw = new RepositoryRuntime(
        1, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true, 1L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(raw, "gpg.key", false));
    RepositoryRuntime group = runtime(RepositoryType.GROUP, "ALLOW", null);
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> fixture.service.get(group, "gpg.key", false));
  }

  private static final class Fixture {
    final AptRegistryDao registry = mock(AptRegistryDao.class);
    final AptRepositorySettings repositorySettings = mock(AptRepositorySettings.class);
    final AptAssetSupport assets = mock(AptAssetSupport.class);
    final AptMetadataBuilder metadataBuilder = mock(AptMetadataBuilder.class);
    final AptSigningService signing = mock(AptSigningService.class);
    final AptLeaseManager leases = mock(AptLeaseManager.class);
    final AptLeaseManager.Lease lease = mock(AptLeaseManager.Lease.class);
    final RawProxyService proxy = mock(RawProxyService.class);
    final AptProxyProjectionService proxyProjection = mock(AptProxyProjectionService.class);
    final AptRepositorySettings.Settings hostedSettings = new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, true, null,
        "kkRepo", "kkRepo");
    final AptRepositorySettings.Settings proxySettings = new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, false, null,
        "kkRepo", "kkRepo");
    final AptSigningService.SigningMaterial key = new AptSigningService.SigningMaterial(
        1, "key", "fingerprint", "public", "private", "");
    final AptService service;

    Fixture() {
      when(repositorySettings.get(any())).thenReturn(hostedSettings);
      when(leases.acquire(anyString())).thenReturn(lease);
      when(lease.owner()).thenReturn("owner");
      when(lease.fencingToken()).thenReturn(9L);
      when(signing.active(any())).thenReturn(key);
      when(registry.findPackage(anyLong(), anyString(), anyString(), anyString(), anyString(),
          anyString())).thenReturn(Optional.empty());
      when(registry.findPackageByPath(anyLong(), anyString())).thenReturn(Optional.empty());
      service = new AptService(
          registry, new AptPublishedSnapshotCache(registry), repositorySettings,
          new AptDebPackageInspector(), new AptComponentFactory(),
          assets, metadataBuilder, signing, leases, proxy, proxyProjection);
    }

    void readyToPublish(RepositoryRuntime runtime, String distribution, long revision) {
      AptRegistryDao.SuiteState state = suite(runtime, distribution, revision, revision - 1);
      when(registry.findSuite(runtime.id(), distribution)).thenReturn(Optional.of(state));
      when(registry.ensureSuite(eq(runtime.id()), eq(distribution), any())).thenReturn(state);
      when(registry.findPublishedSnapshot(runtime.id(), distribution)).thenReturn(Optional.empty());
      when(metadataBuilder.build(runtime, hostedSettings, state, key)).thenReturn(
          new AptMetadataBuilder.BuiltSnapshot(
              Map.of("dists/" + distribution + "/Release", ".apt/release"),
              "a".repeat(64), key.revision(), Instant.EPOCH));
      when(registry.publishSnapshot(any(), eq("owner"), eq(9L))).thenReturn(true);
    }
  }

  private static RepositoryRuntime runtime(
      RepositoryType type, String writePolicy, Integer metadataTtl) {
    return new RepositoryRuntime(
        1, "apt", RepositoryFormat.APT, type, "apt-" + type.name().toLowerCase(), true, 1L,
        writePolicy, null, null, true,
        type == RepositoryType.PROXY ? "https://apt.example/" : null,
        60, metadataTtl, true, null, List.of());
  }

  private static byte[] archive(String name, String version, String architecture) throws Exception {
    return AptTestPackage.deb("gz", AptTestPackage.control(name, version, architecture));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static AssetRecord asset(
      RepositoryRuntime runtime, long id, Long blobId, Long componentId, String path) {
    return new AssetRecord(
        id, runtime.id(), componentId, blobId, RepositoryFormat.APT, path, new byte[32],
        path.substring(path.lastIndexOf('/') + 1), "package",
        "application/vnd.debian.binary-package", 1L, null, Instant.EPOCH, Map.of());
  }

  private static AptRegistryDao.PackageRecord record(
      RepositoryRuntime runtime,
      String distribution,
      String component,
      String name,
      String version,
      String architecture,
      String path,
      String sha256,
      Long assetId,
      Long componentId) {
    Map<String, Object> fields = Map.of(
        "Package", name,
        "Version", version,
        "Architecture", architecture,
        "Maintainer", "Demo <demo@example.com>",
        "Description", "demo");
    return new AptRegistryDao.PackageRecord(
        1L, runtime.id(), distribution, component, architecture, name, version, name,
        path.substring(path.lastIndexOf('/') + 1), path, fields, "b".repeat(32),
        "c".repeat(40), sha256, 1, assetId, componentId, AptRegistryDao.SOURCE_HOSTED,
        1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AptRegistryDao.SuiteState suite(
      RepositoryRuntime runtime, String distribution, long desired, long published) {
    return new AptRegistryDao.SuiteState(
        runtime.id(), distribution, desired, Instant.EPOCH, published, 1,
        Instant.EPOCH, null, null, Instant.EPOCH);
  }

  private static AptRegistryDao.Snapshot snapshot(
      RepositoryRuntime runtime, String distribution, long revision, Map<String, String> manifest) {
    return new AptRegistryDao.Snapshot(
        runtime.id(), distribution, revision, 1, manifest, "a".repeat(64), Instant.EPOCH);
  }

  private static AptRegistryDao.SigningKey signingKey(
      RepositoryRuntime runtime, int revision, String publicKey) {
    return new AptRegistryDao.SigningKey(
        runtime.id(), revision, "key-" + revision, "fingerprint-" + revision,
        "encrypted", publicKey, true, Instant.EPOCH);
  }
}
