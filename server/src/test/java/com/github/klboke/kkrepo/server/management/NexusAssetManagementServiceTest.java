package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetNotFoundException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.UnsupportedAssetDeleteException;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class NexusAssetManagementServiceTest {
  private RepositoryDao repositoryDao;
  private AssetDao assetDao;
  private ComponentDao componentDao;
  private BlobStoreDao blobStoreDao;
  private RepositoryRuntimeRegistry runtimeRegistry;
  private RawHostedService rawHostedService;
  private NexusRepositoryManagementAuthorizer authorizer;
  private NexusAssetIdCodec codec;
  private AssetPublicIdService publicIdService;
  private NexusAssetManagementService service;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    repositoryDao = mock(RepositoryDao.class);
    assetDao = mock(AssetDao.class);
    componentDao = mock(ComponentDao.class);
    blobStoreDao = mock(BlobStoreDao.class);
    runtimeRegistry = mock(RepositoryRuntimeRegistry.class);
    rawHostedService = mock(RawHostedService.class);
    authorizer = mock(NexusRepositoryManagementAuthorizer.class);
    codec = new NexusAssetIdCodec();
    publicIdService = mock(AssetPublicIdService.class);
    when(publicIdService.nativePublicId(anyString(), anyLong(), anyLong()))
        .thenAnswer(invocation -> codec.encodeAssetId(
            invocation.getArgument(0), (Long) invocation.getArgument(2)));
    when(publicIdService.resolveAssetId(anyLong(), anyString()))
        .thenAnswer(invocation -> {
          java.math.BigInteger value = new java.math.BigInteger(
              (String) invocation.getArgument(1), 16);
          return value.bitLength() <= 63 ? value.longValue() : null;
        });
    ForwardedHeaderPolicy forwarded = mock(ForwardedHeaderPolicy.class);
    service = new NexusAssetManagementService(
        repositoryDao, assetDao, componentDao, blobStoreDao, runtimeRegistry, rawHostedService,
        authorizer, codec, publicIdService, forwarded);
    request = new MockHttpServletRequest("GET", "/service/rest/v1/search/assets");
    request.setContextPath("/kkrepo");
    when(forwarded.serverBaseUrl(request)).thenReturn("https://packages.example.test");
    when(authorizer.repositoryActionAllowed(
        eq(request), any(RepositoryRecord.class), anyString(), eq(PermissionAction.BROWSE)))
        .thenReturn(true);
  }

  @Test
  void missingRepositoryReturnsNexusEmptyPageWithoutAssetEnumeration() {
    when(repositoryDao.findByName("missing")).thenReturn(Optional.empty());

    var page = service.search("missing", null, null, request);

    assertEquals(List.of(), page.items());
    assertNull(page.continuationToken());
    verify(authorizer).requireSearch(request);
    verifyNoInteractions(assetDao);
  }

  @Test
  void searchPermissionIsRequiredBeforeRepositoryLookup() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "missing search permission"))
        .when(authorizer).requireSearch(request);

    ResponseStatusException failure = assertThrows(
        ResponseStatusException.class,
        () -> service.search("raw-hosted", null, null, request));

    assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
    verifyNoInteractions(repositoryDao, assetDao);
  }

  @Test
  void emptyPageAlwaysSerializesNullContinuationToken() throws Exception {
    ObjectMapper mapper = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    assertEquals(
        "{\"items\":[],\"continuationToken\":null}",
        mapper.writeValueAsString(
            new NexusAssetManagementService.AssetPage(List.of(), null)));
  }

  @Test
  void nameSearchUsesMavenComponentNameRatherThanAssetStoragePath() {
    RepositoryRecord repository = repository(RepositoryFormat.MAVEN2, RepositoryType.HOSTED);
    AssetWithBlob stored = stored(
        12L, repository, 92L, "com/acme/tool/1.0/tool-1.0.jar");
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(assetDao.listAssetWithBlobPageByComponentName(repository.id(), "tool", 0, 51))
        .thenReturn(List.of(stored));

    var page = service.search(repository.name(), "tool", null, request);

    assertEquals(1, page.items().size());
    assertEquals(stored.asset().path(), page.items().getFirst().path());
    assertNull(page.continuationToken());
    verify(assetDao, never()).findAssetByPath(repository.id(), "tool");
  }

  @Test
  void listAndDetailUseSeparateNexusShapes() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    AssetWithBlob stored = stored(12L, repository, 92L, "com/acme/tool/tool.zip");
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(assetDao.listAssetWithBlobPageByComponentName(
        repository.id(), stored.asset().path(), 0, 51)).thenReturn(List.of(stored));
    when(assetDao.findAssetWithBlobById(12L)).thenReturn(Optional.of(stored));
    when(blobStoreDao.findById(1L)).thenReturn(Optional.of(new BlobStoreRecord(
        1L, "default", "FILE", null, null, null, null, Map.of())));

    var page = service.search(repository.name(), stored.asset().path(), null, request);
    var item = page.items().getFirst();
    var detail = service.get(item.id(), request);
    var detailJson = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .valueToTree(detail);
    assertEquals(false, detailJson.has("formatAttributes"));

    assertEquals(stored.asset().path(), item.path());
    assertEquals("raw", item.format());
    assertEquals(Map.of("sha1", "sha1", "sha256", "sha256", "md5", "md5"), item.checksum());
    assertEquals(
        "https://packages.example.test/kkrepo/repository/raw-hosted/com/acme/tool/tool.zip",
        item.downloadUrl());
    assertEquals("application/zip", detail.contentType());
    assertEquals(Instant.EPOCH, detail.lastModified());
    assertNull(detail.lastDownloaded());
    assertEquals(Instant.EPOCH, detail.blobCreated());
    assertEquals("default", detail.blobStoreName());
    assertEquals(4L, detail.fileSize());
    assertEquals("compat-test", detail.uploader());
    assertEquals("127.0.0.1", detail.uploaderIp());
    assertEquals(Map.of("raw", Map.of()), detail.formatSpecificAttributes());
    assertEquals(true, detailJson.path("raw").isObject());
    verify(authorizer).requireRepositoryAction(
        request, repository, stored.asset().path(), PermissionAction.READ);
  }

  @Test
  void mavenDetailIncludesCoordinateAttributes() {
    RepositoryRecord repository = repository(RepositoryFormat.MAVEN2, RepositoryType.HOSTED);
    AssetWithBlob stored = stored(
        21L, repository, 93L, "com/acme/tool/1.0/tool-1.0-tests.jar");
    ComponentRecord component = new ComponentRecord(
        93L, repository.id(), RepositoryFormat.MAVEN2, "com.acme", "tool", "1.0",
        "artifact", null, Map.of(), Instant.EPOCH);
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(assetDao.findAssetWithBlobById(21L)).thenReturn(Optional.of(stored));
    when(componentDao.findById(93L)).thenReturn(Optional.of(component));
    when(blobStoreDao.findById(1L)).thenReturn(Optional.of(new BlobStoreRecord(
        1L, "default", "FILE", null, null, null, null, Map.of())));

    var detail = service.get(codec.encodeAssetId(repository.name(), 21L), request);

    assertEquals(Map.of(
        "maven2", Map.of(
            "extension", "jar",
            "groupId", "com.acme",
            "artifactId", "tool",
            "version", "1.0",
            "classifier", "tests")), detail.formatSpecificAttributes());
  }

  @Test
  void keysetPaginationIsStableAndContinuationIsRepositoryBound() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    List<AssetWithBlob> firstPage = storedRange(repository, 1, 51, "files/");
    when(assetDao.listAssetWithBlobPage(repository.id(), 0, 51)).thenReturn(firstPage);

    var page = service.search(repository.name(), null, null, request);

    assertEquals(50, page.items().size());
    assertEquals(50L, codec.decodeContinuation(page.continuationToken()).lastAssetId());
    when(assetDao.listAssetWithBlobPage(repository.id(), 50L, 51)).thenReturn(List.of());
    assertEquals(
        List.of(),
        service.search(repository.name(), null, page.continuationToken(), request).items());
    assertThrows(
        NexusAssetManagementService.InvalidSearchRequestException.class,
        () -> service.search(
            repository.name(), null, codec.encodeContinuation(99L, 50L), request));
  }

  @Test
  void selectorFilteringAtPhysicalEndDoesNotEmitAnEmptyContinuationPage() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    List<AssetWithBlob> stored = new ArrayList<>();
    for (long id = 1; id <= 51; id++) {
      stored.add(stored(id, repository, null,
          (id == 1 ? "secret/" : "public/") + id + ".zip"));
    }
    when(assetDao.listAssetWithBlobPage(repository.id(), 0, 51)).thenReturn(stored);
    when(assetDao.listAssetWithBlobPage(repository.id(), 51, 51)).thenReturn(List.of());
    when(authorizer.repositoryActionAllowed(
        eq(request), eq(repository), anyString(), eq(PermissionAction.BROWSE)))
        .thenAnswer(invocation -> !invocation.<String>getArgument(2).startsWith("secret/"));

    var page = service.search(repository.name(), null, null, request);

    assertEquals(50, page.items().size());
    assertNull(page.continuationToken());
  }

  @Test
  void negativeSelectorFilteringFetchesUntilPageIsFullWithoutLeakingDeniedPaths() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    List<AssetWithBlob> first = new ArrayList<>();
    for (long id = 1; id <= 51; id++) {
      first.add(stored(id, repository, null,
          (id <= 40 ? "secret/" : "public/") + id + ".zip"));
    }
    when(assetDao.listAssetWithBlobPage(repository.id(), 0, 51)).thenReturn(first);
    when(assetDao.listAssetWithBlobPage(repository.id(), 51, 51))
        .thenReturn(storedRange(repository, 52, 102, "public/"));
    when(authorizer.repositoryActionAllowed(
        eq(request), eq(repository), anyString(), eq(PermissionAction.BROWSE)))
        .thenAnswer(invocation -> !invocation.<String>getArgument(2).startsWith("secret/"));

    var page = service.search(repository.name(), null, null, request);

    assertEquals(50, page.items().size());
    assertEquals(true, page.items().stream().noneMatch(item -> item.path().startsWith("secret/")));
    assertEquals(90L, codec.decodeContinuation(page.continuationToken()).lastAssetId());
    verify(assetDao).listAssetWithBlobPage(repository.id(), 51, 51);
  }

  @Test
  void positiveSelectorFilteringUsesEachCandidatePathAcrossKeysetPages() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    List<AssetWithBlob> first = new ArrayList<>();
    for (long id = 1; id <= 51; id++) {
      first.add(stored(id, repository, null,
          (id == 51 ? "team-a/" : "team-b/") + id + ".zip"));
    }
    when(assetDao.listAssetWithBlobPage(repository.id(), 0, 51)).thenReturn(first);
    when(assetDao.listAssetWithBlobPage(repository.id(), 51, 51))
        .thenReturn(storedRange(repository, 52, 102, "team-a/"));
    when(authorizer.repositoryActionAllowed(
        eq(request), eq(repository), anyString(), eq(PermissionAction.BROWSE)))
        .thenAnswer(invocation -> invocation.<String>getArgument(2).startsWith("team-a/"));

    var page = service.search(repository.name(), null, null, request);

    assertEquals(50, page.items().size());
    assertEquals(true, page.items().stream().allMatch(item -> item.path().startsWith("team-a/")));
    assertEquals(100L, codec.decodeContinuation(page.continuationToken()).lastAssetId());
  }

  @Test
  void deleteUsesExactRawAssetAndRejectsOtherFormats() {
    RepositoryRecord raw = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    AssetWithBlob stored = stored(12L, raw, null, "tool.zip");
    RepositoryRuntime runtime = runtime(raw);
    when(repositoryDao.findByName(raw.name())).thenReturn(Optional.of(raw));
    when(assetDao.findAssetById(12L)).thenReturn(Optional.of(stored.asset()));
    when(runtimeRegistry.resolve(raw.name())).thenReturn(Optional.of(runtime));
    when(rawHostedService.deleteById(runtime, 12L)).thenReturn(MavenResponse.noBody(204));

    assertEquals(204, service.delete(codec.encodeAssetId(raw.name(), 12L), request));
    verify(rawHostedService).deleteById(runtime, 12L);
    verify(authorizer).requireRepositoryAction(
        request, raw, "tool.zip", PermissionAction.DELETE);

    RepositoryRecord maven = repository(RepositoryFormat.MAVEN2, RepositoryType.HOSTED);
    AssetRecord mavenAsset = stored(21L, maven, null, "a.jar").asset();
    when(repositoryDao.findByName(maven.name())).thenReturn(Optional.of(maven));
    when(assetDao.findAssetById(21L)).thenReturn(Optional.of(mavenAsset));
    assertThrows(UnsupportedAssetDeleteException.class,
        () -> service.delete(codec.encodeAssetId(maven.name(), 21L), request));
  }

  @Test
  void validIdCannotCrossRepositoryBoundary() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    RepositoryRecord other = new RepositoryRecord(
        9L, "other", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(assetDao.findAssetWithBlobById(77L))
        .thenReturn(Optional.of(stored(77L, other, null, "secret.zip")));

    assertThrows(AssetNotFoundException.class,
        () -> service.get(codec.encodeAssetId(repository.name(), 77L), request));
  }

  @Test
  void nexusHistoricalAliasCanResolveButUnknownValidIdReturnsNotFound() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    AssetWithBlob stored = stored(12L, repository, null, "tool.zip");
    String historicalOpaque = "fedcba98765432100123456789abcdef";
    String historicalId = codec.encodeAssetId(repository.name(), historicalOpaque);
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(publicIdService.resolveAssetId(repository.id(), historicalOpaque)).thenReturn(12L);
    when(assetDao.findAssetWithBlobById(12L)).thenReturn(Optional.of(stored));

    assertEquals(12L, new java.math.BigInteger(
        codec.decodeAssetId(service.get(historicalId, request).id()).opaqueId(), 16).longValueExact());

    String unknownOpaque = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    when(publicIdService.resolveAssetId(repository.id(), unknownOpaque)).thenReturn(null);
    assertThrows(AssetNotFoundException.class,
        () -> service.get(codec.encodeAssetId(repository.name(), unknownOpaque), request));
  }

  @Test
  void getAllowsNativePublicIdBackfillWithinItsTransaction() throws Exception {
    Method method = NexusAssetManagementService.class.getMethod(
        "get", String.class, jakarta.servlet.http.HttpServletRequest.class);

    assertFalse(method.getAnnotation(Transactional.class).readOnly());
  }

  private static RepositoryRecord repository(RepositoryFormat format, RepositoryType type) {
    String name = format == RepositoryFormat.RAW ? "raw-hosted" : "maven-releases";
    return new RepositoryRecord(
        7L, name, format, type, format.id() + "-" + type.name().toLowerCase(),
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
  }

  private static List<AssetWithBlob> storedRange(
      RepositoryRecord repository, long first, long last, String prefix) {
    List<AssetWithBlob> stored = new ArrayList<>();
    for (long id = first; id <= last; id++) {
      stored.add(stored(id, repository, null, prefix + id + ".zip"));
    }
    return stored;
  }

  private static AssetWithBlob stored(
      long id, RepositoryRecord repository, Long componentId, String path) {
    AssetRecord asset = new AssetRecord(
        id, repository.id(), componentId, id + 100, repository.format(), path, null,
        path.substring(path.lastIndexOf('/') + 1), "ARTIFACT", "application/zip", 4L,
        null, Instant.EPOCH, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        id + 100, 1L, "blob", null, path, null, "sha1", "sha256", "md5", 4L,
        "application/zip", "compat-test", "127.0.0.1", Instant.EPOCH, Instant.EPOCH, Map.of());
    return new AssetWithBlob(asset, blob);
  }

  private static RepositoryRuntime runtime(RepositoryRecord repository) {
    return new RepositoryRuntime(
        repository.id(), repository.name(), repository.format(), repository.type(),
        repository.recipeName(), true, repository.blobStoreId(), "ALLOW", null, null,
        true, null, 60, 60, true, "ATTACHMENT", List.of());
  }
}
