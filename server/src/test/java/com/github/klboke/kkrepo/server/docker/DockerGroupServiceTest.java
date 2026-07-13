package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerGroupServiceTest {
  @Test
  void blobLookupUsesManifestHitMemberBeforeGroupOrder() {
    DockerHostedService hosted = mock(DockerHostedService.class);
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        new InMemorySharedCache(),
        mock(RepositoryDao.class),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        true,
        60);
    DockerGroupService service = new DockerGroupService(hosted, mock(DockerProxyService.class), manifestStore, cache);
    RepositoryRuntime hostedOne = hostedRuntime(101L, "docker-hosted-one");
    RepositoryRuntime hostedTwo = hostedRuntime(102L, "docker-hosted-two");
    RepositoryRuntime group = groupRuntime(hostedOne, hostedTwo);
    String digestValue = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    DockerDigest digest = DockerDigest.parse(digestValue);
    DockerResponse manifestResponse = DockerResponse.noBody(200)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, digestValue);
    DockerResponse blobResponse = DockerResponse.noBody(200)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, digestValue);
    when(hosted.getManifest(hostedOne, "library/alpine", "latest", true))
        .thenThrow(new DockerProtocolException(DockerErrorCode.MANIFEST_UNKNOWN, "missing"));
    when(hosted.getManifest(hostedTwo, "library/alpine", "latest", true))
        .thenReturn(manifestResponse);
    when(manifestStore.getManifest(hostedTwo, "library/alpine", "latest"))
        .thenReturn(storedManifest(hostedTwo, "library/alpine", digestValue));
    when(hosted.getBlob(hostedTwo, digest, true)).thenReturn(blobResponse);

    service.getManifest(group, "library/alpine", "latest", true);
    DockerResponse response = service.getBlob(group, "library/alpine", digest, true);

    assertEquals(digestValue, response.headers().get(DockerConstants.CONTENT_DIGEST_HEADER));
    verify(hosted).getBlob(hostedTwo, digest, true);
    verify(hosted, never()).getBlob(hostedOne, digest, true);
  }

  @Test
  void manifestLookupPassesAcceptHeaderToMembers() {
    DockerHostedService hosted = mock(DockerHostedService.class);
    DockerGroupService service = new DockerGroupService(hosted, mock(DockerProxyService.class));
    RepositoryRuntime hostedOne = hostedRuntime(101L, "docker-hosted-one");
    RepositoryRuntime group = groupRuntime(hostedOne);
    List<String> accept = List.of(DockerConstants.MEDIA_TYPE_OCI_INDEX);
    String digestValue = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    DockerResponse manifestResponse = DockerResponse.noBody(200)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, digestValue);
    when(hosted.getManifest(hostedOne, "library/alpine", "latest", true, accept))
        .thenReturn(manifestResponse);

    DockerResponse response = service.getManifest(group, "library/alpine", "latest", true, accept);

    assertEquals(digestValue, response.headers().get(DockerConstants.CONTENT_DIGEST_HEADER));
    verify(hosted).getManifest(hostedOne, "library/alpine", "latest", true, accept);
  }

  @Test
  void tagListPushesCursorToMembersAndPaginatesMergedTags() {
    DockerHostedService hosted = mock(DockerHostedService.class);
    DockerGroupService service = new DockerGroupService(hosted, mock(DockerProxyService.class));
    RepositoryRuntime hostedOne = hostedRuntime(101L, "docker-hosted-one");
    RepositoryRuntime hostedTwo = hostedRuntime(102L, "docker-hosted-two");
    RepositoryRuntime group = groupRuntime(hostedOne, hostedTwo);
    when(hosted.tags(hostedOne, "library/alpine", "b", 3))
        .thenReturn(new DockerTagList("library/alpine", List.of("c", "e", "g"), false));
    when(hosted.tags(hostedTwo, "library/alpine", "b", 3))
        .thenReturn(new DockerTagList("library/alpine", List.of("d", "e"), false));

    DockerTagList page = service.tags(group, "library/alpine", "b", 2);

    assertEquals(List.of("c", "d"), page.tags());
    assertEquals(true, page.hasNext());
    verify(hosted).tags(hostedOne, "library/alpine", "b", 3);
    verify(hosted).tags(hostedTwo, "library/alpine", "b", 3);
    verify(hosted, never()).tags(hostedOne, "library/alpine", null, 1000);
  }

  @Test
  void catalogMergesMembersAndPaginatesByCursor() {
    DockerHostedService hosted = mock(DockerHostedService.class);
    DockerGroupService service = new DockerGroupService(hosted, mock(DockerProxyService.class));
    RepositoryRuntime hostedOne = hostedRuntime(101L, "docker-hosted-one");
    RepositoryRuntime hostedTwo = hostedRuntime(102L, "docker-hosted-two");
    RepositoryRuntime group = groupRuntime(hostedOne, hostedTwo);
    when(hosted.catalog(hostedOne, "library/alpine", 3))
        .thenReturn(new DockerCatalogList(List.of("team/app", "team/base", "z/late"), false));
    when(hosted.catalog(hostedTwo, "library/alpine", 3))
        .thenReturn(new DockerCatalogList(List.of("team/base", "team/side"), false));

    DockerCatalogList page = service.catalog(group, "library/alpine", 2);

    assertEquals(List.of("team/app", "team/base"), page.repositories());
    assertEquals(true, page.hasNext());
    verify(hosted).catalog(hostedOne, "library/alpine", 3);
    verify(hosted).catalog(hostedTwo, "library/alpine", 3);
  }

  private static RepositoryRuntime hostedRuntime(long id, String name) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.DOCKER,
        RepositoryType.HOSTED,
        "docker-hosted",
        true,
        1L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        false,
        null,
        null,
        List.of());
  }

  private static RepositoryRuntime groupRuntime(RepositoryRuntime... members) {
    return new RepositoryRuntime(
        999L,
        "docker-group",
        RepositoryFormat.DOCKER,
        RepositoryType.GROUP,
        "docker-group",
        true,
        1L,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        false,
        null,
        null,
        List.of(members));
  }

  private static DockerManifestStore.StoredManifest storedManifest(
      RepositoryRuntime runtime, String imageName, String digest) {
    Instant now = Instant.now();
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
        "user",
        "127.0.0.1",
        null,
        Map.of(),
        now,
        now);
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
        now,
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
        "user",
        "127.0.0.1",
        now,
        now,
        Map.of());
    return new DockerManifestStore.StoredManifest(manifest, asset, blob);
  }
}
