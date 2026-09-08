package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetPathFilter;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.ContentSelectorPreviewService.PreviewRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ContentSelectorManagementTest {
  private final AssetDao assets = mock(AssetDao.class);
  private final RepositoryCatalogCache catalog = mock(RepositoryCatalogCache.class);
  private final ContentSelectorPreviewService service = new ContentSelectorPreviewService(assets, catalog);

  @Test
  void previewValidatesBeforeAnyDatabaseAccess() {
    assertThrows(SecurityValidationException.class, () -> service.preview(new PreviewRequest("*", "csel", "path ==")));
    assertThrows(SecurityValidationException.class, () -> service.preview(new PreviewRequest(null, "csel", "path == 'x'")));
    verifyNoInteractions(assets, catalog);
  }

  @Test
  void previewPushesPrefixAndFormatToSqlThenChecksEveryReturnedPath() {
    repositories();
    when(assets.listSelectorCandidates(anyMap(), eq(0L), eq(200)))
        .thenReturn(List.of(asset(1, "Team/wrong-case"), asset(2, "team/ok"), asset(3, "private/secret")));
    var result = service.preview(new PreviewRequest("*-raw", "csel", "format == 'raw' and path =^ '/team/'"));
    assertEquals(List.of("/team/ok"), result.results().stream().map(a -> a.name()).toList());
    assertFalse(result.truncated());
    verify(assets).listSelectorCandidates(Map.of(1L, AssetPathFilter.prefix("team/")), 0L, 200);
    assertEquals(0, service.preview(new PreviewRequest("missing", "csel", "path =^ '/team/'")).total());
  }

  @Test
  void previewCapsResultsAndBoundsResidualScans() {
    repositories();
    when(assets.listSelectorCandidates(anyMap(), anyLong(), eq(200))).thenAnswer(invocation -> {
      long after = invocation.getArgument(1);
      return LongStream.rangeClosed(after + 1, after + 200).mapToObj(id -> asset(id, "team/" + id)).toList();
    });
    var capped = service.preview(new PreviewRequest("raw", "csel", "path =^ '/team/'"));
    assertEquals(10, capped.results().size()); assertTrue(capped.truncated());
    var bounded = service.preview(new PreviewRequest("raw", "csel", "path =~ '.*never.*'"));
    assertEquals(0, bounded.total()); assertTrue(bounded.truncated()); assertEquals(10_000, bounded.scanned());
  }

  @Test
  void previewRequiresAuthenticationAndEitherCreateOrUpdate() {
    var security = mock(SecurityManagementService.class);
    var previews = mock(ContentSelectorPreviewService.class);
    var controller = new ContentSelectorManagementController(previews, security, catalog);
    var request = mock(HttpServletRequest.class);
    var input = new PreviewRequest("*", "csel", "path =^ '/' ");
    assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.preview(request, input)).getStatusCode().value());
    var subject = new PermissionSubject("Local", "reader", Set.of(), null);
    when(request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE))
        .thenReturn(new AuthenticatedSubject("Local", "reader", "local", null, subject));
    when(security.decide(eq(subject), anyString())).thenReturn(AccessDecision.deny("missing"));
    assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.preview(request, input)).getStatusCode().value());
    verifyNoInteractions(previews);
    when(security.decide(subject, "nexus:selectors:update")).thenReturn(AccessDecision.allow());
    controller.preview(request, input);
    verify(previews).preview(input);
    reset(previews);
    when(security.decide(subject, "nexus:selectors:update")).thenReturn(AccessDecision.deny("missing"));
    when(security.decide(subject, "nexus:selectors:create")).thenReturn(AccessDecision.allow());
    controller.preview(request, input);
    verify(previews).preview(input);
    repositories();
    var options = controller.options(request);
    assertTrue(options.permissions().get("create")); assertFalse(options.permissions().get("read"));
    assertTrue(options.usedBy().isEmpty()); verify(security, never()).listPrivileges();
  }

  @Test
  void previewCountsMatchesAcrossPagesButReturnsOnlyTenSamples() {
    repositories();
    when(assets.listSelectorCandidates(anyMap(), eq(0L), eq(200))).thenReturn(
        LongStream.rangeClosed(1, 200).mapToObj(id -> asset(id, "team/" + id)).toList());
    when(assets.listSelectorCandidates(anyMap(), eq(200L), eq(200))).thenReturn(List.of(asset(201, "team/last")));
    var result = service.preview(new PreviewRequest("*", "csel", "path =^ '/team/'"));
    assertEquals(201, result.total()); assertEquals(201, result.scanned());
    assertEquals(10, result.results().size()); assertTrue(result.truncated()); assertTrue(result.totalExact());
    assertEquals("raw", result.results().getFirst().repositoryName());
    assertEquals("/team/1", result.results().getFirst().name());
  }

  @Test
  void previewNeverExposesInternalProtocolAssets() {
    when(catalog.snapshot()).thenReturn(new RepositoryCatalogCache.RepositoryCatalog(Instant.EPOCH,
        List.of(new RepositoryRecord(1L, "swift", RepositoryFormat.SWIFT, RepositoryType.HOSTED,
            "swift-hosted", true, 1L, null, null, null, null, null, false, Map.of())), Map.of(), Map.of()));
    when(assets.listSelectorCandidates(anyMap(), eq(0L), eq(200))).thenReturn(List.of(
        new AssetRecord(1L, 1L, null, null, RepositoryFormat.SWIFT, ".swift/private-index", new byte[32],
            "private-index", "FILE", "application/json", 1L, null, Instant.EPOCH, Map.of())));
    assertEquals(0, service.preview(new PreviewRequest("swift", "csel", "path =^ '/' ")).total());
  }

  @Test
  void selectorReadPermissionExposesUsageButDoesNotGrantWriteActions() {
    repositories();
    var security = mock(SecurityManagementService.class);
    var request = mock(HttpServletRequest.class);
    var subject = new PermissionSubject("Local", "observer", Set.of(), null);
    when(request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE))
        .thenReturn(new AuthenticatedSubject("Local", "observer", "local", null, subject));
    when(security.decide(eq(subject), anyString())).thenReturn(AccessDecision.deny("missing"));
    var controller = new ContentSelectorManagementController(service, security, catalog);
    assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.options(request)).getStatusCode().value());
    when(security.decide(subject, "nexus:selectors:read")).thenReturn(AccessDecision.allow());
    when(security.listPrivileges()).thenReturn(List.of(
        new SecurityPayloads.PrivilegeView("p", "team-read", "", "repository-content-selector", false,
            Map.of("selectorName", "team"), ""),
        new SecurityPayloads.PrivilegeView("unrelated", "unrelated", "", "wildcard", false, Map.of(), "")));
    var result = controller.options(request);
    assertEquals(Map.of("team", List.of("team-read")), result.usedBy());
    assertTrue(result.permissions().get("read")); assertFalse(result.permissions().get("update"));
    assertFalse(result.permissions().get("createPrivilege"));
  }

  private void repositories() {
    when(catalog.snapshot()).thenReturn(new RepositoryCatalogCache.RepositoryCatalog(Instant.EPOCH,
        List.of(new RepositoryRecord(1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED,
            "raw-hosted", true, 1L, null, null, null, null, null, false, Map.of())), Map.of(), Map.of()));
  }
  private static AssetRecord asset(long id, String path) {
    return new AssetRecord(id, 1L, null, null, RepositoryFormat.RAW, path, new byte[32], path,
        "FILE", "text/plain", 1L, null, Instant.EPOCH, Map.of());
  }
}
