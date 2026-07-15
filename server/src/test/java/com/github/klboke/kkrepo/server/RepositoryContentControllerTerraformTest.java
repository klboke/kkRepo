package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.terraform.TerraformService;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class RepositoryContentControllerTerraformTest {

  @Test
  void providerPutForwardsExplicitProtocolMetadata() throws Exception {
    RepositoryRuntime runtime = new RepositoryRuntime(
        1L,
        "terraform",
        RepositoryFormat.TERRAFORM,
        RepositoryType.HOSTED,
        "terraform-hosted",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        null,
        null,
        null,
        List.of());
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    TerraformService terraform = mock(TerraformService.class);
    when(runtimes.resolve("terraform")).thenReturn(Optional.of(runtime));
    when(terraform.put(
            eq(runtime),
            any(),
            any(InputStream.class),
            eq("application/zip"),
            eq("attachment; filename=\"terraform-provider-cloud_1.2.3_linux_amd64.zip\""),
            eq("6.0"),
            any(),
            eq("127.0.0.1")))
        .thenReturn(MavenResponse.created());
    RepositoryContentController controller = controller(runtimes, terraform);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "PUT", "/repository/terraform/v1/providers/acme/cloud/1.2.3/download/linux/amd64");
    request.setContent(new byte[] {1});
    request.setRemoteAddr("127.0.0.1");
    request.addHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"terraform-provider-cloud_1.2.3_linux_amd64.zip\"");
    request.addHeader(TerraformService.PROVIDER_PROTOCOLS_HEADER, "6.0");

    ResponseEntity<?> response = controller.put("terraform", request, "application/zip");

    assertEquals(201, response.getStatusCode().value());
    verify(terraform).put(
        eq(runtime),
        any(),
        any(InputStream.class),
        eq("application/zip"),
        eq("attachment; filename=\"terraform-provider-cloud_1.2.3_linux_amd64.zip\""),
        eq("6.0"),
        any(),
        eq("127.0.0.1"));
  }

  private static RepositoryContentController controller(
      RepositoryRuntimeRegistry runtimes, TerraformService terraform) {
    return new RepositoryContentController(
        runtimes,
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null,
        null,
        null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""),
        terraform);
  }
}
