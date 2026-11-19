package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupWriteTargetServiceTest {
  private final RepositoryDao repositories = mock(RepositoryDao.class);

  @Test
  void usesTheConfiguredHostedMember() {
    RepositoryRecord group = repository(10, "group", RepositoryType.GROUP, null);
    RepositoryRecord hosted = repository(11, "hosted", RepositoryType.HOSTED, null);
    when(repositories.findById(10)).thenReturn(java.util.Optional.of(group));
    when(repositories.listMembers(10)).thenReturn(List.of(hosted));
    GroupWriteTargetService service = new GroupWriteTargetService(repositories);

    assertEquals("hosted", service.writeTargetRecord(group).orElseThrow().name());
  }

  @Test
  void fallsBackToTheFirstHostedMemberInOrder() {
    RepositoryRecord group = repository(10, "group", RepositoryType.GROUP, null);
    RepositoryRecord proxy = repository(11, "proxy", RepositoryType.PROXY, null);
    RepositoryRecord hosted = repository(12, "hosted", RepositoryType.HOSTED, null);
    when(repositories.findById(10)).thenReturn(java.util.Optional.of(group));
    when(repositories.listMembers(10)).thenReturn(List.of(proxy, hosted));
    GroupWriteTargetService service = new GroupWriteTargetService(repositories);

    assertEquals(12, service.writeTargetRecord(group).orElseThrow().id());
  }

  @Test
  void resolvesNestedGroupsAndRejectsMissingTargets() {
    RepositoryRecord group = repository(10, "group", RepositoryType.GROUP, null);
    RepositoryRecord nested = repository(11, "nested", RepositoryType.GROUP, null);
    RepositoryRecord hosted = repository(12, "hosted", RepositoryType.HOSTED, null);
    when(repositories.findById(10)).thenReturn(java.util.Optional.of(group));
    when(repositories.listMembers(10)).thenReturn(List.of(nested));
    when(repositories.listMembers(11)).thenReturn(List.of(hosted));
    GroupWriteTargetService service = new GroupWriteTargetService(repositories);

    assertEquals(12, service.writeTargetRecord(group).orElseThrow().id());
    assertTrue(service.writeTargetRecord(repository(20, "empty", RepositoryType.GROUP, null))
        .isEmpty());
  }

  @Test
  void readsTheConfiguredWriteRepositoryAttribute() {
    RepositoryRecord group = repository(
        10, "group", RepositoryType.GROUP, "hosted");
    RepositoryRecord hosted = repository(11, "hosted", RepositoryType.HOSTED, null);
    when(repositories.findById(10)).thenReturn(java.util.Optional.of(group));
    when(repositories.listMembers(10)).thenReturn(List.of(hosted));
    GroupWriteTargetService service = new GroupWriteTargetService(repositories);

    assertEquals(11, service.writeTargetRecord(group).orElseThrow().id());
  }

  private static RepositoryRecord repository(
      long id, String name, RepositoryType type, String writeRepository) {
    Map<String, Object> group = writeRepository == null
        ? Map.of() : Map.of("writeRepository", writeRepository);
    return new RepositoryRecord(
        id, name, RepositoryFormat.RAW, type, "raw-" + type.name().toLowerCase(), true, 1L,
        null, null, null, null, type == RepositoryType.HOSTED ? "ALLOW" : null, true,
        type == RepositoryType.GROUP ? Map.of("group", group) : Map.of());
  }
}
