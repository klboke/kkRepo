package com.github.klboke.kkrepo.server.kkquality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Project;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authorization matrix of the kkQuality surface.
 *
 * <p>The table in {@code docs/en/dev/kkquality-design.md} is the spec: the application privileges
 * cover every project, a repository binding only covers its own projects, and ingestion requires
 * edit rights on the bound repository (or the application update privilege).
 */
class KkQualityAuthorizerTest {
  private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

  private SecurityManagementService security;
  private RepositoryDao repositories;
  private KkQualityAuthorizer authorizer;
  private final AuthenticatedSubject subject = new AuthenticatedSubject(
      "local", "alice", "local", null, new PermissionSubject("local", "alice", Set.of(), null));

  @BeforeEach
  void setUp() {
    security = mock(SecurityManagementService.class);
    repositories = mock(RepositoryDao.class);
    authorizer = new KkQualityAuthorizer(security, repositories);
    when(repositories.findById(3L)).thenReturn(Optional.of(repository(3L, "repo-a")));
    when(repositories.findById(4L)).thenReturn(Optional.of(repository(4L, "repo-b")));
    when(repositories.findByName("repo-a")).thenReturn(Optional.of(repository(3L, "repo-a")));
  }

  @Test
  void globalPrivilegesCoverEveryProjectAndIngestion() {
    when(security.decide(any(), eq(KkQualityAuthorizer.READ_PRIVILEGE)))
        .thenReturn(AccessDecision.allow());
    when(security.decide(any(), eq(KkQualityAuthorizer.UPDATE_PRIVILEGE)))
        .thenReturn(AccessDecision.allow());

    assertTrue(authorizer.canReadProject(subject, project(3L)));
    assertTrue(authorizer.canReadProject(subject, project(null)));
    assertTrue(authorizer.canIngest(subject, 3L));
    assertTrue(authorizer.canIngest(subject, null));
  }

  @Test
  void repositoryReadCoversOnlyTheBoundProjects() {
    when(security.decide(any(), anyString())).thenReturn(AccessDecision.deny("no application grant"));
    when(security.decide(any(), any(RepositoryPermission.class)))
        .thenAnswer(invocation -> decision(invocation.getArgument(1)));

    assertTrue(authorizer.canReadProject(subject, project(3L)));
    assertFalse(authorizer.canReadProject(subject, project(4L)));
    // An unbound project is only visible through the application privilege.
    assertFalse(authorizer.canReadProject(subject, project(null)));
  }

  @Test
  void ingestRequiresEditNotReadOnTheBoundRepository() {
    when(security.decide(any(), anyString())).thenReturn(AccessDecision.deny("no application grant"));
    when(security.decide(any(), any(RepositoryPermission.class)))
        .thenAnswer(invocation -> decision(invocation.getArgument(1)));

    assertTrue(authorizer.canIngest(subject, 3L));
    assertFalse(authorizer.canIngest(subject, 4L));
    assertFalse(authorizer.canIngest(subject, 5L));
    // No binding and no application privilege: ingestion is refused rather than silently global.
    assertFalse(authorizer.canIngest(subject, null));
  }

  @Test
  void requireIngestTranslatesDenialIntoForbidden() {
    when(security.decide(any(), anyString())).thenReturn(AccessDecision.deny("no application grant"));
    when(security.decide(any(), any(RepositoryPermission.class)))
        .thenAnswer(invocation -> decision(invocation.getArgument(1)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> authorizer.requireIngest(subject, 4L));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
  }

  @Test
  void readableRepositoryIdsListsOnlyReadableRepositories() {
    when(security.decide(any(), eq(KkQualityAuthorizer.READ_PRIVILEGE)))
        .thenReturn(AccessDecision.deny("no application grant"));
    when(security.decide(any(), any(RepositoryPermission.class)))
        .thenAnswer(invocation -> decision(invocation.getArgument(1)));
    when(repositories.list()).thenReturn(List.of(
        repository(3L, "repo-a"), repository(4L, "repo-b")));

    assertEquals(List.of(3L), authorizer.readableRepositoryIds(subject));
  }

  @Test
  void unknownRepositoryNamesAreNotResolved() {
    assertTrue(authorizer.findRepository("nope").isEmpty());
    assertTrue(authorizer.findRepository(" ").isEmpty());
    assertTrue(authorizer.findRepository(null).isEmpty());
  }

  /** Grants read+edit on repo-a only. */
  private static AccessDecision decision(RepositoryPermission permission) {
    boolean allowed = "repo-a".equals(permission.repository());
    return allowed ? AccessDecision.allow() : AccessDecision.deny("not granted");
  }

  private static Project project(Long repositoryId) {
    return new Project(
        5L, "ext-5", "orders", "Orders", null, "private", repositoryId, CREATED, CREATED);
  }

  private static RepositoryRecord repository(Long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven2-hosted", true, 1L, null,
        null, "RELEASE", "STRICT", "ALLOW", false, Map.of());
  }
}
