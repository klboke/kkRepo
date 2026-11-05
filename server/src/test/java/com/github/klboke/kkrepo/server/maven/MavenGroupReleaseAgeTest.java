package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.releaseage.ReleaseAgeDeniedException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenGroupReleaseAgeTest {
  private static final MavenPath JAR =
      new MavenPathParser().parsePath("com/acme/demo/1.2/demo-1.2.jar");

  @Test
  void quarantinedProxyMemberDoesNotHideAHostedMemberThatHoldsTheArtifact() {
    MavenHostedService hosted = mock(MavenHostedService.class);
    MavenProxyService proxy = mock(MavenProxyService.class);
    RepositoryRuntime quarantining = member(2L, "central", RepositoryType.PROXY, 2880);
    RepositoryRuntime internal = member(3L, "internal", RepositoryType.HOSTED, 0);
    MavenResponse expected = MavenResponse.noBody(200);
    when(proxy.get(quarantining, JAR, true))
        .thenThrow(new ReleaseAgeDeniedException("quarantined", null));
    when(hosted.get(internal, JAR, true)).thenReturn(expected);

    MavenResponse response = service(hosted, proxy).get(
        group(List.of(quarantining, internal)), JAR, true);

    assertSame(expected, response);
  }

  @Test
  void quarantineIsReportedInsteadOfNotFoundWhenNoMemberServesTheArtifact() {
    MavenHostedService hosted = mock(MavenHostedService.class);
    MavenProxyService proxy = mock(MavenProxyService.class);
    RepositoryRuntime quarantining = member(2L, "central", RepositoryType.PROXY, 2880);
    RepositoryRuntime internal = member(3L, "internal", RepositoryType.HOSTED, 0);
    ReleaseAgeDeniedException denied = new ReleaseAgeDeniedException("quarantined", null);
    when(proxy.get(quarantining, JAR, false)).thenThrow(denied);
    when(hosted.get(internal, JAR, false))
        .thenThrow(new MavenExceptions.MavenNotFoundException(JAR.path()));

    ReleaseAgeDeniedException thrown = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> service(hosted, proxy).get(
            group(List.of(quarantining, internal)), JAR, false));

    assertSame(denied, thrown);
  }

  private static MavenGroupService service(MavenHostedService hosted, MavenProxyService proxy) {
    return new MavenGroupService(
        hosted, proxy, mock(AssetDao.class), mock(BlobStorageRegistry.class),
        mock(MavenAssetWriter.class), mock(AssetMetadataCache.class));
  }

  private static RepositoryRuntime member(
      long id, String name, RepositoryType type, int minimumReleaseAgeMinutes) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.MAVEN2, type, "maven2-" + type.name().toLowerCase(), true, 1L,
        null, "MIXED", "PERMISSIVE", true,
        type == RepositoryType.PROXY ? "https://repo.example/maven2/" : null,
        1440, 1440, true, null, List.of(), minimumReleaseAgeMinutes);
  }

  private static RepositoryRuntime group(List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        1L, "maven-public", RepositoryFormat.MAVEN2, RepositoryType.GROUP, "maven2-group", true,
        1L, null, "MIXED", "PERMISSIVE", true, null, 1440, 1440, true, null, members);
  }
}
