package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanFinding;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRunSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.WaiverCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SecurityScanManagementServiceWaiverTest {
  private SecurityScanDao scans;
  private RepositoryDao repositories;
  private AssetDao assets;
  private SecurityManagementService security;
  private SecurityScanDocumentStore documents;
  private SecurityScanningProperties properties;
  private SecurityScanRepositoryScope repositoryScope;

  private SecurityScanManagementService service;
  private AuthenticatedSubject actor;

  @BeforeEach
  void setUp() {
    scans = mock(SecurityScanDao.class);
    repositories = mock(RepositoryDao.class);
    assets = mock(AssetDao.class);
    security = mock(SecurityManagementService.class);
    documents = mock(SecurityScanDocumentStore.class);
    properties = mock(SecurityScanningProperties.class);
    repositoryScope = mock(SecurityScanRepositoryScope.class);
    service = new SecurityScanManagementService(
        scans, repositories, assets, security, documents, properties, repositoryScope);
    PermissionSubject permissions =
        new PermissionSubject("test", "security-admin", Set.of("nx-admin"), null);
    actor = new AuthenticatedSubject("test", "security-admin", "local", null, permissions);
    when(security.decide(
        eq(permissions), eq("nexus:security-scanning-waivers:create")))
        .thenReturn(AccessDecision.allow());
  }

  @Test
  void findingWaiverContextExposesNamedAssociatedArtifactsWithoutInternalInput() {
    ScanFinding finding = finding(41L, 7L);
    RepositoryRecord repository = repository(11L, "maven-hosted");
    AssetRecord asset = asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar");
    when(scans.findFinding(41L)).thenReturn(Optional.of(finding));
    when(scans.listRunSubjects(eq(7L), anyLong(), anyLong(), anyInt()))
        .thenReturn(List.of(new ScanRunSubject(7L, 11L, 23L, 3L, 1L, Instant.now())));
    when(repositories.findById(11L)).thenReturn(Optional.of(repository));
    when(assets.findAssetById(23L)).thenReturn(Optional.of(asset));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());

    var context = service.findingWaiverContext(actor, 41L);

    assertEquals(41L, context.findingId());
    assertEquals("CVE-2026-0041", context.advisoryId());
    assertEquals(1, context.targets().size());
    assertEquals(1, context.targetCount());
    assertEquals(0, context.waivedTargetCount());
    assertEquals("maven-hosted", context.targets().getFirst().repository());
    assertEquals("com/acme/demo/1.0/demo-1.0.jar", context.targets().getFirst().assetPath());
  }

  @Test
  void findingWaiverContextOnlyOffersTargetsThatAreNotAlreadyCovered() {
    Instant now = Instant.now();
    ScanFinding finding = finding(41L, 7L);
    when(scans.findFinding(41L)).thenReturn(Optional.of(finding));
    when(scans.listRunSubjects(eq(7L), anyLong(), anyLong(), anyInt()))
        .thenReturn(List.of(new ScanRunSubject(7L, 11L, 23L, 3L, 1L, now)));
    when(repositories.findById(11L))
        .thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(assets.findAssetById(23L))
        .thenReturn(Optional.of(asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar")));
    when(scans.listWaiversForFindings(
        any(), any(), any(), any(), anyLong(), anyInt()))
        .thenReturn(List.of(waiver(
            51L, 11L, 23L, 41L, null, null, "Already accepted",
            now.plusSeconds(3600), now)));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());

    var context = service.findingWaiverContext(actor, 41L);

    assertEquals(1, context.targetCount());
    assertEquals(1, context.waivedTargetCount());
    assertEquals(List.of(), context.targets());
  }

  @Test
  void findingSearchUsesOneExtraRowForStableCursorPagination() {
    Instant now = Instant.now();
    RepositoryRecord repository = repository(11L, "maven-hosted");
    when(repositories.list()).thenReturn(List.of(repository));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(scans.listFindingsByRepositories(
        List.of(11L), null, null, "demo", 0L, 2))
        .thenReturn(List.of(finding(41L, 7L), finding(42L, 8L)));
    when(scans.listRunSubjects(eq(7L), anyLong(), anyLong(), anyInt()))
        .thenReturn(List.of(new ScanRunSubject(7L, 11L, 23L, 3L, 1L, now)));
    when(scans.listWaiversForFindings(
        any(), any(), any(), any(), anyLong(), anyInt()))
        .thenReturn(List.of());

    var page = service.findingPage(actor, null, null, null, "demo", 0L, 1);

    assertEquals(1, page.items().size());
    assertEquals(41L, page.items().getFirst().id());
    assertEquals(List.of("maven-hosted"), page.items().getFirst().repositories());
    assertEquals(41L, page.nextAfter());
    verify(scans).listFindingsByRepositories(
        List.of(11L), null, null, "demo", 0L, 2);
  }

  @Test
  void findingSearchMatchesRepositoryNameWithoutPerFindingRepositoryLookup() {
    Instant now = Instant.now();
    RepositoryRecord repository = repository(11L, "maven-hosted");
    when(repositories.list()).thenReturn(List.of(repository));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(scans.listFindingsByRepositories(
        List.of(11L), null, null, "maven-hosted", 0L, 2))
        .thenReturn(List.of(finding(41L, 7L)));
    when(scans.listRunSubjects(eq(7L), anyLong(), anyLong(), anyInt()))
        .thenReturn(List.of(new ScanRunSubject(7L, 11L, 23L, 3L, 1L, now)));
    when(scans.listWaiversForFindings(
        any(), any(), any(), any(), anyLong(), anyInt()))
        .thenReturn(List.of());

    var page = service.findingPage(
        actor, null, null, null, "maven-hosted", 0L, 1);

    assertEquals(List.of("maven-hosted"), page.items().getFirst().repositories());
    verify(scans).listFindingsByRepositories(
        List.of(11L), null, null, "maven-hosted", 0L, 2);
  }

  @Test
  void findingWaiverCountsConsumeBoundedKeysetPages() {
    Instant now = Instant.now();
    ScanFinding finding = finding(41L, 7L);
    RepositoryRecord repository = repository(11L, "maven-hosted");
    when(repositories.list()).thenReturn(List.of(repository));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(scans.listFindingsByRepositories(
        List.of(11L), null, null, null, 0L, 2)).thenReturn(List.of(finding));
    when(scans.listRunSubjects(eq(7L), anyLong(), anyLong(), anyInt()))
        .thenReturn(List.of(new ScanRunSubject(7L, 11L, 23L, 3L, 1L, now)));
    List<ScanWaiver> firstPage = java.util.stream.LongStream.rangeClosed(1, 256)
        .mapToObj(id -> waiver(
            id,
            11L,
            23L,
            41L,
            null,
            null,
            "Accepted risk",
            now.plusSeconds(3600),
            now))
        .toList();
    ScanWaiver secondPage = waiver(
        257L,
        11L,
        23L,
        41L,
        null,
        null,
        "Accepted risk",
        now.plusSeconds(3600),
        now);
    when(scans.listWaiversForFindings(
        any(), any(), any(), any(), eq(0L), eq(256)))
        .thenReturn(firstPage);
    when(scans.listWaiversForFindings(
        any(), any(), any(), any(), eq(256L), eq(256)))
        .thenReturn(List.of(secondPage));

    var page = service.findingPage(actor, null, null, null, null, 0L, 1);

    assertEquals(257, page.items().getFirst().activeWaiverCount());
    assertEquals(1, page.items().getFirst().waivedTargetCount());
    verify(scans).listWaiversForFindings(
        any(), any(), any(), any(), eq(256L), eq(256));
  }

  @Test
  void scanningSearchRejectsUnboundedQueries() {
    ResponseStatusException exception = assertThrows(
        ResponseStatusException.class,
        () -> service.findingPage(actor, null, null, null, "x".repeat(201), 0L, 25));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
  }

  @Test
  void waiverPaginationContinuesPastRowsHiddenByRepositoryPermissions() {
    Instant now = Instant.now();
    List<ScanWaiver> hidden = java.util.stream.LongStream.rangeClosed(1, 100)
        .mapToObj(id -> waiver(
            id, 12L, null, null, "CVE-HIDDEN", null, "Accepted hidden risk",
            now.plusSeconds(3600), now))
        .toList();
    ScanWaiver firstVisible = waiver(
        101L, 11L, null, null, "CVE-VISIBLE-1", null, "Accepted visible risk",
        now.plusSeconds(3600), now);
    ScanWaiver secondVisible = waiver(
        102L, 11L, null, null, "CVE-VISIBLE-2", null, "Accepted visible risk",
        now.plusSeconds(3600), now);
    when(repositories.list()).thenReturn(List.of(repository(11L, "maven-hosted")));
    when(repositories.findById(11L))
        .thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(security.decide(
        eq(actor.permissionSubject()), eq("nexus:security-scanning:read")))
        .thenReturn(AccessDecision.allow());
    when(scans.listWaivers(null, "accepted", 0L, 100)).thenReturn(hidden);
    when(scans.listWaivers(null, "accepted", 100L, 100))
        .thenReturn(List.of(firstVisible, secondVisible));

    var page = service.waiverPage(actor, null, "accepted", 0L, 1);

    assertEquals(1, page.items().size());
    assertEquals(101L, page.items().getFirst().id());
    assertEquals(101L, page.nextAfter());
    verify(scans).listWaivers(null, "accepted", 100L, 100);
  }

  @Test
  void findingWaiverStatusAndDetailsUseTheActualArtifactScope() {
    Instant now = Instant.now();
    ScanFinding finding = finding(41L, 7L);
    RepositoryRecord repository = repository(11L, "maven-hosted");
    RepositoryRecord secondRepository = repository(12L, "maven-secondary");
    AssetRecord asset = asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar");
    AssetRecord secondAsset =
        asset(24L, 12L, "com/acme/demo/1.0/demo-1.0.jar");
    ScanRunSubject subject = new ScanRunSubject(7L, 11L, 23L, 3L, 1L, now);
    ScanRunSubject secondSubject = new ScanRunSubject(7L, 12L, 24L, 3L, 1L, now);
    ScanWaiver active = waiver(
        51L, 11L, 23L, 41L, null, null, "Upgrade is scheduled", now.plusSeconds(3600), now);
    ScanWaiver overlapping = waiver(
        54L, 11L, 23L, null, "CVE-2026-0041", "pkg:maven/com.acme/demo@1.0",
        "Overlapping legacy acceptance", now.plusSeconds(7200), now);
    ScanWaiver expired = waiver(
        52L, 11L, 23L, null, "CVE-2026-0041", "pkg:maven/com.acme/demo@1.0",
        "Previous acceptance", now.minusSeconds(1), now.minusSeconds(7200));
    when(repositories.list()).thenReturn(List.of(repository, secondRepository));
    when(repositories.findById(11L)).thenReturn(Optional.of(repository));
    when(repositories.findById(12L)).thenReturn(Optional.of(secondRepository));
    when(assets.findAssetById(23L)).thenReturn(Optional.of(asset));
    when(assets.findAssetById(24L)).thenReturn(Optional.of(secondAsset));
    when(scans.listFindingsByRepositories(
        List.of(11L, 12L), null, null, null, 0L, 51)).thenReturn(List.of(finding));
    when(scans.findFinding(41L)).thenReturn(Optional.of(finding));
    when(scans.listRunSubjects(eq(7L), anyLong(), anyLong(), anyInt()))
        .thenReturn(List.of(subject, secondSubject));
    when(scans.listWaiversForFindings(
        any(), any(), any(), any(), anyLong(), anyInt()))
        .thenReturn(List.of(active, overlapping, expired));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(security.decide(
        eq(actor.permissionSubject()), eq("nexus:security-scanning:read")))
        .thenReturn(AccessDecision.allow());

    var findingView = service.findings(actor, null, null, null, 0L, 50).getFirst();
    var details = service.findingWaivers(actor, 41L);

    assertEquals(2, findingView.activeWaiverCount());
    assertEquals(1, findingView.expiredWaiverCount());
    assertEquals(2, findingView.waiverTargetCount());
    assertEquals(1, findingView.waivedTargetCount());
    assertEquals(
        List.of("maven-hosted", "maven-secondary"), findingView.repositories());
    assertEquals(2, details.activeWaiverCount());
    assertEquals(1, details.expiredWaiverCount());
    assertEquals(3, details.waivers().size());
    assertEquals("maven-hosted", details.waivers().getFirst().repository());
    assertEquals("com/acme/demo/1.0/demo-1.0.jar", details.waivers().getFirst().assetPath());
    assertEquals("security-admin", details.waivers().getFirst().approvedBy());
    verify(scans, org.mockito.Mockito.times(2)).listWaiversForFindings(
        eq(List.of(41L)),
        eq(List.of(7L)),
        eq(List.of("CVE-2026-0041", "GHSA-demo")),
        eq(List.of("pkg:maven/com.acme/demo@1.0", "demo")),
        eq(0L),
        eq(256));
  }

  @Test
  void findingWaiverRejectsAnArtifactThatIsNotAssociatedWithTheFinding() {
    when(repositories.findById(11L)).thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(assets.findAssetById(23L))
        .thenReturn(Optional.of(asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar")));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(scans.findFindingForUpdate(41L)).thenReturn(Optional.of(finding(41L, 7L)));
    when(scans.runSubjectExists(7L, 11L, 23L)).thenReturn(false);
    WaiverCommand command = new WaiverCommand(
        "FINDING",
        11L,
        23L,
        41L,
        null,
        null,
        Map.of(),
        "Accepted until the dependency upgrade is released",
        null,
        null,
        Instant.now().plusSeconds(604800));

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> service.createWaiver(actor, command));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    verify(scans, never()).createWaiver(any());
  }

  @Test
  void findingWaiverPinsStableSelectorsFromTheFinding() {
    when(repositories.findById(11L)).thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(assets.findAssetById(23L))
        .thenReturn(Optional.of(asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar")));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(scans.findFindingForUpdate(41L)).thenReturn(Optional.of(finding(41L, 7L)));
    when(scans.runSubjectExists(7L, 11L, 23L)).thenReturn(true);
    when(scans.listActiveWaivers(
        eq(11L), eq(23L), any(Instant.class), eq(0L), eq(1000)))
        .thenReturn(List.of());
    when(scans.createWaiver(any())).thenAnswer(invocation -> invocation.getArgument(0));
    WaiverCommand command = new WaiverCommand(
        "GLOBAL",
        11L,
        23L,
        41L,
        "CVE-OTHER",
        "pkg:maven/other/package@9",
        Map.of(),
        "Accepted until the dependency upgrade is released",
        null,
        null,
        Instant.now().plusSeconds(604800));

    service.createWaiver(actor, command);

    ArgumentCaptor<SecurityScanDao.ScanWaiver> waiver =
        ArgumentCaptor.forClass(SecurityScanDao.ScanWaiver.class);
    verify(scans).createWaiver(waiver.capture());
    verify(scans).invalidatePolicyStatesForWaiver(waiver.getValue());
    assertEquals("FINDING", waiver.getValue().scopeType());
    assertEquals("CVE-2026-0041", waiver.getValue().advisorySelector());
    assertEquals("pkg:maven/com.acme/demo@1.0", waiver.getValue().packageSelector());
  }

  @Test
  void findingWaiverMayBeCreatedWithoutExpiration() {
    when(repositories.findById(11L)).thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(assets.findAssetById(23L))
        .thenReturn(Optional.of(asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar")));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(scans.findFindingForUpdate(41L)).thenReturn(Optional.of(finding(41L, 7L)));
    when(scans.runSubjectExists(7L, 11L, 23L)).thenReturn(true);
    when(scans.listActiveWaivers(
        eq(11L), eq(23L), any(Instant.class), eq(0L), eq(1000)))
        .thenReturn(List.of());
    when(scans.createWaiver(any())).thenAnswer(invocation -> invocation.getArgument(0));
    WaiverCommand command = new WaiverCommand(
        "FINDING",
        11L,
        23L,
        41L,
        null,
        null,
        Map.of(),
        "Risk accepted until this waiver is revoked",
        null,
        null,
        null);

    service.createWaiver(actor, command);

    ArgumentCaptor<SecurityScanDao.ScanWaiver> waiver =
        ArgumentCaptor.forClass(SecurityScanDao.ScanWaiver.class);
    verify(scans).createWaiver(waiver.capture());
    assertNull(waiver.getValue().expiresAt());
  }

  @Test
  void findingWaiverRejectsAnAlreadyCoveredRepositoryArtifact() {
    Instant now = Instant.now();
    ScanFinding finding = finding(41L, 7L);
    when(repositories.findById(11L)).thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(assets.findAssetById(23L))
        .thenReturn(Optional.of(asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar")));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(scans.findFindingForUpdate(41L)).thenReturn(Optional.of(finding));
    when(scans.runSubjectExists(7L, 11L, 23L)).thenReturn(true);
    when(scans.listActiveWaivers(
        eq(11L), eq(23L), any(Instant.class), eq(0L), eq(1000)))
        .thenReturn(List.of(waiver(
            51L, 11L, 23L, 41L, null, null, "Already accepted",
            now.plusSeconds(3600), now)));
    WaiverCommand command = new WaiverCommand(
        "FINDING",
        11L,
        23L,
        41L,
        null,
        null,
        Map.of(),
        "Duplicate acceptance",
        null,
        null,
        now.plusSeconds(7200));

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> service.createWaiver(actor, command));

    assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    assertEquals(
        "Finding is already waived for this repository artifact",
        exception.getReason());
    verify(scans).findFindingForUpdate(41L);
    verify(scans, never()).createWaiver(any());
  }

  @Test
  void deletingAnAssetScopedWaiverRequiresAdministrationOfTheAssetRepository() {
    ScanWaiver waiver = waiver(
        51L, null, 23L, null, "CVE-2026-0041", null,
        "Accepted risk", null, Instant.now());
    when(security.decide(
        eq(actor.permissionSubject()), eq("nexus:security-scanning-waivers:delete")))
        .thenReturn(AccessDecision.allow());
    when(scans.findWaiver(51L)).thenReturn(Optional.of(waiver));
    when(assets.findAssetById(23L))
        .thenReturn(Optional.of(asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar")));
    when(repositories.findById(11L))
        .thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.deny("not an administrator"));

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> service.deleteWaiver(actor, 51L));

    assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    verify(scans, never()).deleteWaiver(51L);
    verify(scans, never()).invalidatePolicyStatesForWaiver(any());
  }

  @Test
  void deletingAnAssetScopedWaiverInvalidatesOnlyItsMatchingPolicyContexts() {
    ScanWaiver waiver = waiver(
        51L, null, 23L, null, "CVE-2026-0041", null,
        "Accepted risk", null, Instant.now());
    when(security.decide(
        eq(actor.permissionSubject()), eq("nexus:security-scanning-waivers:delete")))
        .thenReturn(AccessDecision.allow());
    when(scans.findWaiver(51L)).thenReturn(Optional.of(waiver));
    when(scans.deleteWaiver(51L)).thenReturn(true);
    when(assets.findAssetById(23L))
        .thenReturn(Optional.of(asset(23L, 11L, "com/acme/demo/1.0/demo-1.0.jar")));
    when(repositories.findById(11L))
        .thenReturn(Optional.of(repository(11L, "maven-hosted")));
    when(security.decide(eq(actor.permissionSubject()), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());

    assertEquals(waiver, service.deleteWaiver(actor, 51L));

    verify(scans).deleteWaiver(51L);
    verify(scans).invalidatePolicyStatesForWaiver(waiver);
  }

  @Test
  void selectorlessBroadWaiverIsRejected() {
    WaiverCommand command = new WaiverCommand(
        "GLOBAL",
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        "Too broad",
        null,
        null,
        Instant.now().plusSeconds(604800));

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> service.createWaiver(actor, command));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    verify(scans, never()).createWaiver(any());
  }

  private static ScanFinding finding(long id, long runId) {
    return new ScanFinding(
        id,
        runId,
        "finding-key",
        null,
        "CVE-2026-0041",
        List.of("GHSA-demo"),
        "grype",
        "pkg:maven/com.acme/demo@1.0",
        "demo",
        "1.0",
        List.of("1.1"),
        Severity.CRITICAL,
        "nvd",
        null,
        9.8,
        "Demo vulnerability",
        "Description",
        null,
        List.of(),
        "active",
        Instant.now());
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

  private static AssetRecord asset(long id, long repositoryId, String path) {
    return new AssetRecord(
        id,
        repositoryId,
        null,
        1L,
        RepositoryFormat.MAVEN2,
        path,
        null,
        "demo-1.0.jar",
        "file",
        "application/java-archive",
        1024L,
        null,
        Instant.now(),
        Map.of());
  }

  private static ScanWaiver waiver(
      long id,
      Long repositoryId,
      Long assetId,
      Long findingId,
      String advisorySelector,
      String packageSelector,
      String reason,
      Instant expiresAt,
      Instant createdAt) {
    return new ScanWaiver(
        id,
        "FINDING",
        repositoryId,
        assetId,
        findingId,
        advisorySelector,
        packageSelector,
        Map.of(),
        reason,
        null,
        null,
        "security-admin",
        "security-admin",
        expiresAt,
        createdAt,
        createdAt);
  }
}
