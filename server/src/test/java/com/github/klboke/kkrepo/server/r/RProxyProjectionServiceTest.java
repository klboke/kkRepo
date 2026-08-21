package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class RProxyProjectionServiceTest {
  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final RawProxyService proxy = mock(RawProxyService.class);
  private final RAssetSupport assets = mock(RAssetSupport.class);
  private final RProxyProjectionService service = new RProxyProjectionService(
      registry,
      proxy,
      assets,
      mock(RSourcePackageInspector.class),
      mock(RComponentFactory.class));

  @Test
  void groupPreparationProjectsOnceAndSkipsVerifiedUnchangedIndex() throws Exception {
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, "r-proxy", RepositoryFormat.R, RepositoryType.PROXY, "r-proxy", true,
        1L, null, null, null, true, "https://example.invalid/", 60, 60, true,
        null, List.of());
    byte[] compressed = gzip("Package: demo\nVersion: 1.0.0\nMD5sum: " + "a".repeat(32) + "\n\n");
    String releaseIdentity = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(compressed));
    when(proxy.getMetadataFromUrlUnindexed(
        any(), anyString(), anyString(), anyBoolean())).thenReturn(MavenResponse.noBody(200));
    when(assets.serve(runtime, "src/contrib/PACKAGES.gz", false)).thenReturn(
        MavenResponse.ok(
            new ByteArrayInputStream(compressed), compressed.length, "application/x-gzip",
            null, Instant.EPOCH));
    when(registry.findProxyDistribution(runtime.id(), "src/contrib")).thenReturn(Optional.of(
        new RRegistryDao.ProxyDistribution(
            runtime.id(), "src/contrib", releaseIdentity, Map.of(), true,
            Instant.EPOCH, Instant.EPOCH)));
    when(registry.findSuite(runtime.id(), "src/contrib")).thenReturn(Optional.of(
        new RRegistryDao.SuiteState(
            runtime.id(), "src/contrib", 9L, Instant.EPOCH, 9L, 1,
            Instant.EPOCH, null, null, Instant.EPOCH)));

    assertEquals(9L, service.prepareGroupMember(runtime, Instant.EPOCH));

    verify(proxy).getMetadataFromUrlUnindexed(
        runtime, "src/contrib/PACKAGES.gz",
        "https://example.invalid/src/contrib/PACKAGES.gz", false);
    verify(assets).serve(runtime, "src/contrib/PACKAGES.gz", false);
    verify(registry, never()).savePackage(any());
    verify(registry, never()).observeProxyDistribution(
        anyLong(), anyString(), anyString(), any(), anyBoolean(), any());
  }

  private static byte[] gzip(String value) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return output.toByteArray();
  }
}
