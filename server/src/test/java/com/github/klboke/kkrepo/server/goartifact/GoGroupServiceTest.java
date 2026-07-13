package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoGroupServiceTest {
  @Test
  void returnsFirstSuccessfulProxyMemberAfterMissesAndFailures() {
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime miss = runtime(2L, "miss", RepositoryType.PROXY, List.of());
    RepositoryRuntime failed = runtime(3L, "failed", RepositoryType.PROXY, List.of());
    RepositoryRuntime success = runtime(4L, "success", RepositoryType.PROXY, List.of());
    MavenResponse expected = MavenResponse.noBody(200);
    when(proxy.get(miss, "example.com/demo/@latest", true))
        .thenThrow(new MavenExceptions.MavenNotFoundException("missing"));
    when(proxy.get(failed, "example.com/demo/@latest", true))
        .thenThrow(new MavenExceptions.BadUpstreamException("offline"));
    when(proxy.get(success, "example.com/demo/@latest", true)).thenReturn(expected);

    MavenResponse response = new GoGroupService(proxy).get(
        runtime(1L, "group", RepositoryType.GROUP, List.of(miss, failed, success)),
        "example.com/demo/@latest", true);

    assertSame(expected, response);
  }

  @Test
  void supportsNestedGroupsAndSkipsHostedMembers() {
    GoProxyService proxy = mock(GoProxyService.class);
    RepositoryRuntime hosted = runtime(2L, "hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime success = runtime(3L, "success", RepositoryType.PROXY, List.of());
    RepositoryRuntime nested = runtime(4L, "nested", RepositoryType.GROUP, List.of(hosted, success));
    MavenResponse expected = MavenResponse.noBody(200);
    when(proxy.get(success, "example.com/demo/@v/list", false)).thenReturn(expected);

    assertSame(expected, new GoGroupService(proxy).get(
        runtime(1L, "group", RepositoryType.GROUP, List.of(nested)),
        "example.com/demo/@v/list", false));
  }

  @Test
  void rejectsNonGroupsAndEmptyOrExhaustedGroups() {
    GoGroupService service = new GoGroupService(mock(GoProxyService.class));
    assertThrows(IllegalStateException.class,
        () -> service.get(runtime(1L, "proxy", RepositoryType.PROXY, List.of()), "x", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(runtime(1L, "empty", RepositoryType.GROUP, List.of()), "x", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(
            runtime(1L, "hosted-only", RepositoryType.GROUP,
                List.of(runtime(2L, "hosted", RepositoryType.HOSTED, List.of()))),
            "x", false));
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.GO, type, name, true, 7L,
        null, null, null, true, "https://proxy.golang.org/",
        60, 60, true, null, members);
  }
}
