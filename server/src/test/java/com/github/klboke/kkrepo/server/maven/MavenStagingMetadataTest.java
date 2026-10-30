package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MavenStagingMetadataTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final ComponentDao components = mock(ComponentDao.class);
  private final MavenStagingContentAdapter adapter = new MavenStagingContentAdapter(
      mock(RawHostedService.class), mock(MavenHostedService.class), staging, components);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "maven-hosted", RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven-hosted",
      true, 1L, "ALLOW", "RELEASE", "STRICT", true, null, null, null, null, null, List.of());

  @Test
  void mergesLiveAndStagedVersionsIntoGaMetadata() throws Exception {
    when(components.listByGa(1L, "com.acme", "demo")).thenReturn(List.of(
        new ComponentRecord(1L, 1L, RepositoryFormat.MAVEN2, "com.acme", "demo", "1.0",
            "jar", new byte[32], Map.of(), Instant.now())));
    when(staging.listItems(7L)).thenReturn(List.of(
        new StagingItemRecord(
            2L, 7L, 1L, RepositoryFormat.MAVEN2,
            "com/acme/demo/1.1/demo-1.1.jar", new byte[32], "ADD", "STAGED",
            1L, 1L, "application/java-archive", 1L, "2".repeat(64), "3".repeat(32),
            "1".repeat(40), null, null, null,
            Map.of("mavenGroupId", "com.acme", "mavenArtifactId", "demo",
                "mavenVersion", "1.1", "mavenSnapshot", false),
            Instant.now(), Instant.now())));

    MavenResponse response = adapter.generatedMetadata(
        runtime, 7L, "com/acme/demo/maven-metadata.xml", false).orElseThrow();
    String xml = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(xml.contains("<version>1.0</version>"));
    assertTrue(xml.contains("<version>1.1</version>"));
    assertTrue(xml.contains("<latest>1.1</latest>"));
  }

  @Test
  void leavesSnapshotMetadataToTheLiveRebuildPath() {
    assertTrue(adapter.generatedMetadata(
        runtime, 7L, "com/acme/demo/1.0-SNAPSHOT/maven-metadata.xml", false).isEmpty());
  }
}
