package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerraformStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final TerraformService terraformHosted = mock(TerraformService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final TerraformStagingContentAdapter adapter =
      new TerraformStagingContentAdapter(hosted, terraformHosted, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "tf-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED, "tf-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "v1/modules/acme/network/aws/1.2.3/network.zip";

  @Test
  void acceptsKnownTerraformPaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("nope").isEmpty());
  }

  @Test
  void promotionReplaysTheTerraformWriter() {
    when(terraformHosted.put(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(terraformHosted).put(
        eq(runtime), any(), any(), any(), any(), any(), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void stageCapturesProviderPlatformMetadata() throws Exception {
    String providerPath = "v1/providers/acme/demo/1.0.0/package/linux/"
        + "terraform-provider-demo_1.0.0_linux_amd64.zip";
    when(hosted.storeStagedBlobFile(
        eq(runtime), eq(providerPath), any(), any(), any(), any(), any()))
        .thenReturn(blob());
    var upload = adapter.stage(runtime, providerPath,
        new ByteArrayInputStream(new byte[] {1}), "application/zip", "alice", "127.0.0.1");
    @SuppressWarnings("unchecked")
    Map<String, Object> descriptor =
        (Map<String, Object>) upload.attributes().get("terraformProvider");
    assertEquals("acme", descriptor.get("namespace"));
    assertEquals("linux", descriptor.get("os"));
    assertEquals("5.0", descriptor.get("protocols"));
  }

  @Test
  void servesProviderVersionsFromStagedPlatforms() {
    when(staging.listItems(2L)).thenReturn(List.of(providerItem()));
    when(terraformHosted.stagedProviderVersions(any(), any(), any(), any(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(runtime, 2L,
        "v1/providers/acme/demo/versions", false, "https://repo.example/repository/tf-hosted")
        .isPresent());

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<List<Map<String, Object>>> platforms =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(terraformHosted).stagedProviderVersions(
        eq(runtime), eq("acme"), eq("demo"), platforms.capture(), eq(false));
    assertEquals("1.0.0", platforms.getValue().getFirst().get("version"));
  }

  @Test
  void servesStagedProviderDownloadAndSums() {
    when(staging.listItems(2L)).thenReturn(List.of(providerItem()));
    when(terraformHosted.stagedProviderDownload(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));
    when(terraformHosted.stagedProviderSums(
        any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
        .thenReturn(MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(runtime, 2L,
        "v1/providers/acme/demo/1.0.0/download/linux/amd64", false,
        "https://repo.example/repository/tf-hosted").isPresent());
    assertTrue(adapter.generatedMetadata(runtime, 2L,
        "v1/providers/acme/demo/1.0.0/staging/terraform-provider-demo_1.0.0_SHA256SUMS",
        false, "https://repo.example/repository/tf-hosted").isPresent());

    verify(terraformHosted).stagedProviderDownload(
        eq(runtime), eq("acme"), eq("demo"), eq("1.0.0"), eq("linux"), eq("amd64"),
        eq("https://repo.example/repository/tf-hosted"),
        eq("terraform-provider-demo_1.0.0_linux_amd64.zip"), eq("a".repeat(64)),
        eq("5.0"), eq(false));
    verify(terraformHosted).stagedProviderSums(
        eq(runtime), eq("acme"), eq("demo"), eq("1.0.0"), any(), eq(false), eq(false));
  }

  private StagingItemRecord providerItem() {
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("namespace", "acme");
    descriptor.put("name", "demo");
    descriptor.put("version", "1.0.0");
    descriptor.put("os", "linux");
    descriptor.put("arch", "amd64");
    descriptor.put("filename", "terraform-provider-demo_1.0.0_linux_amd64.zip");
    descriptor.put("sha256", "a".repeat(64));
    descriptor.put("size", 10);
    descriptor.put("protocols", "5.0");
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.TERRAFORM,
        "v1/providers/acme/demo/1.0.0/package/linux/terraform-provider-demo_1.0.0_linux_amd64.zip",
        new byte[32], "ADD", "STAGED", 1L, 1L, "application/zip", 1L, "2".repeat(64),
        "3".repeat(32), "a".repeat(64), null, null, null,
        Map.of("terraformProvider", descriptor), Instant.now(), Instant.now());
  }

  private static com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "a".repeat(64), "3".repeat(32), 10L, "application/zip", "alice",
        "127.0.0.1", now, now, Map.of());
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.TERRAFORM, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/zip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of(), Instant.now(), Instant.now());
  }
}
