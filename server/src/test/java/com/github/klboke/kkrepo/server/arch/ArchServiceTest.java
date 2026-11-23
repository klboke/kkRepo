package com.github.klboke.kkrepo.server.arch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    when(assets.store(any(), any(), any(), anyString(), anyString(), anyString(), anyString(),
        anyLong(), anyString(), anyString(), anyBoolean())).thenReturn(true);

    service.publish(runtime, fileName, new ByteArrayInputStream(new byte[] {1}), "alice", "ip");

    verify(assets).delete(runtime, fileName + ".sig");
  }

  @Test
  void allowOncePublicationIsEnforcedAtomicallyAtTheFinalBinding() throws Exception {
    RepositoryRuntime allowOnce = allowOnceRuntime();
    String fileName = "demo-1.0.0-1-x86_64.pkg.tar.zst";
    ArchPackageFilename parsed = ArchPackageFilename.parse(fileName);
    when(assets.find(allowOnce, fileName)).thenReturn(Optional.empty());
    when(inspector.inspect(any(), eq(fileName), anyLong(), anyString(), anyString()))
        .thenReturn(new ArchPackageInspector.Inspected(parsed, desc(fileName)));
    // The existence pre-check passed, but another request won the atomic insert-if-absent
    // binding while this body was in flight.
    when(assets.store(any(), any(), any(), anyString(), anyString(), anyString(), anyString(),
        anyLong(), anyString(), anyString(), eq(true))).thenReturn(false);

    org.junit.jupiter.api.Assertions.assertThrows(
        com.github.klboke.kkrepo.server.maven.MavenExceptions.WritePolicyDenied.class,
        () -> service.publish(allowOnce, fileName,
            new ByteArrayInputStream(new byte[] {1}), "alice", "ip"));
    verify(assets, org.mockito.Mockito.never()).delete(any(), anyString());
  }

  @Test
  void allowOnceSignaturePublicationIsAlsoEnforcedAtomically() throws Exception {
    RepositoryRuntime allowOnce = allowOnceRuntime();
    String fileName = "demo-1.0.0-1-x86_64.pkg.tar.zst.sig";
    when(assets.find(allowOnce, fileName)).thenReturn(Optional.empty());
    when(assets.storeSignature(eq(allowOnce), eq(fileName), any(), anyString(), anyString(),
        eq(true))).thenReturn(false);

    org.junit.jupiter.api.Assertions.assertThrows(
        com.github.klboke.kkrepo.server.maven.MavenExceptions.WritePolicyDenied.class,
        () -> service.publishSignature(allowOnce, fileName,
            new ByteArrayInputStream(new byte[] {1, 2, 3}), "alice", "ip"));
  }

  @Test
  void stagedDatabaseReplacesLiveDescriptorsShadowedByTheStagedEdit() {
    com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord live =
        archComponent("demo", "1.0-1", "x86_64", "demo-1.0-1-x86_64.pkg.tar.zst");
    com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord staged =
        archComponent("demo", "2.0-1", "x86_64", "demo-2.0-1-x86_64.pkg.tar.zst");
    when(components.listByRepositoryId(1L)).thenReturn(List.of(live));
    when(database.build(any(), eq(false))).thenReturn(new byte[0]);

    service.stagedDatabase(runtime, new ArchPathParser().parse("core.db"),
        List.of(staged), null, false);

    org.mockito.ArgumentCaptor<List<com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord>>
        merged = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(database).build(merged.capture(), eq(false));
    // One authoritative descriptor for the staged bytes: the shadowed live projection is gone.
    org.junit.jupiter.api.Assertions.assertEquals(1, merged.getValue().size());
    org.junit.jupiter.api.Assertions.assertEquals("2.0-1",
        merged.getValue().get(0).attributes().get("archVersion"));
  }

  @Test
  void stagedSignatureRequestsSignTheFilesDatabase() {
    when(components.listByRepositoryId(1L)).thenReturn(List.of());
    when(database.build(any(), eq(true))).thenReturn(new byte[0]);
    when(signing.active(runtime)).thenReturn(java.util.Optional.of(
        new ArchSigningService.SigningMaterial(
            1, "key", "fp", "public", "private", "passphrase")));
    when(signing.sign(any(), any(), any())).thenReturn(new byte[] {1});

    service.stagedDatabase(runtime, new ArchPathParser().parse("core.files.sig"),
        List.of(), null, false);

    verify(database).build(any(), eq(true));
  }

  @Test
  void groupDatabaseResolvesVersionsWithinEachMemberWithPacmanOrdering() {
    RepositoryRuntime member = new RepositoryRuntime(
        2L, "arch-member", RepositoryFormat.ARCH, RepositoryType.HOSTED, "arch-hosted", true,
        1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
    RepositoryRuntime group = new RepositoryRuntime(
        3L, "arch-group", RepositoryFormat.ARCH, RepositoryType.GROUP, "arch-group", true,
        1L, null, null, null, true, null, null, null, null, null, List.of(member));
    when(components.listByRepositoryId(2L)).thenReturn(List.of(
        archComponent("demo", "1.0-1", "x86_64", "demo-1.0-1-x86_64.pkg.tar.zst"),
        archComponent("demo", "2.0-1", "x86_64", "demo-2.0-1-x86_64.pkg.tar.zst")));
    when(database.buildDescs(any(), eq(false))).thenReturn(new byte[0]);

    service.get(group, new ArchPathParser().parse("core.db"), false);

    org.mockito.ArgumentCaptor<List<com.github.klboke.kkrepo.protocol.arch.ArchDesc>> descs =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(database).buildDescs(descs.capture(), eq(false));
    // The member's own two versions resolve with pacman ordering before the first-member
    // precedence applies, so the group advertises the current release.
    org.junit.jupiter.api.Assertions.assertEquals(1, descs.getValue().size());
    org.junit.jupiter.api.Assertions.assertEquals("2.0-1", descs.getValue().get(0).version());
  }

  private RepositoryRuntime allowOnceRuntime() {
    return new RepositoryRuntime(
        1L, "arch-hosted", RepositoryFormat.ARCH, RepositoryType.HOSTED, "arch-hosted", true,
        1L, "ALLOW_ONCE", null, null, true, null, null, null, null, null, List.of());
  }

  private static com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord
      archComponent(String name, String version, String architecture, String fileName) {
    java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
    attributes.put("archFileName", fileName);
    attributes.put("archName", name);
    attributes.put("archVersion", version);
    attributes.put("archArchitecture", architecture);
    return new com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord(
        null, 1L, RepositoryFormat.ARCH, null, name, version + "@" + architecture,
        "arch-package", new byte[32], attributes, java.time.Instant.now());
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
