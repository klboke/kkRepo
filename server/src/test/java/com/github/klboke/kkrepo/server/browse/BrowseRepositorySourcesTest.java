package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowseRepositorySourcesTest {
  @Test
  void recursivelyFlattensSwiftGroupsInMemberOrderAndAvoidsCycles() {
    RepositoryRecord outer = repository(1L, "swift-outer", RepositoryType.GROUP);
    RepositoryRecord nested = repository(2L, "swift-nested", RepositoryType.GROUP);
    RepositoryRecord first = repository(3L, "swift-first", RepositoryType.HOSTED);
    RepositoryRecord second = repository(4L, "swift-second", RepositoryType.PROXY);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.listMembers(outer.id())).thenReturn(List.of(nested, second));
    when(repositoryDao.listMembers(nested.id())).thenReturn(List.of(first, outer));

    assertEquals(List.of("swift-first", "swift-second"),
        BrowseRepositorySources.swiftSources(outer, repositoryDao).stream()
            .map(RepositoryRecord::name)
            .toList());
  }

  @Test
  void recursivelyFlattensCondaGroupsInMemberOrderAndAvoidsCycles() {
    RepositoryRecord outer = repository(
        11L, "conda-outer", RepositoryType.GROUP, RepositoryFormat.CONDA);
    RepositoryRecord nested = repository(
        12L, "conda-nested", RepositoryType.GROUP, RepositoryFormat.CONDA);
    RepositoryRecord first = repository(
        13L, "conda-first", RepositoryType.HOSTED, RepositoryFormat.CONDA);
    RepositoryRecord second = repository(
        14L, "conda-second", RepositoryType.PROXY, RepositoryFormat.CONDA);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.listMembers(outer.id())).thenReturn(List.of(nested, second));
    when(repositoryDao.listMembers(nested.id())).thenReturn(List.of(first, outer));

    assertEquals(List.of("conda-first", "conda-second"),
        BrowseRepositorySources.condaSources(outer, repositoryDao).stream()
            .map(RepositoryRecord::name)
            .toList());
  }

  private static RepositoryRecord repository(
      long id,
      String name,
      RepositoryType type) {
    return repository(id, name, type, RepositoryFormat.SWIFT);
  }

  private static RepositoryRecord repository(
      long id,
      String name,
      RepositoryType type,
      RepositoryFormat format) {
    return new RepositoryRecord(
        id,
        name,
        format,
        type,
        format.name().toLowerCase(Locale.ROOT) + "-" + type.name().toLowerCase(Locale.ROOT),
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
