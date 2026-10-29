package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanFinding;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRunSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRunSubjectAsset;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Cost assertions accompany the actual list capabilities and real group-scope resolver. */
class SecurityScanManagementServiceQueryBudgetTest {
  static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
  static class Fixture {
    final SecurityScanDao scans = mock(SecurityScanDao.class);
    final RepositoryDao repos = mock(RepositoryDao.class);
    final AssetDao assets = mock(AssetDao.class);
    final BrowseNodeDao browse = mock(BrowseNodeDao.class);
    final DockerRegistryDao docker = mock(DockerRegistryDao.class);
    final SecurityManagementService security = mock(SecurityManagementService.class);
    final AuthenticatedSubject actor = new AuthenticatedSubject("Local", "actor", "local", null,
        new PermissionSubject("Local", "actor", Set.of(), null));
    final Map<Long, RepositoryRecord> repositories = new LinkedHashMap<>();
    final Map<Long, AssetRecord> content = new LinkedHashMap<>();
    // JdbcAssetDao chunks SELECT * by 500 IDs; these are query counts, not latency benchmarks.
    int loadedAssets, assetSql;
    Fixture(boolean writer, boolean groupAdmin) {
      when(repos.list()).thenAnswer(i -> List.copyOf(repositories.values()));
      when(repos.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(repositories.get(i.getArgument(0))));
      when(security.decide(any(),anyString())).thenAnswer(i -> {
        String permission = i.getArgument(1);
        return writer || permission.endsWith(":read") ? AccessDecision.allow() : AccessDecision.deny("reader");
      });
      when(security.decide(any(),any(RepositoryPermission.class))).thenAnswer(i -> {
        RepositoryPermission permission = i.getArgument(1);
        return permission.action() == PermissionAction.BROWSE || writer && (!groupAdmin || permission.repository().equals("group"))
            ? AccessDecision.allow() : AccessDecision.deny("source not administered");
      });
      when(assets.findAssetsByIds(any())).thenAnswer(i -> {
        Collection<Long> ids = i.getArgument(0);
        List<Long> unique = ids.stream().distinct().toList();
        loadedAssets += unique.size(); assetSql += (unique.size() + 499) / 500;
        return unique.stream().filter(content::containsKey).collect(Collectors.toMap(x -> x, content::get));
      });
      when(assets.findAssetById(anyLong())).thenAnswer(i -> Optional.ofNullable(content.get(i.getArgument(0))));
    }
    SecurityScanManagementService service() {
      var scope = new SecurityScanRepositoryScope(scans,repos);
      var docs = mock(SecurityScanDocumentStore.class);
      var persistence = mock(SecurityScanDocumentPersistence.class);
      var properties = new SecurityScanningProperties();
      return new SecurityScanManagementService(scans,repos,assets,browse,docker,security,docs,persistence,properties,scope);
    }
    long count(Object mock, String name) {
      return mockingDetails(mock).getInvocations().stream().filter(i -> i.getMethod().getName().equals(name)).count();
    }
  }

