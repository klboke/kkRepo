package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class ComposerComponentWriterTest {
  private static final String DIST_PATH =
      "company/example/1.0.0/company-example-1.0.0.zip";

  @Test
  void promotesAUniqueStagingAssetWithoutMutatingIt() {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    when(components.insert(any())).thenReturn(42L);
    when(assets.tryInsertAsset(any())).thenReturn(OptionalLong.of(99L));
    ComposerComponentWriter writer = new ComposerComponentWriter(components, assets, browse, cache);
    AssetRecord staging = stagingAsset();

    assertEquals(42L, writer.bindHostedArchive(
        ComposerHostedServiceTest.runtime("hosted", RepositoryType.HOSTED, List.of()),
        staging, "company/example", "1.0.0", Map.of(), DIST_PATH, "admin", "127.0.0.1"));

    ArgumentCaptor<AssetRecord> promoted = ArgumentCaptor.forClass(AssetRecord.class);
    verify(assets).tryInsertAsset(promoted.capture());
    assertEquals(DIST_PATH, promoted.getValue().path());
    assertEquals(42L, promoted.getValue().componentId());
    assertEquals(7L, promoted.getValue().assetBlobId());
    verify(assets, never()).updateAssetBlobBindingAndMetadata(
        anyLong(), any(), anyLong(), anyString(), anyString(), anyLong(), any(), anyMap());
    verify(browse).upsertPathAncestors(1L, DIST_PATH, 99L, 42L);
  }

  @Test
  void duplicateCoordinateDoesNotPublishOrDeleteAnotherUploadsAsset() {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    when(components.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
    ComposerComponentWriter writer = new ComposerComponentWriter(
        components, assets, mock(BrowseNodeDao.class), mock(AssetMetadataCache.class));

    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> writer.bindHostedArchive(
        ComposerHostedServiceTest.runtime("hosted", RepositoryType.HOSTED, List.of()),
        stagingAsset(), "company/example", "1.0.0", Map.of(), DIST_PATH, "admin", "127.0.0.1"));

    verify(assets, never()).tryInsertAsset(any());
    verify(assets, never()).deleteAssetById(anyLong());
  }

  private static AssetRecord stagingAsset() {
    return new AssetRecord(
        5L, 1L, 6L, 7L, RepositoryFormat.COMPOSER,
        "_composer/uploads/request/package.zip", new byte[32], "package.zip", "composer",
        "application/zip", 123L, null, Instant.parse("2026-01-01T00:00:00Z"), Map.of());
  }
}
