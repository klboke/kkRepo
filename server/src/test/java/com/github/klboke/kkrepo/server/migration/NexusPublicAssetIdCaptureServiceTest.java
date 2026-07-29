package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusPublicAsset;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.server.management.AssetPublicIdService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NexusPublicAssetIdCaptureServiceTest {
  private final AssetDao assetDao = mock(AssetDao.class);
  private final AssetPublicIdService publicIdService = mock(AssetPublicIdService.class);
  private final NexusRestClient client = mock(NexusRestClient.class);
  private final NexusPublicAssetIdCaptureService service =
      new NexusPublicAssetIdCaptureService(assetDao, publicIdService);

  @Test
  void verifiedExactAssetRegistersNexusAlias() throws Exception {
    RepositoryDataMigrationAssetRecord source = source("tools/setup.exe");
    when(assetDao.findAssetWithBlobById(12L)).thenReturn(Optional.of(target(source, "abc123")));
    when(client.findPublicAssets(
        "windows-components", source.sourcePath(), "raw", null, "setup.exe", null)).thenReturn(List.of(
        new NexusPublicAsset(
            "public-id", "windows-components", source.sourcePath(), "ABC123", "http://download")));

    service.capture(
        client, "http://nexus.example/", 99L, "windows-components", 7L, source, 12L);

    verify(publicIdService).registerNexusAlias(
        "public-id", "windows-components", 7L, 12L, "http://nexus.example/", 99L);
  }

  @Test
  void exportedPublicAssetRegistersWithoutSearchRequest() throws Exception {
    RepositoryDataMigrationAssetRecord source = source(
        "gems/demo.gem",
        Map.of("nexusPublicAssetId", "public-id", "nexusSha1", "ABC123"));
    when(assetDao.findAssetWithBlobById(12L)).thenReturn(Optional.of(target(source, "abc123")));

    service.capture(
        client, "http://nexus.example/", 99L, "windows-components", 7L, source, 12L);

    verifyNoInteractions(client);
    verify(publicIdService).registerNexusAlias(
        "public-id", "windows-components", 7L, 12L, "http://nexus.example/", 99L);
  }

  @Test
  void checksumMismatchFailsBeforeRegistration() throws Exception {
    RepositoryDataMigrationAssetRecord source = source("tools/setup.exe");
    when(assetDao.findAssetWithBlobById(12L)).thenReturn(Optional.of(target(source, "abc123")));
    when(client.findPublicAssets(
        "windows-components", source.sourcePath(), "raw", null, "setup.exe", null)).thenReturn(List.of(
        new NexusPublicAsset(
            "public-id", "windows-components", source.sourcePath(), "different", null)));

    assertThrows(IOException.class, () -> service.capture(
        client, "http://nexus.example/", 99L, "windows-components", 7L, source, 12L));
    verifyNoInteractions(publicIdService);
  }

  @Test
  void prefetchedPublicAssetRegistersWithoutAnotherNexusSearch() throws Exception {
    RepositoryDataMigrationAssetRecord source = source("packages/demo.whl");
    NexusPublicAsset nexus = new NexusPublicAsset(
        "public-id", "pypi-qunhe", source.sourcePath(), "ABC123", null);
    when(assetDao.findAssetWithBlobById(12L)).thenReturn(Optional.of(target(source, "abc123")));

    service.capture(
        "http://nexus.example/", 99L, "pypi-qunhe", 7L, source.sourcePath(), nexus, 12L);

    verifyNoInteractions(client);
    verify(publicIdService).registerNexusAlias(
        "public-id", "pypi-qunhe", 7L, 12L, "http://nexus.example/", 99L);
  }

  @Test
  void prefetchedPublicAssetMustBelongToExpectedRepositoryAndPath() {
    RepositoryDataMigrationAssetRecord source = source("gems/demo.gem");
    NexusPublicAsset nexus = new NexusPublicAsset(
        "public-id", "other-repository", source.sourcePath(), "abc123", null);

    assertThrows(IOException.class, () -> service.capture(
        "http://nexus.example/", 99L, "gem-qunhe", 7L, source.sourcePath(), nexus, 12L),
        "a Nexus asset from another repository must not be registered");
    verifyNoInteractions(assetDao, publicIdService, client);
  }

  private static RepositoryDataMigrationAssetRecord source(String path) {
    return source(path, Map.of());
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path,
      Map<String, Object> metadata) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "#1:2", null, path, new byte[32], RepositoryFormat.RAW,
        null, "setup.exe", null, "asset", "application/octet-stream", 4L,
        null, null, null, null, null, null, null,
        "migrated", 0, null, null, null, 12L, 112L, null, metadata, Instant.EPOCH);
  }

  private static AssetWithBlob target(RepositoryDataMigrationAssetRecord source, String sha1) {
    AssetRecord asset = new AssetRecord(
        12L, 7L, null, 112L, RepositoryFormat.RAW, source.sourcePath(), new byte[32],
        "setup.exe", "asset", "application/octet-stream", 4L,
        null, Instant.EPOCH, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        112L, 1L, "blob", new byte[32], "object", new byte[32], sha1, "sha256", "md5",
        4L, "application/octet-stream", "migration", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
    return new AssetWithBlob(asset, blob);
  }
}
