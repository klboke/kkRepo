package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelmStagingMetadataTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final HelmHostedService helmHosted = mock(HelmHostedService.class);
  private final HelmStagingContentAdapter adapter = new HelmStagingContentAdapter(
      mock(RawHostedService.class), helmHosted, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "helm-hosted", RepositoryFormat.HELM, RepositoryType.HOSTED, "helm-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void buildsAnIndexFromStagedCharts() throws Exception {
    when(staging.listItems(7L)).thenReturn(List.of(
        new StagingItemRecord(
            2L, 7L, 1L, RepositoryFormat.HELM, "demo-1.0.0.tgz", new byte[32],
            "ADD", "STAGED", 1L, 1L, "application/gzip", 1L, "2".repeat(64),
            "3".repeat(32), "1".repeat(40), null, null, null,
            Map.of("helmName", "demo", "helmVersion", "1.0.0", "helmApiVersion", "v2",
                "helmDigest", "2".repeat(64), "helmCreated", Instant.now().toString(),
                "helmUrls", List.of("demo-1.0.0.tgz")),
            Instant.now(), Instant.now())));
    when(helmHosted.get(any(), any(), any(Boolean.class)))
        .thenThrow(new MavenExceptions.MavenNotFoundException("index.yaml"));

    String yaml = new String(adapter.generatedMetadata(
        runtime, 7L, "index.yaml", false).orElseThrow().body().readAllBytes(),
        StandardCharsets.UTF_8);

    assertTrue(yaml.contains("demo"));
    assertTrue(yaml.contains("1.0.0"));
    assertTrue(yaml.contains("demo-1.0.0.tgz"));
  }
}
