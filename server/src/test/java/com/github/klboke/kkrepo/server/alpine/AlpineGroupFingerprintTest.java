package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlpineGroupFingerprintTest {

  @Test
  void lookupFingerprintMatchesPublishedProjectionFingerprint() {
    String namespace = "v3.23/main/x86_64";
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    when(registry.findSuite(11L, namespace)).thenReturn(Optional.of(suite(11L, namespace, 7L)));
    when(registry.findSuite(12L, namespace)).thenReturn(Optional.of(suite(12L, namespace, 9L)));

    RepositoryRuntime first = runtime(11L, RepositoryFormat.ALPINE, RepositoryType.HOSTED, true,
        List.of());
    RepositoryRuntime second = runtime(12L, RepositoryFormat.ALPINE, RepositoryType.PROXY, true,
        List.of());
    RepositoryRuntime offline = runtime(13L, RepositoryFormat.ALPINE, RepositoryType.HOSTED, false,
        List.of());
    RepositoryRuntime unrelated = runtime(14L, RepositoryFormat.RAW, RepositoryType.HOSTED, true,
        List.of());
    RepositoryRuntime nested = runtime(20L, RepositoryFormat.ALPINE, RepositoryType.GROUP, true,
        List.of(second, offline));
    RepositoryRuntime group = runtime(21L, RepositoryFormat.ALPINE, RepositoryType.GROUP, true,
        List.of(first, unrelated, nested));
    AlpineRepositorySettings repositorySettings = mock(AlpineRepositorySettings.class);
    when(repositorySettings.get(second)).thenReturn(new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), true, true, true,
        "fixture.rsa.pub", "RSA", "fixture", List.of()));

    AlpineService service = new AlpineService(
        registry, null, repositorySettings, null, null, null, null, null, null, null, null, null);
    LinkedHashMap<Long, Long> publishedRevisions = new LinkedHashMap<>();
    publishedRevisions.put(11L, 7L);
    publishedRevisions.put(12L, 9L);

    assertEquals(
        AlpineService.fingerprint(publishedRevisions),
        service.memberFingerprint(group, namespace));
  }

  private static AlpineRegistryDao.SuiteState suite(
      long repositoryId, String namespace, long publishedRevision) {
    return new AlpineRegistryDao.SuiteState(
        repositoryId,
        namespace,
        publishedRevision,
        Instant.EPOCH,
        publishedRevision,
        1,
        Instant.EPOCH,
        null,
        null,
        Instant.EPOCH);
  }

  private static RepositoryRuntime runtime(
      long id,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        "repository-" + id,
        format,
        type,
        format.name().toLowerCase() + "-" + type.name().toLowerCase(),
        online,
        1L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        members);
  }
}
