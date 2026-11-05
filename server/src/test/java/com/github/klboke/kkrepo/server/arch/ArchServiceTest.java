package com.github.klboke.kkrepo.server.arch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.arch.ArchDesc;
import com.github.klboke.kkrepo.protocol.arch.ArchPackageFilename;
import com.github.klboke.kkrepo.protocol.arch.ArchPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArchServiceTest {
  private final ComponentDao components = mock(ComponentDao.class);
  private final ArchAssetSupport assets = mock(ArchAssetSupport.class);
  private final ArchPackageInspector inspector = mock(ArchPackageInspector.class);
  private final ArchDatabase database = mock(ArchDatabase.class);
  private final RawProxyService proxy = mock(RawProxyService.class);
  private final ArchSigningService signing = mock(ArchSigningService.class);
  private final ArchService service = new ArchService(
      components, assets, inspector, database, proxy, signing);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "arch-hosted", RepositoryFormat.ARCH, RepositoryType.HOSTED, "arch-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void replacingAPackageRemovesThePreviousSignature() throws Exception {
    String fileName = "demo-1.0.0-1-x86_64.pkg.tar.zst";
    ArchPackageFilename parsed = ArchPackageFilename.parse(fileName);
    when(assets.find(runtime, fileName)).thenReturn(Optional.of(mock(AssetRecord.class)));
    when(inspector.inspect(any(), eq(fileName), anyLong(), anyString(), anyString()))
        .thenReturn(new ArchPackageInspector.Inspected(parsed, desc(fileName)));

    service.publish(runtime, fileName, new ByteArrayInputStream(new byte[] {1}), "alice", "ip");

    verify(assets).delete(runtime, fileName + ".sig");
  }

  @Test
  void deletingAPackageRemovesItsSignature() {
    String fileName = "demo-1.0.0-1-x86_64.pkg.tar.zst";
    when(assets.find(runtime, fileName)).thenReturn(Optional.of(mock(AssetRecord.class)));

    service.delete(runtime, new ArchPathParser().parse(fileName), true);

    verify(assets).delete(runtime, fileName);
    verify(assets).delete(runtime, fileName + ".sig");
  }

  private static ArchDesc desc(String fileName) {
    return new ArchDesc(
        fileName, "demo", "demo", "1.0.0-1", "Demo", 1L, 2L, "md5", "sha256", null, null,
        List.of("MIT"), "x86_64", 1L, "packager", List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of());
  }
}
