package com.github.klboke.kkrepo.server.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RawAssetWriterTest {
  @Test
  void deleteMissingAssetIsNoOp() {
    Fixture fixture = fixture();

    assertEquals(0, fixture.writer.deleteAsset(runtime(), mock(BlobStorage.class), "missing"));

    verifyNoInteractions(fixture.componentDao, fixture.browseNodeDao, fixture.cache);
  }

  @Test
  void deletesAssetMetadataAndUnreferencedBlob() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime();
    AssetRecord asset = new AssetRecord(
        11L, runtime.id(), 22L, 33L, RepositoryFormat.RAW, "docs/file.txt", null,
        "file.txt", "raw", "text/plain", 4L, null, Instant.EPOCH, Map.of());
    when(fixture.assetDao.findAssetByPath(runtime.id(), "docs/file.txt"))
        .thenReturn(Optional.of(asset));

    assertEquals(1, fixture.writer.deleteAsset(
        runtime, mock(BlobStorage.class), "docs/file.txt"));

    verify(fixture.browseNodeDao).deleteByAssetId(11L);
    verify(fixture.assetDao).deleteAssetById(11L);
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(33L, "asset unlinked");
    verify(fixture.componentDao).deleteIfNoAssets(22L);
    verify(fixture.cache).evictAfterCommit(runtime.id(), "docs/file.txt");
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    BrowseNodeDao browseNodeDao = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    return new Fixture(
        assetDao,
        componentDao,
        browseNodeDao,
        cache,
        new RawAssetWriter(assetDao, componentDao, browseNodeDao, cache, null));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw", true, 1L,
        "ALLOW", null, null, true, null, 60, 60, true, "ATTACHMENT", List.of());
  }

  private record Fixture(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache cache,
      RawAssetWriter writer) {
  }
}
