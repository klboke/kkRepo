package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchCriteria;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetSummaryView;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.InvalidSearchRequestException;
import com.github.klboke.kkrepo.server.management.NexusComponentSearchService.SearchRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class NexusComponentSearchServiceTest {
  private ComponentDao componentDao;
  private AssetDao assetDao;
  private RepositoryDao repositoryDao;
  private NexusRepositoryManagementAuthorizer authorizer;
  private NexusAssetIdCodec codec;
  private NexusAssetManagementService assetManagementService;
  private NexusComponentSearchService service;
  private MockHttpServletRequest request;
  private RepositoryRecord repository;

  @BeforeEach
  void setUp() {
    componentDao = mock(ComponentDao.class);
    assetDao = mock(AssetDao.class);
    repositoryDao = mock(RepositoryDao.class);
    authorizer = mock(NexusRepositoryManagementAuthorizer.class);
    codec = new NexusAssetIdCodec();
    assetManagementService = mock(NexusAssetManagementService.class);
    service = new NexusComponentSearchService(
        componentDao, assetDao, repositoryDao, authorizer, codec, assetManagementService);
    request = new MockHttpServletRequest("GET", "/service/rest/v1/search");
    repository = new RepositoryRecord(
        7L, "maven-releases", RepositoryFormat.MAVEN2, RepositoryType.HOSTED,
        "maven2-hosted", true, 1L, null, null, "RELEASE", "STRICT", "ALLOW", true,
        Map.of());
    when(repositoryDao.list()).thenReturn(List.of(repository));
    when(authorizer.repositoryActionAllowed(
        eq(request), eq(repository), any(String.class), eq(PermissionAction.BROWSE)))
        .thenReturn(true);
    when(assetManagementService.componentSearchSummary(
        eq(repository), any(AssetWithBlob.class), eq(request)))
        .thenAnswer(invocation -> {
          AssetWithBlob stored = invocation.getArgument(1);
          AssetRecord asset = stored.asset();
          return new AssetSummaryView(
              "https://repo.example/repository/maven-releases/" + asset.path(),
              asset.path(), codec.encodeAssetId(repository.name(), asset.id()),
              repository.name(), repository.format().id(), Map.of("sha1", "sha1"));
        });
  }

  @Test
  void companyMavenQueryMapsToNexusComponentAndNestedAssetShape() {
    ComponentSearchRow row = component(12L, "com.acme", "demo-api", "1.2.3");
    AssetWithBlob asset = asset(112L, row.id(), "com/acme/demo-api/1.2.3/demo-api-1.2.3.jar");
    when(componentDao.searchPage(any(ComponentSearchCriteria.class), eq(0L), eq(51)))
        .thenReturn(List.of(row));
    when(assetDao.listAssetWithBlobByComponent(row.id())).thenReturn(List.of(asset));

    var page = service.search(
        new SearchRequest("demo-api", null, "maven2", null, null, null, null), request);

    assertEquals(1, page.items().size());
    var item = page.items().getFirst();
    assertEquals("maven-releases", item.repository());
    assertEquals("maven2", item.format());
    assertEquals("com.acme", item.group());
    assertEquals("demo-api", item.name());
    assertEquals("1.2.3", item.version());
    assertEquals(List.of(asset.asset().path()),
        item.assets().stream().map(AssetSummaryView::path).toList());
    assertFalse(item.id().isBlank());
    assertNull(page.continuationToken());

    ArgumentCaptor<ComponentSearchCriteria> criteria =
        ArgumentCaptor.forClass(ComponentSearchCriteria.class);
    verify(componentDao).searchPage(criteria.capture(), eq(0L), eq(51));
    assertEquals("demo-api", criteria.getValue().keyword());
    assertEquals(RepositoryFormat.MAVEN2, criteria.getValue().format());
  }

  @Test
  void searchTransactionAllowsDurablePublicIdRegistration() throws Exception {
    Transactional transaction = NexusComponentSearchService.class
        .getMethod("search", SearchRequest.class, jakarta.servlet.http.HttpServletRequest.class)
        .getAnnotation(Transactional.class);

    assertNotNull(transaction);
    assertFalse(transaction.readOnly());
  }

  @Test
  void statelessContinuationIsBoundToTheOriginalQuery() {
    List<ComponentSearchRow> rows = new ArrayList<>();
    for (long id = 1; id <= 51; id++) {
      rows.add(component(id, "com.acme", "library-" + id, "1.0"));
      when(assetDao.listAssetWithBlobByComponent(id)).thenReturn(List.of(
          asset(id + 100, id, "com/acme/library-" + id + "/1.0/library.jar")));
    }
    when(componentDao.searchPage(any(ComponentSearchCriteria.class), eq(0L), eq(51)))
        .thenReturn(rows);
    when(componentDao.searchPage(any(ComponentSearchCriteria.class), eq(50L), eq(51)))
        .thenReturn(List.of(rows.get(50)));
    SearchRequest firstQuery = new SearchRequest(
        "library", null, "maven2", null, null, null, null);

    var first = service.search(firstQuery, request);

    assertEquals(50, first.items().size());
    assertFalse(first.continuationToken().isBlank());
    var second = service.search(new SearchRequest(
        "library", null, "maven2", null, null, null, first.continuationToken()), request);
    assertEquals(List.of("library-51"),
        second.items().stream().map(NexusComponentSearchService.ComponentView::name).toList());
    assertThrows(InvalidSearchRequestException.class, () -> service.search(new SearchRequest(
        "different", null, "maven2", null, null, null, first.continuationToken()), request));
  }

  @Test
  void componentsWithoutAnyBrowseVisibleAssetAreOmitted() {
    ComponentSearchRow denied = component(10L, "com.acme", "secret", "1.0");
    ComponentSearchRow visible = component(11L, "com.acme", "public", "1.0");
    when(componentDao.searchPage(any(ComponentSearchCriteria.class), eq(0L), eq(51)))
        .thenReturn(List.of(denied, visible));
    when(assetDao.listAssetWithBlobByComponent(10L))
        .thenReturn(List.of(asset(110L, 10L, "secret/secret.jar")));
    when(assetDao.listAssetWithBlobByComponent(11L))
        .thenReturn(List.of(asset(111L, 11L, "public/public.jar")));
    when(authorizer.repositoryActionAllowed(
        eq(request), eq(repository), any(String.class), eq(PermissionAction.BROWSE)))
        .thenAnswer(invocation -> invocation.<String>getArgument(2).startsWith("public/"));

    var page = service.search(
        new SearchRequest(null, null, "maven2", null, null, null, null), request);

    assertEquals(List.of("public"),
        page.items().stream().map(NexusComponentSearchService.ComponentView::name).toList());
  }

  @Test
  void searchPermissionIsRequiredBeforeDatabaseEnumeration() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "missing search permission"))
        .when(authorizer).requireSearch(request);

    assertThrows(ResponseStatusException.class, () -> service.search(
        new SearchRequest("demo", null, "maven2", null, null, null, null), request));

    verifyNoInteractions(componentDao, assetDao, repositoryDao);
  }

  @Test
  void unknownFormatReturnsNexusEmptyPage() {
    var page = service.search(
        new SearchRequest("demo", null, "not-a-format", null, null, null, null), request);

    assertEquals(List.of(), page.items());
    assertNull(page.continuationToken());
    verifyNoInteractions(componentDao, assetDao);
  }

  @Test
  void invalidSha1ReturnsNexusEmptyPageWithoutDatabaseSearch() {
    var page = service.search(
        new SearchRequest(null, null, null, null, null, null, "abc", null), request);

    assertEquals(List.of(), page.items());
    assertNull(page.continuationToken());
    verifyNoInteractions(componentDao, assetDao, repositoryDao);
  }

  @Test
  void validSha1IsNormalizedAndPassedToSharedDatabaseSearch() {
    String sha1 = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";
    when(componentDao.searchPage(any(ComponentSearchCriteria.class), eq(0L), eq(51)))
        .thenReturn(List.of());

    service.search(
        new SearchRequest(null, null, null, null, null, null, sha1, null), request);

    ArgumentCaptor<ComponentSearchCriteria> criteria =
        ArgumentCaptor.forClass(ComponentSearchCriteria.class);
    verify(componentDao).searchPage(criteria.capture(), eq(0L), eq(51));
    assertEquals(sha1.toLowerCase(java.util.Locale.ROOT), criteria.getValue().sha1());
  }

  private ComponentSearchRow component(long id, String group, String name, String version) {
    return new ComponentSearchRow(
        id, repository.id(), repository.name(), repository.format(), group, name, version,
        "artifact", Instant.EPOCH, null);
  }

  private AssetWithBlob asset(long id, long componentId, String path) {
    return new AssetWithBlob(new AssetRecord(
        id, repository.id(), componentId, id + 1000, repository.format(), path, null,
        path.substring(path.lastIndexOf('/') + 1), "ARTIFACT", "application/java-archive",
        1L, null, Instant.EPOCH, Map.of()), null);
  }
}
