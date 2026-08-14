package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PubAssetWriterTest {

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void componentAttributesIncludePublishProvenance() {
    PubPackageMetadata metadata = new PubPackageMetadata(
        "example_package",
        "1.0.0",
        Map.of("name", "example_package", "version", "1.0.0"));
    PubAssetWriter.Digests digests = new PubAssetWriter.Digests(
        "md5",
        "sha1",
        "a".repeat(64),
        "sha512",
        512L);
    Map<String, Object> attrs = PubAssetWriter.componentAttributes(
        metadata,
        digests,
        "packages/example_package/versions/1.0.0.tar.gz",
        Instant.parse("2026-07-08T00:00:00Z"),
        Map.of(
            "publishSource", "pub-client",
            "publishedBy", "alice",
            "publishApiKeyId", 42L,
            "uploadSessionId", "session-1",
            "sourceClient", "Dart pub 3.9.0"));

    assertEquals("example_package", attrs.get("packageName"));
    assertEquals("1.0.0", attrs.get("version"));
    assertEquals("a".repeat(64), attrs.get("archiveSha256"));
    assertEquals(512L, attrs.get("archiveSize"));
    assertEquals("packages/example_package/versions/1.0.0.tar.gz", attrs.get("archivePath"));
    assertEquals("2026-07-08T00:00:00Z", attrs.get("publishedAt"));
    assertEquals("pub-client", attrs.get("publishSource"));
    assertEquals("alice", attrs.get("publishedBy"));
    assertEquals(42L, attrs.get("publishApiKeyId"));
    assertEquals("session-1", attrs.get("uploadSessionId"));
    assertEquals("Dart pub 3.9.0", attrs.get("sourceClient"));
  }

  @Test
  void proxyMetadataRecordsTheCurrentClientIp() {
    AssetDao assetDao = mock(AssetDao.class);
    BrowseNodeDao browseNodeDao = mock(BrowseNodeDao.class);
    BlobStorage storage = mock(BlobStorage.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    PubAssetWriter writer = new PubAssetWriter(
        assetDao, mock(ComponentDao.class), browseNodeDao, cache, null);
    RepositoryRuntime runtime = runtime();
    String path = "api/packages/example_package";
    byte[] body = "{\"name\":\"example_package\"}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.209");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    when(assetDao.findReusableBlobBySha256(eq(1L), anyString(), eq((long) body.length)))
        .thenReturn(Optional.empty());
    when(storage.putFile(eq(runtime.name()), eq(path), any(Path.class), anyString()))
        .thenReturn(new BlobReference("bucket", "objects/pub-metadata", "sha256", body.length));
    when(assetDao.insertBlobOrFindExisting(any(AssetBlobRecord.class)))
        .thenAnswer(invocation -> ((AssetBlobRecord) invocation.getArgument(0)).withId(2L));
    when(assetDao.findAssetByPath(runtime.id(), path)).thenReturn(Optional.empty());
    when(assetDao.tryInsertAsset(any(AssetRecord.class))).thenReturn(OptionalLong.of(3L));

    writer.writeMetadata(
        runtime, storage, 1L, path, body, Map.of("packageName", "example_package"),
        Map.of("remoteEtag", "etag"), false);

    ArgumentCaptor<AssetBlobRecord> blobCaptor = ArgumentCaptor.forClass(AssetBlobRecord.class);
    verify(assetDao).insertBlobOrFindExisting(blobCaptor.capture());
    assertEquals("proxy", blobCaptor.getValue().createdBy());
    assertEquals("198.51.100.209", blobCaptor.getValue().createdByIp());
    verify(browseNodeDao).upsertPathAncestors(runtime.id(), path, 3L, null);
    verify(cache).evictAfterCommit(runtime.id(), path);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        2L,
        "pub-proxy",
        RepositoryFormat.PUB,
        RepositoryType.PROXY,
        "pub-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://pub.dev/",
        1440,
        1440,
        List.of());
  }
}
