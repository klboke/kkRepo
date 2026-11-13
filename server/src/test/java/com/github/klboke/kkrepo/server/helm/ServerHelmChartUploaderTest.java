package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MultipartPart;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerHelmChartUploaderTest {

  @Test
  void resolvesTheRuntimeAndDelegatesToTheHelmService() throws Exception {
    HelmHostedService helm = mock(HelmHostedService.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = runtime();
    when(runtimes.resolve("repo")).thenReturn(Optional.of(runtime));
    when(helm.push(any(), any(), any(), any())).thenReturn(MavenResponse.noBody(201));
    ServerHelmChartUploader uploader = new ServerHelmChartUploader(helm, runtimes);

    int status = uploader.upload(
        descriptor(),
        new MultipartPart("chart", "chart.tgz", "application/gzip", new byte[] {1}),
        "anonymous",
        "127.0.0.1");

    assertEquals(201, status);
  }

  @Test
  void rejectsAnUnknownRepository() {
    HelmHostedService helm = mock(HelmHostedService.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.empty());
    ServerHelmChartUploader uploader = new ServerHelmChartUploader(helm, runtimes);

    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> uploader.upload(
        descriptor(),
        new MultipartPart("chart", "chart.tgz", "application/gzip", new byte[] {1}),
        "anonymous",
        "127.0.0.1"));
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L,
        "repo",
        new FormatId("helm"),
        RepositoryKind.HOSTED,
        "helm-hosted",
        true,
        1L,
        "ALLOW",
        "RELEASE",
        "STRICT",
        null,
        Map.of());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L,
        "repo",
        RepositoryFormat.HELM,
        RepositoryType.HOSTED,
        "helm-hosted",
        true,
        1L,
        "ALLOW",
        "RELEASE",
        "STRICT",
        true,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }
}
