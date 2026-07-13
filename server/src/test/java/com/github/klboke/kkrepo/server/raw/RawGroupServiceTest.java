package com.github.klboke.kkrepo.server.raw;

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

class RawGroupServiceTest {
  @Test
  void probesMembersInOrderAndRecursesIntoNestedGroups() {
    RawHostedService hosted = mock(RawHostedService.class);
    RawProxyService proxy = mock(RawProxyService.class);
    RawGroupService service = new RawGroupService(hosted, proxy);
    RepositoryRuntime hostedMissing = runtime(1, "hosted-missing", RepositoryType.HOSTED, List.of());
    RepositoryRuntime proxyBroken = runtime(2, "proxy-broken", RepositoryType.PROXY, List.of());
    RepositoryRuntime hostedUnsupported = runtime(3, "hosted-unsupported", RepositoryType.HOSTED, List.of());
    RepositoryRuntime proxySuccess = runtime(4, "proxy-success", RepositoryType.PROXY, List.of());
    RepositoryRuntime nested = runtime(5, "nested", RepositoryType.GROUP, List.of(proxySuccess));
    RepositoryRuntime group = runtime(
        6, "all", RepositoryType.GROUP,
        List.of(hostedMissing, proxyBroken, hostedUnsupported, nested));
    MavenResponse expected = MavenResponse.noBody(200);

    when(hosted.get(hostedMissing, "docs/file.txt", false))
        .thenThrow(new MavenExceptions.MavenNotFoundException("missing"));
    when(proxy.get(proxyBroken, "docs/file.txt", false))
        .thenThrow(new MavenExceptions.BadUpstreamException("offline"));
    when(hosted.get(hostedUnsupported, "docs/file.txt", false))
        .thenThrow(new MavenExceptions.MethodNotAllowed("unsupported"));
    when(proxy.get(proxySuccess, "docs/file.txt", false)).thenReturn(expected);

    assertSame(expected, service.get(group, "docs/file.txt", false));
  }

  @Test
  void rejectsNonGroupsAndEmptyGroups() {
    RawGroupService service = new RawGroupService(
        mock(RawHostedService.class), mock(RawProxyService.class));

    assertThrows(IllegalStateException.class,
        () -> service.get(runtime(1, "hosted", RepositoryType.HOSTED, List.of()), "file", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(runtime(2, "empty", RepositoryType.GROUP, List.of()), "file", false));
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.RAW, type, "raw", true, 1L,
        "ALLOW", null, null, true, "https://upstream.example.test/",
        60, 60, true, "ATTACHMENT", members);
  }
}