  static RepositoryRecord repo(long id,String name,RepositoryType type) {
    return new RepositoryRecord(id,name,RepositoryFormat.MAVEN2,type,"maven2-"+type.name().toLowerCase(),true,1L,null,null,null,null,null,true,Map.of());
  }
  static AssetRecord asset(long id,long repo) {
    return new AssetRecord(id,repo,null,1L,RepositoryFormat.MAVEN2,"app-"+id+".jar",null,"app.jar","file","application/java-archive",1024L,null,NOW,Map.of());
  }
  @Test
  void groupTaskQueries() {
    for(int size:new int[]{10,50,200}) for(boolean writer:new boolean[]{false,true}) {
      Fixture f=new Fixture(writer,true);
      var group=repo(1000L,"group",RepositoryType.GROUP); f.repositories.put(1000L,group);
      List<ScanTask> tasks=new ArrayList<>();
      for(long id=1;id<=size;id++) {
        f.repositories.put(id,repo(id,"source-"+id,RepositoryType.HOSTED)); f.content.put(id,asset(id,id));
        var t=mock(ScanTask.class); when(t.id()).thenReturn(id); when(t.repositoryId()).thenReturn(id);
        when(t.assetId()).thenReturn(id); when(t.profileId()).thenReturn(1L);
        when(t.subjectKind()).thenReturn(SubjectKind.ASSET_BLOB); when(t.stage()).thenReturn(ScanStage.CATALOG_AND_MATCH);
        when(t.requestReason()).thenReturn(RequestReason.MANUAL); when(t.status()).thenReturn(TaskStatus.FAILED);
        tasks.add(t);
      }
      when(f.repos.findParentGroupIds(anyList())).thenAnswer(i -> {
        List<Long> ids=i.getArgument(0);
        return ids.stream().filter(id -> id != 1000L).collect(Collectors.toMap(id -> id, id -> List.of(1000L)));
      });
      when(f.scans.findRepositoryConfigs(anyList())).thenReturn(List.of(new RepositoryScanConfig(1000L,true,1L,true,true,EnforcementMode.AUDIT,
          PolicyAction.ALLOW,PolicyAction.ALLOW,PolicyAction.ALLOW,null,null,1,NOW,NOW)));
      when(f.scans.listTasksByRepositories(anyList(),isNull(),isNull(),eq(0L),anyInt(),isNull())).thenReturn(tasks);
      var s=f.service();
      var page=s.taskPage(f.actor,null,null,null,0,size);
      assertEquals(size,page.items().size());
      assertTrue(page.items().stream().allMatch(t -> t.canManage() == writer && !t.canRescan()));
      assertEquals(0,f.count(f.repos,"findById"));
      assertEquals(writer?2:0,f.count(f.repos,"findParentGroupIds"));
      assertEquals(writer?1:0,f.count(f.scans,"findRepositoryConfigs"));
      assertEquals(0,f.count(f.scans,"findRepositoryConfig"));
      assertEquals(0,f.count(f.repos,"listGroupsContaining"));
    }
  }
  @Test
  void repeatedRepositoryWaiverQueries() {
    for(int size:new int[]{10,50,200}) for(boolean writer:new boolean[]{false,true}) {
      Fixture f=new Fixture(writer,false); f.repositories.put(1L,repo(1L,"hosted",RepositoryType.HOSTED));
      List<ScanWaiver> rows=LongStream.rangeClosed(1,size).mapToObj(id -> new ScanWaiver(id,"REPOSITORY",1L,null,null,"CVE-2026-1",null,Map.of(),"accepted",null,null,"admin","admin",null,NOW,NOW)).toList();
      when(f.scans.listWaivers(isNull(),isNull(),eq(0L),anyInt())).thenReturn(rows);
      var s=f.service();
      var page=s.waiverPage(f.actor,null,null,0,size);
      assertEquals(size,page.items().size());
      assertTrue(page.items().stream().allMatch(w -> w.canDelete() == writer));
      assertEquals(0,f.count(f.repos,"findById"));
    }
  }
  @Test
  void oneFindingManyAssets() {
    for(int size:new int[]{1000,10000}) for(boolean writer:new boolean[]{false,true}) {
      Fixture f=new Fixture(writer,false); f.repositories.put(1L,repo(1L,"hosted",RepositoryType.HOSTED));
      List<ScanRunSubject> subjects=new ArrayList<>();
      for(long id=1;id<=size;id++){f.content.put(id,asset(id,1L)); subjects.add(new ScanRunSubject(7L,1L,id,3L,1L,NOW));}
      var finding=new ScanFinding(41L,7L,"finding",null,"CVE-2026-1",List.of(),"grype","pkg:maven/a/b@1","b","1",List.of("2"),Severity.CRITICAL,"nvd",null,9.8,"issue","description",null,List.of(),"active",NOW);
      when(f.scans.listFindingsByRepositories(anyList(),isNull(),isNull(),isNull(),eq(0L),eq(11),eq(true))).thenReturn(List.of(finding));
      when(f.scans.listRunSubjects(eq(7L),anyLong(),anyLong(),eq(1000),eq(true))).thenAnswer(i -> {
        int after=(int)(long)i.getArgument(2); return subjects.subList(after,Math.min(size,after+1000));
      });
      when(f.scans.listRunSubjectAssets(eq(7L),anyLong(),anyLong(),eq(1000),eq(true))).thenAnswer(i -> {
        int after=(int)(long)i.getArgument(2);
        return subjects.subList(after,Math.min(size,after+1000)).stream().map(subject -> new ScanRunSubjectAsset(subject,1L)).toList();
      });
      when(f.scans.countCurrentRunSubjects(eq(7L),anyList(),eq(true))).thenReturn((long)size);
      when(f.scans.listCurrentRunSubjects(eq(7L),anyList(),eq(0L),eq(0L),eq(3),eq(true))).thenReturn(subjects.subList(0,3));
      var s=f.service();
      var page=s.findingPage(f.actor,null,null,null,null,0,10,true);
      assertEquals(writer,page.items().getFirst().canCreateWaiver());
      assertEquals(size,page.items().getFirst().waiverTargetCount());
      assertEquals(writer?size/1000+1:0,f.count(f.scans,"listRunSubjectAssets"));
      assertEquals(3,f.loadedAssets);
      assertEquals(1,f.assetSql);
    }
  }
}
