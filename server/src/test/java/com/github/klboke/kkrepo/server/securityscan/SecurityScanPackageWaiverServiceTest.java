package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingPackageScope;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingQuery;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.PackageWaiverCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SecurityScanPackageWaiverServiceTest {
  private static final String PURL = "pkg:maven/org.yaml/snakeyaml@1.30";

  private SecurityScanDao scans;
  private SecurityManagementService security;
  private SecurityScanManagementService service;
  private AuthenticatedSubject actor;
  private final Set<String> granted = new HashSet<>();
  private final Set<String> administered = new HashSet<>();
  private final Set<String> hidden = new HashSet<>();
  /** The waiver table: package waiver lookups read it, and creates and deletes change it. */
  private final List<ScanWaiver> stored = new ArrayList<>();
  private long nextWaiverId = 10_000;

  @BeforeEach
  void setUp() {
    scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
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
        new PermissionSubject("test", "operator", Set.of("operator"), null);
    actor = new AuthenticatedSubject("test", "operator", "local", null, permissions);
    granted.addAll(Set.of(
        "nexus:security-scanning-waivers:create", "nexus:security-scanning-waivers:delete"));
    administered.add("releases");
    when(security.decide(eq(permissions), any(String.class))).thenAnswer(invocation ->
        granted.contains(invocation.<String>getArgument(1))
            ? AccessDecision.allow() : AccessDecision.deny("missing"));
    when(security.decide(eq(permissions), any(RepositoryPermission.class))).thenAnswer(invocation -> {
      RepositoryPermission permission = invocation.getArgument(1);
      boolean allowed = permission.action() == PermissionAction.BROWSE
          ? !hidden.contains(permission.repository())
          : administered.contains(permission.repository());
      return allowed ? AccessDecision.allow() : AccessDecision.deny("missing");
    });
    List<RepositoryRecord> rows = List.of(repository(11L, "releases"), repository(12L, "snapshots"));
    when(repositories.list()).thenReturn(rows);
    rows.forEach(row -> when(repositories.findById(row.id())).thenReturn(Optional.of(row)));
    when(scans.listFindingPackageScopes(any(FindingQuery.class), any(), anyInt())).thenReturn(List.of(
        new FindingPackageScope(11L, PURL),
        new FindingPackageScope(12L, PURL),
        new FindingPackageScope(12L, null)));
    when(scans.listPackageWaivers(
        eq("snakeyaml"), eq("1.30"), any(), any(), anyLong(), anyInt()))
        .thenAnswer(invocation -> {
          Long repositoryId = invocation.getArgument(3);
          long afterId = invocation.getArgument(4);
          int limit = invocation.getArgument(5);
          return stored.stream()
              .filter(waiver -> waiver.id() > afterId)
              .filter(waiver -> repositoryId == null || repositoryId.equals(waiver.repositoryId()))
              .sorted(Comparator.comparing(ScanWaiver::id))
              .limit(limit)
              .toList();
        });
    when(scans.deleteWaiver(anyLong())).thenAnswer(invocation ->
        stored.removeIf(waiver -> waiver.id().equals(invocation.<Long>getArgument(0))));
    when(scans.createWaiver(any())).thenAnswer(invocation -> {
      ScanWaiver draft = invocation.getArgument(0);
      ScanWaiver created = waiver(nextWaiverId++, draft.scopeType(), draft.repositoryId(), null,
          draft.packageSelector(), draft.expiresAt());
      stored.add(created);
      return created;
    });
  }

  @Test
  void contextDefaultsToAnAdministeredRepositoryAndListsTheWaiversItWouldReplace() {
    stored.add(waiver(7L, "FINDING", 11L, 41L, PURL, Instant.now().plusSeconds(60)));

    var context = service.packageWaiverContext(actor, "snakeyaml", "1.30", null, false);

    assertEquals(List.of(11L), context.repositories().stream()
        .map(SecurityScanManagementService.PackageWaiverRepository::id).toList(),
        "only administered repositories that contain the package are offered");
    assertEquals(11L, context.repositoryId());
    assertFalse(context.canWaiveGlobally());
    assertEquals(List.of(PURL), context.packageUrls());
    assertFalse(context.missingPackageUrl(), "the URL-less finding is in another repository");
    assertEquals(List.of(7L), context.replacedWaivers().stream()
        .map(SecurityScanManagementService.WaiverView::id).toList());
    verify(scans).listFindingPackageScopes(argThat(query ->
        "snakeyaml".equals(query.packageName()) && "1.30".equals(query.installedVersion())
            && !query.currentOnly() && query.repositoryIds().equals(List.of(11L, 12L))),
        isNull(), eq(1000));

    var global = assertThrows(ResponseStatusException.class,
        () -> service.packageWaiverContext(actor, "snakeyaml", "1.30", null, true));
    assertEquals(HttpStatus.FORBIDDEN, global.getStatusCode());
    var foreign = assertThrows(ResponseStatusException.class,
        () -> service.packageWaiverContext(actor, "snakeyaml", "1.30", 12L, false));
    assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode());

    granted.add("nexus:security-scanning:update");
    var all = service.packageWaiverContext(actor, "snakeyaml", "1.30", null, true);
    assertNull(all.repositoryId());
    assertTrue(all.canWaiveGlobally());
    assertTrue(all.missingPackageUrl());
  }

  @Test
  void replacesExactlyTheConfirmedWaiversAndCreatesOnePackageWaiverPerUrl() {
    Instant expiresAt = Instant.now().plusSeconds(86_400);
    ScanWaiver finding = waiver(7L, "FINDING", 11L, 41L, PURL, null);
    ScanWaiver asset = waiver(9L, "ASSET", 11L, null, PURL, Instant.now().minusSeconds(5));
    stored.addAll(List.of(finding, asset));

    var result = service.createPackageWaiver(actor, new PackageWaiverCommand(
        "snakeyaml", "1.30", 11L, " shaded ", expiresAt, List.of(9L, 7L), List.of(PURL)));

    assertEquals(List.of(7L, 9L), result.replaced().stream().map(ScanWaiver::id).toList());
    assertEquals(1, result.created().size());
    var order = inOrder(scans);
    order.verify(scans).deleteWaiver(7L);
    order.verify(scans).deleteWaiver(9L);
    // Every waiver row is deleted before the shared waiver revision row is touched.
    order.verify(scans, org.mockito.Mockito.times(2)).invalidatePolicyStatesForWaiver(
        argThat(waiver -> waiver.id() == 7L || waiver.id() == 9L));
    order.verify(scans).createWaiver(argThat(draft -> "REPOSITORY".equals(draft.scopeType())
        && Long.valueOf(11L).equals(draft.repositoryId())
        && draft.assetId() == null && draft.findingId() == null
        && draft.advisorySelector() == null
        && PURL.equals(draft.packageSelector())
        && "shaded".equals(draft.reason())
        && "operator".equals(draft.createdBy())
        && "operator".equals(draft.approvedBy())
        && expiresAt.equals(draft.expiresAt())));
    // The final check reads the waivers again once the new waiver has taken the revision row.
    order.verify(scans).invalidatePolicyStatesForWaiver(result.created().getFirst());
    order.verify(scans).listPackageWaivers("snakeyaml", "1.30", List.of(PURL), 11L, 0L, 1000);
    assertEquals(result.created(), stored);
    verify(scans).invalidatePolicyStatesForWaiver(finding);
    verify(scans).invalidatePolicyStatesForWaiver(asset);
    verify(scans).listFindingPackageScopes(argThat(query ->
        query.repositoryIds().equals(List.of(11L))), isNull(), eq(1000));
  }

  @Test
  void rejectsAnUnconfirmedChangeAndMissingPrivilegesWithoutChangingWaivers() {
    stored.add(waiver(7L, "FINDING", 11L, 41L, PURL, null));

    var changed = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, command(11L, List.of())));
    assertEquals(HttpStatus.CONFLICT, changed.getStatusCode());

    granted.remove("nexus:security-scanning-waivers:delete");
    var noDelete = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, command(11L, List.of(7L))));
    assertEquals(HttpStatus.FORBIDDEN, noDelete.getStatusCode());

    var global = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, command(null, List.of())));
    assertEquals(HttpStatus.FORBIDDEN, global.getStatusCode(), "global waivers need security admin");

    var notAdministered = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, command(12L, List.of())));
    assertEquals(HttpStatus.FORBIDDEN, notAdministered.getStatusCode());

    var past = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, new PackageWaiverCommand(
            "snakeyaml", "1.30", 11L, "reason", Instant.now().minusSeconds(1), List.of(),
            null)));
    assertEquals(HttpStatus.BAD_REQUEST, past.getStatusCode());

    verify(scans, never()).deleteWaiver(anyLong());
    verify(scans, never()).createWaiver(any());
  }

  @Test
  void aScopeWithoutPackageUrlsCannotBeWaivedAsAPackage() {
    when(scans.listFindingPackageScopes(any(FindingQuery.class), any(), anyInt()))
        .thenReturn(List.of(new FindingPackageScope(11L, null)));

    var error = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, command(11L, List.of())));

    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    verify(scans, never()).listPackageWaivers(
        any(), any(), any(), any(), anyLong(), anyInt());
    verify(scans, never()).createWaiver(any());
  }

  @Test
  void contextHonoursAnExplicitScopeAndFallsBackToAllRepositoriesForSecurityAdministrators() {
    assertEquals(11L, service.packageWaiverContext(actor, "snakeyaml", "1.30", 11L, false)
        .repositoryId());

    administered.clear();
    var denied = assertThrows(ResponseStatusException.class,
        () -> service.packageWaiverContext(actor, "snakeyaml", "1.30", null, false));
    assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());

    granted.add("nexus:security-scanning:update");
    var global = service.packageWaiverContext(actor, "snakeyaml", null, null, false);
    assertNull(global.repositoryId(), "without an administered repository, admins get all");
    assertEquals("", global.installedVersion());
    assertTrue(global.repositories().isEmpty());

    when(scans.listFindingPackageScopes(any(FindingQuery.class), any(), anyInt()))
        .thenReturn(List.of(new FindingPackageScope(11L, null)));
    org.mockito.Mockito.clearInvocations(scans);
    var noUrl = service.packageWaiverContext(actor, "snakeyaml", "1.30", null, true);
    assertTrue(noUrl.packageUrls().isEmpty());
    assertTrue(noUrl.missingPackageUrl());
    assertTrue(noUrl.replacedWaivers().isEmpty());
    verify(scans, never()).listPackageWaivers(
        any(), any(), any(), any(), anyLong(), anyInt());

    for (String blank : new String[] {null, " "}) {
      var error = assertThrows(ResponseStatusException.class,
          () -> service.packageWaiverContext(actor, blank, "1.30", null, false));
      assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
  }

  @Test
  void securityAdministratorsReplaceGlobalAndRepositoryWaiversWithOneGlobalPackageWaiver() {
    granted.add("nexus:security-scanning:update");
    ScanWaiver repositoryWaiver = waiver(7L, "REPOSITORY", 11L, null, PURL, null);
    ScanWaiver globalWaiver = waiver(8L, "GLOBAL", null, null, PURL, null);
    stored.addAll(List.of(repositoryWaiver, globalWaiver));

    var result = service.createPackageWaiver(actor, new PackageWaiverCommand(
        "snakeyaml", "1.30", null, "shaded", null, List.of(8L, 7L), null));

    assertEquals(2, result.replaced().size());
    ScanWaiver created = result.created().getFirst();
    assertEquals("GLOBAL", created.scopeType());
    assertNull(created.repositoryId());
    assertNull(created.expiresAt(), "a package waiver may be created without expiry");
    verify(scans).deleteWaiver(7L);
    verify(scans).deleteWaiver(8L);
    verify(scans).listFindingPackageScopes(argThat(query ->
        query.repositoryIds().equals(List.of(11L, 12L))), isNull(), eq(1000));
  }

  @Test
  void packageUrlsTheCallerDidNotConfirmAreNotWaived() {
    // The preview showed one package URL; a scan has since reported a second one.
    String added = "pkg:npm/snakeyaml@1.30";
    when(scans.listFindingPackageScopes(any(FindingQuery.class), any(), anyInt()))
        .thenReturn(List.of(new FindingPackageScope(11L, PURL), new FindingPackageScope(11L, added)));
    Instant expiresAt = Instant.now().plusSeconds(3600);

    var stale = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, new PackageWaiverCommand(
            "snakeyaml", "1.30", 11L, "reason", expiresAt, List.of(), List.of(PURL))));
    assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
    verify(scans, never()).createWaiver(any());

    var confirmed = service.createPackageWaiver(actor, new PackageWaiverCommand(
        "snakeyaml", "1.30", 11L, "reason", expiresAt, List.of(), List.of(added, PURL)));
    assertEquals(List.of(PURL, added), confirmed.created().stream()
        .map(ScanWaiver::packageSelector).toList());

    var nullId = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, new PackageWaiverCommand("snakeyaml", "1.30", 11L, "reason", expiresAt,
            java.util.Arrays.asList(7L, null), null)));
    assertEquals(HttpStatus.BAD_REQUEST, nullId.getStatusCode());
  }

  @Test
  void repositoryChoicesComeFromEveryPageOfPackageScopes() {
    // The actor browses both repositories but administers only the one on the second page.
    administered.clear();
    administered.add("snapshots");
    List<FindingPackageScope> firstPage = java.util.stream.IntStream.range(0, 1000)
        .mapToObj(index -> new FindingPackageScope(11L, PURL + "?variant=" + index))
        .toList();
    when(scans.listFindingPackageScopes(any(FindingQuery.class), isNull(), eq(1000)))
        .thenReturn(firstPage);
    when(scans.listFindingPackageScopes(
        any(FindingQuery.class), eq(firstPage.getLast()), eq(1000)))
        .thenReturn(List.of(new FindingPackageScope(12L, PURL)));

    var context = service.packageWaiverContext(actor, "snakeyaml", "1.30", null, false);

    assertEquals(List.of(12L), context.repositories().stream()
        .map(SecurityScanManagementService.PackageWaiverRepository::id).toList(),
        "a repository beyond the first page of scopes is still offered");
    assertEquals(12L, context.repositoryId());
    assertEquals(List.of(PURL), context.packageUrls());
    assertEquals(12L, service.packageWaiverContext(actor, "snakeyaml", "1.30", 12L, false)
        .repositoryId(), "and can be selected explicitly");
  }

  @Test
  void rejectsInvalidCommandsOversizedScopesAndConcurrentDeletes() {
    for (PackageWaiverCommand invalid : List.of(
        new PackageWaiverCommand("snakeyaml", "1.30", 11L, " ", null, List.of(), null),
        new PackageWaiverCommand(" ", "1.30", 11L, "reason", null, List.of(), null))) {
      var error = assertThrows(ResponseStatusException.class,
          () -> service.createPackageWaiver(actor, invalid));
      assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
    var missing = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, null));
    assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

    when(scans.listFindingPackageScopes(any(FindingQuery.class), any(), anyInt())).thenReturn(
        java.util.stream.IntStream.range(0, 21)
            .mapToObj(index -> new FindingPackageScope(11L, PURL + "?variant=" + index))
            .toList());
    var tooManyUrls = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, command(11L, List.of())));
    assertEquals(HttpStatus.BAD_REQUEST, tooManyUrls.getStatusCode());

    when(scans.listFindingPackageScopes(any(FindingQuery.class), any(), anyInt()))
        .thenReturn(List.of(new FindingPackageScope(11L, PURL)));
    java.util.stream.LongStream.rangeClosed(1, 1000)
        .forEach(id -> stored.add(waiver(id, "FINDING", 11L, id, PURL, null)));
    var tooManyWaivers = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, command(11L, List.of())));
    assertEquals(HttpStatus.BAD_REQUEST, tooManyWaivers.getStatusCode());
    verify(scans, never()).deleteWaiver(anyLong());

    stored.clear();
    stored.add(waiver(7L, "FINDING", 11L, 41L, PURL, null));
    when(scans.deleteWaiver(7L)).thenReturn(false);
    var concurrent = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, command(11L, List.of(7L))));
    assertEquals(HttpStatus.CONFLICT, concurrent.getStatusCode(),
        "a waiver deleted by another replica rolls the replacement back");
    verify(scans, never()).createWaiver(any());
  }

  @Test
  void waiversOfRepositoriesTheActorCannotBrowseAreNeitherDisclosedNorReplaced() {
    // Scan administration allows the all-repositories scope but grants no browse access.
    granted.add("nexus:security-scanning:update");
    hidden.add("snapshots");
    ScanWaiver browsable = waiver(7L, "REPOSITORY", 11L, null, PURL, null);
    ScanWaiver concealed = waiver(8L, "REPOSITORY", 12L, null, PURL, null);
    ScanWaiver global = waiver(9L, "GLOBAL", null, null, PURL, null);
    stored.addAll(List.of(browsable, concealed, global));

    var context = service.packageWaiverContext(actor, "snakeyaml", "1.30", null, true);
    assertEquals(List.of(7L, 9L), context.replacedWaivers().stream()
        .map(SecurityScanManagementService.WaiverView::id).toList());

    var probing = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, command(null, List.of(7L, 8L, 9L))));
    assertEquals(HttpStatus.CONFLICT, probing.getStatusCode(),
        "naming a concealed waiver does not make it replaceable");

    var result = service.createPackageWaiver(actor, command(null, List.of(7L, 9L)));
    assertEquals(List.of(7L, 9L), result.replaced().stream().map(ScanWaiver::id).toList());
    verify(scans).deleteWaiver(7L);
    verify(scans).deleteWaiver(9L);
    verify(scans, never()).deleteWaiver(8L);
    assertTrue(stored.contains(concealed),
        "the concealed waiver stays, and does not count as a concurrent change");
  }

  @Test
  void anAdministeredRepositoryTheActorCannotBrowseIsNotAScope() {
    // Repository administration is a separate permission and grants no browse access.
    hidden.add("releases");

    var error = assertThrows(ResponseStatusException.class, () -> service.createPackageWaiver(
        actor, new PackageWaiverCommand("snakeyaml", "1.30", 11L, "reason", null, null, null)));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode(),
        "a hidden repository is reported like a missing one");
    verify(scans, never()).listFindingPackageScopes(any(), any(), anyInt());
    verify(scans, never()).createWaiver(any());

    // The preview only reads repositories the actor may browse, so it offers no such scope.
    when(scans.listFindingPackageScopes(
        argThat(query -> query != null && query.repositoryIds().equals(List.of(12L))),
        any(), anyInt()))
        .thenReturn(List.of(new FindingPackageScope(12L, PURL)));
    var preview = assertThrows(ResponseStatusException.class,
        () -> service.packageWaiverContext(actor, "snakeyaml", "1.30", 11L, false));
    assertEquals(HttpStatus.NOT_FOUND, preview.getStatusCode(), "as in the preview");
  }

  @Test
  void waiversTheActorCannotBrowseDoNotCountTowardsTheReplacementLimit() {
    granted.add("nexus:security-scanning:update");
    hidden.add("snapshots");
    // A full first page of waivers in a repository the actor cannot browse, then one it can.
    java.util.stream.LongStream.rangeClosed(1, 1000)
        .forEach(id -> stored.add(waiver(id, "REPOSITORY", 12L, null, PURL, null)));
    stored.add(waiver(1001L, "GLOBAL", null, null, PURL, null));

    var context = service.packageWaiverContext(actor, "snakeyaml", "1.30", null, true);
    assertEquals(List.of(1001L), context.replacedWaivers().stream()
        .map(SecurityScanManagementService.WaiverView::id).toList(),
        "waivers beyond the first page are still found");

    var result = service.createPackageWaiver(actor, command(null, List.of(1001L)));
    assertEquals(List.of(1001L), result.replaced().stream().map(ScanWaiver::id).toList());
    verify(scans).deleteWaiver(1001L);
    verify(scans, never()).deleteWaiver(1L);
  }

  @Test
  void anOmittedConfirmationMeansNothingMayBeReplaced() {
    assertEquals(1, service.createPackageWaiver(actor, command(11L, null)).created().size());

    stored.add(waiver(7L, "FINDING", 11L, 41L, PURL, null));
    var unconfirmed = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, command(11L, null)));
    assertEquals(HttpStatus.CONFLICT, unconfirmed.getStatusCode());
  }

  @Test
  void aWaiverCommittedByAConcurrentRequestRollsThePackageWaiverBack() {
    // Another replica passed the same checks and committed its waiver first; this request
    // sees it once its own invalidation has waited for the shared waiver revision row.
    ScanWaiver concurrent = waiver(500L, "REPOSITORY", 11L, null, PURL, null);
    when(scans.invalidatePolicyStatesForWaiver(any())).thenAnswer(invocation -> {
      if (!stored.contains(concurrent)) stored.add(concurrent);
      return 0;
    });

    var error = assertThrows(ResponseStatusException.class,
        () -> service.createPackageWaiver(actor, command(11L, List.of())));

    assertEquals(HttpStatus.CONFLICT, error.getStatusCode(),
        "the second of two simultaneous package waivers is rejected instead of duplicating");
    verify(scans).createWaiver(any());
  }

  private static PackageWaiverCommand command(Long repositoryId, List<Long> replaceWaiverIds) {
    return new PackageWaiverCommand("snakeyaml", "1.30", repositoryId, "reason",
        Instant.now().plusSeconds(3600), replaceWaiverIds, null);
  }

  private static ScanWaiver waiver(
      long id, String scopeType, Long repositoryId, Long findingId, String packageSelector,
      Instant expiresAt) {
    Instant now = Instant.now();
    return new ScanWaiver(id, scopeType, repositoryId, null, findingId, null, packageSelector,
        Map.of(), "reason", null, null, "test", "test", expiresAt, now, now);
  }

  private static RepositoryRecord repository(long id, String name) {
    return new RepositoryRecord(id, name, RepositoryFormat.MAVEN2, RepositoryType.HOSTED,
        "maven2-hosted", true, 1L, null, null, null, null, null, true, Map.of());
  }
}
