package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmGroupServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/pkg");
  private static final String HAR = "@scope/pkg/-/@scope/pkg-1.0.0.har";
  private static final String HSP = "@scope/pkg/-/@scope/pkg-1.0.0.hsp";
  private static final String BASE = "https://repo.example/repository/group";
  private final OhpmHostedService hosted = mock(OhpmHostedService.class);
  private final OhpmProxyService proxy = mock(OhpmProxyService.class);
  private final RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
  private final RepositoryRuntime group = mock(RepositoryRuntime.class);
  private final RepositoryRuntime first = mock(RepositoryRuntime.class);
  private final RepositoryRuntime second = mock(RepositoryRuntime.class);
  private final com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao selections =
      mock(com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao.class);
  private final OhpmVersionReader versions = new OhpmVersionReader(selections, JSON);
  private final OhpmGroupService service = new OhpmGroupService(hosted, proxy, repositories, JSON, versions);
  private ObjectNode firstDocument;
  private ObjectNode secondDocument;

  @Test
  void repeatedFileReadsUseOnlySelectedVersionWithoutSerializingFullMemberMetadata() throws Exception {
    String fingerprint = fingerprint();
    clearInvocations(hosted, proxy, selections);
    for (int index = 0; index < 10; index++) {
      assertArrayEquals(new byte[] {1}, read(service.download(group, PACKAGE, HAR, fingerprint, BASE, false)));
    }
    verify(hosted, never()).metadata(any(), any(), anyString(), anyBoolean());
    verify(proxy, never()).metadata(any(), any(), anyString(), anyBoolean());
    verify(selections, times(10)).findVersion(1L, PACKAGE.id(), "1.0.0", false);
  }

  @Test
  void malformedMemberMetadataStopsResolutionAndClosesTheResponse() throws Exception {
    var body = spy(new ByteArrayInputStream(new byte[] {'{'}));
    when(hosted.metadata(eq(first), eq(PACKAGE), anyString(), eq(false)))
        .thenReturn(MavenResponse.ok(body, 1, "application/json", null, null));
    assertEquals(502, assertThrows(OhpmException.class,
        () -> service.metadata(group, PACKAGE, BASE, false)).status());
    verify(body).close();
    verifyNoInteractions(proxy);
  }

  @Test
  void repositoryTypeChangedSinceResolutionIsRejected() {
    when(group.isGroup()).thenReturn(false);
    assertEquals(405, assertThrows(OhpmException.class,
        () -> service.metadata(group, PACKAGE, BASE, false)).status());
    verifyNoInteractions(hosted, proxy);
  }

  @BeforeEach
  void setup() throws Exception {
    for (var runtime : List.of(group, first, second)) {
      when(runtime.format()).thenReturn(RepositoryFormat.OHPM);
      when(runtime.online()).thenReturn(true);
    }
    when(group.id()).thenReturn(10L);
    when(group.isGroup()).thenReturn(true);
    when(group.members()).thenReturn(List.of(first, second));
    when(first.id()).thenReturn(1L);
    when(first.isHosted()).thenReturn(true);
    when(second.id()).thenReturn(2L);
    when(second.isProxy()).thenReturn(true);
    when(repositories.resolveFreshById(10)).thenReturn(Optional.of(group));
    firstDocument = document(1, true);
    secondDocument = document(9, true);
    when(hosted.metadata(eq(first), eq(PACKAGE), anyString(), eq(false))).thenAnswer(call -> metadata(firstDocument));
    when(proxy.metadata(eq(second), eq(PACKAGE), anyString(), eq(false))).thenAnswer(call -> metadata(secondDocument));
    when(selections.findVersion(eq(1L), anyString(), anyString(), eq(false))).thenAnswer(call ->
        Optional.of(new com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao.Version(
            JSON.convertValue(firstDocument.path("versions").path("1.0.0"), Map.class),
            firstDocument.path("packageType").textValue(), firstDocument.path("hspType").textValue(), null, Map.of())));
    when(proxy.versionMetadata(eq(second), eq(PACKAGE), anyString())).thenAnswer(call ->
        com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata.parse(PACKAGE, secondDocument));
    when(hosted.download(eq(first), eq(HAR), anyBoolean())).thenAnswer(call -> content(1));
    when(hosted.download(eq(first), eq(HSP), anyBoolean())).thenAnswer(call -> content(2));
    when(proxy.download(eq(second), eq(PACKAGE), eq(HAR), anyBoolean())).thenAnswer(call -> content(9));
    when(proxy.download(eq(second), eq(PACKAGE), eq(HSP), anyBoolean())).thenAnswer(call -> content(10));
  }

  @Test
  void pairDownloadsUseSameFirstMemberAcrossRepeatedRequests() throws Exception {
    String fingerprint = fingerprint();
    assertArrayEquals(new byte[] {1}, read(service.download(group, PACKAGE, HAR, fingerprint, BASE, false)));
    assertArrayEquals(new byte[] {2}, read(service.download(group, PACKAGE, HSP, fingerprint, BASE, false)));
    verify(proxy, never()).download(any(), any(), anyString(), anyBoolean());
    verify(repositories, times(3)).resolveFreshById(10);
  }

  @Test
  void missingWinningFileNeverFallsBackToAnotherMembersFile() throws Exception {
    String fingerprint = fingerprint();
    when(hosted.download(first, HSP, false)).thenThrow(new OhpmException(404, "missing file"));
    assertEquals(404, assertThrows(OhpmException.class,
        () -> service.download(group, PACKAGE, HSP, fingerprint, BASE, false)).status());
    verify(proxy, never()).download(any(), any(), anyString(), anyBoolean());
  }

  @Test
  void aHarWinnerDoesNotExposeTheLosingMembersHsp() throws Exception {
    firstDocument = document(1, false);
    String fingerprint = fingerprint();
    assertEquals(404, assertThrows(OhpmException.class,
        () -> service.download(group, PACKAGE, HSP, fingerprint, BASE, false)).status());
    verify(proxy, never()).download(any(), any(), anyString(), anyBoolean());
  }

  @Test
  void reorderRejectsOldPairUrlsAndNewUrlsSelectTheNewWinner() throws Exception {
    String old = fingerprint();
    when(group.members()).thenReturn(List.of(second, first));
    assertEquals(409, assertThrows(OhpmException.class,
        () -> service.download(group, PACKAGE, HSP, old, BASE, false)).status());
    assertArrayEquals(new byte[] {9}, read(service.download(group, PACKAGE, HAR, fingerprint(), BASE, false)));
  }

  @Test
  void changingOnlyHspInvalidatesTheHarUrlToo() throws Exception {
    String old = fingerprint();
    firstDocument.withObject("/versions/1.0.0/dist").put("integrity_hsp", integrity(3));
    assertEquals(409, assertThrows(OhpmException.class,
        () -> service.download(group, PACKAGE, HAR, old, BASE, false)).status());
  }

  @Test
  void contentChangedAfterSelectionIsRejectedBeforeOpeningTheBlob() throws Exception {
    String fingerprint = fingerprint();
    var opened = new AtomicBoolean();
    MavenResponse changed = OhpmContentIntegrity.attach(MavenResponse.ok(() -> {
      opened.set(true);
      return new ByteArrayInputStream(new byte[] {8});
    }, 1, "application/octet-stream", "new", Instant.now()), hex(8));
    when(hosted.download(first, HAR, false)).thenReturn(changed);
    assertEquals(409, assertThrows(OhpmException.class,
        () -> service.download(group, PACKAGE, HAR, fingerprint, BASE, false)).status());
    assertFalse(opened.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 410})
  void missingMemberPackageAllowsFollowingMember(int status) throws Exception {
    when(selections.findVersion(eq(1L), anyString(), anyString(), eq(false))).thenReturn(Optional.empty());
    when(hosted.metadata(eq(first), eq(PACKAGE), anyString(), eq(false)))
        .thenThrow(new OhpmException(status, "missing"));
    assertArrayEquals(new byte[] {9}, read(service.download(group, PACKAGE, HAR, fingerprint(), BASE, false)));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 451, 502, 503})
  void memberFailureCannotSilentlyChangePriority(int status) throws Exception {
    when(hosted.metadata(eq(first), eq(PACKAGE), anyString(), eq(false)))
        .thenThrow(new OhpmException(status, "unavailable"));
    assertEquals(status, assertThrows(OhpmException.class,
        () -> service.metadata(group, PACKAGE, BASE, false)).status());
    verify(proxy, never()).metadata(any(), any(), anyString(), anyBoolean());
  }

  @Test
  void freshOfflineMemberAndInvalidNestedGroupAreRejected() {
    when(first.online()).thenReturn(false);
    assertEquals(503, assertThrows(OhpmException.class, () -> service.metadata(group, PACKAGE, BASE, false)).status());
    when(first.online()).thenReturn(true);
    when(first.isHosted()).thenReturn(false);
    when(first.isGroup()).thenReturn(true);
    assertEquals(502, assertThrows(OhpmException.class, () -> service.metadata(group, PACKAGE, BASE, false)).status());
  }

  @Test
  void missingOrMalformedSourceFingerprintDoesNotResolveMembers() {
    for (String source : new String[] {null, "", "forged"}) {
      assertEquals(400, assertThrows(OhpmException.class,
          () -> service.download(group, PACKAGE, HAR, source, BASE, false)).status());
    }
    verifyNoInteractions(repositories);
  }

  @Test
  void metadataHeadPreservesLengthAndValidatorWithoutABody() throws Exception {
    var get = service.metadata(group, PACKAGE, BASE, false);
    var head = service.metadata(group, PACKAGE, BASE, true);
    assertEquals(get.etag(), head.etag());
    assertEquals(get.contentLength(), head.contentLength());
    assertNull(head.lastModified(), "Member timestamps cannot validate group configuration changes");
    assertFalse(head.hasBody());
    get.closeBodyIfOpen();
  }

  private String fingerprint() throws Exception {
    String url = JSON.readTree(read(service.metadata(group, PACKAGE, BASE, false)))
        .path("versions").path("1.0.0").path("dist").path("tarball").asText();
    return url.substring(url.indexOf("kkrepo_source=") + "kkrepo_source=".length());
  }

  private static MavenResponse metadata(ObjectNode body) throws Exception {
    byte[] bytes = JSON.writeValueAsBytes(body);
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", null, Instant.EPOCH);
  }

  private static MavenResponse content(int value) throws Exception {
    return OhpmContentIntegrity.attach(MavenResponse.ok(new ByteArrayInputStream(new byte[] {(byte) value}),
        1, "application/octet-stream", "etag", Instant.EPOCH), hex(value));
  }

  private static byte[] read(MavenResponse response) throws Exception {
    try (var body = response.body()) { return body.readAllBytes(); }
  }

  private static ObjectNode document(int value, boolean hsp) throws Exception {
    var body = JSON.createObjectNode().put("name", PACKAGE.id());
    body.putObject("dist-tags").put("latest", "1.0.0");
    var dist = body.putObject("versions").putObject("1.0.0").put("name", PACKAGE.id()).put("version", "1.0.0")
        .putObject("dist").put("integrity", integrity(value)).put("tarball", BASE + "/" + HAR);
    if (hsp) {
      body.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
      dist.put("integrity_hsp", integrity(value + 1)).put("resolved_hsp", BASE + "/" + HSP);
    }
    return body;
  }

  private static String integrity(int value) throws Exception {
    return OhpmIntegrity.compute(new ByteArrayInputStream(new byte[] {(byte) value})).value();
  }

  private static String hex(int value) throws Exception {
    return HexFormat.of().formatHex(Base64.getDecoder().decode(integrity(value).substring(7)));
  }
}
