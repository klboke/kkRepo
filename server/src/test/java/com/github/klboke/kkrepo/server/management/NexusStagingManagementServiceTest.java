package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.staging.StagingService;
import com.github.klboke.kkrepo.server.staging.StagingService.StagingItemView;
import com.github.klboke.kkrepo.server.staging.StagingService.StagingRepositoryView;
import com.github.klboke.kkrepo.server.staging.StagingService.StagingSessionView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class NexusStagingManagementServiceTest {
  private final StagingService staging = mock(StagingService.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final ForwardedHeaderPolicy forwarded = mock(ForwardedHeaderPolicy.class);
  private final NexusStagingManagementService service = new NexusStagingManagementService(
      staging, repositories, new NexusStagingIdCodec(), forwarded);

  @Test
  void exposesStagedAssetsUsingNexusShapes() {
    PermissionSubject actor = new PermissionSubject("LOCAL", "alice", Set.of(), null);
    MockHttpServletRequest request = request(actor);
    StagingItemView item = item(11, "releases/demo.txt", "demo.txt", "1.0.0");
    when(staging.getByKey(eq("session-1"), any())).thenReturn(session());
    when(staging.itemsByKey(eq("7"), any())).thenReturn(List.of(item));
    when(repositories.findById(1L)).thenReturn(Optional.of(repository()));
    when(repositories.findByName("raw-hosted")).thenReturn(Optional.of(repository()));

    var page = service.searchAssets("raw-hosted", "session-1", null, request);

    assertEquals(1, page.items().size());
    assertEquals("releases/demo.txt", page.items().getFirst().path());
    assertTrue(page.items().getFirst().downloadUrl().contains("stagingSession=7"));
    assertEquals(new NexusStagingIdCodec().encodeAsset(7, 11),
        page.items().getFirst().id());
  }

  @Test
  void exposesStagedComponentsGroupedByProjection() {
    PermissionSubject actor = new PermissionSubject("LOCAL", "alice", Set.of(), null);
    MockHttpServletRequest request = request(actor);
    when(staging.getByKey(eq("7"), any())).thenReturn(session());
    when(staging.itemsByKey(eq("7"), any())).thenReturn(List.of(
        item(1, "demo/a.txt", "demo", "1.0.0"),
        item(2, "demo/b.txt", "demo", "1.0.0")));
    when(repositories.findById(1L)).thenReturn(Optional.of(repository()));
    when(repositories.findByName("raw-hosted")).thenReturn(Optional.of(repository()));

    var page = service.components("raw-hosted", "7", request);

    assertEquals(1, page.items().size());
    assertEquals("demo", page.items().getFirst().name());
    assertEquals(2, page.items().getFirst().assets().size());
  }

  @Test
  void listsOnlyRepositoriesInTheSession() {
    PermissionSubject actor = new PermissionSubject("LOCAL", "alice", Set.of(), null);
    MockHttpServletRequest request = request(actor);
    when(staging.getByKey(eq("7"), any())).thenReturn(session());
    when(repositories.findById(1L)).thenReturn(Optional.of(repository()));
    when(forwarded.serverBaseUrl(request)).thenReturn("https://repo.test");

    var repositories = service.repositories("7", request);

    assertEquals(1, repositories.size());
    assertEquals("raw-hosted", repositories.getFirst().name());
    assertEquals("https://repo.test/repository/raw-hosted",
        repositories.getFirst().url());
  }

  private StagingSessionView session() {
    StagingSessionRecord record = new StagingSessionRecord(
        7L, "release-1", "OPEN", 1, "LOCAL", "alice", Instant.EPOCH, Instant.EPOCH,
        null, null, Instant.MAX, null, Map.of());
    return new StagingSessionView(
        record, List.of(new StagingRepositoryView(1L, "raw-hosted", "raw")), 2);
  }

  private StagingItemView item(long id, String path, String name, String version) {
    return new StagingItemView(
        id, 1L, "raw", path, "ADD", "STAGED", "text/plain", 5L,
        "a".repeat(64), path,
        Map.of(
            "stagingComponent",
            Map.of("name", name, "version", version, "kind", "raw")),
        Instant.EPOCH, Instant.EPOCH);
  }

  private RepositoryRecord repository() {
    return new RepositoryRecord(
        1L, "raw-hosted", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
  }

  private MockHttpServletRequest request(PermissionSubject actor) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/service/rest/v1/assets");
    request.setAttribute(
        AuthenticatedSubject.REQUEST_ATTRIBUTE,
        new AuthenticatedSubject("LOCAL", "alice", "realm", null, actor));
    return request;
  }
}
