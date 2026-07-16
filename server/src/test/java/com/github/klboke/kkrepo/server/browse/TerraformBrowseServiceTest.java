package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TerraformBrowseServiceTest {
  @Test
  void projectsRevisionedProviderAssetsOntoNexusPublicTree() {
    RepositoryRecord group = repository(1L, "terraform-group", RepositoryType.GROUP);
    RepositoryRecord hosted = repository(2L, "terraform-hosted", RepositoryType.HOSTED);
    String versionPath = "v1/providers/acme/demo/1.0.10";
    String archivePath = versionPath + "/package/linux/"
        + "terraform-provider-demo_1.0.10_linux_amd64.zip";
    String sumsPath = versionPath + "/metadata-r2/terraform-provider-demo_1.0.10_SHA256SUMS";
    String signaturePath = sumsPath + ".sig";
    TerraformRegistryDao.ProviderPlatform platform = new TerraformRegistryDao.ProviderPlatform(
        hosted.id(), "acme", "demo", "1.0.10", "linux", "amd64",
        "terraform-provider-demo_1.0.10_linux_amd64.zip", archivePath, "sha256", "5.0", 2,
        Instant.parse("2026-07-16T02:30:42Z"));
    TerraformRegistryDao.ProviderState state = new TerraformRegistryDao.ProviderState(
        hosted.id(), "acme", "demo", "1.0.10", 2, sumsPath, signaturePath, 1,
        Instant.parse("2026-07-16T02:30:42Z"));
    TerraformRegistryDao registry = mock(TerraformRegistryDao.class);
    when(registry.listProviderPlatforms(hosted.id(), "acme", "demo", "1.0.10"))
        .thenReturn(List.of(platform));
    when(registry.findProviderState(hosted.id(), "acme", "demo", "1.0.10"))
        .thenReturn(Optional.of(state));
    StubAssetDao assets = new StubAssetDao(
        Map.of(
            key(hosted.id(), archivePath), asset(10L, hosted.id(), 100L, archivePath, 512L, "application/zip"),
            key(hosted.id(), sumsPath), asset(11L, hosted.id(), 101L, sumsPath, 96L, "text/plain"),
            key(hosted.id(), signaturePath),
                asset(12L, hosted.id(), 102L, signaturePath, 287L, "application/octet-stream")),
        Map.of(
            100L, blob(100L, "archive-sha1"),
            101L, blob(101L, "sums-sha1"),
            102L, blob(102L, "signature-sha1")));
    TerraformBrowseService service = new TerraformBrowseService(registry, assets);

    assertEquals(List.of("download"), names(service.list(group, List.of(hosted), versionPath)));
    assertEquals(List.of("linux"), names(service.list(
        group, List.of(hosted), versionPath + "/download")));
    assertEquals(List.of("amd64"), names(service.list(
        group, List.of(hosted), versionPath + "/download/linux")));

    List<BrowseController.BrowseEntry> files = service.list(
        group, List.of(hosted), versionPath + "/download/linux/amd64").orElseThrow();
    assertEquals(List.of(
        "terraform-provider-demo_1.0.10_linux_amd64.zip",
        "SHA256SUMS",
        "SHA256SUMS.sig"), files.stream().map(BrowseController.BrowseEntry::name).toList());
    assertTrue(files.stream().allMatch(BrowseController.BrowseEntry::leaf));
    assertTrue(files.stream().allMatch(entry -> "terraform-hosted".equals(entry.sourceRepository())));
    assertEquals(versionPath + "/download/linux/amd64/SHA256SUMS", files.get(1).path());
    assertEquals(
        "/repository/terraform-group/" + versionPath + "/download/linux/amd64/SHA256SUMS",
        files.get(1).downloadUrl());

    BrowseController.BrowseEntry versions = service.versionsJson(
        group, "v1/providers/acme/demo");
    assertEquals("versions.json", versions.name());
    assertEquals("v1/providers/acme/demo/versions.json", versions.path());
    assertEquals("/repository/terraform-group/v1/providers/acme/demo/versions.json",
        versions.downloadUrl());
  }

  private static List<String> names(
      Optional<List<BrowseController.BrowseEntry>> entries) {
    return entries.orElseThrow().stream().map(BrowseController.BrowseEntry::name).toList();
  }

  private static RepositoryRecord repository(long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.TERRAFORM,
        type,
        "terraform-" + type.name().toLowerCase(),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static AssetRecord asset(
      long id,
      long repositoryId,
      long blobId,
      String path,
      long size,
      String contentType) {
    return new AssetRecord(
        id,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.TERRAFORM,
        path,
        PersistenceHashes.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1),
        "terraform",
        contentType,
        size,
        null,
        Instant.parse("2026-07-16T02:30:42Z"),
        Map.of());
  }

  private static AssetBlobRecord blob(long id, String sha1) {
    String reference = "s3://bucket/terraform/" + id;
    return new AssetBlobRecord(
        id,
        1L,
        reference,
        PersistenceHashes.pathHash(reference),
        "terraform/" + id,
        PersistenceHashes.pathHash("terraform/" + id),
        sha1,
        "sha256-" + id,
        "md5-" + id,
        100L,
        "application/octet-stream",
        "admin",
        "127.0.0.1",
        Instant.parse("2026-07-16T02:30:42Z"),
        Instant.parse("2026-07-16T02:30:42Z"),
        Map.of());
  }

  private static String key(long repositoryId, String path) {
    return repositoryId + "|" + path;
  }

  private static final class StubAssetDao extends AssetDaoAdapter {
    private final Map<String, AssetRecord> assets;
    private final Map<Long, AssetBlobRecord> blobs;

    private StubAssetDao(
        Map<String, AssetRecord> assets,
        Map<Long, AssetBlobRecord> blobs) {
      this.assets = new LinkedHashMap<>(assets);
      this.blobs = new LinkedHashMap<>(blobs);
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return Optional.ofNullable(assets.get(key(repositoryId, path)));
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
      return Optional.ofNullable(blobs.get(assetBlobId));
    }
  }
}
