package com.github.klboke.kkrepo.server.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.server.cargo.CargoHostedService;
import com.github.klboke.kkrepo.server.composer.ComposerHostedService;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.maven.MavenHostedService;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import com.github.klboke.kkrepo.server.pypi.PypiHostedService;
import com.github.klboke.kkrepo.server.pub.PubHostedService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.yum.YumService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

class ComponentUploadServiceTest {

  @Test
  void uploadSpecsIncludeCargoSingleAssetUpload() {
    ComponentUploadService service = service(mock(CargoHostedService.class));

    assertTrue(service.definitions().stream().anyMatch(def ->
        def.format().equals("cargo")
            && !def.multipleUpload()
            && def.assetFields().size() == 1
            && def.assetFields().getFirst().name().equals("asset")));
  }

  @Test
  void uploadSpecsIncludePubSingleAssetUpload() {
    ComponentUploadService service = service(mock(CargoHostedService.class));

    assertTrue(service.definitions().stream().anyMatch(def ->
        def.format().equals("pub")
            && !def.multipleUpload()
            && def.assetFields().size() == 1
            && def.assetFields().getFirst().name().equals("asset")));
  }

  @Test
  void composerComponentUploadDelegatesWithCoordinateOverrides() throws Exception {
    ComposerHostedService composerHosted = mock(ComposerHostedService.class);
    when(composerHosted.uploadArchive(
        any(), any(), eq("example.zip"), eq("application/x-tar"),
        eq("company/example"), eq("1.2.3"), eq("alice"), eq("127.0.0.1")))
        .thenReturn("company/example/1.0.0/company-example-1.0.0.zip");
    ComponentUploadService service = service(composerHosted);

    ComponentUploadService.UploadResult result = service.upload(
        "composer-hosted",
        Map.of(
            "composer.name", new String[] {"company/example"},
            "composer.version", new String[] {"1.2.3"}),
        files("composer.asset", "example.zip"),
        "alice",
        "127.0.0.1");

    assertEquals(List.of("company/example/1.0.0/company-example-1.0.0.zip"), result.paths());
  }

  @Test
  void cargoComponentUploadDelegatesToHostedCrateUpload() throws Exception {
    CargoHostedService cargoHosted = mock(CargoHostedService.class);
    when(cargoHosted.uploadCrate(any(RepositoryRuntime.class), any(InputStream.class), eq("alice"), eq("127.0.0.1")))
        .thenReturn("crates/demo/0.1.0/demo-0.1.0.crate");
    ComponentUploadService service = service(cargoHosted);

    ComponentUploadService.UploadResult result = service.upload(
        "cargo-hosted",
        Map.of(),
        files("cargo.asset", "demo-0.1.0.crate"),
        "alice",
        "127.0.0.1");

    assertEquals(List.of("crates/demo/0.1.0/demo-0.1.0.crate"), result.paths());
    verify(cargoHosted).uploadCrate(
        any(RepositoryRuntime.class), any(InputStream.class), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void cargoComponentUploadRejectsNonCrateAsset() throws Exception {
    CargoHostedService cargoHosted = mock(CargoHostedService.class);
    ComponentUploadService service = service(cargoHosted);

    UploadValidationException thrown = assertThrows(
        UploadValidationException.class,
        () -> service.upload("cargo-hosted", Map.of(), files("cargo.asset", "demo.txt"), "alice", "127.0.0.1"));

    assertEquals("Cargo upload requires a .crate asset", thrown.getMessage());
    verify(cargoHosted, never()).uploadCrate(any(), any(), any(), any());
  }

  @Test
  void pubComponentUploadDelegatesToHostedArchiveUpload() throws Exception {
    PubHostedService pubHosted = mock(PubHostedService.class);
    when(pubHosted.uploadArchive(any(RepositoryRuntime.class), any(InputStream.class), eq("alice"), eq("127.0.0.1"),
        eq("component-upload")))
        .thenReturn("packages/demo/versions/1.0.0.tar.gz");
    ComponentUploadService service = service(pubHosted);

    ComponentUploadService.UploadResult result = service.upload(
        "pub-hosted",
        Map.of(),
        files("pub.asset", "demo-1.0.0.tar.gz"),
        "alice",
        "127.0.0.1");

    assertEquals(List.of("packages/demo/versions/1.0.0.tar.gz"), result.paths());
    verify(pubHosted).uploadArchive(
        any(RepositoryRuntime.class), any(InputStream.class), eq("alice"), eq("127.0.0.1"), eq("component-upload"));
  }

  @Test
  void pubComponentUploadRejectsNonTarGzAsset() throws Exception {
    PubHostedService pubHosted = mock(PubHostedService.class);
    ComponentUploadService service = service(pubHosted);

    UploadValidationException thrown = assertThrows(
        UploadValidationException.class,
        () -> service.upload("pub-hosted", Map.of(), files("pub.asset", "demo.zip"), "alice", "127.0.0.1"));

    assertEquals("Pub upload requires a .tar.gz archive", thrown.getMessage());
    verify(pubHosted, never()).uploadArchive(any(), any(), any(), any(), any());
  }

  private static ComponentUploadService service(CargoHostedService cargoHosted) {
    return service(runtime("cargo-hosted", RepositoryFormat.CARGO), cargoHosted, mock(PubHostedService.class));
  }

  private static ComponentUploadService service(PubHostedService pubHosted) {
    return service(runtime("pub-hosted", RepositoryFormat.PUB), mock(CargoHostedService.class), pubHosted);
  }

  private static ComponentUploadService service(ComposerHostedService composerHosted) {
    RepositoryRuntime runtime = runtime("composer-hosted", RepositoryFormat.COMPOSER);
    RepositoryRuntimeRegistry registry = mock(RepositoryRuntimeRegistry.class);
    when(registry.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return new ComponentUploadService(
        registry,
        mock(AssetDao.class),
        mock(MavenHostedService.class),
        mock(NpmHostedService.class),
        mock(PypiHostedService.class),
        mock(HelmHostedService.class),
        mock(CargoHostedService.class),
        mock(PubHostedService.class),
        composerHosted,
        mock(RawHostedService.class),
        mock(YumService.class));
  }

  private static ComponentUploadService service(
      RepositoryRuntime runtime,
      CargoHostedService cargoHosted,
      PubHostedService pubHosted) {
    RepositoryRuntimeRegistry registry = mock(RepositoryRuntimeRegistry.class);
    when(registry.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return new ComponentUploadService(
        registry,
        mock(AssetDao.class),
        mock(MavenHostedService.class),
        mock(NpmHostedService.class),
        mock(PypiHostedService.class),
        mock(HelmHostedService.class),
        cargoHosted,
        pubHosted,
        mock(RawHostedService.class),
        mock(YumService.class));
  }

  private static LinkedMultiValueMap<String, MultipartFile> files(String field, String filename) {
    LinkedMultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
    files.add(field, new MockMultipartFile(
        field,
        filename,
        "application/x-tar",
        "crate".getBytes(StandardCharsets.UTF_8)));
    return files;
  }

  private static RepositoryRuntime runtime(String name, RepositoryFormat format) {
    return new RepositoryRuntime(
        1L,
        name,
        format,
        RepositoryType.HOSTED,
        name,
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        null,
        null,
        null,
        List.of());
  }
}
