package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cargo.CargoPublishPayload.CargoPackageMetadata;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CargoAssetWriterTest {

  @Test
  void proxyCrateRejectsChecksumMismatchBeforePersistingMetadata() {
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findReusableBlobBySha256(anyLong(), anyString(), anyLong())).thenReturn(Optional.empty());
    when(assetDao.hasLiveBlobForObjectKeyHash(anyLong(), org.mockito.ArgumentMatchers.any(byte[].class)))
        .thenReturn(false);
    CountingBlobStorage storage = new CountingBlobStorage();
    CargoAssetWriter writer = new CargoAssetWriter(
        assetDao,
        mock(ComponentDao.class),
        mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class),
        null);
    String path = "crates/demo/1.0.0/demo-1.0.0.crate";

    CargoExceptions.BadUpstreamException thrown = assertThrows(
        CargoExceptions.BadUpstreamException.class,
        () -> writer.writeProxyCrate(
            runtime(),
            storage,
            1L,
            new CargoPackageMetadata("demo", "demo", "1.0.0", "1.0.0", null, Map.of()),
            Map.of("name", "demo", "vers", "1.0.0", "cksum", "0".repeat(64)),
            path,
            new ByteArrayInputStream("crate-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "application/x-tar",
            Map.of("remoteUrl", "https://static.crates.io/crates/demo/demo-1.0.0.crate"),
            "0".repeat(64)));

    assertEquals("Cargo crate checksum mismatch for " + path, thrown.getMessage());
    assertEquals(1, storage.puts);
    assertEquals(1, storage.deletes);
  }

  @Test
  void proxyMetadataDoesNotRecoverSoftDeletedBlobRows() {
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(anyLong(), anyString())).thenReturn(Optional.empty());
    when(assetDao.insertBlobOrFindExisting(any(AssetBlobRecord.class)))
        .thenAnswer(invocation -> ((AssetBlobRecord) invocation.getArgument(0)).withId(99L));
    when(assetDao.tryInsertAsset(any(AssetRecord.class))).thenReturn(OptionalLong.of(100L));
    CountingBlobStorage storage = new CountingBlobStorage();
    CargoAssetWriter writer = new CargoAssetWriter(
        assetDao,
        mock(ComponentDao.class),
        mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class),
        null);

    writer.writeMetadata(
        runtime(),
        storage,
        1L,
        "it/oa/demo",
        "{\"name\":\"demo\"}\n".getBytes(StandardCharsets.UTF_8),
        "text/plain",
        "index",
        Map.of("crateName", "demo"),
        Map.of("remoteEtag", "\"abc\""),
        false);

    verify(assetDao, never()).recoverDeletedBlobBySha256(anyLong(), anyString(), anyLong());
    assertEquals(1, storage.puts);
    assertEquals(0, storage.deletes);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        50,
        "cargo-proxy",
        RepositoryFormat.CARGO,
        RepositoryType.PROXY,
        "cargo-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://index.crates.io/",
        0,
        0,
        true,
        null,
        List.of());
  }

  private static final class CountingBlobStorage implements BlobStorage {
    private int puts;
    private int deletes;

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      try {
        content.readAllBytes();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      puts++;
      return new BlobReference("bucket", "objects/" + sha256, sha256, size);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
      deletes++;
    }
  }
}
