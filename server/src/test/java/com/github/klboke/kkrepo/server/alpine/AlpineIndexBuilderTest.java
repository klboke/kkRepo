package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineMediaTypes;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AlpineIndexBuilderTest {

  @Test
  void publishesRepositoryArchitectureWhilePreservingNoarchPackageMetadata() {
    AlpineRegistryDao.PackageRecord row = new AlpineRegistryDao.PackageRecord(
        1L,
        2L,
        "v3.23",
        "main",
        "x86_64",
        "demo",
        "1.0.0-r0",
        "noarch",
        "demo-1.0.0-r0.apk",
        "v3.23/main/x86_64/demo-1.0.0-r0.apk",
        Map.of("A", "noarch", "I", "42"),
        "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "data-sha256",
        "blob-sha256",
        123L,
        3L,
        4L,
        AlpineRegistryDao.SOURCE_HOSTED,
        1L,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH);

    AlpineIndexRecord record = AlpineIndexBuilder.indexRecord(row);

    assertEquals("x86_64", record.architecture());
    assertEquals("noarch", row.packageArchitecture());
  }

  @Test
  void buildsDeterministicImmutableSignedSnapshotAndSortsPackageFamilies() throws Exception {
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    AlpineAssetSupport assets = mock(AlpineAssetSupport.class);
    AlpineSigningService signing = mock(AlpineSigningService.class);
    AlpineIndexBuilder builder = new AlpineIndexBuilder(registry, assets, signing);
    AlpineSigningService.SigningMaterial material = new AlpineSigningService.SigningMaterial(
        4, "fixture.rsa.pub", "fingerprint", "public", null, null,
        AlpineSignature.Type.RSA256);
    when(signing.sign(any(java.nio.file.Path.class), eq(material)))
        .thenReturn(new AlpineSignature(
            AlpineSignature.Type.RSA256, "fixture.rsa.pub", new byte[] {1, 2, 3}));
    AtomicReference<byte[]> stored = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    doAnswer(invocation -> {
      path.set(invocation.getArgument(1));
      stored.set(Files.readAllBytes(invocation.getArgument(2)));
      return null;
    }).when(assets).storeGeneratedFile(
        any(), any(), any(), eq(AlpineMediaTypes.APK_INDEX), any());
    Instant created = Instant.parse("2026-08-15T00:00:00Z");
    AlpineRegistryDao.SuiteState state = new AlpineRegistryDao.SuiteState(
        7L, "v3.20/main/x86_64", 9L, created, 8L, 3, null, null, null, created);
    AlpineRepositorySettings.Settings settings = new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), true, false, true, "fixture.rsa.pub", "RSA256",
        "kkRepo fixture", List.of());
    String identity = "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    AlpineRegistryDao.PackageRecord newer = row("demo", "2-r0", identity, 20L);
    AlpineRegistryDao.PackageRecord older = row("demo", "1-r0", identity, 10L);
    AlpineRegistryDao.PackageRecord other = row("alpha", "3-r0", identity, 30L);

    AlpineIndexBuilder.BuiltSnapshot built = builder.build(
        runtime(), settings, state, material,
        visitor -> List.of(other, newer, older).forEach(visitor));

    assertTrue(path.get().startsWith(".alpine/snapshots/"));
    assertTrue(path.get().endsWith("/9/APKINDEX.tar.gz"));
    assertEquals(path.get(), built.manifest().get("v3.20/main/x86_64/APKINDEX.tar.gz"));
    assertEquals(64, built.indexSha256().length());
    assertEquals(stored.get().length, built.size());
    assertEquals(4, built.signingKeyRevision());
    assertEquals(created, built.createdAt());
    AlpineIndexArchive.Parsed parsed = AlpineIndexArchive.read(
        new ByteArrayInputStream(stored.get()), List.of(), false);
    assertEquals(List.of("alpha", "demo", "demo"),
        parsed.records().stream().map(AlpineIndexRecord::packageName).toList());
    assertEquals(List.of("1-r0", "2-r0"), parsed.records().stream()
        .filter(record -> "demo".equals(record.packageName()))
        .map(AlpineIndexRecord::version).toList());
    verify(assets).storeGeneratedFile(
        eq(runtime()), eq(path.get()), any(), eq(AlpineMediaTypes.APK_INDEX), any());
  }

  @Test
  void defaultBuildStreamsDaoAndRejectsMalformedNamespace() {
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    AlpineIndexBuilder builder = new AlpineIndexBuilder(
        registry, mock(AlpineAssetSupport.class), mock(AlpineSigningService.class));
    AlpineRegistryDao.SuiteState malformed = new AlpineRegistryDao.SuiteState(
        7L, "bad", 1L, Instant.EPOCH, 0L, 0, null, null, null, Instant.EPOCH);
    assertThrows(IllegalArgumentException.class, () -> builder.build(
        runtime(), settings(), malformed, mock(AlpineSigningService.SigningMaterial.class)));
  }

  @Test
  void defaultBuildStreamsTheNamespaceDaoAndWrapsIndexIoFailures() {
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    AlpineAssetSupport assets = mock(AlpineAssetSupport.class);
    AlpineSigningService signing = mock(AlpineSigningService.class);
    AlpineIndexBuilder builder = new AlpineIndexBuilder(registry, assets, signing);
    AlpineSigningService.SigningMaterial material = new AlpineSigningService.SigningMaterial(
        1, "fixture.rsa.pub", "fingerprint", "public", null, null, AlpineSignature.Type.RSA);
    when(signing.sign(any(java.nio.file.Path.class), eq(material)))
        .thenReturn(new AlpineSignature(
            AlpineSignature.Type.RSA, "fixture.rsa.pub", new byte[] {1}));
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      java.util.function.Consumer<AlpineRegistryDao.PackageRecord> visitor =
          invocation.getArgument(4);
      visitor.accept(row("demo", "1-r0", "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", 10L));
      return null;
    }).when(registry).visitPackages(eq(7L), eq("v3.20/main/x86_64"), eq("main"),
        eq("x86_64"), any());
    AlpineRegistryDao.SuiteState state = new AlpineRegistryDao.SuiteState(
        7L, "v3.20/main/x86_64", 1L, null, 0L, 0, null, null, null, Instant.EPOCH);

    AlpineIndexBuilder.BuiltSnapshot built = builder.build(
        runtime(), settings(), state, material);

    assertEquals(1, built.signingKeyRevision());
    verify(registry).visitPackages(
        eq(7L), eq("v3.20/main/x86_64"), eq("main"), eq("x86_64"), any());

    assertThrows(IllegalStateException.class, () -> builder.build(
        runtime(), settings(), state, material,
        visitor -> {
          throw new UncheckedIOException(new IOException("fixture index write failure"));
        }));
  }

  private static AlpineRepositorySettings.Settings settings() {
    return new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), true, false, true, "key.rsa.pub", "RSA", "", List.of());
  }

  private static AlpineRegistryDao.PackageRecord row(
      String name, String version, String identity, long size) {
    return new AlpineRegistryDao.PackageRecord(
        null, 7L, "v3.20/main/x86_64", "main", "x86_64", name, version, "x86_64",
        name + "-" + version + ".apk", "v3.20/main/x86_64/" + name + "-" + version + ".apk",
        Map.of("I", "7", "T", name + " package", "Z", "custom"),
        identity, "a".repeat(64),
        "b".repeat(64), size, 1L, 2L, AlpineRegistryDao.SOURCE_HOSTED, 1L,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        7L, "alpine", RepositoryFormat.ALPINE, RepositoryType.HOSTED, "alpine-hosted", true,
        1L, "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }
}
