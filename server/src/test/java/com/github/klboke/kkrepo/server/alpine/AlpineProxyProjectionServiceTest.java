package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineChecksums;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlpineProxyProjectionServiceTest {
  private static final String NAMESPACE = "v3.20/main/x86_64";
  private static final String INDEX_PATH = NAMESPACE + "/APKINDEX.tar.gz";
  private static final String PACKAGE_PATH = NAMESPACE + "/demo-1.0-r0.apk";

  private final AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
  private final RawProxyService proxy = mock(RawProxyService.class);
  private final AlpineAssetSupport assets = mock(AlpineAssetSupport.class);
  private final AlpinePackageInspector inspector = new AlpinePackageInspector();
  private final AlpineComponentFactory components = new AlpineComponentFactory();
  private final RepositoryRuntime runtime = runtime();
  private AlpineProxyProjectionService service;

  @BeforeEach
  void setUp() {
    service = new AlpineProxyProjectionService(registry, proxy, assets, inspector, components);
    when(proxy.getMetadataFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(proxy.getPinnedAssetFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    AtomicLong ids = new AtomicLong(10);
    when(registry.savePackage(any())).thenAnswer(invocation -> {
      AlpineRegistryDao.PackageRecord row = invocation.getArgument(0);
      return row.id() == null ? withId(row, ids.incrementAndGet()) : row;
    });
    when(assets.bindProxyPackage(any(), anyString(), any(), anyString(), any()))
        .thenReturn(asset());
  }

  @Test
  void passthroughProjectsUnsignedIndexWithoutChangingResponseBytes() throws Exception {
    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    byte[] index = indexArchive(indexRecord(apk));
    when(assets.serve(runtime, INDEX_PATH, false)).thenReturn(response(index));
    AlpineRepositorySettings.Settings settings = settings(false, false, List.of());

    service.observePassthrough(
        runtime, settings, new com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser()
            .parse(INDEX_PATH));

    ArgumentCaptor<AlpineRegistryDao.PackageRecord> row =
        ArgumentCaptor.forClass(AlpineRegistryDao.PackageRecord.class);
    verify(registry).savePackage(row.capture());
    assertTrue(row.getValue().path().endsWith("demo-1.0-r0.apk"));
    assertTrue(row.getValue().assetId() == null);
    verify(registry).observeProxyDistribution(
        eq(runtime.id()), eq(NAMESPACE), anyString(), any(), eq(false), any(Instant.class));
    verify(assets, never()).bindProxyPackage(any(), anyString(), any(), anyString(), any());
  }

  @Test
  void knownPassthroughPackageIsVerifiedAgainstSignedIndexProjectionAndBound() throws Exception {
    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    AlpineRegistryDao.PackageRecord expected = expected(apk, null, null);
    when(registry.findPackageByPath(runtime.id(), PACKAGE_PATH)).thenReturn(Optional.of(expected));
    when(registry.findPackage(
        runtime.id(), NAMESPACE, "main", "demo", "1.0-r0", "x86_64"))
        .thenReturn(Optional.of(expected));
    when(assets.serve(runtime, PACKAGE_PATH, false)).thenReturn(response(apk.bytes()));

    service.observePassthrough(
        runtime, settings(false, false, List.of()),
        new com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser().parse(PACKAGE_PATH));

    verify(assets).bindProxyPackage(
        eq(runtime), eq(PACKAGE_PATH), any(),
        eq(PACKAGE_PATH), any());
    verify(registry).replacePackageRelations(anyLong(), anyLong(), any());
  }

  @Test
  void resignRefreshRequiresTrustFetchesEveryPackageAndRetiresStaleProjection() throws Exception {
    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    KeyPair key = rsa();
    byte[] unsigned = indexArchive(indexRecord(apk));
    byte[] signed = AlpineTestPackage.concatenate(
        signatureArchive(".SIGN.RSA256.fixture.rsa.pub", sign(unsigned, key)), unsigned);
    when(assets.serve(runtime, INDEX_PATH, false)).thenAnswer(ignored -> response(signed));
    when(assets.serve(runtime, PACKAGE_PATH, false)).thenReturn(response(apk.bytes()));
    AlpineRegistryDao.PackageRecord stale = new AlpineRegistryDao.PackageRecord(
        99L, runtime.id(), NAMESPACE, "main", "x86_64", "old", "1-r0", "x86_64",
        "old-1-r0.apk", NAMESPACE + "/old-1-r0.apk", Map.of(),
        "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "a".repeat(64), "b".repeat(64), 10L,
        77L, 88L, AlpineRegistryDao.SOURCE_PROXY, 1L, Instant.EPOCH, Instant.EPOCH,
        Instant.EPOCH);
    when(registry.listPackagePage(
        runtime.id(), NAMESPACE, "", 0L, AlpineRegistryDao.PACKAGE_PAGE_SIZE))
        .thenReturn(List.of(stale));
    when(registry.deletePackage(
        eq(runtime.id()), eq(NAMESPACE), eq("main"), eq("old"), eq("1-r0"),
        eq("x86_64"), eq("upstream-index-replaced"), any(Instant.class)))
        .thenReturn(Optional.of(stale));
    AlpineRepositorySettings.Settings trusted = settings(
        true, true, List.of("filename=fixture.rsa.pub\n" + publicPem(key)));

    service.refreshForResign(runtime, trusted, NAMESPACE);

    verify(proxy).getPinnedAssetFromUrlUnindexed(
        eq(runtime), eq(PACKAGE_PATH), anyString(), eq(true));
    verify(assets).bindProxyPackage(eq(runtime), eq(PACKAGE_PATH), any(), anyString(), any());
    verify(registry).deletePackage(
        eq(runtime.id()), eq(NAMESPACE), eq("main"), eq("old"), eq("1-r0"),
        eq("x86_64"), eq("upstream-index-replaced"), any(Instant.class));
    verify(assets).retirePackageProjection(77L);
  }

  @Test
  void refreshDueHonorsMetadataAgeAndRefreshesExpiredProjection() throws Exception {
    Instant now = Instant.parse("2026-08-15T00:00:00Z");
    AlpineRegistryDao.ProxyDistribution fresh = new AlpineRegistryDao.ProxyDistribution(
        runtime.id(), NAMESPACE, "release", Map.of(), true, now, now);
    when(registry.findProxyDistribution(runtime.id(), NAMESPACE))
        .thenReturn(Optional.of(fresh));
    assertFalse(service.refreshDue(runtime, settings(true, true, List.of()), NAMESPACE,
        now.plusSeconds(30)));
    verify(proxy, never()).getMetadataFromUrlUnindexed(any(), anyString(), anyString(), anyBoolean());

    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    KeyPair key = rsa();
    byte[] unsigned = indexArchive(indexRecord(apk));
    byte[] signed = AlpineTestPackage.concatenate(
        signatureArchive(".SIGN.RSA.fixture.rsa.pub", signSha1(unsigned, key)), unsigned);
    when(registry.findProxyDistribution(runtime.id(), NAMESPACE)).thenReturn(Optional.empty());
    when(assets.serve(runtime, INDEX_PATH, false)).thenReturn(response(signed));
    when(assets.serve(runtime, PACKAGE_PATH, false)).thenReturn(response(apk.bytes()));
    assertTrue(service.refreshDue(runtime, settings(
        true, true, List.of("filename=fixture.rsa.pub\n" + publicPem(key))), NAMESPACE, now));
  }

  @Test
  void failsClosedForUnsignedResignAndMismatchedPackageButPassthroughObservationIsBestEffort()
      throws Exception {
    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    byte[] unsigned = indexArchive(indexRecord(apk));
    when(assets.serve(runtime, INDEX_PATH, false)).thenReturn(response(unsigned));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.refreshForResign(runtime, settings(true, true, List.of()), NAMESPACE));

    AlpineRegistryDao.PackageRecord wrong = expected(apk, null, null);
    wrong = new AlpineRegistryDao.PackageRecord(
        wrong.id(), wrong.repositoryId(), wrong.distribution(), wrong.component(),
        wrong.architecture(), "different", wrong.version(), wrong.packageArchitecture(),
        wrong.filename(), wrong.path(), wrong.controlFields(), wrong.identity(), wrong.dataSha256(),
        wrong.sha256(), wrong.size(), wrong.assetId(), wrong.componentId(), wrong.sourceKind(),
        wrong.revision(), wrong.indexedAt(), wrong.createdAt(), wrong.updatedAt());
    when(registry.findPackageByPath(runtime.id(), PACKAGE_PATH)).thenReturn(Optional.of(wrong));
    when(assets.serve(runtime, PACKAGE_PATH, false)).thenReturn(response(apk.bytes()));
    service.observePassthrough(
        runtime, settings(false, false, List.of()),
        new com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser().parse(PACKAGE_PATH));
    verify(assets, never()).bindProxyPackage(any(), eq(PACKAGE_PATH), any(), anyString(), any());
  }

  @Test
  void unknownPackageProjectsIndexBeforeBindingAndRetainsExistingAsset() throws Exception {
    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    AlpineRegistryDao.PackageRecord existing = expected(apk, 41L, 42L);
    when(registry.findPackageByPath(runtime.id(), PACKAGE_PATH))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(registry.findPackage(
        runtime.id(), NAMESPACE, "main", "demo", "1.0-r0", "x86_64"))
        .thenReturn(Optional.of(existing));
    when(assets.serve(runtime, INDEX_PATH, false)).thenReturn(response(indexArchive(indexRecord(apk))));
    when(assets.serve(runtime, PACKAGE_PATH, false)).thenReturn(response(apk.bytes()));

    service.observePassthrough(
        runtime, settings(false, false, List.of()),
        new com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser().parse(PACKAGE_PATH));

    verify(proxy).getMetadataFromUrlUnindexed(
        eq(runtime), eq(INDEX_PATH), anyString(), eq(false));
    verify(assets).bindProxyPackage(eq(runtime), eq(PACKAGE_PATH), any(), anyString(), any());
    verify(assets).retirePackageProjection(41L);
  }

  @Test
  void indexRefreshPreservesVerifiedCachedAssetWhenMetadataChanges() throws Exception {
    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    AlpineRegistryDao.PackageRecord existing = expected(apk, 41L, 42L);
    when(registry.findPackage(
        runtime.id(), NAMESPACE, "main", "demo", "1.0-r0", "x86_64"))
        .thenReturn(Optional.of(existing));
    when(assets.serve(runtime, INDEX_PATH, false)).thenReturn(response(indexArchive(indexRecord(apk))));

    service.observePassthrough(
        runtime, settings(false, false, List.of()),
        new com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser().parse(INDEX_PATH));

    ArgumentCaptor<AlpineRegistryDao.PackageRecord> saved =
        ArgumentCaptor.forClass(AlpineRegistryDao.PackageRecord.class);
    verify(registry).savePackage(saved.capture());
    assertEquals(41L, saved.getValue().assetId());
    assertEquals(42L, saved.getValue().componentId());
    assertEquals(AlpineTestPackage.sha256(apk.bytes()), saved.getValue().sha256());
  }

  @Test
  void invalidOrUnclosableCachedPackageFailsAsBadUpstream() throws Exception {
    AlpineTestPackage.Fixture apk = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    KeyPair key = rsa();
    byte[] unsigned = indexArchive(indexRecord(apk));
    byte[] signed = AlpineTestPackage.concatenate(
        signatureArchive(".SIGN.RSA256.fixture.rsa.pub", sign(unsigned, key)), unsigned);
    AlpineRepositorySettings.Settings trusted = settings(
        true, true, List.of("filename=fixture.rsa.pub\n" + publicPem(key)));
    when(assets.serve(runtime, INDEX_PATH, false)).thenAnswer(ignored -> response(signed));
    when(assets.serve(runtime, PACKAGE_PATH, false)).thenReturn(response(new byte[] {1, 2, 3}));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.refreshForResign(runtime, trusted, NAMESPACE));

    InputStreamWithFailingClose failingClose = new InputStreamWithFailingClose(apk.bytes());
    when(assets.serve(runtime, PACKAGE_PATH, false)).thenReturn(MavenResponse.ok(
        failingClose, apk.bytes().length, "application/octet-stream", null, null));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.refreshForResign(runtime, trusted, NAMESPACE));
    assertTrue(failingClose.closeAttempted);
  }

  @Test
  void resignRejectsOversizedAndOverflowingPackageSetsBeforeFetch() throws Exception {
    KeyPair key = rsa();
    AlpineRepositorySettings.Settings trusted = settings(
        true, true, List.of("filename=fixture.rsa.pub\n" + publicPem(key)));
    String huge = indexRecord("huge", "1-r0", Long.toString(50L * 1024 * 1024 * 1024 + 1));
    byte[] hugeUnsigned = indexArchive(huge);
    when(assets.serve(runtime, INDEX_PATH, false)).thenReturn(response(AlpineTestPackage.concatenate(
        signatureArchive(".SIGN.RSA256.fixture.rsa.pub", sign(hugeUnsigned, key)), hugeUnsigned)));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.refreshForResign(runtime, trusted, NAMESPACE));

    String overflow = indexRecord("one", "1-r0", Long.toString(Long.MAX_VALUE))
        + indexRecord("two", "1-r0", Long.toString(Long.MAX_VALUE));
    byte[] overflowUnsigned = indexArchive(overflow);
    when(assets.serve(runtime, INDEX_PATH, false)).thenReturn(response(AlpineTestPackage.concatenate(
        signatureArchive(".SIGN.RSA256.fixture.rsa.pub", sign(overflowUnsigned, key)),
        overflowUnsigned)));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.refreshForResign(runtime, trusted, NAMESPACE));
    verify(proxy, never()).getPinnedAssetFromUrlUnindexed(
        any(), anyString(), anyString(), anyBoolean());
  }

  private static AlpineRepositorySettings.Settings settings(
      boolean resign, boolean verify, List<String> keys) {
    return new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), resign, verify, true, "fixture.rsa.pub", "RSA",
        "test", keys);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        2L, "alpine-proxy", RepositoryFormat.ALPINE, RepositoryType.PROXY, "alpine-proxy", true,
        1L, "ALLOW", null, null, true, "https://example.invalid/alpine/", 60, 60, true,
        null, List.of());
  }

  private static AlpineRegistryDao.PackageRecord expected(
      AlpineTestPackage.Fixture fixture, Long assetId, Long componentId) {
    return new AlpineRegistryDao.PackageRecord(
        10L, 2L, NAMESPACE, "main", "x86_64", "demo", "1.0-r0", "x86_64",
        "demo-1.0-r0.apk", PACKAGE_PATH,
        Map.of("C", AlpineChecksums.v2Identity(fixture.controlMember()), "P", "demo", "V", "1.0-r0",
            "A", "x86_64", "S", Long.toString(fixture.bytes().length), "I", "7"),
        AlpineChecksums.v2Identity(fixture.controlMember()),
        AlpineTestPackage.sha256(fixture.dataMember()), AlpineTestPackage.sha256(fixture.bytes()),
        fixture.bytes().length, assetId, componentId, AlpineRegistryDao.SOURCE_PROXY, 1L,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AlpineRegistryDao.PackageRecord withId(
      AlpineRegistryDao.PackageRecord row, long id) {
    return new AlpineRegistryDao.PackageRecord(
        id, row.repositoryId(), row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version(), row.packageArchitecture(), row.filename(), row.path(),
        row.controlFields(), row.identity(), row.dataSha256(), row.sha256(), row.size(),
        row.assetId(), row.componentId(), row.sourceKind(), row.revision(), row.indexedAt(),
        row.createdAt(), row.updatedAt());
  }

  private static AssetRecord asset() {
    return new AssetRecord(
        70L, 2L, 80L, 90L, RepositoryFormat.ALPINE, PACKAGE_PATH, new byte[32],
        "demo-1.0-r0.apk", "package", "application/vnd.alpine.apk", 100L, null,
        Instant.EPOCH, Map.of());
  }

  private static MavenResponse response(byte[] bytes) {
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream", null, null);
  }

  private static String indexRecord(AlpineTestPackage.Fixture fixture) {
    return """
        C:%s
        P:demo
        V:1.0-r0
        A:x86_64
        S:%d
        I:7
        T:fixture
        D:musl>=1
        p:cmd:demo=1

        """.formatted(
        AlpineChecksums.v2Identity(fixture.controlMember()), fixture.bytes().length);
  }

  private static String indexRecord(String name, String version, String size) {
    return """
        C:Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=
        P:%s
        V:%s
        A:x86_64
        S:%s
        I:7

        """.formatted(name, version, size);
  }

  private static byte[] indexArchive(String record) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(result);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      write(tar, "DESCRIPTION", "fixture".getBytes(StandardCharsets.UTF_8));
      write(tar, "APKINDEX", record.getBytes(StandardCharsets.UTF_8));
      tar.finish();
    }
    return result.toByteArray();
  }

  private static byte[] signatureArchive(String name, byte[] signature) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(result);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      write(tar, name, signature);
      tar.finish();
    }
    return result.toByteArray();
  }

  private static void write(TarArchiveOutputStream tar, String name, byte[] bytes)
      throws Exception {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  private static KeyPair rsa() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static byte[] sign(byte[] bytes, KeyPair key) throws Exception {
    return sign(bytes, key, "SHA256withRSA");
  }

  private static byte[] signSha1(byte[] bytes, KeyPair key) throws Exception {
    return sign(bytes, key, "SHA1withRSA");
  }

  private static byte[] sign(byte[] bytes, KeyPair key, String algorithm) throws Exception {
    Signature signature = Signature.getInstance(algorithm);
    signature.initSign(key.getPrivate());
    signature.update(bytes);
    return signature.sign();
  }

  private static String publicPem(KeyPair key) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'})
        .encodeToString(key.getPublic().getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
  }

  private static final class InputStreamWithFailingClose extends FilterInputStream {
    private boolean closeAttempted;

    private InputStreamWithFailingClose(byte[] bytes) {
      super(new ByteArrayInputStream(bytes));
    }

    @Override
    public void close() throws IOException {
      closeAttempted = true;
      throw new IOException("fixture close failure");
    }
  }
}
