package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.maven.MavenContentType;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MavenMetadataServiceTest {
  private static final MavenPathParser PARSER = new MavenPathParser();

  @Test
  void emptyArtifactDeletesPreviouslyGeneratedMetadata() {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    MavenAssetWriter writer = mock(MavenAssetWriter.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRuntime runtime = runtime();
    when(components.listByGa(10L, "com.acme", "demo")).thenReturn(List.of());

    new MavenMetadataService(components, assets, writer)
        .rebuildGa(runtime, storage, 7L, "com.acme", "demo", "system", null);

    verify(writer).deleteAsset(
        eq(runtime),
        eq(storage),
        argThatPath("com/acme/demo/maven-metadata.xml"));
  }

  @Test
  void artifactMetadataUsesMavenVersionOrderAndLatestNonSnapshotRelease() throws Exception {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    MavenAssetWriter writer = mock(MavenAssetWriter.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRuntime runtime = runtime();
    Instant older = Instant.parse("2026-07-12T10:00:00Z");
    Instant newer = Instant.parse("2026-07-13T11:12:13Z");
    when(components.listByGa(10L, "com.acme", "demo")).thenReturn(List.of(
        component(1L, "1.10", older),
        component(2L, "1.2", newer),
        component(3L, "2.0-SNAPSHOT", older),
        component(4L, "2.0", newer)));
    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);

    new MavenMetadataService(components, assets, writer)
        .rebuildGa(runtime, storage, 7L, "com.acme", "demo", "system", null);

    verify(writer).writeBytes(
        eq(runtime),
        eq(storage),
        eq(7L),
        argThatPath("com/acme/demo/maven-metadata.xml"),
        body.capture(),
        eq(MavenContentType.XML),
        eq("system"),
        eq(null));
    MavenMetadataXml.Parsed parsed = MavenMetadataXml.read(body.getValue());
    assertEquals(List.of("1.2", "1.10", "2.0-SNAPSHOT", "2.0"), parsed.versions);
    assertEquals("2.0", parsed.latest);
    assertEquals("2.0", parsed.release);
    assertEquals("20260713111213", parsed.lastUpdated);
  }

  @Test
  void snapshotOnlyArtifactHasNoRelease() throws Exception {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    MavenAssetWriter writer = mock(MavenAssetWriter.class);
    BlobStorage storage = mock(BlobStorage.class);
    when(components.listByGa(10L, "com.acme", "demo")).thenReturn(List.of(
        component(1L, "1.0-SNAPSHOT", Instant.parse("2026-07-13T00:00:00Z"))));
    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);

    new MavenMetadataService(components, assets, writer)
        .rebuildGa(runtime(), storage, 7L, "com.acme", "demo", "system", null);

    verify(writer).writeBytes(any(), any(), eq(7L), any(), body.capture(), any(), any(), any());
    MavenMetadataXml.Parsed parsed = MavenMetadataXml.read(body.getValue());
    assertEquals("1.0-SNAPSHOT", parsed.latest);
    assertNull(parsed.release);
  }

  @Test
  void missingSnapshotComponentDeletesBaseVersionMetadata() {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    MavenAssetWriter writer = mock(MavenAssetWriter.class);
    BlobStorage storage = mock(BlobStorage.class);
    Coordinates coords = coordinates(
        "com/acme/demo/1.0-SNAPSHOT/demo-1.0-SNAPSHOT.jar");
    when(components.findByGav(10L, "com.acme", "demo", "1.0-SNAPSHOT"))
        .thenReturn(Optional.empty());

    new MavenMetadataService(components, assets, writer)
        .rebuildBaseVersionIfSnapshot(runtime(), storage, 7L, coords, "system", null);

    verify(writer).deleteAsset(
        any(),
        eq(storage),
        argThatPath("com/acme/demo/1.0-SNAPSHOT/maven-metadata.xml"));
  }

  @Test
  void snapshotMetadataSelectsLatestBuildPerClassifierAndIgnoresChecksums() throws Exception {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    MavenAssetWriter writer = mock(MavenAssetWriter.class);
    BlobStorage storage = mock(BlobStorage.class);
    ComponentRecord component = component(5L, "1.0-SNAPSHOT", Instant.parse("2026-07-13T12:00:00Z"));
    when(components.findByGav(10L, "com.acme", "demo", "1.0-SNAPSHOT"))
        .thenReturn(Optional.of(component));
    Instant first = Instant.parse("2026-07-13T12:34:56Z");
    Instant second = Instant.parse("2026-07-13T12:35:56Z");
    when(assets.listAssetsByComponent(5L)).thenReturn(List.of(
        asset(1L, "com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260713.123456-1.jar", "artifact", first),
        asset(2L, "com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260713.123556-2.jar", "artifact", second),
        asset(3L, "com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260713.123556-2-sources.jar", "artifact", second),
        asset(4L, "com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260713.123556-2.pom", "pom", second),
        asset(5L, "com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260713.123556-2.jar.sha1", "checksum", second)));
    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);

    new MavenMetadataService(components, assets, writer)
        .rebuildBaseVersionIfSnapshot(
            runtime(),
            storage,
            7L,
            coordinates("com/acme/demo/1.0-SNAPSHOT/demo-1.0-SNAPSHOT.jar"),
            "system",
            null);

    verify(writer).writeBytes(
        any(),
        eq(storage),
        eq(7L),
        argThatPath("com/acme/demo/1.0-SNAPSHOT/maven-metadata.xml"),
        body.capture(),
        eq(MavenContentType.XML),
        eq("system"),
        eq(null));
    MavenMetadataXml.Parsed parsed = MavenMetadataXml.read(body.getValue());
    assertEquals("20260713.123556", parsed.snapshotTimestamp);
    assertEquals(2, parsed.snapshotBuildNumber);
    assertEquals(3, parsed.snapshotVersions.size());
    assertTrue(parsed.snapshotVersions.stream().anyMatch(snapshot ->
        snapshot.classifier == null
            && "jar".equals(snapshot.extension)
            && "1.0-20260713.123556-2".equals(snapshot.value)));
    assertTrue(parsed.snapshotVersions.stream().anyMatch(snapshot ->
        "sources".equals(snapshot.classifier)
            && "jar".equals(snapshot.extension)
            && "1.0-20260713.123556-2".equals(snapshot.value)));
    assertTrue(parsed.snapshotVersions.stream().noneMatch(snapshot ->
        snapshot.extension.endsWith("sha1")));
  }

  @Test
  void releaseCoordinatesDoNotTriggerSnapshotMetadataWork() {
    ComponentDao components = mock(ComponentDao.class);
    MavenAssetWriter writer = mock(MavenAssetWriter.class);

    new MavenMetadataService(components, mock(AssetDao.class), writer)
        .rebuildBaseVersionIfSnapshot(
            runtime(),
            mock(BlobStorage.class),
            7L,
            coordinates("com/acme/demo/1.0/demo-1.0.jar"),
            "system",
            null);

    org.mockito.Mockito.verifyNoInteractions(components, writer);
  }

  private static org.mockito.ArgumentMatcher<MavenPath> path(String expected) {
    return value -> value != null && expected.equals(value.path());
  }

  private static MavenPath argThatPath(String expected) {
    return org.mockito.ArgumentMatchers.argThat(path(expected));
  }

  private static Coordinates coordinates(String path) {
    return PARSER.parsePath(path).coordinates();
  }

  private static ComponentRecord component(long id, String version, Instant updatedAt) {
    return new ComponentRecord(
        id,
        10L,
        RepositoryFormat.MAVEN2,
        "com.acme",
        "demo",
        version,
        "maven",
        null,
        Map.of(),
        updatedAt);
  }

  private static AssetRecord asset(long id, String path, String kind, Instant updatedAt) {
    return new AssetRecord(
        id,
        10L,
        5L,
        100L + id,
        RepositoryFormat.MAVEN2,
        path,
        null,
        null,
        kind,
        MavenContentType.forFileName(path),
        100L,
        null,
        updatedAt,
        Map.of());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10L,
        "maven-hosted",
        RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED,
        "maven2-hosted",
        true,
        7L,
        "ALLOW",
        "MIXED",
        "PERMISSIVE",
        true,
        null,
        null,
        null,
        true,
        null,
        List.of());
  }
}
