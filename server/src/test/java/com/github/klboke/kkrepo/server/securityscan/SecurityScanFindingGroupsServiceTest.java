package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingGroup;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingGroupBy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingGroupCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingQuery;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.FindingFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SecurityScanFindingGroupsServiceTest {
  private SecurityScanDao scans;
  private RepositoryDao repositories;
  private SecurityManagementService security;
  private SecurityScanManagementService service;
  private AuthenticatedSubject actor;

  @BeforeEach
  void setUp() {
    scans = mock(SecurityScanDao.class);
    repositories = mock(RepositoryDao.class);
    security = mock(SecurityManagementService.class);
    service = new SecurityScanManagementService(
        scans,
        repositories,
        mock(AssetDao.class),
        mock(BrowseNodeDao.class),
        mock(DockerRegistryDao.class),
        security,
        mock(SecurityScanDocumentStore.class),
        mock(SecurityScanDocumentPersistence.class),
        mock(SecurityScanningProperties.class),
        mock(SecurityScanRepositoryScope.class));
    PermissionSubject permissions =
        new PermissionSubject("test", "reader", Set.of("reader"), null);
    actor = new AuthenticatedSubject("test", "reader", "local", null, permissions);
    when(security.decide(eq(permissions), any(String.class)))
        .thenReturn(AccessDecision.deny("missing"));
    when(repositories.list()).thenReturn(List.of(
        repository(11L, "visible"), repository(12L, "hidden")));
    when(security.decide(eq(permissions), any(RepositoryPermission.class)))
        .thenAnswer(invocation -> invocation.<RepositoryPermission>getArgument(1)
            .repository().equals("visible")
            ? AccessDecision.allow() : AccessDecision.deny("hidden"));
  }

  @Test
  void groupsAreScopedToVisibleRepositoriesAndPagedWithAnOpaqueKeysetCursor() {
    var filter = new FindingFilter(true, null, null, "MAVEN2", "org.acme", "app", null);
    when(scans.listFindingGroups(any(), eq(FindingGroupBy.PACKAGE), isNull(), eq(2)))
        .thenReturn(List.of(packageGroup("log4j-core", "2.14.1", Severity.CRITICAL),
            packageGroup("snakeyaml", "", Severity.HIGH)));

    var page = service.findingGroupPage(
        actor, FindingGroupBy.PACKAGE, null, Severity.HIGH, " yaml ", null, 1, true, filter);

    assertEquals(1, page.items().size());
    assertEquals("log4j-core", page.items().getFirst().packageName());
    assertEquals("CRITICAL", page.items().getFirst().maxSeverity());
    assertNotNull(page.nextCursor());
    verify(scans).listFindingGroups(argThat(query -> query.repositoryIds().equals(List.of(11L))
        && query.repositoryId() == null
        && query.severity() == Severity.HIGH
        && "yaml".equals(query.query())
        && query.currentOnly()
        && query.unwaivedAt() != null
        && "MAVEN2".equals(query.artifactFormat())
        && "org.acme".equals(query.artifactNamespace())
        && "app".equals(query.artifactName())
        && query.artifactVersion() == null), eq(FindingGroupBy.PACKAGE), isNull(), eq(2));

    FindingGroupCursor expected =
        packageGroup("log4j-core", "2.14.1", Severity.CRITICAL).cursor(FindingGroupBy.PACKAGE);
    when(scans.listFindingGroups(any(), eq(FindingGroupBy.PACKAGE), eq(expected), eq(2)))
        .thenReturn(List.of(packageGroup("snakeyaml", "", Severity.HIGH)));
    var second = service.findingGroupPage(
        actor, FindingGroupBy.PACKAGE, null, null, null, page.nextCursor(), 1, true, filter);
    assertEquals("snakeyaml", second.items().getFirst().packageName());
    assertNull(second.nextCursor());
  }

  @Test
  void groupsKeepWaivedAssociationsUnlessHiddenAndRejectForeignCursorsOrRepositories() {
    when(scans.listFindingGroups(any(), any(), any(), anyInt())).thenReturn(List.of());
    service.findingGroupPage(actor, FindingGroupBy.ARTIFACT, 11L, null, null, null, 10, true,
        FindingFilter.NONE);
    verify(scans).listFindingGroups(argThat(query -> query.unwaivedAt() == null
        && query.currentOnly()
        && Long.valueOf(11L).equals(query.repositoryId())), eq(FindingGroupBy.ARTIFACT),
        isNull(), eq(11));

    var historical = assertThrows(ResponseStatusException.class, () -> service.findingGroupPage(
        actor, FindingGroupBy.ARTIFACT, 11L, null, null, null, 10, false, FindingFilter.NONE));
    assertEquals(HttpStatus.BAD_REQUEST, historical.getStatusCode(),
        "grouping is limited to current findings");

    String packageCursor = FindingGroupPagination.encode(FindingGroupBy.PACKAGE,
        new FindingGroupCursor(4, List.of("demo", "1.0")));
    var invalidCursor = assertThrows(ResponseStatusException.class, () -> service.findingGroupPage(
        actor, FindingGroupBy.ARTIFACT, null, null, null, packageCursor, 10, true, null));
    assertEquals(HttpStatus.BAD_REQUEST, invalidCursor.getStatusCode());

    var hidden = assertThrows(ResponseStatusException.class, () -> service.findingGroupPage(
        actor, FindingGroupBy.ARTIFACT, 12L, null, null, null, 10, true, null));
    assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());

    var missing = assertThrows(ResponseStatusException.class, () -> service.findingGroupPage(
        actor, null, null, null, null, null, 10, true, null));
    assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
  }

  @Test
  void treeFiltersUseTheScopedFindingQueryWhileUnfilteredPagesKeepTheLegacyQuery() {
    when(scans.listFindings(any(FindingQuery.class), anyLong(), anyInt())).thenReturn(List.of());
    var filter = new FindingFilter(false, "demo", "", null, null, null, null);

    assertTrue(service.findingPage(actor, null, null, null, null, 0L, 10, true, filter)
        .items().isEmpty());
    verify(scans).listFindings(argThat((FindingQuery query) ->
        query.repositoryIds().equals(List.of(11L))
            && "demo".equals(query.packageName())
            && "".equals(query.installedVersion())
            && query.unwaivedAt() == null
            && query.currentOnly()), eq(0L), eq(11));
    verify(scans, never()).listFindingsByRepositories(
        any(), any(), any(), any(), anyLong(), anyInt(), anyBoolean());

    service.findingPage(actor, null, null, null, null, 0L, 10, true, FindingFilter.NONE);
    verify(scans).listFindingsByRepositories(List.of(11L), null, null, null, 0L, 11, true);

    var hidden = assertThrows(ResponseStatusException.class, () -> service.findingPage(
        actor, 12L, null, null, null, 0L, 10, true, filter));
    assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());
  }

  @Test
  void artifactGroupCursorsCarryTheWholeArtifactKey() {
    FindingGroup app = new FindingGroup(null, null, "maven2", "org.acme", "app", "1.0",
        Severity.HIGH, 1, 1, 1, 0, 1, 0, 0, 0);
    FindingGroup tool = new FindingGroup(null, null, "maven2", "", "raw/tool.bin", "",
        Severity.LOW, 1, 1, 1, 0, 0, 0, 1, 0);
    when(scans.listFindingGroups(any(), any(), isNull(), eq(2))).thenReturn(List.of(app, tool));

    for (FindingGroupBy groupBy : List.of(FindingGroupBy.ARTIFACT, FindingGroupBy.ARTIFACT_VERSION)) {
      var page = service.findingGroupPage(
          actor, groupBy, null, null, null, null, 1, true, FindingFilter.NONE);
      FindingGroupCursor cursor = FindingGroupPagination.decode(groupBy, page.nextCursor());
      assertEquals(groupBy == FindingGroupBy.ARTIFACT
              ? List.of("maven2", "org.acme", "app")
              : List.of("maven2", "org.acme", "app", "1.0"),
          cursor.key());
      assertEquals(Severity.HIGH.rank(), cursor.severityRank());
      assertEquals("maven2", page.items().getFirst().artifactFormat());
    }
  }

  @Test
  void cursorsRoundTripArbitraryKeyTextAndRejectTampering() {
    var cursor = new FindingGroupCursor(5, List.of("MAVEN2", "", "dir.with|pipes/and.dots", "v1"));
    String encoded = FindingGroupPagination.encode(FindingGroupBy.ARTIFACT_VERSION, cursor);
    assertEquals(cursor, FindingGroupPagination.decode(FindingGroupBy.ARTIFACT_VERSION, encoded));
    assertNull(FindingGroupPagination.decode(FindingGroupBy.PACKAGE, null));
    assertNull(FindingGroupPagination.decode(FindingGroupBy.PACKAGE, ""));
    for (String invalid : List.of(
        "g1.ARTIFACT_VERSION.5.AA.AA.AA",
        "g2" + encoded.substring(2),
        encoded.replace(".5.", ".9."),
        encoded.replace(".5.", ".x."),
        encoded + ".AA",
        "g1.ARTIFACT_VERSION.5.@@.AA.AA.AA",
        "x".repeat(16 * 1024 + 1))) {
      var error = assertThrows(ResponseStatusException.class,
          () -> FindingGroupPagination.decode(FindingGroupBy.ARTIFACT_VERSION, invalid), invalid);
      assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
  }

  private static FindingGroup packageGroup(String name, String version, Severity severity) {
    return new FindingGroup(name, version, null, null, null, null, severity,
        2, 1, 3, severity == Severity.CRITICAL ? 2 : 0, severity == Severity.HIGH ? 2 : 0,
        0, 0, 0);
  }

  private static RepositoryRecord repository(long id, String name) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED,
        "maven2-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }
}
