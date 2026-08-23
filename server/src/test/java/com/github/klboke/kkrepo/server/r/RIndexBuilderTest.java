package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.protocol.r.RPackageIndex;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;

class RIndexBuilderTest {
  @Test
  void buildsByteStableLatestOnlyPackagesSnapshotWithBoundedSource() throws Exception {
    RRegistryDao registry = mock(RRegistryDao.class);
    RAssetSupport assets = mock(RAssetSupport.class);
    RIndexBuilder builder = new RIndexBuilder(registry, assets);
    Instant created = Instant.parse("2026-08-21T00:00:00Z");
    RRegistryDao.SuiteState state = new RRegistryDao.SuiteState(
        7L, "src/contrib", 9L, created, 8L, 0, null, null, null, created);
    AtomicReference<byte[]> first = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    doAnswer(invocation -> {
      path.set(invocation.getArgument(1));
      first.set(Files.readAllBytes(invocation.getArgument(2)));
      return null;
    }).when(assets).storeGeneratedFile(eq(runtime()), any(), any(), any());
    RRegistryDao.PackageRecord alpha = row("alpha", "3.0", 3L);
    RRegistryDao.PackageRecord selectedByRVersion = row("demo", "0.75", 1L);
    RRegistryDao.PackageRecord lexicalTie = row(
        "demo", "0.75", 4L, "src/contrib/z-demo_0.75.tar.gz");
    RRegistryDao.PackageRecord lexicallyLarger = row("demo", "0.9", 2L);

    RIndexBuilder.BuiltSnapshot built = builder.build(
        runtime(), state,
        visitor -> List.of(alpha, lexicalTie, selectedByRVersion, lexicallyLarger)
            .forEach(visitor));
    byte[] firstBytes = first.get();
    AtomicReference<byte[]> second = new AtomicReference<>();
    doAnswer(invocation -> {
      second.set(Files.readAllBytes(invocation.getArgument(2)));
      return null;
    }).when(assets).storeGeneratedFile(eq(runtime()), eq(path.get()), any(), any());
    builder.build(
        runtime(), state,
        visitor -> List.of(alpha, lexicalTie, selectedByRVersion, lexicallyLarger)
            .forEach(visitor));

    assertTrue(path.get().startsWith(".r/snapshots/"));
    assertTrue(path.get().endsWith("/9/PACKAGES.gz"));
    assertEquals(path.get(), built.manifest().get("src/contrib/PACKAGES.gz"));
    assertEquals(64, built.indexSha256().length());
    assertEquals(firstBytes.length, built.size());
    assertEquals(created, built.createdAt());
    assertArrayEquals(firstBytes, second.get());
    try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(
        new ByteArrayInputStream(firstBytes))) {
      var packages = RPackageIndex.parse(gzip.readAllBytes());
      assertEquals(List.of("alpha", "demo"),
          packages.stream().map(item -> item.packageName()).toList());
      assertEquals("0.75", packages.get(1).version());
    }
  }

  @Test
  void defaultBuildUsesForwardDaoCursor() {
    RRegistryDao registry = mock(RRegistryDao.class);
    RAssetSupport assets = mock(RAssetSupport.class);
    RIndexBuilder builder = new RIndexBuilder(registry, assets);
    RRegistryDao.SuiteState state = new RRegistryDao.SuiteState(
        7L, "src/contrib", 1L, Instant.EPOCH, 0L, 0, null, null, null, Instant.EPOCH);

    builder.build(runtime(), state);

    verify(registry).visitPackages(
        eq(7L), eq("src/contrib"), eq("source"), eq("source"), any());
  }

  @Test
  void convertsStreamingWriteFailuresIntoSnapshotBuildFailures() {
    RIndexBuilder builder = new RIndexBuilder(
        mock(RRegistryDao.class), mock(RAssetSupport.class));
    RRegistryDao.SuiteState state = new RRegistryDao.SuiteState(
        7L, "src/contrib", 1L, Instant.EPOCH, 0L, 0, null, null, null, Instant.EPOCH);

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> builder.build(runtime(), state, visitor -> {
          throw new UncheckedIOException(new IOException("fixture failure"));
        }));

    assertTrue(failure.getMessage().contains("Failed to build R PACKAGES.gz snapshot"));
  }

  private static RRegistryDao.PackageRecord row(String name, String version, long id) {
    String filename = name + "_" + version + ".tar.gz";
    return row(name, version, id, "src/contrib/" + filename);
  }

  private static RRegistryDao.PackageRecord row(
      String name, String version, long id, String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1);
    return new RRegistryDao.PackageRecord(
        id, 7L, "src/contrib", "source", "source", name, version,
        ("r1|" + version).getBytes(StandardCharsets.US_ASCII), "source", filename,
        path,
        Map.of("Package", name, "Version", version, "License", "MIT"),
        "%032x".formatted(id), "a".repeat(64), "b".repeat(64), 12L,
        id, id, RRegistryDao.SOURCE_HOSTED, id, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        7L, "r-hosted", RepositoryFormat.R, RepositoryType.HOSTED, "r-hosted", true,
        1L, "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }
}
