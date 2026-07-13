package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class ComposerHostedServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void buildsComposerV2MetadataAndRewritesDistUrl() throws Exception {
    ComponentDao components = mock(ComponentDao.class);
    when(components.listByName(1L, "company/example")).thenReturn(List.of(
        component("1.2.3", false),
        component("dev-main", true)));
    ComposerHostedService service = new ComposerHostedService(
        JSON, components, mock(ComposerAssetSupport.class),
        mock(ComposerArchiveInspector.class), mock(ComposerComponentWriter.class));

    MavenResponse response = service.get(
        runtime("hosted", RepositoryType.HOSTED, List.of()),
        new ComposerPathParser().parse("p2/company/example.json"),
        "https://repo.test/repository/composer-hosted",
        null,
        false);

    Map<String, Object> body;
    try (var input = response.body()) {
      body = JSON.readValue(input, new TypeReference<>() {});
    }
    Map<?, ?> packages = (Map<?, ?>) body.get("packages");
    List<?> versions = (List<?>) packages.get("company/example");
    assertEquals(1, versions.size(), "stable endpoint must not include dev versions");
    Map<?, ?> version = (Map<?, ?>) versions.getFirst();
    Map<?, ?> dist = (Map<?, ?>) version.get("dist");
    assertEquals(
        "https://repo.test/repository/composer-hosted/company/example/1.0.0/company-example-1.0.0.zip",
        dist.get("url"));
    assertTrue(response.etag() != null && !response.etag().isBlank());
  }

  @Test
  void bindFailureDeletesOnlyTheUploadStagingPath() throws Exception {
    ComponentDao components = mock(ComponentDao.class);
    ComposerAssetSupport assets = mock(ComposerAssetSupport.class);
    ComposerArchiveInspector inspector = mock(ComposerArchiveInspector.class);
    ComposerComponentWriter writer = mock(ComposerComponentWriter.class);
    AssetRecord stagingAsset = mock(AssetRecord.class);
    AssetBlobRecord blob = mock(AssetBlobRecord.class);
    when(components.findByNameAndVersion(1L, "company/example", "1.0.0"))
        .thenReturn(Optional.empty());
    when(inspector.inspect(any(), eq("package.zip"), eq(null), eq(null)))
        .thenReturn(new ComposerArchiveInspector.Inspected(
            "company/example", "1.0.0",
            Map.of("name", "company/example", "version", "1.0.0")));
    when(assets.find(any(), anyString())).thenReturn(Optional.of(stagingAsset));
    when(assets.blob(eq(stagingAsset), anyString())).thenReturn(blob);
    when(blob.sha1()).thenReturn("abc123");
    doThrow(new MavenExceptions.WritePolicyDenied("duplicate"))
        .when(writer).bindHostedArchive(
            any(), eq(stagingAsset), eq("company/example"), eq("1.0.0"), any(),
            eq("company/example/1.0.0/company-example-1.0.0.zip"), eq("admin"), eq("127.0.0.1"));
    ComposerHostedService service = new ComposerHostedService(JSON, components, assets, inspector, writer);

    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.uploadArchive(
        runtime("hosted", RepositoryType.HOSTED, List.of()),
        new ByteArrayInputStream(new byte[] {1, 2, 3}), "package.zip", "application/zip",
        null, null, "admin", "127.0.0.1"));

    ArgumentCaptor<String> stagedPath = ArgumentCaptor.forClass(String.class);
    verify(assets).store(any(), stagedPath.capture(), any(), eq("application/zip"), any(),
        eq("admin"), eq("127.0.0.1"));
    assertTrue(stagedPath.getValue().startsWith("_composer/uploads/"));
    verify(assets).delete(any(), eq(stagedPath.getValue()));
    verify(assets, never()).delete(
        any(), eq("company/example/1.0.0/company-example-1.0.0.zip"));
  }

  @Test
  void denyWritePolicyRejectsComposerUploadBeforeStaging() throws Exception {
    ComponentDao components = mock(ComponentDao.class);
    ComposerAssetSupport assets = mock(ComposerAssetSupport.class);
    ComposerArchiveInspector inspector = mock(ComposerArchiveInspector.class);
    ComposerComponentWriter writer = mock(ComposerComponentWriter.class);
    ComposerHostedService service = new ComposerHostedService(JSON, components, assets, inspector, writer);

    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.uploadArchive(
        runtime("hosted", RepositoryType.HOSTED, "DENY", List.of()),
        new ByteArrayInputStream(new byte[] {1, 2, 3}), "package.zip", "application/zip",
        null, null, "admin", "127.0.0.1"));

    verifyNoInteractions(assets, inspector, writer);
    verify(components, never()).findByNameAndVersion(anyLong(), anyString(), anyString());
  }

  private static ComponentRecord component(String version, boolean dev) {
    Map<String, Object> metadata = Map.of(
        "name", "company/example",
        "version", version,
        "dist", Map.of(
            "type", "zip",
            "shasum", "abc",
            "path", "company/example/1.0.0/company-example-1.0.0.zip"));
    return new ComponentRecord(
        dev ? 2L : 1L,
        1L,
        RepositoryFormat.COMPOSER,
        "company",
        "company/example",
        version,
        "composer-package",
        PersistenceHashes.componentCoordinateHash(null, "company/example", version),
        Map.of("composerMetadata", metadata),
        Instant.parse(dev ? "2026-01-02T00:00:00Z" : "2026-01-01T00:00:00Z"));
  }

  static RepositoryRuntime runtime(String name, RepositoryType type, List<RepositoryRuntime> members) {
    return runtime(name, type, "ALLOW_ONCE", members);
  }

  static RepositoryRuntime runtime(
      String name, RepositoryType type, String writePolicy, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        1L, name, RepositoryFormat.COMPOSER, type, "composer-" + type.name().toLowerCase(),
        true, 1L, writePolicy, null, null, true,
        type == RepositoryType.PROXY ? "https://repo.packagist.org/" : null,
        1440, 60, true, null, members);
  }
}
