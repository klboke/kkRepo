package com.github.klboke.kkrepo.server.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxProtocol;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingComponents;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenVsxStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final OpenVsxVsixInspector inspector = mock(OpenVsxVsixInspector.class);
  private final OpenVsxAssetSupport assets = mock(OpenVsxAssetSupport.class);
  private final OpenVsxHostedService openvsxHosted = mock(OpenVsxHostedService.class);
  private final StagingDao staging = mock(StagingDao.class);
  private final OpenVsxStagingContentAdapter adapter = new OpenVsxStagingContentAdapter(
      hosted, inspector, assets, openvsxHosted, staging, 1024 * 1024);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "openvsx-hosted", RepositoryFormat.OPENVSX, RepositoryType.HOSTED,
      "openvsx-hosted", true, 1L, "ALLOW", null, null, true, null, null, null, null, null,
      List.of());

  @Test
  void normalizesFileRoutesToTheirStoragePathAndRecognizesPublication() {
    assertEquals("acme/demo/1.0.0/demo-1.0.0.vsix",
        adapter.normalizePath("api/acme/demo/1.0.0/file/demo-1.0.0.vsix").orElseThrow());
    assertTrue(adapter.normalizePath("api/acme/demo").isPresent());
    assertTrue(adapter.supportsPublication("api/-/publish"));
    assertFalse(adapter.supportsPublication("api/acme/demo"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void servesLiveAndStagedExtensionMetadata() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("openvsxVersion", "1.0.0");
    attributes.put("openvsxPackageJson", Map.of("displayName", "Demo"));
    attributes.put("openvsxPath", "acme/demo/1.0.0/demo-1.0.0.vsix");
    attributes.put("openvsxSize", 10);
    attributes.put("openvsxSha1", "a".repeat(40));
    attributes.put("openvsxSha256", "b".repeat(64));
    ComponentRecord component = new ComponentRecord(
        null, 1L, RepositoryFormat.OPENVSX, "acme", "demo", "1.0.0",
        "openvsx-extension", new byte[32], attributes, Instant.now());
    Map<String, Object> itemAttributes = new LinkedHashMap<>();
    itemAttributes.put(StagingComponents.ATTRIBUTE, StagingComponents.encode(component));
    StagingItemRecord staged = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.OPENVSX, "acme/demo/1.0.0/demo-1.0.0.vsix",
        new byte[32], "ADD", "STAGED", 1L, 1L, OpenVsxProtocol.VSIX_MEDIA_TYPE, 1L,
        "2".repeat(64), "3".repeat(32), "1".repeat(40), null, null, null,
        itemAttributes, Instant.now(), Instant.now());
    when(staging.listItems(8L)).thenReturn(List.of(staged));
    when(openvsxHosted.stagedGet(any(), any(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(com.github.klboke.kkrepo.server.maven.MavenResponse.noBody(200));

    assertTrue(adapter.generatedMetadata(
        runtime, 8L, "api/acme/demo", false,
        "https://repo.example/repository/openvsx-hosted").isPresent());

    org.mockito.ArgumentCaptor<List<ComponentRecord>> components =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(openvsxHosted).stagedGet(
        eq(runtime), any(), any(),
        eq("https://repo.example/repository/openvsx-hosted"), eq("openvsx-hosted"),
        components.capture(), eq(false));
    assertEquals("demo", components.getValue().getFirst().name());
  }

  @Test
  void leavesLiveMetadataAloneWhenNothingIsStaged() {
    when(staging.listItems(8L)).thenReturn(List.of());
    assertTrue(adapter.generatedMetadata(
        runtime, 8L, "api/acme/demo", false, "https://repo.example").isEmpty());
  }

  @Test
  void stagesAVsixWithItsPromotionComponentProjection() throws Exception {
    OpenVsxVsixInspector.Inspected inspected = inspected();
    when(inspector.inspect(any(), any())).thenReturn(inspected);
    when(assets.find(any(), any())).thenReturn(Optional.empty());
    AssetBlobRecord blob = blob();
    when(hosted.storeStagedBlobFile(
        any(), eq("acme/demo/1.0.0/demo-1.0.0.vsix"), any(),
        eq(OpenVsxProtocol.VSIX_MEDIA_TYPE), any(), any(), any()))
        .thenReturn(blob);

    OpenVsxStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, "api/-/publish", new ByteArrayInputStream(new byte[] {1}), null,
        "alice", "127.0.0.1");

    assertEquals("acme/demo/1.0.0/demo-1.0.0.vsix", upload.logicalPath());
    assertEquals(OpenVsxProtocol.VSIX_MEDIA_TYPE, upload.contentType());
    assertNotNull(upload.attributes().get(StagingComponents.ATTRIBUTE));
  }

  @Test
  void rejectsAnAlreadyPublishedExtensionVersion() throws Exception {
    when(inspector.inspect(any(), any())).thenReturn(inspected());
    when(assets.find(any(), any())).thenReturn(Optional.of(mock(AssetRecord.class)));

    assertThrows(MavenExceptions.BadRequestException.class, () -> adapter.stage(
        runtime, "api/-/publish", new ByteArrayInputStream(new byte[] {1}), null,
        "alice", "127.0.0.1"));
  }

  @Test
  void preflightRejectsAnExistingLiveVersion() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "openvsx-hosted", RepositoryFormat.OPENVSX, RepositoryType.HOSTED,
        "openvsx-hosted", true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.OPENVSX, "acme/demo/1.0.0/demo-1.0.0.vsix",
        new byte[32], "ADD", "STAGED", 1L, 1L, OpenVsxProtocol.VSIX_MEDIA_TYPE, 1L,
        "2".repeat(64), "3".repeat(32), "1".repeat(40), null, null, null, Map.of(),
        Instant.now(), Instant.now());

    assertEquals("Extension version already exists",
        adapter.validateItem(repository, item, mock(AssetRecord.class)));
  }

  private static OpenVsxVsixInspector.Inspected inspected() {
    return new OpenVsxVsixInspector.Inspected(
        "acme", "demo", "1.0.0", null, false, "demo-1.0.0.vsix", "Demo", "Demo extension",
        Map.of("name", "demo", "version", "1.0.0", "publisher", "acme"));
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32],
        "1".repeat(40), "2".repeat(64), "3".repeat(32), 1L,
        OpenVsxProtocol.VSIX_MEDIA_TYPE, "alice", "127.0.0.1", now, now, Map.of());
  }
}
