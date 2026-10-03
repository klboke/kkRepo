package com.github.klboke.kkrepo.server.gitlfs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.routing.*;
import com.github.klboke.kkrepo.server.routing.gitlfs.GitLfsProtocolHandler;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GitLfsProtocolHandlerTest {
  private static final String OID = "a".repeat(64);
  private static final String URL = "/repository/lfs/" + OID;
  private final GitLfsHostedService service = mock(GitLfsHostedService.class);
  private final BlobStorageRegistry stores = mock(BlobStorageRegistry.class);
  private final BlobStorage storage = mock(BlobStorage.class);
  private final ArtifactDownloadPolicy policy = mock(ArtifactDownloadPolicy.class);
  private MockMvc mvc;

  @BeforeEach void setup() throws Exception {
    var handler = new GitLfsProtocolHandler(service, stores, policy, new ObjectMapper());
    var dispatcher = mock(RepositoryProtocolDispatcher.class);
    when(dispatcher.dispatchRead(eq("lfs"), any(), any())).thenAnswer(call -> handler.handle(
        new RepositoryProtocolRequest(GitLfsHostedServiceTest.repository(), "lfs", OID,
            call.getArgument(2), call.getArgument(1), null, null)));
    mvc = MockMvcBuilders.standaloneSetup(new RepositoryProtocolController(dispatcher)).build();
    Instant time = Instant.parse("2026-01-01T00:00:00Z");
    var asset = new AssetRecord(3L, 1, 1L, 2L, RepositoryFormat.GITLFS, OID, new byte[32],
        OID, "gitlfs", "application/octet-stream", 6L, null, time, Map.of());
    var blob = new AssetBlobRecord(2L, 1, "blob://bucket/key", new byte[32], "key", new byte[32],
        "etag-sha1", OID, "md5", 6, "application/octet-stream", "alice", null, time, time, Map.of());
    when(service.asset(1, OID)).thenReturn(asset);
    when(service.blob(asset)).thenReturn(blob);
    when(stores.forBlobStoreId(1)).thenReturn(storage);
    when(storage.get(any())).thenAnswer(call -> Optional.of(new ByteArrayInputStream("abcdef".getBytes())));
    when(storage.getRange(any(), eq(1L), eq(3L))).thenAnswer(call -> Optional.of(new ByteArrayInputStream("bcd".getBytes())));
  }

  private org.springframework.mock.web.MockHttpServletResponse perform(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
    var result = mvc.perform(request).andReturn();
    if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
    return result.getResponse();
  }
  @Test void getUsesControllerStreamingContractAndRangeOpensOnlyRequestedBytes() throws Exception {
    var full = perform(get(URL));
    assertEquals(200, full.getStatus());
    assertEquals("abcdef", full.getContentAsString());
    var partial = perform(get(URL).header("Range", "bytes=1-3"));
    assertEquals(206, partial.getStatus());
    assertEquals("bytes 1-3/6", partial.getHeader("Content-Range"));
    assertEquals("bcd", partial.getContentAsString());
    verify(storage).getRange(any(), eq(1L), eq(3L));
    verify(policy, times(2)).beforeReadFromRepository(3, 2, 1);
  }
  @Test void headAndConditionalGetStillCheckPolicyWithoutOpeningBytes() throws Exception {
    var head = perform(head(URL));
    assertEquals(200, head.getStatus());
    assertEquals("6", head.getHeader("Content-Length"));
    assertEquals(304, perform(get(URL).header("If-None-Match", "\"etag-sha1\"")).getStatus());
    verify(policy, times(2)).beforeReadFromRepository(3, 2, 1);
    verifyNoInteractions(storage);
  }
  @Test void missingObjectReturnsLfsJsonThroughTypedGetController() throws Exception {
    when(service.asset(1, OID)).thenThrow(new GitLfsException(404, "Not found"));
    var missing = perform(get(URL));
    assertEquals(404, missing.getStatus());
    assertEquals("application/vnd.git-lfs+json", missing.getContentType());
    assertEquals("Not found", new ObjectMapper().readTree(missing.getContentAsString()).path("message").asText());
  }
  @Test void invalidRangeIs416AndIfRangeMismatchFallsBackToFullObject() throws Exception {
    var invalid = perform(get(URL).header("Range", "bytes=10-12"));
    assertEquals(416, invalid.getStatus());
    assertEquals("bytes */6", invalid.getHeader("Content-Range"));
    var result = perform(get(URL).header("Range", "bytes=1-3").header("If-Range", "\"old\""));
    assertEquals(200, result.getStatus());
    assertEquals("abcdef", result.getContentAsString());
  }
}
