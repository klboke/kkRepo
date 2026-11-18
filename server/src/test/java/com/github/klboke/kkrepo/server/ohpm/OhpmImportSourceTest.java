package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OhpmImportSourceTest {
  private final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
  private final RepositoryRuntime runtime = mock(RepositoryRuntime.class);
  private final OhpmImportSource source = new OhpmImportSource(fetcher, 1024 * 1024);
  private final OhpmPackageId packageId = OhpmPackageId.parse("@scope/pkg");

  @BeforeEach
  void setup() {
    when(runtime.format()).thenReturn(RepositoryFormat.OHPM);
    when(runtime.isProxy()).thenReturn(true);
    when(runtime.online()).thenReturn(true);
    when(runtime.proxyRemoteUrl()).thenReturn("https://upstream.example/ohpm");
    when(runtime.strictRedirectHosts()).thenReturn(Set.of());
  }

  @Test
  void invalidSourceConfigurationsNeverContactUpstream() {
    when(runtime.online()).thenReturn(false);
    assertEquals(400, assertThrows(OhpmException.class, () -> source.metadata(runtime, packageId)).status());
    when(runtime.online()).thenReturn(true);
    when(runtime.isProxy()).thenReturn(false);
    assertEquals(400, assertThrows(OhpmException.class, () -> source.metadata(runtime, packageId)).status());
    when(runtime.isProxy()).thenReturn(true);
    when(runtime.format()).thenReturn(RepositoryFormat.NPM);
    assertEquals(400, assertThrows(OhpmException.class, () -> source.metadata(runtime, packageId)).status());
    verifyNoInteractions(fetcher);
  }

  @ParameterizedTest
  @CsvSource({"404,404", "401,502", "503,502"})
  void upstreamErrorsAreMappedAndTheResponseBodyIsClosed(int upstream, int expected) throws Exception {
    var input = spy(new ByteArrayInputStream(new byte[0]));
    when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(upstream, Map.of(), input));
    assertEquals(expected, assertThrows(OhpmException.class, () -> source.metadata(runtime, packageId)).status());
    verify(input).close();
  }

  @Test
  void directoryRejectsMismatchedPackageOrChecksumBeforeReturningAnUpload() throws Exception {
    byte[] archive = OhpmArchiveUploadTest.har("{name:'@scope/pkg',version:'1.0.0',main:'Index.ets'}");
    assertEquals(409, assertThrows(OhpmException.class, () -> source.directory(
        new ByteArrayInputStream(archive), null, false, metadata("@other/pkg", archive))).status());
    assertEquals(409, assertThrows(OhpmException.class, () -> source.directory(
        new ByteArrayInputStream(archive), null, false, metadata("@scope/pkg", new byte[] {9}))).status());
    assertEquals(400, assertThrows(OhpmException.class, () -> source.directory(
        new ByteArrayInputStream(archive), new ByteArrayInputStream(new byte[] {1}), true, null)).status());
    try (var upload = source.directory(new ByteArrayInputStream(archive), null, false, metadata("@scope/pkg", archive))) {
      assertEquals("1.0.0", upload.metadata().version());
      assertTrue(upload.metadata().metadata().path("dist-tags").has("latest"));
      assertFalse(upload.metadata().metadata().path("versions").path("1.0.0").path("dist").has("tarball"));
    }
  }

  private static OhpmPackageMetadata metadata(String name, byte[] archive) throws Exception {
    var root = new ObjectMapper().createObjectNode().put("name", name);
    root.putObject("dist-tags").put("latest", "1.0.0");
    root.putObject("versions").putObject("1.0.0").put("name", name).put("version", "1.0.0")
        .put("main", "Index.ets").putObject("dist")
        .put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(archive)).value())
        .put("tarball", "https://upstream.example/ohpm/" + name + "/-/pkg-1.0.0.har");
    return OhpmPackageMetadata.parse(OhpmPackageId.parse(name), root);
  }
}
