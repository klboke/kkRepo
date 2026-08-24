package com.github.klboke.kkrepo.server.routing.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

class HelmChartUploadProtocolHandlerTest {
  @Test
  void registersAndHandlesHostedChartUpload() throws Exception {
    RepositoryRuntime runtime = runtime();
    HelmHostedService helm = mock(HelmHostedService.class);
    MultipartFile chart = mock(MultipartFile.class);
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    servletRequest.setRemoteAddr("192.0.2.10");
    when(helm.push(runtime, chart, "anonymous", "192.0.2.10"))
        .thenReturn(MavenResponse.created());
    HelmChartUploadProtocolHandler handler = new HelmChartUploadProtocolHandler(helm);

    ResponseEntity<?> response = handler.handle(new RepositoryProtocolRequest(
        runtime,
        runtime.name(),
        "api/charts",
        HttpMethod.POST,
        servletRequest,
        "multipart/form-data",
        chart));

    assertEquals(201, response.getStatusCode().value());
    assertEquals(1, handler.routes().size());
    verify(helm).push(runtime, chart, "anonymous", "192.0.2.10");
  }

  @Test
  void rejectsUploadWithoutChartPartBeforeCallingService() {
    HelmHostedService helm = mock(HelmHostedService.class);
    HelmChartUploadProtocolHandler handler = new HelmChartUploadProtocolHandler(helm);

    assertThrows(MavenExceptions.BadRequestException.class, () -> handler.handle(
        new RepositoryProtocolRequest(
            runtime(),
            "repo",
            "api/charts",
            HttpMethod.POST,
            new MockHttpServletRequest(),
            "multipart/form-data",
            null)));
    verifyNoInteractions(helm);
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
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }
}
