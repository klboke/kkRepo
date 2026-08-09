package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AptMetadataBuilderTest {

  @Test
  void buildsDeterministicCompressedIndicesByHashReleaseAndSignatures() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    AptAssetSupport assets = mock(AptAssetSupport.class);
    AptSigningService signing = mock(AptSigningService.class);
    RepositoryRuntime runtime = runtime();
    AptRepositorySettings.Settings settings = new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, true, 7,
        "Example", "Example packages");
    AptRegistryDao.SuiteState state = new AptRegistryDao.SuiteState(
        runtime.id(), "stable", 4, Instant.parse("2026-08-08T00:00:00Z"),
        3, 1, null, null, null, Instant.EPOCH);
    AptSigningService.SigningMaterial key = new AptSigningService.SigningMaterial(
        2, "key", "fingerprint", "public", "private", "");
    when(registry.listArchitectures(runtime.id(), "stable", "main"))
        .thenReturn(List.of("all", "amd64"));
    doAnswer(invocation -> {
      String architecture = invocation.getArgument(3);
      Consumer<AptRegistryDao.PackageRecord> visitor = invocation.getArgument(4);
      List<AptRegistryDao.PackageRecord> records = "all".equals(architecture)
          ? List.of(record("common", "1.0", "all", "a".repeat(64)))
          : List.of(
              record("demo", "2.0", "amd64", "b".repeat(64)),
              record("demo", "1.0~rc1", "amd64", "c".repeat(64)));
      records.forEach(visitor);
      return null;
    }).when(registry).visitPackages(
        eq(runtime.id()), eq("stable"), eq("main"), anyString(), any());
    AtomicReference<String> amd64Packages = new AtomicReference<>();
    doAnswer(invocation -> {
      String hiddenPath = invocation.getArgument(1);
      Path file = invocation.getArgument(2);
      if (hiddenPath.endsWith("binary-amd64/Packages")) {
        amd64Packages.set(Files.readString(file, StandardCharsets.UTF_8));
      }
      return null;
    }).when(assets).storeGeneratedFile(
        eq(runtime), anyString(), any(), anyString(), any());
    when(signing.sign(any(), eq(key), eq(state.desiredAt())))
        .thenReturn(new AptSigningService.SignedRelease(
            "inrelease".getBytes(StandardCharsets.UTF_8),
            "signature".getBytes(StandardCharsets.UTF_8)));

    AptMetadataBuilder.BuiltSnapshot built =
        new AptMetadataBuilder(registry, assets, signing).build(runtime, settings, state, key);

    assertEquals(2, built.signingKeyRevision());
    assertEquals(state.desiredAt(), built.createdAt());
    assertEquals(19, built.manifest().size());
    assertTrue(built.manifest().containsKey("dists/stable/Release"));
    assertTrue(built.manifest().keySet().stream().anyMatch(path -> path.contains("by-hash/SHA256")));
    verify(assets, times(8)).storeGeneratedFile(
        eq(runtime), anyString(), any(), anyString(), any());
    verify(assets, times(3)).storeGenerated(
        eq(runtime), anyString(), any(), anyString(), any());
    assertTrue(amd64Packages.get().indexOf("Version: 1.0~rc1")
        < amd64Packages.get().indexOf("Version: 2.0"));
    ArgumentCaptor<byte[]> release = ArgumentCaptor.forClass(byte[].class);
    verify(signing).sign(release.capture(), eq(key), eq(state.desiredAt()));
    String releaseText = new String(release.getValue(), StandardCharsets.UTF_8);
    assertTrue(releaseText.contains("Origin: Example"));
    assertTrue(releaseText.contains("Valid-Until:"));
    assertTrue(releaseText.contains("binary-all/Packages.xz"));
    assertTrue(releaseText.contains("binary-amd64/Packages.gz"));
    assertEquals(64, built.releaseSha256().length());
  }

  @Test
  void usesCurrentTimeAndConfiguredArchitecturesWhenAllIsAbsent() {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    AptAssetSupport assets = mock(AptAssetSupport.class);
    AptSigningService signing = mock(AptSigningService.class);
    RepositoryRuntime runtime = runtime();
    AptRepositorySettings.Settings settings = new AptRepositorySettings.Settings(
        "testing", "contrib", List.of("arm64"), false, true, true, null,
        "kkRepo", "kkRepo");
    AptRegistryDao.SuiteState state = new AptRegistryDao.SuiteState(
        runtime.id(), "testing", 1, null, 0, 0, null, null, null, Instant.EPOCH);
    AptSigningService.SigningMaterial key = new AptSigningService.SigningMaterial(
        1, "key", "fingerprint", "public", "private", "");
    when(registry.listArchitectures(runtime.id(), "testing", "contrib"))
        .thenReturn(List.of());
    when(signing.sign(any(), eq(key), any()))
        .thenReturn(new AptSigningService.SignedRelease(new byte[0], new byte[0]));

    AptMetadataBuilder.BuiltSnapshot built =
        new AptMetadataBuilder(registry, assets, signing).build(runtime, settings, state, key);
    assertTrue(!built.createdAt().isBefore(Instant.now().minusSeconds(10)));
    assertEquals(11, built.manifest().size());
  }

  private static AptRegistryDao.PackageRecord record(
      String name, String version, String architecture, String sha256) {
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("Package", name);
    fields.put("Version", version);
    fields.put("Architecture", architecture);
    fields.put("Maintainer", "Demo <demo@example.com>");
    fields.put("Description", "demo package");
    fields.put("Filename", "stale");
    return new AptRegistryDao.PackageRecord(
        null, 1, "stable", "main", architecture, name, version, name,
        name + "_" + version + "_" + architecture + ".deb",
        "pool/" + name.substring(0, 1) + "/" + name + "/" + name + ".deb",
        Map.copyOf(fields), "d".repeat(32), "e".repeat(40), sha256, 123,
        1L, 2L, AptRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1, "apt", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted", true, 1L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
  }
}
