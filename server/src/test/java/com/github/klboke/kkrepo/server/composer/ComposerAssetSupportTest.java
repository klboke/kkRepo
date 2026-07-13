package com.github.klboke.kkrepo.server.composer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposerAssetSupportTest {

  @Test
  void storesHostedAssetWithAuthenticatedUploader() {
    RawHostedService rawHosted = mock(RawHostedService.class);
    ComposerAssetSupport assets = new ComposerAssetSupport(
        mock(AssetDao.class), mock(BlobStorageRegistry.class), rawHosted);
    RepositoryRuntime runtime = ComposerHostedServiceTest.runtime("hosted",
        com.github.klboke.kkrepo.core.RepositoryType.HOSTED, java.util.List.of());
    Map<String, Object> attributes = Map.of(
        "composerPackage", "company/example",
        "composerVersion", "1.2.3");

    assets.store(
        runtime,
        "company/example/1.2.3/company-example-1.2.3.zip",
        new ByteArrayInputStream(new byte[] {1, 2, 3}),
        "application/zip",
        attributes,
        "admin",
        "127.0.0.1");

    verify(rawHosted).putInternal(
        eq(runtime),
        eq("company/example/1.2.3/company-example-1.2.3.zip"),
        any(ByteArrayInputStream.class),
        eq("application/zip"),
        eq(attributes),
        eq("admin"),
        eq("127.0.0.1"));
  }
}
