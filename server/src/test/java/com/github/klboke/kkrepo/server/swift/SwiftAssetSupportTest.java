package com.github.klboke.kkrepo.server.swift;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import org.junit.jupiter.api.Test;

class SwiftAssetSupportTest {
  @Test
  void delegatesUncachedPolicyMetadata() {
    ArtifactDownloadPolicy policy = mock(ArtifactDownloadPolicy.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    SwiftAssetSupport support = new SwiftAssetSupport(
        mock(AssetDao.class),
        mock(BlobStorageRegistry.class),
        mock(RawHostedService.class),
        policy);

    support.beforeUncachedRead(
        runtime, "acme/demo/1.2.3.zip", "source-archive", "application/zip", 42L);

    verify(policy).beforeUncachedRead(
        runtime, "acme/demo/1.2.3.zip", "source-archive", "application/zip", 42L);
  }
}
