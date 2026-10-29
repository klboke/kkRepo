package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  void batchesSharedAncestorsAndCyclesWithoutRetainingTopologyBetweenRequests() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    SecurityScanRepositoryScope scope = new SecurityScanRepositoryScope(scans, repositories);
    var hosted = repository(1L, "hosted", RepositoryType.HOSTED);
    var proxy = repository(2L, "proxy", RepositoryType.PROXY);
    var group = repository(3L, "group", RepositoryType.GROUP);
    var hostedConfig = config(3L, true);
    var proxyConfig = config(4L, false);
    when(repositories.findParentGroupIds(List.of(1L, 2L)))
        .thenReturn(Map.of(1L, List.of(3L), 2L, List.of(3L)));
    when(repositories.findParentGroupIds(List.of(3L))).thenReturn(Map.of(3L, List.of(4L)));
    when(repositories.findParentGroupIds(List.of(4L))).thenReturn(Map.of(4L, List.of(3L)));
    when(scans.findRepositoryConfigs(List.of(1L, 2L, 3L, 4L)))
        .thenReturn(List.of(hostedConfig, proxyConfig));

    var result = scope.effectiveConfigsForSources(List.of(hosted, proxy, group));
    assertEquals(List.of(hostedConfig), result.get(1L));
    assertEquals(List.of(hostedConfig, proxyConfig), result.get(2L));
    assertFalse(result.containsKey(3L));
    org.mockito.Mockito.verify(repositories).findParentGroupIds(List.of(3L));
    org.mockito.Mockito.verify(repositories).findParentGroupIds(List.of(4L));
    org.mockito.Mockito.verify(scans, org.mockito.Mockito.never()).findRepositoryConfig(org.mockito.ArgumentMatchers.anyLong());

    when(repositories.findParentGroupIds(List.of(1L, 2L))).thenReturn(Map.of());
    assertEquals(Map.of(1L, List.of(), 2L, List.of()),
        scope.effectiveConfigsForSources(List.of(hosted, proxy)));
    assertEquals(Map.of(), scope.effectiveConfigsForSources(List.of(group)));
    assertEquals(Map.of(), scope.effectiveConfigsForSources(List.of()));
  }

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

  @Test
  void appliesHostedAndProxyFlagsOnlyToMatchingConcreteSources() {
    RepositoryScanConfig hostedOnly = new RepositoryScanConfig(
        2L, true, 1L, true, false, EnforcementMode.AUDIT,
        PolicyAction.ALLOW, PolicyAction.ALLOW, PolicyAction.ALLOW,
        3600L, 1L, 1L, Instant.EPOCH, Instant.EPOCH);

    assertTrue(SecurityScanRepositoryScope.appliesToSource(
        hostedOnly, RepositoryType.HOSTED));
    assertFalse(SecurityScanRepositoryScope.appliesToSource(
        hostedOnly, RepositoryType.PROXY));
    assertFalse(SecurityScanRepositoryScope.appliesToSource(
        hostedOnly, RepositoryType.GROUP));
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
