package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityScanRepositoryScopeTest {
  @Test
  void resolvesGroupOnlyScanningConfigToConcreteMemberContent() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    SecurityScanRepositoryScope scope = new SecurityScanRepositoryScope(scans, repositories);
    RepositoryRecord source = repository(1L, "member", RepositoryType.HOSTED);
    RepositoryRecord group = repository(2L, "public", RepositoryType.GROUP);
    RepositoryScanConfig groupConfig = config(2L, true);

    when(repositories.findById(1L)).thenReturn(Optional.of(source));
    when(repositories.findById(2L)).thenReturn(Optional.of(group));
    when(repositories.listGroupsContaining(1L)).thenReturn(List.of(group));
    when(repositories.listGroupsContaining(2L)).thenReturn(List.of());
    when(repositories.listMembers(2L)).thenReturn(List.of(source));
    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.empty());
    when(scans.findRepositoryConfig(2L)).thenReturn(Optional.of(groupConfig));

    assertEquals(List.of(groupConfig), scope.effectiveConfigsForSource(1L));
    assertEquals(List.of(1L), scope.sourceRepositoryIds(2L));
  }

  @Test
  void honorsTheSourceTypeToggleFromAGroupConfig() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    SecurityScanRepositoryScope scope = new SecurityScanRepositoryScope(scans, repositories);
    RepositoryRecord source = repository(1L, "member", RepositoryType.HOSTED);
    RepositoryRecord group = repository(2L, "public", RepositoryType.GROUP);

    when(repositories.findById(1L)).thenReturn(Optional.of(source));
    when(repositories.listGroupsContaining(1L)).thenReturn(List.of(group));
    when(repositories.listGroupsContaining(2L)).thenReturn(List.of());
    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.empty());
    when(scans.findRepositoryConfig(2L)).thenReturn(Optional.of(config(2L, false)));

    assertTrue(scope.effectiveConfigsForSource(1L).isEmpty());
  }

  private static RepositoryRecord repository(
      long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.MAVEN2, type, "maven2-" + type.name().toLowerCase(),
        true, null, null, null, null, null, null, true, Map.of());
  }

  private static RepositoryScanConfig config(long repositoryId, boolean scanHosted) {
    return new RepositoryScanConfig(
        repositoryId, true, 1L, scanHosted, true, EnforcementMode.AUDIT,
        PolicyAction.ALLOW, PolicyAction.ALLOW, PolicyAction.ALLOW,
        3600L, 1L, 1L, Instant.EPOCH, Instant.EPOCH);
  }
}
