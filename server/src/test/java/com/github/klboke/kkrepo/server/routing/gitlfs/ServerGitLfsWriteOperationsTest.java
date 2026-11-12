package com.github.klboke.kkrepo.server.routing.gitlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryPolicy;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ServerGitLfsWriteOperationsTest {

  @Test
  void convertsTheExistingHandlerResponse() throws Exception {
    GitLfsProtocolHandler handler = mock(GitLfsProtocolHandler.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.of(runtime()));
    doReturn(ResponseEntity.status(202).header("LFS-Test", "yes").body(Map.of("ok", true)))
        .when(handler).handle(any());
    ServerGitLfsWriteOperations operations =
        new ServerGitLfsWriteOperations(handler, runtimes, new ObjectMapper());

    RepositoryResponse response = operations.handle(request());

    assertEquals(202, response.status());
    assertEquals(List.of("yes"), response.headers().get("LFS-Test"));
    assertEquals(true, new ObjectMapper().readTree(response.body()).get("ok").asBoolean());
  }

  @Test
  void rejectsARequestWithoutTheHostServlet() {
    ServerGitLfsWriteOperations operations = new ServerGitLfsWriteOperations(
        mock(GitLfsProtocolHandler.class), mock(RepositoryRuntimeRegistry.class),
        new ObjectMapper());
    RepositoryDescriptor descriptor = descriptor();
    RepositoryRequest request = new RepositoryRequest(
        descriptor, "repo", "objects/batch", "POST", Map.of(), Map.of(),
        new byte[0], null, RepositoryContext.empty(descriptor), Map.of());

    assertThrows(IllegalStateException.class, () -> operations.handle(request));
  }

  private static RepositoryRequest request() {
    RepositoryDescriptor descriptor = descriptor();
    MockHttpServletRequest servlet =
        new MockHttpServletRequest("POST", "/repository/repo/objects/batch");
    RepositoryContext context = new RepositoryContext() {
      @Override
      public RepositoryDescriptor repository() {
        return descriptor;
      }

      @Override
      public Optional<BlobStorage> blobs() {
        return Optional.empty();
      }

      @Override
      public <T> Optional<T> service(Class<T> type) {
        return Optional.empty();
      }

      @Override
      public RepositoryPolicy policy() {
        return new RepositoryPolicy("ADD", "RELEASE", "STRICT");
      }

      @Override
      public Object hostContext() {
        return servlet;
      }
    };
    return new RepositoryRequest(
        descriptor, "repo", "objects/batch", "POST", Map.of(), Map.of(),
        "{}".getBytes(StandardCharsets.UTF_8), null, context, Map.of());
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L, "repo", new FormatId("gitlfs"), RepositoryKind.HOSTED, "gitlfs-hosted",
        true, 1L, "ADD", "RELEASE", "STRICT", null, Map.of());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "repo", RepositoryFormat.GITLFS, RepositoryType.HOSTED, "gitlfs-hosted",
        true, 1L, "ADD", "RELEASE", "STRICT", true, null, null, null, null, null, List.of());
  }
}
