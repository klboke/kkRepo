package com.github.klboke.kkrepo.server.yum;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class YumMetadataCleanupTest {
  private final BlobStorageRegistry storage = mock(BlobStorageRegistry.class);
  private final YumMetadataCleanup cleanup = new YumMetadataCleanup(null, null, null, storage);

  @Test
  void onlyRecognizesExactManagedPathsAndDigestNamedMetadata() {
    for (String root : List.of("", "release/", "release/arch/")) {
      assertTrue(cleanup.isGenerated(asset(root + "repodata/repomd.xml", true), blob("system")));
      for (String kind : List.of("primary", "filelists", "other")) {
        assertTrue(cleanup.isGenerated(asset(root + "repodata/" + "a".repeat(64) + "-" + kind + ".xml.gz", true), blob("admin")));
      }
    }
    for (String path : List.of("demo.rpm", "repomd.xml", "repodata/comps.xml", "repodata/repomd.xml.asc",
        "repodata/extra/repomd.xml", "Repodata/repomd.xml", "repodata/primary.xml.gz",
        "repodata/" + "b".repeat(64) + "-primary.xml.gz")) {
      assertFalse(cleanup.isGenerated(asset(path, true), blob("system")), path);
    }
    verifyNoInteractions(storage);
  }

  @Test
  void userBindingOverridesSharedBlobSystemAuthorAndUnknownProvenanceFailsClosed() {
    assertFalse(cleanup.isGenerated(asset("repodata/repomd.xml", false), blob("system")));
    assertFalse(cleanup.isGenerated(asset("repodata/repomd.xml", "true"), blob("system")));
    assertFalse(cleanup.isGenerated(asset("repodata/repomd.xml", true), null));
    var wrongFormat = asset("repodata/repomd.xml", true);
    when(wrongFormat.format()).thenReturn(RepositoryFormat.RAW);
    assertFalse(cleanup.isGenerated(wrongFormat, blob("system")));
    verifyNoInteractions(storage);
  }

  @Test
  void legacyHashedMetadataRequiresSystemAuditAndUnmodifiedRawAssetAttributes() {
    String path = "old/repodata/" + "a".repeat(64) + "-primary.xml.gz";
    assertTrue(cleanup.isGenerated(asset(path, null), blob("system")));
    assertFalse(cleanup.isGenerated(asset(path, null), blob("admin")));
    var migrated = asset(path, null);
    when(migrated.attributes()).thenReturn(Map.of("path", path, "source", "nexus-repository-data-migration"));
    assertFalse(cleanup.isGenerated(migrated, blob("system")));
  }

  @Test
  void legacyRepomdRequiresExactGeneratorContentAndRefusesMissingOrOversizedBlob() throws Exception {
    var primary = YumService.gzipMetadata(YumService.primaryXml(List.of()));
    var filelists = YumService.gzipMetadata(YumService.filelistsXml(List.of()));
    var other = YumService.gzipMetadata(YumService.otherXml(List.of()));
    String xml = YumService.repomdXml(List.of(
        new YumService.RepomdEntry("primary", "repodata/" + primary.fileName(), primary),
        new YumService.RepomdEntry("filelists", "repodata/" + filelists.fileName(), filelists),
        new YumService.RepomdEntry("other", "repodata/" + other.fileName(), other)));
    var store = mock(BlobStorage.class);
    when(storage.forBlobStoreId(1L)).thenReturn(store);
    var asset = asset("repodata/repomd.xml", null);
    var blob = blob("system");
    for (String content : List.of(xml, "user data", xml.replace("</repomd>", "<custom>keep me</custom></repomd>"))) {
      when(store.get(any())).thenReturn(Optional.of(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
      assertEquals(content.equals(xml), cleanup.isGenerated(asset, blob));
    }
    when(store.get(any())).thenReturn(Optional.empty());
    assertFalse(cleanup.isGenerated(asset, blob));
    when(store.get(any())).thenThrow(new IllegalStateException("storage unavailable"));
    assertFalse(cleanup.isGenerated(asset, blob));
    when(blob.size()).thenReturn(16385L);
    assertFalse(cleanup.isGenerated(asset, blob));
  }

  private static AssetRecord asset(String path, Object generated) {
    var asset = mock(AssetRecord.class);
    when(asset.path()).thenReturn(path);
    when(asset.format()).thenReturn(RepositoryFormat.YUM);
    when(asset.attributes()).thenReturn(generated == null ? Map.of("path", path)
        : Map.of("path", path, YumMetadataCleanup.GENERATED_ATTRIBUTE, generated));
    return asset;
  }

  private static AssetBlobRecord blob(String author) {
    var blob = mock(AssetBlobRecord.class);
    when(blob.createdBy()).thenReturn(author);
    when(blob.sha256()).thenReturn("a".repeat(64));
    when(blob.blobStoreId()).thenReturn(1L);
    when(blob.blobRef()).thenReturn("blob://bucket/metadata");
    when(blob.objectKey()).thenReturn("metadata");
    return blob;
  }
}
