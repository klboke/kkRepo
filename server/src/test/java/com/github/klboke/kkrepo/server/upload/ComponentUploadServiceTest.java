package com.github.klboke.kkrepo.server.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import com.github.klboke.kkrepo.server.ansible.AnsibleGalaxyService;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.alpine.AlpineService;
import com.github.klboke.kkrepo.server.cargo.CargoHostedService;
import com.github.klboke.kkrepo.server.composer.ComposerHostedService;
import com.github.klboke.kkrepo.server.conda.CondaService;
import com.github.klboke.kkrepo.server.conan.ConanService;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.maven.MavenHostedService;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import com.github.klboke.kkrepo.server.pypi.PypiHostedService;
import com.github.klboke.kkrepo.server.pub.PubHostedService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.swift.SwiftService;
import com.github.klboke.kkrepo.server.swift.SwiftPublishLimits;
import com.github.klboke.kkrepo.server.terraform.TerraformService;
import com.github.klboke.kkrepo.server.yum.YumService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

class ComponentUploadServiceTest {

  @Test
  void alpineDefinitionAndDelegationPreserveRepositoryNamespace() throws Exception {
    AlpineService alpine = mock(AlpineService.class);
    ComponentUploadService service = service(alpine);
    String path = "v3.23/main/x86_64/demo-1-r0.apk";
    when(alpine.publish(
        any(RepositoryRuntime.class), eq("v3.23"), eq("main"), eq("x86_64"),
        eq("demo-1-r0.apk"), any(InputStream.class), eq("alice"), eq("127.0.0.1")))
        .thenReturn(new AlpineService.PublishedPackage(
            path, "demo", "1-r0", "x86_64", "identity", "a".repeat(64), 5));

    UploadDefinition definition = service.definition("alpine");
    assertFalse(definition.multipleUpload());
    assertEquals(List.of("distribution", "channel", "repositoryArchitecture"),
        definition.componentFields().stream().map(UploadFieldDefinition::name).toList());
    assertEquals(List.of("asset"),
        definition.assetFields().stream().map(UploadFieldDefinition::name).toList());

    ComponentUploadService.UploadResult result = service.upload(
        "alpine-hosted",
        Map.of(
            "alpine.distribution", new String[] {" v3.23 "},
            "alpine.channel", new String[] {" main "},
            "alpine.repositoryArchitecture", new String[] {" x86_64 "}),
        files("alpine.asset", "demo-1-r0.apk"),
        "alice", "127.0.0.1");

    assertEquals(List.of(path), result.paths());
    verify(alpine).publish(
        any(RepositoryRuntime.class), eq("v3.23"), eq("main"), eq("x86_64"),
        eq("demo-1-r0.apk"), any(InputStream.class), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void alpineComponentUploadValidatesServiceFilenameAndCoordinates() {
    RepositoryRuntime runtime = runtime("alpine-hosted", RepositoryFormat.ALPINE);
    ComponentUploadService unavailable = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    Map<String, String[]> fields = Map.of(
        "alpine.distribution", new String[] {"v3.23"},
        "alpine.channel", new String[] {"main"},
        "alpine.repositoryArchitecture", new String[] {"x86_64"});
    assertEquals("Alpine upload service is unavailable", assertThrows(
        UploadValidationException.class,
        () -> unavailable.upload(runtime.name(), fields,
            files("alpine.asset", "demo.apk"), "alice", "ip")).getMessage());

    ComponentUploadService service = service(mock(AlpineService.class));
    assertEquals("Alpine upload requires an .apk package", assertThrows(
        UploadValidationException.class,
        () -> service.upload(runtime.name(), fields,
            files("alpine.asset", "demo.tar.gz"), "alice", "ip")).getMessage());
    assertTrue(assertThrows(UploadValidationException.class,
        () -> service.upload(runtime.name(), Map.of(),
            files("alpine.asset", "demo.apk"), "alice", "ip"))
        .getMessage().contains("distribution"));
  }

  @Test
  void aptComponentUploadUsesNexusCompatibleSingleAssetFieldAndSharedImporter() throws Exception {
    AptService apt = mock(AptService.class);
    ComponentUploadService service = service(apt);
    String path = "pool/main/d/demo/demo_1.0_amd64.deb";
    when(apt.publish(
        any(RepositoryRuntime.class), eq("demo_1.0_amd64.deb"), any(InputStream.class),
        isNull(), isNull(), eq("alice"), eq("127.0.0.1")))
        .thenReturn(new AptService.PublishedPackage(
            path, "demo", "1.0", "amd64", "a".repeat(64), 5));

    UploadDefinition definition = service.definition("apt");
    assertFalse(definition.multipleUpload());
    assertTrue(definition.componentFields().isEmpty());
    assertEquals(List.of("asset"),
        definition.assetFields().stream().map(UploadFieldDefinition::name).toList());

    ComponentUploadService.UploadResult result = service.upload(
        "apt-hosted", Map.of(), files("apt.asset", "demo_1.0_amd64.deb"),
        "alice", "127.0.0.1");

    assertEquals(List.of(path), result.paths());
    verify(apt).publish(
        any(RepositoryRuntime.class), eq("demo_1.0_amd64.deb"), any(InputStream.class),
        isNull(), isNull(), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void uploadSpecsIncludeAnsibleGalaxySingleAssetUpload() {
    ComponentUploadService service = service(mock(CargoHostedService.class));

    UploadDefinition definition = service.definition("ansiblegalaxy");

    assertFalse(definition.multipleUpload());
    assertTrue(definition.componentFields().isEmpty());
    assertEquals(List.of("asset"),
        definition.assetFields().stream().map(UploadFieldDefinition::name).toList());
  }

  @Test
  void condaUploadDefinitionAndDelegationPreserveChannelCoordinates() throws Exception {
    CondaService conda = mock(CondaService.class);
    ComponentUploadService service = service(conda);
    UploadDefinition definition = service.definition("conda");
    assertFalse(definition.multipleUpload());
    assertEquals(List.of("channel", "subdir"),
        definition.componentFields().stream().map(UploadFieldDefinition::name).toList());
    assertEquals(List.of("asset"),
        definition.assetFields().stream().map(UploadFieldDefinition::name).toList());

    ComponentUploadService.UploadResult nested = service.upload(
        "conda-hosted",
        Map.of(
            "conda.channel", new String[] {" team/release "},
            "conda.subdir", new String[] {" linux-64 "}),
        files("conda.asset", "demo-1.0-0.conda"),
        "alice", "127.0.0.1");
    assertEquals(List.of("team/release/linux-64/demo-1.0-0.conda"), nested.paths());
    verify(conda).put(
        any(RepositoryRuntime.class), eq("team/release/linux-64/demo-1.0-0.conda"),
        any(InputStream.class), eq("application/x-tar"), eq("alice"), eq("127.0.0.1"));

    ComponentUploadService.UploadResult root = service.upload(
        "conda-hosted",
        Map.of("conda.subdir", new String[] {"noarch"}),
        files("conda.asset", "root-1.0-0.tar.bz2"),
        "alice", "127.0.0.1");
    assertEquals(List.of("noarch/root-1.0-0.tar.bz2"), root.paths());
  }

  @Test
  void condaComponentUploadValidatesServiceAssetSubdirAndFilename() {
    RepositoryRuntime runtime = runtime("conda-hosted", RepositoryFormat.CONDA);
    ComponentUploadService unavailable = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    UploadValidationException missingService = assertThrows(
        UploadValidationException.class,
        () -> unavailable.upload(runtime.name(),
            Map.of("conda.subdir", new String[] {"noarch"}),
            files("conda.asset", "demo-1.0-0.conda"), "alice", "127.0.0.1"));
    assertEquals("Conda upload service is unavailable", missingService.getMessage());

    CondaService conda = mock(CondaService.class);
    ComponentUploadService service = service(conda);
    LinkedMultiValueMap<String, MultipartFile> multiple = files(
        "conda.asset", "demo-1.0-0.conda");
    multiple.add("conda.asset2", new MockMultipartFile(
        "conda.asset2", "other-1.0-0.conda", "application/octet-stream", new byte[] {1}));
    assertEquals("Conda upload requires exactly one package", assertThrows(
        UploadValidationException.class,
        () -> service.upload(runtime.name(),
            Map.of("conda.subdir", new String[] {"noarch"}), multiple,
            "alice", "127.0.0.1")).getMessage());
    assertTrue(assertThrows(UploadValidationException.class,
        () -> service.upload(runtime.name(), Map.of(),
            files("conda.asset", "demo-1.0-0.conda"), "alice", "127.0.0.1"))
        .getMessage().contains("subdir"));

    LinkedMultiValueMap<String, MultipartFile> unnamed = new LinkedMultiValueMap<>();
    unnamed.add("conda.asset", new MockMultipartFile(
        "conda.asset", null, "application/octet-stream", new byte[] {1}));
    assertEquals("Conda package filename is required", assertThrows(
        UploadValidationException.class,
        () -> service.upload(runtime.name(),
            Map.of("conda.subdir", new String[] {"noarch"}), unnamed,
            "alice", "127.0.0.1")).getMessage());
    verify(conda, never()).put(any(), any(), any(), any(), any(), any());
  }

  @Test
  void conanDefinitionAndManifestLastPublicationPreserveRevisionRoutes() throws Exception {
    ConanService conan = mock(ConanService.class);
    ComponentUploadService service = service(conan);
    UploadDefinition definition = service.definition("conan");
    assertTrue(definition.multipleUpload());
    assertEquals(
        List.of("name", "version", "user", "channel", "rrev", "package-id", "prev"),
        definition.componentFields().stream().map(UploadFieldDefinition::name).toList());
    assertEquals(List.of("filename", "asset"),
        definition.assetFields().stream().map(UploadFieldDefinition::name).toList());

    LinkedMultiValueMap<String, MultipartFile> uploads = new LinkedMultiValueMap<>();
    uploads.add("conan.asset1", new MockMultipartFile(
        "conan.asset1", "conanmanifest.txt", "text/plain", "manifest".getBytes()));
    uploads.add("conan.asset2", new MockMultipartFile(
        "conan.asset2", "conanfile.py", "text/x-python", "recipe".getBytes()));
    ComponentUploadService.UploadResult result = service.upload(
        "conan-hosted",
        Map.of(
            "conan.name", new String[] {"demo"},
            "conan.version", new String[] {"1.0"},
            "conan.user", new String[] {"acme"},
            "conan.channel", new String[] {"stable"},
            "conan.rrev", new String[] {"rrev"},
            "conan.asset1.filename", new String[] {"conanmanifest.txt"},
            "conan.asset2.filename", new String[] {"conanfile.py"}),
        uploads,
        "alice",
        "127.0.0.1");

    assertEquals(List.of("conanfile.py", "conanmanifest.txt"), result.paths());
    InOrder order = inOrder(conan);
    order.verify(conan).putInternal(
        any(), eq("v2/conans/demo/1.0/acme/stable/revisions/rrev/files/conanfile.py"),
        any(), eq(6L), eq("text/x-python"), eq(sha1("recipe")),
        eq("alice"), eq("127.0.0.1"));
    order.verify(conan).putInternal(
        any(), eq("v2/conans/demo/1.0/acme/stable/revisions/rrev/files/conanmanifest.txt"),
        any(), eq(8L), eq("text/plain"), eq(sha1("manifest")),
        eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void conanPackageUploadBuildsTheBinaryRevisionRoute() throws Exception {
    ConanService conan = mock(ConanService.class);
    ComponentUploadService service = service(conan);
    LinkedMultiValueMap<String, MultipartFile> uploads = new LinkedMultiValueMap<>();
    uploads.add("conan.asset1", new MockMultipartFile(
        "conan.asset1", "conan_package.tgz", "application/gzip", new byte[] {1}));
    uploads.add("conan.asset2", new MockMultipartFile(
        "conan.asset2", "conanmanifest.txt", "text/plain", new byte[] {2}));

    service.upload(
        "conan-hosted",
        Map.of(
            "conan.name", new String[] {"demo"},
            "conan.version", new String[] {"1.0"},
            "conan.rrev", new String[] {"rrev"},
            "conan.package-id", new String[] {"pkg"},
            "conan.prev", new String[] {"prev"}),
        uploads,
        "alice",
        "ip");

    verify(conan).putInternal(
        any(),
        eq("v2/conans/demo/1.0/_/_/revisions/rrev/packages/pkg/revisions/prev/files/"
            + "conan_package.tgz"),
        any(), eq(1L), eq("application/gzip"), any(), eq("alice"), eq("ip"));
  }

  @Test
  void conanComponentUploadValidatesServiceCoordinatesPathsAndCommitFile() {
    RepositoryRuntime runtime = runtime("conan-hosted", RepositoryFormat.CONAN);
    ComponentUploadService unavailable = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    Map<String, String[]> coordinates = Map.of(
        "conan.name", new String[] {"demo"},
        "conan.version", new String[] {"1.0"},
        "conan.rrev", new String[] {"rrev"});
    assertEquals("Conan upload service is unavailable", assertThrows(
        UploadValidationException.class,
        () -> unavailable.upload(
            runtime.name(), coordinates, files("conan.asset", "conanmanifest.txt"),
            "alice", "ip")).getMessage());

    ConanService conan = mock(ConanService.class);
    ComponentUploadService service = service(conan);
    assertTrue(assertThrows(
        UploadValidationException.class,
        () -> service.upload(
            runtime.name(),
            Map.of(
                "conan.name", new String[] {"demo"},
                "conan.version", new String[] {"1.0"},
                "conan.rrev", new String[] {"rrev"},
                "conan.package-id", new String[] {"pkg"}),
            files("conan.asset", "conanmanifest.txt"), "alice", "ip"))
        .getMessage().contains("package-id and prev"));
    assertTrue(assertThrows(
        UploadValidationException.class,
        () -> service.upload(
            runtime.name(), coordinates,
            files("conan.asset", "not-the-manifest.txt"), "alice", "ip"))
        .getMessage().contains("exactly one conanmanifest.txt"));
    Map<String, String[]> invalidPathFields = new java.util.LinkedHashMap<>(coordinates);
    invalidPathFields.put("conan.asset.filename", new String[] {"../escape"});
    assertTrue(assertThrows(
        UploadValidationException.class,
        () -> service.upload(
            runtime.name(),
            invalidPathFields,
            files("conan.asset", "conanmanifest.txt"), "alice", "ip"))
        .getMessage().contains("Invalid Conan revision file path"));
    verify(conan, never()).putInternal(
        any(), any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void ansibleGalaxyComponentUploadDelegatesCanonicalArchiveToProtocolService() throws Exception {
    AnsibleGalaxyService ansibleService = mock(AnsibleGalaxyService.class);
    ComponentUploadService service = service(ansibleService);

    ComponentUploadService.UploadResult result = service.upload(
        "ansible-hosted",
        Map.of(),
        files("ansiblegalaxy.asset", "acme-tools-1.2.3.tar.gz"),
        "alice",
        "127.0.0.1");

    String path = AnsibleGalaxyPathParser.ARTIFACT_BASE + "acme-tools-1.2.3.tar.gz";
    assertEquals(List.of(path), result.paths());
    verify(ansibleService).putArtifact(
        any(RepositoryRuntime.class), eq(path), any(InputStream.class),
        eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void ansibleGalaxyComponentUploadValidatesServiceAndArchiveName() {
    RepositoryRuntime runtime = runtime("ansible-hosted", RepositoryFormat.ANSIBLEGALAXY);
    ComponentUploadService unavailable = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));

    UploadValidationException missingService = assertThrows(
        UploadValidationException.class,
        () -> unavailable.upload(
            runtime.name(), Map.of(),
            files("ansiblegalaxy.asset", "acme-tools-1.2.3.tar.gz"),
            "alice", "127.0.0.1"));
    assertEquals("Ansible upload service is unavailable", missingService.getMessage());

    AnsibleGalaxyService ansibleService = mock(AnsibleGalaxyService.class);
    ComponentUploadService service = service(ansibleService);
    UploadValidationException invalidName = assertThrows(
        UploadValidationException.class,
        () -> service.upload(
            runtime.name(), Map.of(), files("ansiblegalaxy.asset", "collection.tar.gz"),
            "alice", "127.0.0.1"));
    assertTrue(invalidName.getMessage().contains("canonical namespace-name-version.tar.gz"));
    verify(ansibleService, never()).putArtifact(any(), any(), any(), any(), any());
  }

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
  void uploadSpecsExposeSwiftPublicationContract() {
    ComponentUploadService service = service(mock(CargoHostedService.class));

    UploadDefinition definition = service.definition("swift");

    assertEquals(List.of("scope", "name", "version", "metadata", "signature-format"),
        definition.componentFields().stream().map(UploadFieldDefinition::name).toList());
    assertEquals(List.of("source-archive", "source-archive-signature", "metadata-signature"),
        definition.assetFields().stream().map(UploadFieldDefinition::name).toList());
  }

  @Test
  void swiftComponentUploadDelegatesToSharedImmutablePublishService() throws Exception {
    SwiftService swiftService = mock(SwiftService.class);
    ComponentUploadService service = service(swiftService);
    LinkedMultiValueMap<String, MultipartFile> uploads = files(
        "swift.source-archive", "acme-library-1.2.3.zip");
    uploads.add("swift.source-archive-signature", new MockMultipartFile(
        "swift.source-archive-signature", "archive.sig", "application/octet-stream", new byte[] {7, 8}));

    ComponentUploadService.UploadResult result = service.upload(
        "swift-hosted",
        Map.of(
            "swift.scope", new String[] {"acme"},
            "swift.name", new String[] {"library"},
            "swift.version", new String[] {"1.2.3"},
            "swift.metadata", new String[] {"{\"repositoryURLs\":[\"https://github.com/acme/library\"]}"},
            "swift.signature-format", new String[] {"cms-1.0.0"}),
        uploads,
        "alice",
        "127.0.0.1");

    assertEquals(List.of("acme/library/1.2.3"), result.paths());
    verify(swiftService).publishUpload(
        any(RepositoryRuntime.class),
        eq("acme"),
        eq("library"),
        eq("1.2.3"),
        any(InputStream.class),
        eq("{\"repositoryURLs\":[\"https://github.com/acme/library\"]}"),
        any(byte[].class),
        isNull(),
        eq("cms-1.0.0"),
        isNull(),
        eq("alice"),
        eq("127.0.0.1"));
  }

  @Test
  void swiftComponentUploadRejectsOversizedSignatureBeforePublication() {
    SwiftService swiftService = mock(SwiftService.class);
    ComponentUploadService service = service(swiftService);
    LinkedMultiValueMap<String, MultipartFile> uploads = files(
        "swift.source-archive", "acme-library-1.2.3.zip");
    uploads.add("swift.source-archive-signature", new MockMultipartFile(
        "swift.source-archive-signature",
        "archive.sig",
        "application/octet-stream",
        new byte[SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES + 1]));

    UploadValidationException failure = assertThrows(
        UploadValidationException.class,
        () -> service.upload(
            "swift-hosted",
            Map.of(
                "swift.scope", new String[] {"acme"},
                "swift.name", new String[] {"library"},
                "swift.version", new String[] {"1.2.3"}),
            uploads,
            "alice",
            "127.0.0.1"));

    assertTrue(failure.getMessage().contains("4 KiB"));
    verify(swiftService, never()).publishUpload(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void swiftComponentUploadRejectsOversizedMetadataBeforePublication() {
    SwiftService swiftService = mock(SwiftService.class);
    ComponentUploadService service = service(swiftService);
    String metadata = "x".repeat(SwiftPublishLimits.MAX_METADATA_BYTES + 1);

    UploadValidationException failure = assertThrows(
        UploadValidationException.class,
        () -> service.upload(
            "swift-hosted",
            Map.of(
                "swift.scope", new String[] {"acme"},
                "swift.name", new String[] {"library"},
                "swift.version", new String[] {"1.2.3"},
                "swift.metadata", new String[] {metadata}),
            files("swift.source-archive", "acme-library-1.2.3.zip"),
            "alice",
            "127.0.0.1"));

    assertTrue(failure.getMessage().contains("1 MiB"));
    verify(swiftService, never()).publishUpload(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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

  @Test
  void terraformComponentUploadRejectsMultipleAssets() throws Exception {
    TerraformService terraformService = mock(TerraformService.class);
    ComponentUploadService service = service(terraformService);
    LinkedMultiValueMap<String, MultipartFile> uploads = files("terraform.asset", "provider.zip");
    uploads.add("terraform.asset", new MockMultipartFile(
        "terraform.asset", "extra.zip", "application/zip", new byte[] {1}));

    UploadValidationException thrown = assertThrows(
        UploadValidationException.class,
        () -> service.upload(
            "terraform-hosted",
            Map.of(
                "terraform.kind", new String[] {"provider"},
                "terraform.namespace", new String[] {"acme"},
                "terraform.name", new String[] {"cloud"},
                "terraform.version", new String[] {"1.2.3"},
                "terraform.os", new String[] {"linux"},
                "terraform.arch", new String[] {"amd64"}),
            uploads,
            "alice",
            "127.0.0.1"));

    assertEquals("Terraform upload requires exactly one archive", thrown.getMessage());
    verify(terraformService, never()).put(
        any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void terraformComponentUploadForwardsExplicitProviderProtocols() throws Exception {
    TerraformService terraformService = mock(TerraformService.class);
    ComponentUploadService service = service(terraformService);
    LinkedMultiValueMap<String, MultipartFile> uploads = new LinkedMultiValueMap<>();
    uploads.add("terraform.asset", new MockMultipartFile(
        "terraform.asset",
        "terraform-provider-cloud_1.2.3_linux_amd64.zip",
        "application/zip",
        new byte[] {1}));

    ComponentUploadService.UploadResult result = service.upload(
        "terraform-hosted",
        Map.of(
            "terraform.kind", new String[] {"provider"},
            "terraform.namespace", new String[] {"acme"},
            "terraform.name", new String[] {"cloud"},
            "terraform.version", new String[] {"1.2.3"},
            "terraform.os", new String[] {"linux"},
            "terraform.arch", new String[] {"amd64"},
            "terraform.protocols", new String[] {"6.0"}),
        uploads,
        "alice",
        "127.0.0.1");

    assertEquals(
        List.of("v1/providers/acme/cloud/1.2.3/download/linux/amd64"), result.paths());
    verify(terraformService).put(
        any(),
        any(),
        any(),
        eq("application/zip"),
        eq("attachment; filename=\"terraform-provider-cloud_1.2.3_linux_amd64.zip\""),
        eq("6.0"),
        eq("alice"),
        eq("127.0.0.1"));
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

  private static ComponentUploadService service(TerraformService terraformService) {
    RepositoryRuntime runtime = runtime("terraform-hosted", RepositoryFormat.TERRAFORM);
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
        mock(ComposerHostedService.class),
        mock(RawHostedService.class),
        mock(YumService.class),
        terraformService);
  }

  private static ComponentUploadService service(SwiftService swiftService) {
    RepositoryRuntime runtime = runtime("swift-hosted", RepositoryFormat.SWIFT);
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
        mock(ComposerHostedService.class),
        mock(RawHostedService.class),
        mock(YumService.class),
        mock(TerraformService.class),
        swiftService);
  }

  private static ComponentUploadService service(AnsibleGalaxyService ansibleService) {
    RepositoryRuntime runtime = runtime("ansible-hosted", RepositoryFormat.ANSIBLEGALAXY);
    ComponentUploadService service = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    service.setAnsibleGalaxyService(ansibleService);
    return service;
  }

  private static ComponentUploadService service(CondaService condaService) {
    RepositoryRuntime runtime = runtime("conda-hosted", RepositoryFormat.CONDA);
    ComponentUploadService service = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    service.setCondaService(condaService);
    return service;
  }

  private static ComponentUploadService service(ConanService conanService) {
    RepositoryRuntime runtime = runtime("conan-hosted", RepositoryFormat.CONAN);
    ComponentUploadService service = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    service.setConanService(conanService);
    return service;
  }

  private static ComponentUploadService service(AptService aptService) {
    RepositoryRuntime runtime = runtime("apt-hosted", RepositoryFormat.APT);
    ComponentUploadService service = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    service.setAptService(aptService);
    return service;
  }

  private static ComponentUploadService service(AlpineService alpineService) {
    RepositoryRuntime runtime = runtime("alpine-hosted", RepositoryFormat.ALPINE);
    ComponentUploadService service = service(
        runtime, mock(CargoHostedService.class), mock(PubHostedService.class));
    service.setAlpineService(alpineService);
    return service;
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

  private static String sha1(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-1")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
