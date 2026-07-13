package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerProxyServiceTest {
  @Test
  void freshTagManifestIsServedFromCache() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(mock(DockerBlobStore.class), manifestStore, remoteClient);
    RepositoryRuntime runtime = proxyRuntime(30);
    DockerManifestStore.StoredManifest stored = storedManifest(
        runtime, "library/alpine", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        Instant.now());
    DockerResponse cached = DockerResponse.noBody(200)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, stored.manifest().digest());
    when(manifestStore.getManifest(runtime, "library/alpine", "latest")).thenReturn(stored);
    when(manifestStore.serveManifest(stored, true)).thenReturn(cached);

    DockerResponse response = service.getManifest(runtime, "library/alpine", "latest", true);

    assertEquals(stored.manifest().digest(), response.headers().get(DockerConstants.CONTENT_DIGEST_HEADER));
    verify(remoteClient, never()).get(any(), any(), any());
  }

  @Test
  void cachedManifestIsServedOnlyWhenClientAcceptsItsMediaType() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(mock(DockerBlobStore.class), manifestStore, remoteClient);
    RepositoryRuntime runtime = proxyRuntime(30);
    DockerManifestStore.StoredManifest stored = storedManifest(
        runtime, "library/alpine", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        Instant.now());
    List<String> accept = List.of(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST);
    DockerResponse cached = DockerResponse.noBody(200)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, stored.manifest().digest());
    when(manifestStore.getManifest(runtime, "library/alpine", "latest")).thenReturn(stored);
    when(manifestStore.serveManifest(stored, true, accept)).thenReturn(cached);

    DockerResponse response = service.getManifest(runtime, "library/alpine", "latest", true, accept);

    assertEquals(stored.manifest().digest(), response.headers().get(DockerConstants.CONTENT_DIGEST_HEADER));
    verify(manifestStore).serveManifest(stored, true, accept);
    verify(remoteClient, never()).get(any(), any(), any());
  }

  @Test
  void staleTagManifestIsRevalidatedFromRemote() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(mock(DockerBlobStore.class), manifestStore, remoteClient);
    RepositoryRuntime runtime = proxyRuntime(1);
    DockerManifestStore.StoredManifest cached = storedManifest(
        runtime, "library/alpine", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        Instant.now().minusSeconds(600));
    byte[] remoteBody = "{}".getBytes(StandardCharsets.UTF_8);
    DockerManifestStore.StoredManifest remoteStored = storedManifest(
        runtime, "library/alpine", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        Instant.now());
    when(manifestStore.getManifest(runtime, "library/alpine", "latest")).thenReturn(cached);
    when(remoteClient.get(eq(runtime), eq("library/alpine/manifests/latest"), any()))
        .thenReturn(new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST),
            new ByteArrayInputStream(remoteBody)));
    when(manifestStore.putManifest(
        eq(runtime),
        eq("library/alpine"),
        eq("latest"),
        any(byte[].class),
        eq(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST),
        eq("proxy"),
        eq(runtime.proxyRemoteUrl()),
        eq(false)))
        .thenReturn(remoteStored);

    DockerResponse response = service.getManifest(runtime, "library/alpine", "latest", true);

    assertEquals(remoteStored.manifest().digest(), response.headers().get(DockerConstants.CONTENT_DIGEST_HEADER));
    assertEquals(remoteBody.length, response.contentLength());
    assertEquals(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST, response.contentType());
    verify(remoteClient).get(eq(runtime), eq("library/alpine/manifests/latest"), any());
  }

  @Test
  void remoteManifestIsRejectedWhenMediaTypeIsNotAcceptedByClient() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(mock(DockerBlobStore.class), manifestStore, remoteClient);
    RepositoryRuntime runtime = proxyRuntime(1);
    byte[] remoteBody = "{}".getBytes(StandardCharsets.UTF_8);
    DockerManifestStore.StoredManifest remoteStored = storedManifest(
        runtime, "library/alpine", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        Instant.now());
    when(manifestStore.getManifest(runtime, "library/alpine", "latest"))
        .thenThrow(new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, "missing"));
    when(remoteClient.get(eq(runtime), eq("library/alpine/manifests/latest"), any()))
        .thenReturn(new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST),
            new ByteArrayInputStream(remoteBody)));
    when(manifestStore.putManifest(
        eq(runtime),
        eq("library/alpine"),
        eq("latest"),
        any(byte[].class),
        eq(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST),
        eq("proxy"),
        eq(runtime.proxyRemoteUrl()),
        eq(false)))
        .thenReturn(remoteStored);

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> service.getManifest(
            runtime,
            "library/alpine",
            "latest",
            true,
            List.of(DockerConstants.MEDIA_TYPE_OCI_INDEX)));

    assertEquals(DockerErrorCode.MANIFEST_UNKNOWN, thrown.code());
    assertEquals(404, thrown.status());
  }

  @Test
  void digestManifestReferenceUsesImmutableCacheEvenWhenOld() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(mock(DockerBlobStore.class), manifestStore, remoteClient);
    RepositoryRuntime runtime = proxyRuntime(1);
    String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    DockerManifestStore.StoredManifest stored = storedManifest(
        runtime, "library/alpine", digest, Instant.now().minusSeconds(600));
    DockerResponse cached = DockerResponse.noBody(200)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, digest);
    when(manifestStore.getManifest(runtime, "library/alpine", digest)).thenReturn(stored);
    when(manifestStore.serveManifest(stored, true)).thenReturn(cached);

    DockerResponse response = service.getManifest(runtime, "library/alpine", digest, true);

    assertEquals(digest, response.headers().get(DockerConstants.CONTENT_DIGEST_HEADER));
    verify(remoteClient, never()).get(any(), any(), any());
  }

  @Test
  void manifestRemoteNotFoundIsRememberedInSharedNegativeCache() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    ProxyNegativeCache negativeCache = new ProxyNegativeCache(new InMemorySharedCache(), true, 5, null);
    DockerProxyService service = new DockerProxyService(
        mock(DockerBlobStore.class), manifestStore, remoteClient, negativeCache);
    RepositoryRuntime runtime = proxyRuntime(1);
    when(manifestStore.getManifest(runtime, "alpine", "missing"))
        .thenThrow(new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, "missing"));
    when(remoteClient.get(eq(runtime), eq("library/alpine/manifests/missing"), any()))
        .thenReturn(new HttpRemoteFetcher.Result(404, Map.of(), new ByteArrayInputStream(new byte[0])));

    DockerProtocolException first = assertThrows(DockerProtocolException.class,
        () -> service.getManifest(runtime, "alpine", "missing", false));
    DockerProtocolException second = assertThrows(DockerProtocolException.class,
        () -> service.getManifest(runtime, "alpine", "missing", false));

    assertEquals(DockerErrorCode.MANIFEST_UNKNOWN, first.code());
    assertEquals(DockerErrorCode.MANIFEST_UNKNOWN, second.code());
    verify(remoteClient).get(eq(runtime), eq("library/alpine/manifests/missing"), any());
  }

  @Test
  void blobRemoteNotFoundIsRememberedInSharedNegativeCache() throws Exception {
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    ProxyNegativeCache negativeCache = new ProxyNegativeCache(new InMemorySharedCache(), true, 5, null);
    DockerProxyService service = new DockerProxyService(
        blobStore, mock(DockerManifestStore.class), remoteClient, negativeCache);
    RepositoryRuntime runtime = proxyRuntime(1);
    DockerDigest digest = DockerDigest.parse("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(blobStore.getBlob(runtime, digest, false))
        .thenThrow(new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, digest.value()));
    when(remoteClient.get(eq(runtime), eq("library/alpine/blobs/" + digest.value()), eq("application/octet-stream")))
        .thenReturn(new HttpRemoteFetcher.Result(404, Map.of(), new ByteArrayInputStream(new byte[0])));

    DockerProtocolException first = assertThrows(DockerProtocolException.class,
        () -> service.getBlob(runtime, "alpine", digest, false));
    DockerProtocolException second = assertThrows(DockerProtocolException.class,
        () -> service.getBlob(runtime, "alpine", digest, false));

    assertEquals(DockerErrorCode.BLOB_UNKNOWN, first.code());
    assertEquals(DockerErrorCode.BLOB_UNKNOWN, second.code());
    verify(remoteClient).get(eq(runtime), eq("library/alpine/blobs/" + digest.value()), eq("application/octet-stream"));
  }

  @Test
  void proxiedBlobHeadReturnsDigestLengthAndContentTypeAfterRemoteFetch() throws Exception {
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(
        blobStore, mock(DockerManifestStore.class), remoteClient);
    RepositoryRuntime runtime = proxyRuntime(1);
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    DockerDigest digest = DockerDigest.sha256(body);
    when(blobStore.getBlob(runtime, digest, true))
        .thenThrow(new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, digest.value()));
    when(remoteClient.get(eq(runtime), eq("library/alpine/blobs/" + digest.value()), eq("application/octet-stream")))
        .thenReturn(new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", "application/vnd.oci.image.layer.v1.tar"),
            new ByteArrayInputStream(body)));
    when(blobStore.storage(runtime)).thenReturn(mock(com.github.klboke.kkrepo.core.BlobStorage.class));
    when(blobStore.putBlob(
        eq(runtime),
        eq(digest),
        any(),
        eq((long) body.length),
        eq("application/vnd.oci.image.layer.v1.tar"),
        eq("proxy"),
        eq(runtime.proxyRemoteUrl())))
        .thenReturn(storedBlob(runtime, digest, body.length, "application/vnd.oci.image.layer.v1.tar"));

    DockerResponse response = service.getBlob(runtime, "alpine", digest, true);

    assertEquals(200, response.status());
    assertEquals(body.length, response.contentLength());
    assertEquals("application/vnd.oci.image.layer.v1.tar", response.contentType());
    assertEquals(digest.value(), response.headers().get(DockerConstants.CONTENT_DIGEST_HEADER));
  }

  @Test
  void catalogIsReadFromRemoteAndPaged() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(mock(DockerBlobStore.class), manifestStore, remoteClient);
    RepositoryRuntime runtime = proxyRuntime(1);
    byte[] body = "{\"repositories\":[\"library/alpine\",\"team/app\",\"z/late\"]}"
        .getBytes(StandardCharsets.UTF_8);
    when(remoteClient.get(eq(runtime), eq("_catalog?n=3&last=library%2Falpine"),
        eq(DockerConstants.MEDIA_TYPE_JSON)))
        .thenReturn(new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", DockerConstants.MEDIA_TYPE_JSON),
            new ByteArrayInputStream(body)));

    DockerCatalogList page = service.catalog(runtime, "library/alpine", 2);

    assertEquals(List.of("team/app", "z/late"), page.repositories());
    assertEquals(false, page.hasNext());
    verify(manifestStore, never()).listCatalog(any(), any(), anyInt());
  }

  @Test
  void catalogFallsBackToCachedManifestsWhenRemoteFails() throws Exception {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerRemoteRegistryClient remoteClient = mock(DockerRemoteRegistryClient.class);
    DockerProxyService service = new DockerProxyService(mock(DockerBlobStore.class), manifestStore, remoteClient);
    RepositoryRuntime runtime = proxyRuntime(1);
    when(remoteClient.get(eq(runtime), eq("_catalog?n=3"), eq(DockerConstants.MEDIA_TYPE_JSON)))
        .thenReturn(new HttpRemoteFetcher.Result(
            404,
            Map.of(),
            new ByteArrayInputStream(new byte[0])));
    when(manifestStore.listCatalog(runtime, null, 2))
        .thenReturn(new DockerCatalogList(List.of("cached/app"), false));

    DockerCatalogList page = service.catalog(runtime, null, 2);

    assertEquals(List.of("cached/app"), page.repositories());
    assertEquals(false, page.hasNext());
  }

  private static RepositoryRuntime proxyRuntime(int metadataMaxAgeMinutes) {
    return new RepositoryRuntime(
        10L,
        "docker-proxy",
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://registry-1.docker.io",
        1440,
        metadataMaxAgeMinutes,
        true,
        null,
        false,
        null,
        null,
        List.of());
  }

  private static DockerManifestStore.StoredManifest storedManifest(
      RepositoryRuntime runtime, String imageName, String digest, Instant updatedAt) {
    DockerManifestRecord manifest = new DockerManifestRecord(
        100L,
        runtime.id(),
        imageName,
        new byte[32],
        "sha256",
        digest,
        new byte[32],
        DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST,
        null,
        null,
        null,
        200L,
        2,
        "proxy",
        "remote",
        null,
        Map.of(),
        updatedAt,
        updatedAt);
    AssetRecord asset = new AssetRecord(
        200L,
        runtime.id(),
        null,
        300L,
        RepositoryFormat.DOCKER,
        "docker/manifests/" + imageName,
        new byte[32],
        imageName + "@" + digest,
        "MANIFEST",
        DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST,
        2L,
        null,
        updatedAt,
        Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        300L,
        runtime.blobStoreId(),
        "blob-ref",
        new byte[32],
        "object-key",
        new byte[32],
        null,
        digest.substring("sha256:".length()),
        null,
        2,
        DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST,
        "proxy",
        "remote",
        updatedAt,
        updatedAt,
        Map.of());
    return new DockerManifestStore.StoredManifest(manifest, asset, blob);
  }

  private static DockerBlobStore.StoredBlob storedBlob(
      RepositoryRuntime runtime, DockerDigest digest, long size, String contentType) {
    Instant now = Instant.now();
    AssetRecord asset = new AssetRecord(
        210L,
        runtime.id(),
        null,
        310L,
        RepositoryFormat.DOCKER,
        "docker/blobs/" + digest.value(),
        new byte[32],
        digest.value(),
        "BLOB",
        contentType,
        size,
        null,
        now,
        Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        310L,
        runtime.blobStoreId(),
        "blob-ref",
        new byte[32],
        "object-key",
        new byte[32],
        null,
        digest.hex(),
        null,
        size,
        contentType,
        "proxy",
        "remote",
        now,
        now,
        Map.of());
    return new DockerBlobStore.StoredBlob(asset, blob);
  }
}
