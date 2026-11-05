package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PypiStagingMetadataTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final PypiHostedService pypiHosted = mock(PypiHostedService.class);
  private final PypiStagingContentAdapter adapter = new PypiStagingContentAdapter(
      mock(RawHostedService.class), pypiHosted, staging);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED, "pypi-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void addsStagedFilesToTheProjectSimpleIndex() throws Exception {
    when(staging.listItems(7L)).thenReturn(List.of(item()));
    when(pypiHosted.getIndex(any(), any(), any(Boolean.class)))
        .thenThrow(new PypiExceptions.PypiNotFoundException("demo"));

    String html = new String(adapter.generatedMetadata(
        runtime, 7L, "simple/demo/", false).orElseThrow().body().readAllBytes(),
        StandardCharsets.UTF_8);

    assertTrue(html.contains("demo-1.0.0-py3-none-any.whl"));
    assertTrue(html.contains("#sha256=" + "2".repeat(64)));
  }

  @Test
  void addsStagedProjectsToTheRootSimpleIndex() throws Exception {
    when(staging.listItems(7L)).thenReturn(List.of(item()));
    when(pypiHosted.getRootIndex(any(), any(Boolean.class)))
        .thenThrow(new PypiExceptions.PypiNotFoundException("simple/"));

    String html = new String(adapter.generatedMetadata(
        runtime, 7L, "simple/", false).orElseThrow().body().readAllBytes(),
        StandardCharsets.UTF_8);

    assertTrue(html.contains(">demo</a>"));
  }

  private static StagingItemRecord item() {
    return new StagingItemRecord(
        2L, 7L, 1L, RepositoryFormat.PYPI,
        "packages/demo/1.0.0/demo-1.0.0-py3-none-any.whl", new byte[32], "ADD", "STAGED",
        1L, 1L, "application/zip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        null, null, null, Map.of(), Instant.now(), Instant.now());
  }
}
