package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetNotFoundException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.UnsupportedAssetDeleteException;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class NexusAssetManagementServiceTest {
  private RepositoryDao repositoryDao;
  private AssetDao assetDao;
  private RepositoryRuntimeRegistry runtimeRegistry;
  private RawHostedService rawHostedService;
  private NexusRepositoryManagementAuthorizer authorizer;
  private NexusAssetIdCodec codec;
  private NexusAssetManagementService service;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    repositoryDao = mock(RepositoryDao.class);
    assetDao = mock(AssetDao.class);
    runtimeRegistry = mock(RepositoryRuntimeRegistry.class);
    rawHostedService = mock(RawHostedService.class);
    authorizer = mock(NexusRepositoryManagementAuthorizer.class);
    codec = new NexusAssetIdCodec();
    ForwardedHeaderPolicy forwarded = mock(ForwardedHeaderPolicy.class);
    service = new NexusAssetManagementService(
        repositoryDao, assetDao, runtimeRegistry, rawHostedService,
        authorizer, codec, forwarded);
    request = new MockHttpServletRequest("GET", "/service/rest/v1/search/assets");
    request.setContextPath("/kkrepo");
    when(forwarded.serverBaseUrl(request)).thenReturn("https://packages.example.test");
  }

  @Test
  void missingRepositoryReturnsNexusEmptyPageWithoutDatabaseEnumeration() {
    when(repositoryDao.findByName("missing")).thenReturn(Optional.empty());

    var page = service.search("missing", null, null, request);

    assertEquals(List.of(), page.items());
    assertNull(page.continuationToken());
    verifyNoInteractions(assetDao);
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
  void exactRawNameProducesReusableAssetIdAndNexusAssetFields() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    AssetWithBlob stored = stored(12L, repository, "com/qunhe/tool/tool.zip");
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(assetDao.findAssetByPath(repository.id(), stored.asset().path()))
        .thenReturn(Optional.of(stored.asset()));
    when(assetDao.findAssetWithBlobById(12L)).thenReturn(Optional.of(stored));

    var page = service.search(repository.name(), stored.asset().path(), null, request);

    assertEquals(1, page.items().size());
    var item = page.items().getFirst();
    assertEquals(stored.asset().path(), item.path());
    assertEquals("raw", item.format());
    assertEquals(Map.of("sha1", "sha1", "sha256", "sha256", "md5", "md5"), item.checksum());
    assertEquals(
        "https://packages.example.test/kkrepo/repository/windows-artifacts/com/qunhe/tool/tool.zip",
        item.downloadUrl());
    assertEquals(item, service.get(item.id(), request));
    verify(authorizer).requireRepositoryAction(
        request, repository, stored.asset().path(), PermissionAction.BROWSE);
    verify(authorizer).requireRepositoryAction(
        request, repository, stored.asset().path(), PermissionAction.READ);
  }

  @Test
  void keysetPaginationIsStableAndContinuationIsRepositoryBound() {
    RepositoryRecord repository = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    when(repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    List<AssetWithBlob> firstPage = new ArrayList<>();
    for (long id = 1; id <= 51; id++) {
      firstPage.add(stored(id, repository, "files/" + id + ".zip"));
    }
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
  void deleteUsesExactRawAssetAndRejectsOtherFormats() {
    RepositoryRecord raw = repository(RepositoryFormat.RAW, RepositoryType.HOSTED);
    AssetWithBlob stored = stored(12L, raw, "tool.zip");
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
    AssetRecord mavenAsset = stored(21L, maven, "a.jar").asset();
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
        .thenReturn(Optional.of(stored(77L, other, "secret.zip")));

    assertThrows(AssetNotFoundException.class,
        () -> service.get(codec.encodeAssetId(repository.name(), 77L), request));
  }

  private static RepositoryRecord repository(RepositoryFormat format, RepositoryType type) {
    String name = format == RepositoryFormat.RAW ? "windows-artifacts" : "maven-releases";
    return new RepositoryRecord(
        7L, name, format, type, format.id() + "-" + type.name().toLowerCase(),
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
  }

  private static AssetWithBlob stored(long id, RepositoryRecord repository, String path) {
    AssetRecord asset = new AssetRecord(
        id, repository.id(), null, id + 100, repository.format(), path, null,
        path.substring(path.lastIndexOf('/') + 1), "ARTIFACT", "application/zip", 4L,
        null, Instant.EPOCH, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        id + 100, 1L, "blob", null, path, null, "sha1", "sha256", "md5", 4L,
        "application/zip", "moon", "127.0.0.1", Instant.EPOCH, Instant.EPOCH, Map.of());
    return new AssetWithBlob(asset, blob);
  }

  private static RepositoryRuntime runtime(RepositoryRecord repository) {
    return new RepositoryRuntime(
        repository.id(), repository.name(), repository.format(), repository.type(),
        repository.recipeName(), true, repository.blobStoreId(), "ALLOW", null, null,
        true, null, 60, 60, true, "ATTACHMENT", List.of());
  }
}
