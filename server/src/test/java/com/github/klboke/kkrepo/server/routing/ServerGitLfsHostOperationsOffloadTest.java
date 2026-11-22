package com.github.klboke.kkrepo.server.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.offload.DownloadOffload;
import com.github.klboke.kkrepo.server.routing.gitlfs.ServerGitLfsHostOperations;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class ServerGitLfsHostOperationsOffloadTest {

  @Test
  void delegatesOffloadAndCopiesTheRewrittenHeadersBack() {
    DownloadOffload offload = mock(DownloadOffload.class);
    doAnswer(call -> {
      HttpHeaders headers = call.getArgument(2);
      headers.remove(HttpHeaders.CONTENT_LENGTH);
      headers.set("X-Accel-Redirect", "/internal/blobs/1/objects/abc");
      return true;
    }).when(offload).tryHandoff(any(), eq(200), any(), any());
    RepositoryRuntime runtime = runtime();
    RepositoryDescriptor descriptor = descriptor();
    MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/repository/lfs/abc");
    RepositoryContext context = new PluginRepositoryContext(
        descriptor, List.of(), runtime, servlet);
    ServerGitLfsHostOperations operations =
        new ServerGitLfsHostOperations(null, null, null, null, null);
    operations.setDownloadOffload(offload);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put(HttpHeaders.CONTENT_LENGTH, List.of("4"));
    InputStream body = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));

    assertTrue(operations.offload(context, 200, headers, body));

    assertFalse(headers.containsKey(HttpHeaders.CONTENT_LENGTH));
    assertEquals(List.of("/internal/blobs/1/objects/abc"),
        headers.get("X-Accel-Redirect"));
    verify(offload).tryHandoff(eq(servlet), eq(200), any(), eq(body));
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L, "lfs", new FormatId("gitlfs"), RepositoryKind.HOSTED, "gitlfs-hosted",
        true, 1L, "ALLOW", "RELEASE", "STRICT", null, Map.of());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "lfs", RepositoryFormat.GITLFS, RepositoryType.HOSTED, "gitlfs-hosted",
        true, 1L, "ALLOW", "RELEASE", "STRICT", true, null, null, null, null, null, List.of());
  }
}
