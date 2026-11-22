package com.github.klboke.kkrepo.server.offload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobOffloadSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DownloadOffloadTest {
  private static final String KEY = "repositories/release/blobs/v2/a/b/sha/random-id";
  private static final String NONCE = "1234567890abcdef1234567890abcdef";
  private final DownloadOffloadSettings settings = mock(DownloadOffloadSettings.class);
  private final DownloadOffload offload = new DownloadOffload(settings);

  @Test
  void handsOffTheActualBlobStoreAndClosesTheOriginalStream() {
    var request = request("nginx", true);
    request.addHeader("X-KkRepo-Offload-Probe", NONCE);
    select("nginx", Set.of(12L), true);
    var body = new FileInput(12, KEY);
    var headers = headers();

    assertTrue(offload.tryHandoff(request, 200, headers, body));
    assertTrue(body.closed);
    assertEquals("/_kkrepo_file_offload/12/" + KEY, headers.getFirst("X-Accel-Redirect"));
    assertEquals(NONCE + ":12", headers.getFirst("X-KkRepo-File-Served"));
    assertEquals("\"file-etag\"", headers.getETag());
    assertEquals(-1, headers.getContentLength());
  }

  @Test
  void fallsBackForUntrustedMismatchedPartialOrUnmappedDownloads() {
    select("nginx", Set.of(12L), true);
    assertFallback(request("nginx", false), 200, new FileInput(12, KEY));
    assertFallback(request("apache", true), 200, new FileInput(12, KEY));
    var duplicate = request("nginx", true);
    duplicate.addHeader("X-KkRepo-File-Offload", "nginx");
    assertFallback(duplicate, 200, new FileInput(12, KEY));
    var range = request("nginx", true);
    range.addHeader("Range", "bytes=0-2");
    assertFallback(range, 200, new FileInput(12, KEY));
    var condition = request("nginx", true);
    condition.addHeader("If-None-Match", "\"different\"");
    assertFallback(condition, 200, new FileInput(12, KEY));
    var head = request("nginx", true);
    head.setMethod("HEAD");
    assertFallback(head, 200, new FileInput(12, KEY));
    assertFallback(request("nginx", true), 401, new FileInput(12, KEY));
    assertFallback(request("nginx", true), 206, new FileInput(12, KEY));
    assertFallback(request("nginx", true), 200, new FileInput(90, KEY));
    assertFallback(request("nginx", true), 200, new FileInput(12, "repositories/a/../../outside"));
    assertFallback(request("nginx", true), 200, new FileInput(12, "repositories/a/%2e%2e/escape"));
    assertFalse(offload.tryHandoff(request("nginx", true), 200, headers(), new ByteArrayInputStream(new byte[3])));
    select("nginx", Set.of(12L), false);
    assertFallback(request("nginx", true), 200, new FileInput(12, KEY));
  }

  @Test
  void resolvesAllPresetsAndCustomFromTheSavedContract() {
    for (var method : DownloadOffloadMethod.METHODS) {
      var contract = select(method.id(), Set.of(12L), true);
      var request = request(contract.marker(), true);
      var headers = headers();
      assertTrue(offload.tryHandoff(request, 200, headers, new FileInput(12, KEY)), method.id());
      String root = method.target() != null && method.target().equals("internal-uri")
          ? "/_kkrepo_file_offload/" : "/srv/kkrepo-blobs/";
      assertEquals(root + "12/" + KEY, headers.getFirst(contract.header()), method.id());
    }
  }

  @Test
  void stripsProxyInstructionsAndProofUnlessTheResponseWasApproved() throws Exception {
    var filter = new DownloadOffloadResponseFilter(settings);
    var request = request("custom; header=X-Gateway-File; target=absolute-path", true);
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {
      var wire = (jakarta.servlet.http.HttpServletResponse) res;
      wire.setHeader("x-accel-redirect", "/unexpected");
      wire.addHeader("X-Gateway-File", "/unexpected");
      wire.addHeader("X-KkRepo-File-Served", "fake");
      wire.addHeader("Docker-Content-Digest", "sha256:known");
    });

    assertNull(response.getHeader("X-Accel-Redirect"));
    assertNull(response.getHeader("X-Gateway-File"));
    assertNull(response.getHeader("X-KkRepo-File-Served"));
    assertEquals("sha256:known", response.getHeader("Docker-Content-Digest"));

    var result = new MockHttpServletResponse();
    select("custom", Set.of(12L), true);
    request.addHeader("X-KkRepo-Offload-Probe", NONCE);
    filter.doFilter(request, result, (req, res) -> {
      var outgoing = headers();
      assertTrue(offload.tryHandoff(request, 200, outgoing, new FileInput(12, KEY)));
      var wire = (jakarta.servlet.http.HttpServletResponse) res;
      outgoing.forEach((name, values) -> values.forEach(value -> wire.addHeader(name, value)));
      wire.addHeader("X-Gateway-File", "/unexpected");
    });
    assertEquals(Set.of("/srv/kkrepo-blobs/12/" + KEY), Set.copyOf(result.getHeaders("X-Gateway-File")));
    assertEquals(NONCE + ":12", result.getHeader("X-KkRepo-File-Served"));
  }

  @Test
  void protectsProxyInstructionsWrittenWithIntegerHeaderMethods() throws Exception {
    var filter = new DownloadOffloadResponseFilter(settings);
    var request = request("custom; header=X-Gateway-File", true);
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {
      var wire = (jakarta.servlet.http.HttpServletResponse) res;
      wire.setIntHeader("X-Accel-Redirect", 1);
      wire.addIntHeader("X-KkRepo-File-Served", 2);
      wire.setIntHeader("X-Ordinary", 3);
      wire.addIntHeader("X-Ordinary", 4);
    });

    assertNull(response.getHeader("X-Accel-Redirect"));
    assertNull(response.getHeader("X-KkRepo-File-Served"));
    assertEquals(java.util.List.of("3", "4"), response.getHeaders("X-Ordinary"));
  }

  @Test
  void doesNotIssueAHandoffWhenTheOriginalBodyCannotBeClosed() {
    var request = request("nginx", true);
    select("nginx", Set.of(12L), true);
    var headers = headers();
    var badStream = new FileInput(12, KEY) {
      @Override
      public void close() throws IOException {
        throw new IOException("test close failure");
      }
    };

    assertThrows(UncheckedIOException.class, () -> offload.tryHandoff(request, 200, headers, badStream));
    assertNull(headers.getFirst("X-Accel-Redirect"));
    assertEquals(3, headers.getContentLength());
  }

  private static HttpHeaders headers() {
    var headers = new HttpHeaders();
    headers.setETag("\"file-etag\"");
    headers.setContentLength(3);
    return headers;
  }

  private MockHttpServletRequest request(String marker, boolean trusted) {
    var request = new MockHttpServletRequest("GET", "/repository/releases/fixture");
    request.addHeader("X-KkRepo-File-Offload", marker);
    when(settings.trusted(request)).thenReturn(trusted);
    return request;
  }

  private DownloadOffloadMethod.Contract select(String id, Set<Long> stores, boolean enabled) {
    var contract = DownloadOffloadMethod.byId(id).resolve("X-Gateway-File", "absolute-path");
    when(settings.runtime()).thenReturn(new DownloadOffloadSettings.RuntimeSettings(enabled, contract, stores));
    return contract;
  }

  private void assertFallback(MockHttpServletRequest request, int status, FileInput body) {
    var headers = headers();
    assertFalse(offload.tryHandoff(request, status, headers, body));
    assertFalse(body.closed);
    assertEquals(3, headers.getContentLength());
  }

  private static class FileInput extends ByteArrayInputStream implements BlobOffloadSource {
    private final Target target;
    private boolean closed;

    FileInput(long storeId, String key) {
      super(new byte[3]);
      this.target = new Target(storeId, key);
    }

    @Override
    public Optional<Target> offloadTarget(long responseLength) {
      return Optional.of(target);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
