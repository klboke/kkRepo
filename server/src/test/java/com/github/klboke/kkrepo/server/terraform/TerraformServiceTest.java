package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TerraformServiceTest {
  private static final String BASE = "https://repo.example/repository/terraform";
  private final TerraformPathParser paths = new TerraformPathParser();
  private ObjectMapper mapper;
  private TerraformAssetSupport assets;
  private TerraformArchiveInspector inspector;
  private TerraformSigningService signing;
  private TerraformSignatureVerifier signatureVerifier;
  private TerraformRegistryDao registry;
  private RepositoryRuntimeRegistry runtimes;
  private RawProxyService proxy;
  private HttpRemoteFetcher fetcher;
  private TerraformService service;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    assets = mock(TerraformAssetSupport.class);
    inspector = mock(TerraformArchiveInspector.class);
    signing = mock(TerraformSigningService.class);
    signatureVerifier = mock(TerraformSignatureVerifier.class);
    registry = mock(TerraformRegistryDao.class);
    runtimes = mock(RepositoryRuntimeRegistry.class);
    proxy = mock(RawProxyService.class);
    fetcher = mock(HttpRemoteFetcher.class);
    service = new TerraformService(
        mapper, assets, inspector, signing, signatureVerifier, registry,
        new TerraformPublishLeaseManager(registry), runtimes, proxy, fetcher);
  }

  @Test
  void servesAndPublishesHostedModulesWithNexusUrlTokenSemantics() throws Exception {
    RepositoryRuntime hosted = runtime(1, "terraform", RepositoryType.HOSTED, null, List.of());
    AssetRecord versionOne = asset(1, hosted, 11L,
        "v1/modules/acme/network/aws/1.0.0/network.zip");
    AssetRecord versionTwo = asset(2, hosted, 12L,
        "v1/modules/acme/network/aws/2.0.0/network.zip");
    when(assets.list(eq(hosted), anyString())).thenReturn(List.of(versionOne, versionTwo));
    when(assets.list(hosted, "v1/modules/acme/network/aws/2.0.0/"))
        .thenReturn(List.of(versionTwo));

    MavenResponse versions = service.get(
        hosted, paths.parse("v1/modules/acme/network/aws/versions"), BASE, false);
    Map<String, Object> versionsJson = json(versions);
    assertTrue(versionsJson.toString().contains("2.0.0"));
    assertTrue(versionsJson.toString().indexOf("2.0.0") < versionsJson.toString().indexOf("1.0.0"));

    MavenResponse download = service.get(
        hosted, paths.parse("v1/modules/acme/network/aws/2.0.0/download"), BASE,
        "dXNlcjpwYXNz", false);
    assertEquals(204, download.status());
    assertEquals(
        BASE + "/v1/modules/dXNlcjpwYXNz/acme/network/aws/2.0.0/network.zip",
        download.headers().get("X-Terraform-Get"));

    when(assets.serve(hosted, versionTwo.path(), false)).thenReturn(MavenResponse.noBody(200));
    assertEquals(200, service.get(hosted, paths.parse(versionTwo.path()), BASE, false).status());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, paths.parse("unrelated/path"), BASE, false));

    Path buffered = Files.createTempFile("terraform-module-service-test", ".zip");
    Files.writeString(buffered, "module");
    when(inspector.bufferAndInspect(any(), eq("new.zip"), eq(true), eq(null))).thenReturn(buffered);
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);
    when(assets.find(hosted, "v1/modules/acme/network/aws/3.0.0/new.zip"))
        .thenReturn(Optional.empty(), Optional.of(asset(3, hosted, 13L,
            "v1/modules/acme/network/aws/3.0.0/new.zip")));
    MavenResponse published = service.put(
        hosted,
        paths.parse("v1/modules/acme/network/aws/3.0.0/new.zip"),
        new ByteArrayInputStream("module".getBytes(StandardCharsets.UTF_8)),
        null, null, "alice", "127.0.0.1");
    assertEquals(201, published.status());
    assertFalse(Files.exists(buffered));
    verify(registry).tryAcquirePublishLease(anyString(), anyString(), any());
    verify(registry).releasePublishLease(anyString(), anyString());
    verify(assets).store(eq(hosted), eq("v1/modules/acme/network/aws/3.0.0/new.zip"),
        any(), eq("application/octet-stream"), any(), eq("alice"), eq("127.0.0.1"));

    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> service.put(runtime(2, "denied", RepositoryType.HOSTED, null, List.of(), "DENY"),
            paths.parse("v1/modules/acme/network/aws/3.0.0/new.zip"),
            new ByteArrayInputStream(new byte[0]), null, null, "alice", null));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> service.put(hosted,
            paths.parse("v1/modules/acme/network/aws/1.0.0/alternate.zip"),
            new ByteArrayInputStream(new byte[0]), null, null, "alice", null));
    RepositoryRuntime allow = runtime(
        4, "allow", RepositoryType.HOSTED, null, List.of(), "ALLOW");
    when(assets.list(allow, "v1/modules/acme/network/aws/1.0.0/"))
        .thenReturn(List.of(asset(
            4, allow, 14L, "v1/modules/acme/network/aws/1.0.0/network.zip")));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> service.put(allow,
            paths.parse("v1/modules/acme/network/aws/1.0.0/alternate.zip"),
            new ByteArrayInputStream(new byte[0]), null, null, "alice", null));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.put(runtime(3, "proxy", RepositoryType.PROXY, "https://registry.example", List.of()),
            paths.parse("v1/modules/acme/network/aws/3.0.0/new.zip"),
            new ByteArrayInputStream(new byte[0]), null, null, "alice", null));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.put(hosted, paths.parse("v1/modules/acme/network/aws/versions"),
            new ByteArrayInputStream(new byte[0]), null, null, "alice", null));
  }

  @Test
  void rechecksModuleCoordinateWhileHoldingSharedPublishLease() throws Exception {
    RepositoryRuntime hosted = runtime(5, "terraform", RepositoryType.HOSTED, null, List.of());
    TerraformPath upload = paths.parse("v1/modules/acme/network/aws/1.0.0/alternate.zip");
    AssetRecord concurrent = asset(
        6, hosted, 16L, "v1/modules/acme/network/aws/1.0.0/network.zip");
    Path buffered = Files.createTempFile("terraform-module-lease-test", ".zip");
    Files.writeString(buffered, "module");
    when(assets.list(hosted, "v1/modules/acme/network/aws/1.0.0/"))
        .thenReturn(List.of(), List.of(concurrent));
    when(inspector.bufferAndInspect(any(), eq("alternate.zip"), eq(true), eq(null)))
        .thenReturn(buffered);
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);

    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> service.put(hosted, upload, new ByteArrayInputStream(new byte[0]),
            null, null, "alice", null));

    assertFalse(Files.exists(buffered));
    verify(registry).releasePublishLease(anyString(), anyString());
    verify(assets, never()).store(eq(hosted), anyString(), any(), anyString(), any(), any(), any());
  }

  @Test
  void migrationPublishesModuleThroughDenyPolicyWithSharedLease() throws Exception {
    RepositoryRuntime denied = runtime(
        6, "terraform-denied", RepositoryType.HOSTED, null, List.of(), "DENY");
    TerraformPath upload = paths.parse("v1/modules/acme/network/aws/1.0.0/module.zip");
    Path buffered = Files.createTempFile("terraform-module-migration-test", ".zip");
    Files.writeString(buffered, "module");
    when(assets.list(denied, "v1/modules/acme/network/aws/1.0.0/"))
        .thenReturn(List.of());
    when(inspector.bufferAndInspect(any(), eq("module.zip"), eq(true), eq(null)))
        .thenReturn(buffered);
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);

    MavenResponse response = service.putForMigration(
        denied, upload, new ByteArrayInputStream(new byte[0]),
        "application/zip", null, "nexus-migration", null);

    assertEquals(201, response.status());
    assertFalse(Files.exists(buffered));
    verify(assets).store(
        eq(denied), eq(upload.rawPath()), any(), eq("application/zip"), any(),
        eq("nexus-migration"), eq(null));
    verify(registry).releasePublishLease(anyString(), anyString());
  }

  @Test
  void restoresValidatedProxyCacheArchivesWithoutHostedPublicationState() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        7, "terraform-proxy", RepositoryType.PROXY, "https://registry.terraform.io/", List.of());
    TerraformPath module = paths.parse(
        "v1/modules/terraform-aws-modules/vpc/aws/5.21.0/module.zip");
    TerraformPath provider = paths.parse(
        "v1/providers/hashicorp/null/3.2.4/download/linux/amd64/"
            + "terraform-provider-null_3.2.4_linux_amd64.zip");
    Path bufferedModule = Files.createTempFile("terraform-proxy-module-migration", ".zip");
    Path bufferedProvider = Files.createTempFile("terraform-proxy-provider-migration", ".zip");
    Files.writeString(bufferedModule, "module");
    Files.writeString(bufferedProvider, "provider");
    when(inspector.bufferAndInspect(any(), eq(module.filename()), eq(true), eq(null)))
        .thenReturn(bufferedModule);
    when(inspector.bufferAndInspect(any(), eq(provider.filename()), eq(false), eq("null")))
        .thenReturn(bufferedProvider);
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);
    when(assets.find(proxyRuntime, module.rawPath())).thenReturn(Optional.empty());
    when(assets.find(proxyRuntime, provider.rawPath())).thenReturn(Optional.empty());

    assertEquals(201, service.restoreProxyCacheForMigration(
        proxyRuntime, module, new ByteArrayInputStream(new byte[0]),
        "application/zip", "nexus-migration", "10.0.0.4").status());
    assertEquals(201, service.restoreProxyCacheForMigration(
        proxyRuntime, provider, new ByteArrayInputStream(new byte[0]),
        null, "nexus-migration", null).status());

    assertFalse(Files.exists(bufferedModule));
    assertFalse(Files.exists(bufferedProvider));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> attributes = ArgumentCaptor.forClass(Map.class);
    verify(assets).store(
        eq(proxyRuntime), eq(module.rawPath()), any(), eq("application/zip"),
        attributes.capture(), eq("nexus-migration"), eq("10.0.0.4"));
    verify(assets).store(
        eq(proxyRuntime), eq(provider.rawPath()), any(), eq("application/octet-stream"),
        attributes.capture(), eq("nexus-migration"), eq(null));
    assertEquals("proxy-module-archive", attributes.getAllValues().get(0).get("terraformKind"));
    assertEquals("aws", attributes.getAllValues().get(0).get("system"));
    assertEquals("proxy-provider-archive", attributes.getAllValues().get(1).get("terraformKind"));
    assertEquals("linux", attributes.getAllValues().get(1).get("os"));
    assertEquals("amd64", attributes.getAllValues().get(1).get("arch"));
    verify(registry, times(2)).releasePublishLease(anyString(), anyString());
    verify(registry, never()).publishProvider(any(), any());
    verify(signing, never()).active(any());
  }

  @Test
  void proxyCacheRestoreIsIdempotentUnderTheSharedLease() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        8, "terraform-proxy", RepositoryType.PROXY, "https://registry.terraform.io/", List.of());
    TerraformPath module = paths.parse(
        "v1/modules/terraform-aws-modules/vpc/aws/5.21.0/module.zip");
    Path buffered = Files.createTempFile("terraform-proxy-idempotent-migration", ".zip");
    Files.writeString(buffered, "module");
    when(inspector.bufferAndInspect(any(), eq(module.filename()), eq(true), eq(null)))
        .thenReturn(buffered);
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);
    when(assets.find(proxyRuntime, module.rawPath()))
        .thenReturn(Optional.of(asset(70, proxyRuntime, 71L, module.rawPath())));

    assertEquals(201, service.restoreProxyCacheForMigration(
        proxyRuntime, module, new ByteArrayInputStream(new byte[0]),
        "application/zip", "nexus-migration", null).status());

    assertFalse(Files.exists(buffered));
    verify(assets, never()).store(
        eq(proxyRuntime), anyString(), any(), anyString(), any(), any(), any());
    verify(registry).releasePublishLease(anyString(), anyString());
  }

  @Test
  void rejectsProxyCacheRestoreForTheWrongRepositoryTypeOrRoute() {
    RepositoryRuntime hosted = runtime(9, "terraform", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxyRuntime = runtime(
        10, "terraform-proxy", RepositoryType.PROXY, "https://registry.terraform.io/", List.of());

    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.restoreProxyCacheForMigration(
        hosted,
        paths.parse("v1/modules/acme/network/aws/1.0.0/module.zip"),
        new ByteArrayInputStream(new byte[0]), null, "nexus-migration", null));
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.restoreProxyCacheForMigration(
        proxyRuntime,
        paths.parse("v1/providers/hashicorp/null/versions"),
        new ByteArrayInputStream(new byte[0]), null, "nexus-migration", null));

    verify(inspector, never()).bufferAndInspect(any(), anyString(), anyBoolean(), any());
  }

  @Test
  void servesHostedProviderMetadataArchivesAndSignatures() throws Exception {
    RepositoryRuntime hosted = runtime(10, "terraform", RepositoryType.HOSTED, null, List.of());
    String archivePath = "v1/providers/acme/cloud/1.2.3/package/linux/"
        + "terraform-provider-cloud_1.2.3_linux_amd64.zip";
    String sumsPath = "v1/providers/acme/cloud/1.2.3/metadata-r2/"
        + "terraform-provider-cloud_1.2.3_SHA256SUMS";
    String signaturePath = sumsPath + ".sig";
    String oldSumsPath = "v1/providers/acme/cloud/1.2.3/metadata-r1/"
        + "terraform-provider-cloud_1.2.3_SHA256SUMS";
    String deletedPath = "v1/providers/acme/cloud/1.2.3/package/darwin/"
        + "terraform-provider-cloud_1.2.3_darwin_arm64.zip";
    AssetRecord archive = asset(20, hosted, 21L, archivePath);
    TerraformRegistryDao.ProviderPlatform platform = platform(hosted, archivePath, "abc123", 1);
    TerraformRegistryDao.ProviderPlatform deleted =
        providerPlatform(hosted, deletedPath, "darwin", "arm64");
    TerraformRegistryDao.ProviderState state = new TerraformRegistryDao.ProviderState(
        hosted.id(), "acme", "cloud", "1.2.3", 2, sumsPath, signaturePath, 2, Instant.now());
    when(assets.list(eq(hosted), anyString())).thenReturn(List.of(archive));
    when(registry.findProviderState(hosted.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(Optional.of(state));
    when(registry.listProviderPlatforms(hosted.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(List.of(platform, deleted));
    when(registry.findSigningKey(hosted.id(), 2)).thenReturn(Optional.of(
        new TerraformRegistryDao.SigningKey(hosted.id(), 2, "0011223344556677",
            "encrypted", "PUBLIC KEY", Instant.now())));
    when(assets.find(hosted, archivePath)).thenReturn(Optional.of(archive));

    Map<String, Object> versions = json(service.get(
        hosted, paths.parse("v1/providers/acme/cloud/versions"), BASE, false));
    assertTrue(versions.toString().contains("linux"));
    assertTrue(versions.toString().contains("amd64"));
    assertFalse(versions.toString().contains("darwin"));

    Map<String, Object> download = json(service.get(
        hosted, paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64"), BASE,
        "token", false));
    assertEquals("abc123", download.get("shasum"));
    assertEquals(
        BASE + "/v1/providers/token/acme/cloud/1.2.3/download/linux/amd64/"
            + "terraform-provider-cloud_1.2.3_linux_amd64.zip",
        download.get("download_url"));
    assertTrue(download.toString().contains("0011223344556677"));
    assertFalse(service.get(
        hosted, paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64"), BASE,
        null, true).hasBody());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted,
            paths.parse("v1/providers/acme/cloud/1.2.3/download/darwin/arm64"), BASE, false));

    when(assets.serve(eq(hosted), anyString(), anyBoolean())).thenReturn(MavenResponse.noBody(200));
    assertEquals(200, service.get(hosted, paths.parse(archivePath), BASE, false).status());
    String nexusArchivePath = "v1/providers/acme/cloud/1.2.3/download/linux/amd64/"
        + "terraform-provider-cloud_1.2.3_linux_amd64.zip";
    assertEquals(200, service.get(hosted, paths.parse(nexusArchivePath), BASE, false).status());
    verify(assets, times(2)).serve(hosted, archivePath, false);
    assertEquals(200, service.get(hosted, paths.parse(sumsPath), BASE, true).status());
    assertEquals(200, service.get(hosted, paths.parse(signaturePath), BASE, false).status());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, paths.parse(oldSumsPath), BASE, false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, paths.parse(oldSumsPath.replace("metadata-r1", "metadata-r3")),
            BASE, false));

    when(registry.listProviderPlatforms(hosted.id(), "acme", "cloud", "9.9.9"))
        .thenReturn(List.of());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted,
            paths.parse("v1/providers/acme/cloud/9.9.9/package/linux/missing.zip"), BASE, false));
  }

  @Test
  void reinspectsOrphanedMigratedProviderBeforePublishingThroughDenyPolicy() throws Exception {
    RepositoryRuntime hosted = runtime(
        30, "terraform", RepositoryType.HOSTED, null, List.of(), "DENY");
    TerraformPath upload = paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64");
    String filename = "terraform-provider-cloud_1.2.3_linux_amd64.zip";
    String archivePath = "v1/providers/acme/cloud/1.2.3/package/linux/" + filename;
    Path buffered = Files.createTempFile("terraform-provider-service-test", ".zip");
    Files.writeString(buffered, "provider");
    AssetRecord archive = asset(31, hosted, 32L, archivePath);
    AssetBlobRecord blob = blob(32, "feedface", 8);
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);
    when(registry.listProviderPlatforms(hosted.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(List.of());
    when(registry.findProviderState(hosted.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(Optional.empty());
    when(inspector.bufferAndInspect(any(), eq(filename), eq(false), eq("cloud"))).thenReturn(buffered);
    when(assets.find(hosted, archivePath)).thenReturn(Optional.of(archive));
    when(assets.blob(archive)).thenReturn(blob);
    when(signing.active(hosted)).thenReturn(
        new TerraformSigningService.SigningMaterial(4, "AABBCCDDEEFF0011", "public", "private", "pass"));
    when(signing.sign(any(), any())).thenReturn("signature".getBytes(StandardCharsets.UTF_8));

    MavenResponse response = service.putForMigration(
        hosted, upload, new ByteArrayInputStream("provider".getBytes(StandardCharsets.UTF_8)),
        "application/zip", "attachment; filename=\"" + filename + "\"", "alice", "127.0.0.1");
    assertEquals(201, response.status());
    assertFalse(Files.exists(buffered));
    var publicationOrder = inOrder(inspector, registry);
    publicationOrder.verify(inspector).bufferAndInspect(any(), eq(filename), eq(false), eq("cloud"));
    publicationOrder.verify(registry).tryAcquirePublishLease(anyString(), anyString(), any());
    verify(registry).releasePublishLease(anyString(), anyString());
    ArgumentCaptor<TerraformRegistryDao.ProviderState> state =
        ArgumentCaptor.forClass(TerraformRegistryDao.ProviderState.class);
    verify(registry).publishProvider(any(TerraformRegistryDao.ProviderPlatform.class), state.capture());
    assertEquals(1, state.getValue().revision());
    assertEquals(4, state.getValue().signingKeyRevision());
    verify(assets).store(
        eq(hosted), eq(archivePath), any(), eq("application/zip"), any(),
        eq("alice"), eq("127.0.0.1"));
    verify(assets, atLeastOnce()).storeBytes(eq(hosted), anyString(), any(), anyString(), any());

    assertThrows(MavenExceptions.BadRequestException.class,
        () -> service.putForMigration(hosted, upload, new ByteArrayInputStream(new byte[0]), null,
            "attachment; filename=\"wrong.zip\"", "alice", null));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> service.putForMigration(hosted, upload, new ByteArrayInputStream(new byte[0]), null,
            "attachment; filename=\"terraform-provider-cloud_1.2.3_linux_amd64%2Fevil.zip\"",
            "alice", null));
  }

  @Test
  void deniesProviderPublishBeforeAcquiringTheSharedLease() {
    RepositoryRuntime denied = runtime(
        31, "terraform-denied", RepositoryType.HOSTED, null, List.of(), "DENY");
    TerraformPath upload = paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64");
    String filename = "terraform-provider-cloud_1.2.3_linux_amd64.zip";

    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> service.put(
            denied, upload, new ByteArrayInputStream(new byte[0]), "application/zip",
            "attachment; filename=\"" + filename + "\"", "alice", "127.0.0.1"));

    verify(registry, never()).tryAcquirePublishLease(anyString(), anyString(), any());
    verify(inspector, never()).bufferAndInspect(any(), anyString(), anyBoolean(), anyString());
  }

  @Test
  void redeploysExistingProviderPlatformWhenWritePolicyAllows() throws Exception {
    RepositoryRuntime allow = runtime(
        32, "terraform-allow", RepositoryType.HOSTED, null, List.of(), "ALLOW");
    TerraformPath upload = paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64");
    String filename = "terraform-provider-cloud_1.2.3_linux_amd64.zip";
    String archivePath = "v1/providers/acme/cloud/1.2.3/package/linux/" + filename;
    Path buffered = Files.createTempFile("terraform-provider-redeploy-test", ".zip");
    Files.writeString(buffered, "replacement");
    AssetRecord oldAsset = asset(41, allow, 42L, archivePath);
    AssetRecord replacement = asset(43, allow, 44L, archivePath);
    TerraformRegistryDao.ProviderPlatform oldPlatform = platform(allow, archivePath, "old-sha", 2);
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);
    when(registry.listProviderPlatforms(allow.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(List.of(oldPlatform));
    when(registry.findProviderState(allow.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(Optional.of(new TerraformRegistryDao.ProviderState(
            allow.id(), "acme", "cloud", "1.2.3", 2, "old-sums", "old-signature", 1,
            Instant.now())));
    when(inspector.bufferAndInspect(any(), eq(filename), eq(false), eq("cloud")))
        .thenReturn(buffered);
    when(assets.find(allow, archivePath))
        .thenReturn(Optional.of(oldAsset), Optional.of(replacement));
    when(assets.blob(replacement)).thenReturn(blob(44, "new-sha", 11));
    when(signing.active(allow)).thenReturn(
        new TerraformSigningService.SigningMaterial(
            2, "AABBCCDDEEFF0011", "public", "private", "pass"));
    when(signing.sign(any(), any())).thenReturn("signature".getBytes(StandardCharsets.UTF_8));

    MavenResponse response = service.put(
        allow, upload, new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8)),
        "application/zip", "attachment; filename=\"" + filename + "\"", "alice", null);

    assertEquals(201, response.status());
    assertFalse(Files.exists(buffered));
    ArgumentCaptor<TerraformRegistryDao.ProviderPlatform> platform =
        ArgumentCaptor.forClass(TerraformRegistryDao.ProviderPlatform.class);
    ArgumentCaptor<TerraformRegistryDao.ProviderState> state =
        ArgumentCaptor.forClass(TerraformRegistryDao.ProviderState.class);
    verify(registry).publishProvider(platform.capture(), state.capture());
    assertEquals("new-sha", platform.getValue().sha256());
    assertEquals(3, platform.getValue().revision());
    assertEquals(3, state.getValue().revision());
    ArgumentCaptor<byte[]> sums = ArgumentCaptor.forClass(byte[].class);
    verify(assets).storeBytes(
        eq(allow), eq("v1/providers/acme/cloud/1.2.3/metadata-r3/"
            + "terraform-provider-cloud_1.2.3_SHA256SUMS"), sums.capture(),
        eq("text/plain"), any());
    assertEquals("new-sha  " + filename + "\n",
        new String(sums.getValue(), StandardCharsets.UTF_8));
  }

  @Test
  void rejectsExistingProviderPlatformWhenWritePolicyIsAllowOnce() throws Exception {
    RepositoryRuntime allowOnce = runtime(
        33, "terraform-once", RepositoryType.HOSTED, null, List.of(), "ALLOW_ONCE");
    TerraformPath upload = paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64");
    String filename = "terraform-provider-cloud_1.2.3_linux_amd64.zip";
    String archivePath = "v1/providers/acme/cloud/1.2.3/package/linux/" + filename;
    Path buffered = Files.createTempFile("terraform-provider-redeploy-denied-test", ".zip");
    when(registry.tryAcquirePublishLease(anyString(), anyString(), any())).thenReturn(true);
    when(registry.listProviderPlatforms(allowOnce.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(List.of(platform(allowOnce, archivePath, "old-sha", 1)));
    when(assets.find(allowOnce, archivePath))
        .thenReturn(Optional.of(asset(51, allowOnce, 52L, archivePath)));
    when(inspector.bufferAndInspect(any(), eq(filename), eq(false), eq("cloud")))
        .thenReturn(buffered);

    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.put(
        allowOnce, upload, new ByteArrayInputStream(new byte[0]), "application/zip",
        "attachment; filename=\"" + filename + "\"", "alice", null));

    assertFalse(Files.exists(buffered));
    verify(assets, never()).store(eq(allowOnce), anyString(), any(), anyString(), any(), any(), any());
    verify(registry, never()).publishProvider(any(), any());
  }

  @Test
  void rewritesAndVerifiesProxyProviderMetadataAndRoutes() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        40, "terraform-proxy", RepositoryType.PROXY, "https://registry.example/root", List.of());
    // Some third-party registries use the same generic filename for every platform. The local
    // route must retain both os and arch so one platform cannot replace another's route.
    String filename = "provider.zip";
    String checksum = "0123456789abcdef";
    Map<String, Object> provider = Map.of(
        "protocols", List.of("5.0", "6.0"),
        "os", "linux",
        "arch", "amd64",
        "filename", filename,
        "download_url", "../../packages/" + filename,
        "shasums_url", "../../packages/terraform-provider-cloud_1.2.3_SHA256SUMS",
        "shasums_signature_url", "../../packages/terraform-provider-cloud_1.2.3_SHA256SUMS.sig",
        "shasum", checksum,
        "signing_keys", Map.of("gpg_public_keys", List.of(Map.of("ascii_armor", "PUBLIC"))));
    Map<String, Object> providerArm64 = new java.util.LinkedHashMap<>(provider);
    providerArm64.put("arch", "arm64");
    byte[] sums = (checksum + "  " + filename + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] signature = "sig".getBytes(StandardCharsets.UTF_8);
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenAnswer(invocation -> {
          String local = invocation.getArgument(1);
          String remote = invocation.getArgument(2);
          if (local.startsWith(".terraform/upstream/discovery-")) {
            return response(mapper.writeValueAsBytes(Map.of(
                "modules.v1", "/api/modules/", "providers.v1", "/api/providers/")), "application/json");
          }
          if (remote.endsWith("/versions")) {
            return response(mapper.writeValueAsBytes(Map.of(
                "versions", List.of(Map.of("version", "1.2.3", "protocols", List.of("5.0"))))),
                "application/json");
          }
          if (remote.endsWith("/download/linux/amd64")) {
            return response(mapper.writeValueAsBytes(provider), "application/json");
          }
          if (remote.endsWith("/download/linux/arm64")) {
            return response(mapper.writeValueAsBytes(providerArm64), "application/json");
          }
          if (remote.endsWith("_SHA256SUMS")) return response(sums, "text/plain");
          if (remote.endsWith("_SHA256SUMS.sig")) {
            return response(signature, "application/octet-stream");
          }
          return response("archive".getBytes(StandardCharsets.UTF_8), "application/zip");
        });
    InputStream checksumFailedBody = mock(InputStream.class);
    when(proxy.getAssetFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenReturn(
            MavenResponse.noBody(200),
            MavenResponse.ok(checksumFailedBody, 7, "application/zip", null, null));

    Map<String, Object> versions = json(service.get(
        proxyRuntime, paths.parse("v1/providers/acme/cloud/versions"), BASE, false));
    assertTrue(versions.toString().contains("1.2.3"));

    Map<String, Object> rewritten = json(service.get(
        proxyRuntime, paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64"),
        BASE, "url-token", false));
    assertEquals(BASE + "/v1/providers/url-token/acme/cloud/1.2.3/download/linux/amd64/"
        + filename, rewritten.get("download_url"));
    assertTrue(rewritten.get("shasums_url").toString().contains("metadata-proxy"));
    Map<String, Object> rewrittenArm64 = json(service.get(
        proxyRuntime, paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/arm64"),
        BASE, false));
    assertEquals(BASE + "/v1/providers/acme/cloud/1.2.3/download/linux/arm64/"
        + filename, rewrittenArm64.get("download_url"));
    verify(signatureVerifier, times(2)).verify(eq(sums), any(), eq(List.of("PUBLIC")));
    ArgumentCaptor<byte[]> routeBodies = ArgumentCaptor.forClass(byte[].class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> routeAttributes = ArgumentCaptor.forClass(Map.class);
    verify(assets, times(6)).storeBytes(eq(proxyRuntime), anyString(), routeBodies.capture(),
        eq("application/json"), routeAttributes.capture());
    assertTrue(routeAttributes.getAllValues().stream().anyMatch(attributes ->
        ("v1/providers/acme/cloud/1.2.3/download/linux/amd64/" + filename)
            .equals(attributes.get("targetPath"))));
    assertTrue(routeAttributes.getAllValues().stream().anyMatch(attributes ->
        ("v1/providers/acme/cloud/1.2.3/download/linux/arm64/" + filename)
            .equals(attributes.get("targetPath"))));
    List<Map<String, Object>> routes = routeBodies.getAllValues().stream()
        .map(bytes -> {
          try {
            return mapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});
          } catch (IOException e) {
            throw new AssertionError(e);
          }
        }).toList();
    assertEquals(digest(sums), routes.stream()
        .filter(route -> route.get("remoteUrl").toString().endsWith("_SHA256SUMS"))
        .findFirst().orElseThrow().get("sha256"));
    assertEquals(digest(signature), routes.stream()
        .filter(route -> route.get("remoteUrl").toString().endsWith("_SHA256SUMS.sig"))
        .findFirst().orElseThrow().get("sha256"));

    String localArchive = "v1/providers/acme/cloud/1.2.3/download/linux/amd64/" + filename;
    when(assets.bytes(eq(proxyRuntime), anyString())).thenReturn(mapper.writeValueAsBytes(Map.of(
        "remoteUrl", "https://registry.example/packages/" + filename, "sha256", checksum)));
    AssetRecord archive = asset(41, proxyRuntime, 42L, localArchive);
    when(assets.find(proxyRuntime, localArchive)).thenReturn(Optional.of(archive));
    when(assets.blob(archive)).thenReturn(blob(42, checksum, 7));
    assertEquals(200,
        service.get(proxyRuntime, paths.parse(localArchive), BASE, false).status());

    when(assets.blob(archive)).thenReturn(blob(42, "different", 7));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxyRuntime, paths.parse(localArchive), BASE, false));
    verify(checksumFailedBody).close();
    verify(assets).delete(proxyRuntime, localArchive);
  }

  @Test
  void preservesGoGetterSourceFromProxyModuleDownload() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        42, "terraform-proxy", RepositoryType.PROXY, "https://registry.example/root", List.of());
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenReturn(response(mapper.writeValueAsBytes(Map.of("modules.v1", "/api/modules/")),
            "application/json"));
    String source = "git::https://github.com/acme/network.git?ref=v1.2.3";
    respond(fetcher, new HttpRemoteFetcher.Result(
        204, Map.of("X-Terraform-Get", source), new ByteArrayInputStream(new byte[0])));

    MavenResponse download = service.get(
        proxyRuntime, paths.parse("v1/modules/acme/network/aws/1.2.3/download"), BASE, false);

    assertEquals(204, download.status());
    assertEquals(source, download.headers().get("X-Terraform-Get"));
    verify(assets, never()).storeBytes(eq(proxyRuntime), anyString(), any(), anyString(), any());
  }

  @Test
  void preservesHttpGoGetterSourceFromProxyModuleDownload() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        76, "terraform-proxy", RepositoryType.PROXY, "https://registry.example/root", List.of());
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenReturn(response(mapper.writeValueAsBytes(Map.of("modules.v1", "/api/modules/")),
            "application/json"));
    String source = "https://github.com/acme/network.git//modules/vpc?ref=v1.2.3";
    respond(fetcher, new HttpRemoteFetcher.Result(
        204, Map.of("X-Terraform-Get", source), new ByteArrayInputStream(new byte[0])));

    MavenResponse download = service.get(
        proxyRuntime, paths.parse("v1/modules/acme/network/aws/1.2.3/download"), BASE, false);

    assertEquals(204, download.status());
    assertEquals(source, download.headers().get("X-Terraform-Get"));
    verify(assets, never()).storeBytes(eq(proxyRuntime), anyString(), any(), anyString(), any());
  }

  @Test
  void resolvesRelativeGoGetterSourceFromProxyModuleDownload() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        77, "terraform-proxy", RepositoryType.PROXY, "https://registry.example/root", List.of());
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenReturn(response(mapper.writeValueAsBytes(Map.of("modules.v1", "/api/modules/")),
            "application/json"));
    respond(fetcher, new HttpRemoteFetcher.Result(
        204, Map.of("X-Terraform-Get", "/api/repos/foo//*?archive=tar.gz"),
        new ByteArrayInputStream(new byte[0])));

    MavenResponse download = service.get(
        proxyRuntime, paths.parse("v1/modules/acme/network/aws/1.2.3/download"), BASE, false);

    assertEquals(204, download.status());
    assertEquals("https://registry.example/api/repos/foo//*?archive=tar.gz",
        download.headers().get("X-Terraform-Get"));
    verify(assets, never()).storeBytes(eq(proxyRuntime), anyString(), any(), anyString(), any());
  }

  @Test
  void resolvesAndCachesRelativeHttpSourceFromProxyModuleDownload() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        43, "terraform-proxy", RepositoryType.PROXY, "https://registry.example/root", List.of());
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenReturn(response(mapper.writeValueAsBytes(Map.of("modules.v1", "/api/modules/")),
            "application/json"));
    respond(fetcher, new HttpRemoteFetcher.Result(
        204, Map.of("X-Terraform-Get", "../packages/network.zip"),
        new ByteArrayInputStream(new byte[0])));

    MavenResponse download = service.get(
        proxyRuntime, paths.parse("v1/modules/acme/network/aws/1.2.3/download"), BASE, false);

    assertEquals(204, download.status());
    assertEquals(BASE + "/v1/modules/acme/network/aws/1.2.3/network.zip",
        download.headers().get("X-Terraform-Get"));
    ArgumentCaptor<byte[]> route = ArgumentCaptor.forClass(byte[].class);
    verify(assets).storeBytes(eq(proxyRuntime), anyString(), route.capture(),
        eq("application/json"), any());
    assertEquals(
        "https://registry.example/api/modules/acme/network/aws/packages/network.zip",
        mapper.readValue(route.getValue(), new TypeReference<Map<String, Object>>() {}).get("remoteUrl"));
  }

  @Test
  void mergesGroupVersionsAndPersistsStableMemberBinding() throws Exception {
    RepositoryRuntime first = runtime(51, "first", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime second = runtime(52, "second", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(50, "terraform", RepositoryType.GROUP, null, List.of(first, second));
    AssetRecord one = asset(51, first, 61L, "v1/modules/acme/network/aws/1.0.0/one.zip");
    AssetRecord two = asset(52, second, 62L, "v1/modules/acme/network/aws/2.0.0/two.zip");
    when(assets.list(eq(first), anyString())).thenReturn(List.of(one));
    when(assets.list(eq(second), anyString())).thenReturn(List.of(two));
    when(registry.findSourceBinding(eq(group.id()), anyString())).thenReturn(Optional.empty());

    Map<String, Object> merged = json(service.get(
        group, paths.parse("v1/modules/acme/network/aws/versions"), BASE, false));
    assertTrue(merged.toString().contains("1.0.0"));
    assertTrue(merged.toString().contains("2.0.0"));

    MavenResponse download = service.get(
        group, paths.parse("v1/modules/acme/network/aws/1.0.0/download"), BASE, false);
    assertEquals(204, download.status());
    ArgumentCaptor<TerraformRegistryDao.SourceBinding> binding =
        ArgumentCaptor.forClass(TerraformRegistryDao.SourceBinding.class);
    verify(registry).upsertSourceBinding(binding.capture());
    assertEquals(group.id(), binding.getValue().groupRepositoryId());
    assertEquals(first.id(), binding.getValue().memberRepositoryId());
    assertTrue(binding.getValue().bindingKey().startsWith("asset:sha256:"));
    assertEquals(77, binding.getValue().bindingKey().length());

    when(registry.findSourceBinding(group.id(), binding.getValue().bindingKey())).thenReturn(Optional.of(
        new TerraformRegistryDao.SourceBinding(
            group.id(), binding.getValue().bindingKey(), first.id(), 0,
            Instant.now().plusSeconds(60), Instant.now())));
    when(runtimes.resolveById(first.id())).thenReturn(Optional.of(first));
    when(assets.serve(first, one.path(), false)).thenReturn(MavenResponse.noBody(200));
    assertEquals(200, service.get(group, paths.parse(one.path()), BASE, false).status());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group, paths.parse(one.path()), BASE, true));
  }

  @Test
  @SuppressWarnings("unchecked")
  void mergesPlatformsForTheSameProviderVersionAcrossGroupMembers() throws Exception {
    RepositoryRuntime first = runtime(53, "first-provider", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime second = runtime(54, "second-provider", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        55, "terraform-group", RepositoryType.GROUP, null, List.of(first, second));
    String linuxPath = "v1/providers/acme/cloud/1.2.3/package/linux/"
        + "terraform-provider-cloud_1.2.3_linux_amd64.zip";
    String darwinPath = "v1/providers/acme/cloud/1.2.3/package/darwin/"
        + "terraform-provider-cloud_1.2.3_darwin_arm64.zip";
    when(assets.list(first, "v1/providers/acme/cloud/"))
        .thenReturn(List.of(asset(53, first, 63L, linuxPath)));
    when(assets.list(second, "v1/providers/acme/cloud/"))
        .thenReturn(List.of(asset(54, second, 64L, darwinPath)));
    TerraformRegistryDao.ProviderState firstState = new TerraformRegistryDao.ProviderState(
        first.id(), "acme", "cloud", "1.2.3", 1, "first-sums", "first-sig", 1, Instant.now());
    TerraformRegistryDao.ProviderState secondState = new TerraformRegistryDao.ProviderState(
        second.id(), "acme", "cloud", "1.2.3", 1, "second-sums", "second-sig", 1, Instant.now());
    when(registry.findProviderState(first.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(Optional.of(firstState));
    when(registry.findProviderState(second.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(Optional.of(secondState));
    when(registry.listProviderPlatforms(first.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(List.of(providerPlatform(first, linuxPath, "linux", "amd64")));
    when(registry.listProviderPlatforms(second.id(), "acme", "cloud", "1.2.3"))
        .thenReturn(List.of(providerPlatform(second, darwinPath, "darwin", "arm64")));
    when(assets.find(first, linuxPath)).thenReturn(Optional.of(
        asset(53, first, 63L, linuxPath)));
    when(assets.find(second, darwinPath)).thenReturn(Optional.of(
        asset(54, second, 64L, darwinPath)));

    Map<String, Object> merged = json(service.get(
        group, paths.parse("v1/providers/acme/cloud/versions"), BASE, false));
    List<Map<String, Object>> versions = (List<Map<String, Object>>) merged.get("versions");
    List<Map<String, Object>> platforms =
        (List<Map<String, Object>>) versions.getFirst().get("platforms");

    assertEquals(1, versions.size());
    assertEquals(Set.of("linux/amd64", "darwin/arm64"), Set.copyOf(platforms.stream()
        .map(platform -> platform.get("os") + "/" + platform.get("arch"))
        .toList()));
  }

  @Test
  void rejectsMalformedProxyProviderMetadata() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        70, "terraform-proxy", RepositoryType.PROXY, "https://registry.example", List.of());
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenAnswer(invocation -> {
          String local = invocation.getArgument(1);
          if (local.startsWith(".terraform/upstream/discovery-")) {
            return response(mapper.writeValueAsBytes(Map.of("providers.v1", "/v1/providers/")),
                "application/json");
          }
          return response(mapper.writeValueAsBytes(Map.of("filename", "provider.zip")),
              "application/json");
        });
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxyRuntime,
            paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64"), BASE, false));
    verify(signatureVerifier, never()).verify(any(), any(), any());
  }

  @Test
  void wrapsInvalidProxyProviderFilenameAsBadUpstream() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        72, "terraform-proxy", RepositoryType.PROXY, "https://registry.example", List.of());
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenAnswer(invocation -> {
          String local = invocation.getArgument(1);
          if (local.startsWith(".terraform/upstream/discovery-")) {
            return response(mapper.writeValueAsBytes(Map.of("providers.v1", "/v1/providers/")),
                "application/json");
          }
          return response(mapper.writeValueAsBytes(Map.of("filename", "../provider.zip")),
              "application/json");
        });

    MavenExceptions.BadUpstreamException thrown = assertThrows(
        MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxyRuntime,
            paths.parse("v1/providers/acme/cloud/1.2.3/download/linux/amd64"), BASE, false));

    assertTrue(thrown.getMessage().contains("provider filename"));
    assertTrue(thrown.getCause() instanceof IllegalArgumentException);
  }

  @Test
  void propagatesGroupUpstreamFailureWhenNoMemberProducesVersionMetadata() throws Exception {
    RepositoryRuntime unavailable = runtime(
        73, "terraform-proxy", RepositoryType.PROXY, "https://registry.example", List.of());
    RepositoryRuntime group = runtime(
        74, "terraform-group", RepositoryType.GROUP, null, List.of(unavailable));
    when(proxy.getMetadataFromUrl(eq(unavailable), anyString(), anyString(), anyBoolean()))
        .thenAnswer(invocation -> {
          String local = invocation.getArgument(1);
          if (local.startsWith(".terraform/upstream/discovery-")) {
            return response(mapper.writeValueAsBytes(Map.of("providers.v1", "/v1/providers/")),
                "application/json");
          }
          throw new MavenExceptions.BadUpstreamException("registry unavailable");
        });

    MavenExceptions.BadUpstreamException thrown = assertThrows(
        MavenExceptions.BadUpstreamException.class,
        () -> service.get(
            group, paths.parse("v1/providers/acme/cloud/versions"), BASE, false));

    assertEquals("registry unavailable", thrown.getMessage());
  }

  @Test
  void keysDiscoveryMetadataByConfiguredRemoteUrl() throws Exception {
    RepositoryRuntime original = runtime(
        71, "terraform-proxy", RepositoryType.PROXY,
        "https://registry-one.example", List.of());
    RepositoryRuntime changed = runtime(
        71, "terraform-proxy", RepositoryType.PROXY,
        "https://registry-two.example", List.of());
    Map<String, String> discoveryCachePaths = new java.util.LinkedHashMap<>();
    when(proxy.getMetadataFromUrl(any(), anyString(), anyString(), anyBoolean()))
        .thenAnswer(invocation -> {
          String local = invocation.getArgument(1);
          String remote = invocation.getArgument(2);
          if (remote.endsWith("/.well-known/terraform.json")) {
            discoveryCachePaths.put(remote, local);
            return response(mapper.writeValueAsBytes(Map.of("modules.v1", "/v1/modules/")),
                "application/json");
          }
          return response(mapper.writeValueAsBytes(Map.of("modules", List.of())),
              "application/json");
        });

    service.get(original, paths.parse("v1/modules/acme/network/aws/versions"), BASE, false);
    service.get(changed, paths.parse("v1/modules/acme/network/aws/versions"), BASE, false);

    assertEquals(2, discoveryCachePaths.size());
    assertEquals(2, Set.copyOf(discoveryCachePaths.values()).size());
    assertTrue(discoveryCachePaths.values().stream()
        .allMatch(path -> path.startsWith(".terraform/upstream/discovery-")
            && path.endsWith(".json")));
  }

  @Test
  void resolvesRelativeServiceAgainstDiscoveryDocumentUrl() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        75, "terraform-proxy", RepositoryType.PROXY,
        "https://registry.example/prefix/", List.of());
    List<String> requestedRemotes = new java.util.ArrayList<>();
    when(proxy.getMetadataFromUrl(eq(proxyRuntime), anyString(), anyString(), anyBoolean()))
        .thenAnswer(invocation -> {
          String remote = invocation.getArgument(2);
          requestedRemotes.add(remote);
          if (remote.endsWith("/.well-known/terraform.json")) {
            return response(mapper.writeValueAsBytes(Map.of(
                "modules.v1", "terraform/modules/v1/")), "application/json");
          }
          return response(mapper.writeValueAsBytes(Map.of("modules", List.of())),
              "application/json");
        });

    service.get(proxyRuntime,
        paths.parse("v1/modules/acme/network/aws/versions"), BASE, false);

    assertTrue(requestedRemotes.contains(
        "https://registry.example/prefix/.well-known/terraform/modules/v1/"
            + "acme/network/aws/versions"));
  }

  private Map<String, Object> json(MavenResponse response) throws Exception {
    try (var body = response.body()) {
      return mapper.readValue(body, new TypeReference<>() {});
    }
  }

  private static String digest(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static MavenResponse response(byte[] body, String contentType) {
    return MavenResponse.ok(new ByteArrayInputStream(body), body.length, contentType, null, null);
  }

  private static void respond(HttpRemoteFetcher fetcher, HttpRemoteFetcher.Result result)
      throws IOException {
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      HttpRemoteFetcher.ResultHandler<Object> handler = invocation.getArgument(2);
      return handler.handle(result);
    }).when(fetcher).fetchWithBodyRetry(any(), anyString(), any());
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, String remote, List<RepositoryRuntime> members) {
    return runtime(id, name, type, remote, members, "ALLOW_ONCE");
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, String remote, List<RepositoryRuntime> members,
      String writePolicy) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.TERRAFORM, type, "terraform-" + type.name().toLowerCase(),
        true, 1L, writePolicy, null, null, true, remote, null, null, members);
  }

  private static AssetRecord asset(long id, RepositoryRuntime runtime, Long blobId, String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    return new AssetRecord(
        id, runtime.id(), null, blobId, RepositoryFormat.TERRAFORM, path, new byte[32], name,
        "terraform", "application/zip", 8L, null, Instant.now(), Map.of());
  }

  private static AssetBlobRecord blob(long id, String sha256, long size) {
    return new AssetBlobRecord(
        id, 1, "blob-ref", new byte[32], "object-key", new byte[32], "sha1", sha256,
        "md5", size, "application/zip", "test", null, Instant.now(), Instant.now(), Map.of());
  }

  private static TerraformRegistryDao.ProviderPlatform platform(
      RepositoryRuntime runtime, String path, String sha256, long revision) {
    return new TerraformRegistryDao.ProviderPlatform(
        runtime.id(), "acme", "cloud", "1.2.3", "linux", "amd64",
        path.substring(path.lastIndexOf('/') + 1), path, sha256, "5.0", revision, Instant.now());
  }

  private static TerraformRegistryDao.ProviderPlatform providerPlatform(
      RepositoryRuntime runtime, String path, String os, String arch) {
    return new TerraformRegistryDao.ProviderPlatform(
        runtime.id(), "acme", "cloud", "1.2.3", os, arch,
        path.substring(path.lastIndexOf('/') + 1), path, "sha256-" + os, "5.0", 1,
        Instant.now());
  }
}
