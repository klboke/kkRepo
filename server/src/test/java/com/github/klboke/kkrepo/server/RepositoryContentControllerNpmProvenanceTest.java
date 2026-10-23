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
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import com.github.klboke.kkrepo.server.security.ClientAddressFilter;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RepositoryContentControllerNpmProvenanceTest {
  @ParameterizedTest
  @CsvSource({
      "PUT, demo, 201",
      "PUT, -/package/demo/dist-tags/latest, 201",
      "DELETE, -/package/demo/dist-tags/latest, 200"
  })
  void packageAndTagMutationsUseVerifiedProvenanceUnderTheCustomPrefix(
      String method, String path, int expectedStatus) throws Exception {
    var runtime = new RepositoryRuntime(
        1L, "npm", RepositoryFormat.NPM, RepositoryType.HOSTED, "npm-hosted", true, 7L,
        "ALLOW", null, null, true, null, 60, 30, true, null, List.of());
    var runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("npm")).thenReturn(Optional.of(runtime));
    var npm = mock(NpmHostedService.class);
    when(npm.putPackage(eq(runtime), any(), any(), any(), eq("anonymous"), eq("203.0.113.45")))
        .thenReturn(MavenResponse.noBody(201));
    when(npm.putDistTag(eq(runtime), any(), eq("latest"), any(), eq("anonymous"), eq("203.0.113.45")))
        .thenReturn(MavenResponse.noBody(201));
    when(npm.deleteDistTag(eq(runtime), any(), eq("latest"), eq("anonymous"), eq("203.0.113.45")))
        .thenReturn(MavenResponse.noBody(200));
    var policy = new ForwardedHeaderPolicy("127.0.0.1");
    var controller = RepositoryProtocolControllerTestSupport.controller(
        runtimes,
        null, null, null,
        null, null,
        null, null,
        null,
        npm, null, null,
        null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        new ObjectMapper(), policy);
    var request = new MockHttpServletRequest(method, "/r/npm/" + path);
    new RepositoryUrlPrefix("/r").onStartup(request.getServletContext());
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.45");
    request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    new ClientAddressFilter(policy).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
      var response = method.equals("PUT")
          ? controller.put("npm", request, "application/json")
          : controller.delete("npm", request);
      assertEquals(expectedStatus, response.getStatusCode().value());
    });
    if (path.equals("demo")) {
      verify(npm).putPackage(eq(runtime), any(), any(), any(), eq("anonymous"), eq("203.0.113.45"));
    } else if (method.equals("PUT")) {
      verify(npm).putDistTag(eq(runtime), any(), eq("latest"), any(), eq("anonymous"), eq("203.0.113.45"));
    } else {
      verify(npm).deleteDistTag(eq(runtime), any(), eq("latest"), eq("anonymous"), eq("203.0.113.45"));
    }
  }
}
