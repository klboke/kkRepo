package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DockerRegistryControllerTest {
  @Test
  void baseEndpointReturnsRegistryApiHeader() {
    DockerRegistryController controller = controller(null, null);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/");

    ResponseEntity<?> response = controller.get(request, 100, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(DockerConstants.API_VERSION, response.getHeaders().getFirst(DockerConstants.API_VERSION_HEADER));
  }

  @Test
  void pathBasedUploadLocationIncludesRepositorySegment() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.startUpload(eq(runtime), eq("library/alpine"), eq(null), eq(null), eq(null), anyString(), anyString()))
        .thenReturn(new DockerUploadService.UploadStatus("upload-1", 0, 0, false, null));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v2/docker-hosted/library/alpine/blobs/uploads");
    request.setScheme("http");
    request.setServerName("repo.example.com");
    request.setServerPort(8081);

    ResponseEntity<?> response = controller.post(request, null, null);

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    assertEquals(
        "http://repo.example.com:8081/v2/docker-hosted/library/alpine/blobs/uploads/upload-1",
        response.getHeaders().getFirst(HttpHeaders.LOCATION));
  }

  @Test
  void connectorUploadLocationUsesStandardDockerPathAndPublicUrl() {
    RepositoryRuntime runtime = hosted("docker-hosted", "https://docker.example.com");
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.startUpload(eq(runtime), eq("library/alpine"), eq(null), eq(null), eq(null), anyString(), anyString()))
        .thenReturn(new DockerUploadService.UploadStatus("upload-2", 0, 0, false, null));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v2/library/alpine/blobs/uploads");
    request.setAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE, "docker-hosted");

    ResponseEntity<?> response = controller.post(request, null, null);

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    assertEquals(
        "https://docker.example.com/v2/library/alpine/blobs/uploads/upload-2",
        response.getHeaders().getFirst(HttpHeaders.LOCATION));
  }

  @Test
  void uploadSessionGetReturnsStatusHeaders() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.uploadStatus(runtime, "upload-1"))
        .thenReturn(new DockerUploadService.UploadStatus("upload-1", 0, 42, false, null));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-hosted/library/alpine/blobs/uploads/upload-1");
    request.setScheme("https");
    request.setServerName("repo.example.com");
    request.setServerPort(443);

    ResponseEntity<?> response = controller.get(request, 100, null, null);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    assertEquals(DockerConstants.API_VERSION,
        response.getHeaders().getFirst(DockerConstants.API_VERSION_HEADER));
    assertEquals("https://repo.example.com/v2/docker-hosted/library/alpine/blobs/uploads/upload-1",
        response.getHeaders().getFirst(HttpHeaders.LOCATION));
    assertEquals("0-41", response.getHeaders().getFirst("Range"));
    assertEquals("upload-1", response.getHeaders().getFirst(DockerConstants.UPLOAD_UUID_HEADER));
    verify(hosted).uploadStatus(runtime, "upload-1");
  }

  @Test
  void tagsListAddsNextLinkWhenPageHasMoreTags() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.tags(runtime, "library/alpine", null, 1))
        .thenReturn(new DockerTagList("library/alpine", List.of("3.19"), true));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-hosted/library/alpine/tags/list");

    ResponseEntity<?> response = controller.get(request, 1, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("</v2/docker-hosted/library/alpine/tags/list?n=1&last=3.19>; rel=\"next\"",
        response.getHeaders().getFirst(HttpHeaders.LINK));
  }

  @Test
  void catalogListAddsNextLinkWhenPageHasMoreRepositories() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.catalog(runtime, null, 1))
        .thenReturn(new DockerCatalogList(List.of("library/alpine"), true));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-hosted/_catalog");

    ResponseEntity<?> response = controller.get(request, 1, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("</v2/docker-hosted/_catalog?n=1&last=library%2Falpine>; rel=\"next\"",
        response.getHeaders().getFirst(HttpHeaders.LINK));
    assertEquals(Map.of("repositories", List.of("library/alpine")), response.getBody());
  }

  @Test
  void connectorCatalogUsesRepositoryFromConnectorAttribute() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.catalog(runtime, "library/alpine", 10))
        .thenReturn(new DockerCatalogList(List.of("team/app"), false));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/_catalog");
    request.setAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE, "docker-hosted");

    ResponseEntity<?> response = controller.get(request, 10, "library/alpine", null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(Map.of("repositories", List.of("team/app")), response.getBody());
    verify(hosted).catalog(runtime, "library/alpine", 10);
  }

  @Test
  void manifestHeadReturnsLengthAndDigestHeaders() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.getManifest(runtime, "library/alpine", "latest", true))
        .thenReturn(DockerResponse.noBody(
                200, 42, DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST, java.time.Instant.EPOCH)
            .withHeader(DockerConstants.CONTENT_DIGEST_HEADER,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("HEAD", "/v2/docker-hosted/library/alpine/manifests/latest");

    ResponseEntity<?> response = controller.head(request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(42, response.getHeaders().getContentLength());
    assertEquals(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST, response.getHeaders().getContentType().toString());
    assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        response.getHeaders().getFirst(DockerConstants.CONTENT_DIGEST_HEADER));
  }

  @Test
  void manifestGetPassesAcceptHeaderToHostedService() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    List<String> accept = List.of(DockerConstants.MEDIA_TYPE_OCI_INDEX);
    when(hosted.getManifest(runtime, "library/alpine", "latest", false, accept))
        .thenReturn(DockerResponse.noBody(200)
            .withHeader(DockerConstants.CONTENT_DIGEST_HEADER,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-hosted/library/alpine/manifests/latest");

    ResponseEntity<?> response = controller.get(request, 100, null, null, accept);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(hosted).getManifest(runtime, "library/alpine", "latest", false, accept);
  }

  @Test
  void manifestGetStreamsRawBytesThroughSpringMvcInsteadOfSerializingBodyObject() throws Exception {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    byte[] manifest = "{\"schemaVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    DockerHostedService hosted = mock(DockerHostedService.class);
    when(hosted.getManifest(runtime, "library/alpine", "latest", false))
        .thenReturn(DockerResponse.body(
                200,
                () -> new java.io.ByteArrayInputStream(manifest),
                manifest.length,
                DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST,
                java.time.Instant.EPOCH)
            .withHeader(DockerConstants.CONTENT_DIGEST_HEADER,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(controller(runtime, hosted))
        .setControllerAdvice(new DockerErrorAdvice())
        .build();

    var result = mvc.perform(get("/v2/docker-hosted/library/alpine/manifests/latest"))
        .andReturn();

    assertEquals(200, result.getResponse().getStatus());
    assertEquals(manifest.length, result.getResponse().getContentLength());
    assertEquals(new String(manifest, java.nio.charset.StandardCharsets.UTF_8),
        result.getResponse().getContentAsString());
    assertEquals(DockerConstants.MEDIA_TYPE_SCHEMA2_MANIFEST,
        result.getResponse().getContentType());
    assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        result.getResponse().getHeader(DockerConstants.CONTENT_DIGEST_HEADER));
  }

  @Test
  void manifestPutReturnsOciSubjectHeaderWhenStoredManifestHasSubject() throws Exception {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    DockerResponse stored = DockerResponse.noBody(201)
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER,
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .withHeader(DockerConstants.OCI_SUBJECT_HEADER,
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    when(hosted.putManifest(
        eq(runtime), eq("library/alpine"), eq("sig"), any(), eq(DockerConstants.MEDIA_TYPE_OCI_MANIFEST),
        eq(List.of("stable")), anyString(), anyString()))
        .thenReturn(stored);
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/v2/docker-hosted/library/alpine/manifests/sig");
    request.setContentType(DockerConstants.MEDIA_TYPE_OCI_MANIFEST);
    request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    ResponseEntity<?> response = controller.put(
        request, null, List.of("stable"), DockerConstants.MEDIA_TYPE_OCI_MANIFEST, null);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        response.getHeaders().getFirst(DockerConstants.OCI_SUBJECT_HEADER));
    verify(hosted).putManifest(
        eq(runtime), eq("library/alpine"), eq("sig"), any(), eq(DockerConstants.MEDIA_TYPE_OCI_MANIFEST),
        eq(List.of("stable")), anyString(), anyString());
  }

  @Test
  void referrersWithArtifactTypeReportsOciFilterApplied() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    DockerDigest digest = DockerDigest.parse("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    when(hosted.referrers(runtime, digest, "application/vnd.example.signature"))
        .thenReturn(Map.of("schemaVersion", 2, "mediaType", DockerConstants.MEDIA_TYPE_OCI_INDEX,
            "manifests", List.of()));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-hosted/library/alpine/referrers/" + digest.value());

    ResponseEntity<?> response = controller.get(request, 100, null, "application/vnd.example.signature");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("artifactType", response.getHeaders().getFirst(DockerConstants.OCI_FILTERS_APPLIED_HEADER));
    assertEquals(DockerConstants.MEDIA_TYPE_OCI_INDEX,
        response.getHeaders().getContentType().toString());
  }

  @Test
  void groupRepositoryRejectsDockerWriteRoutesAsUnsupported() throws Exception {
    MockMvc mvc = dockerWriteMvc(repository("docker-group", RepositoryType.GROUP, "docker-group"));

    assertUnsupportedWrite(mvc, post("/v2/docker-group/library/alpine/blobs/uploads"));
    assertUnsupportedWrite(mvc, put("/v2/docker-group/library/alpine/manifests/latest")
        .contentType(DockerConstants.MEDIA_TYPE_OCI_MANIFEST)
        .content("{}"));
    assertUnsupportedWrite(mvc, delete("/v2/docker-group/library/alpine/manifests/latest"));
  }

  @Test
  void proxyRepositoryRejectsDockerWriteRoutesAsUnsupported() throws Exception {
    MockMvc mvc = dockerWriteMvc(repository("docker-proxy", RepositoryType.PROXY, "docker-proxy"));

    assertUnsupportedWrite(mvc, post("/v2/docker-proxy/library/alpine/blobs/uploads"));
    assertUnsupportedWrite(mvc, put("/v2/docker-proxy/library/alpine/manifests/latest")
        .contentType(DockerConstants.MEDIA_TYPE_OCI_MANIFEST)
        .content("{}"));
    assertUnsupportedWrite(mvc, delete("/v2/docker-proxy/library/alpine/manifests/latest"));
  }

  @Test
  void hostedDeleteManifestMissingReturnsDockerError() throws Exception {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    when(manifestStore.deleteReference(runtime, "library/alpine", "missing")).thenReturn(0);
    DockerHostedService hosted = new DockerHostedService(
        mock(DockerBlobStore.class),
        manifestStore,
        mock(DockerUploadService.class));
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(controller(runtime, hosted))
        .setControllerAdvice(new DockerErrorAdvice())
        .build();

    var result = mvc.perform(delete("/v2/docker-hosted/library/alpine/manifests/missing"))
        .andReturn();

    assertEquals(404, result.getResponse().getStatus());
    assertEquals(DockerConstants.API_VERSION,
        result.getResponse().getHeader(DockerConstants.API_VERSION_HEADER));
    assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"MANIFEST_UNKNOWN\""));
  }

  @Test
  void uploadCompleteWithoutDigestReturnsDockerErrorJson() throws Exception {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(controller(runtime, mock(DockerHostedService.class)))
        .setControllerAdvice(new DockerErrorAdvice())
        .build();

    var result = mvc.perform(put("/v2/docker-hosted/library/alpine/blobs/uploads/upload-1")
        .content("abc"))
        .andReturn();

    assertEquals(400, result.getResponse().getStatus());
    assertEquals(DockerConstants.API_VERSION,
        result.getResponse().getHeader(DockerConstants.API_VERSION_HEADER));
    assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"DIGEST_INVALID\""));
  }

  @Test
  void hostedWritePolicyDenyRejectsBlobUploadStart() {
    DockerHostedService service = new DockerHostedService(
        mock(DockerBlobStore.class),
        mock(DockerManifestStore.class),
        mock(DockerUploadService.class));
    RepositoryRuntime runtime = hosted("docker-hosted", null, "DENY");

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> service.startUpload(runtime, "library/alpine", null, null, null, "user", "127.0.0.1"));

    assertEquals(DockerErrorCode.DENIED, thrown.code());
    assertEquals(403, thrown.status());
  }

  @Test
  void crossRepositoryMountRequiresSourcePullPermission() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    AccessDecisionService access = mock(AccessDecisionService.class);
    when(access.decide(any(), any(RepositoryPermission.class))).thenReturn(AccessDecision.deny("missing pull"));
    DockerRegistryController controller = controller(runtime, hosted, access);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v2/docker-hosted/team/app/blobs/uploads");
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, new AuthenticatedSubject(
        "default",
        "alice",
        null,
        null,
        new PermissionSubject("default", "alice", java.util.Set.of(), null)));

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> controller.post(
            request,
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "library/base"));

    assertEquals(DockerErrorCode.DENIED, thrown.code());
    assertEquals(403, thrown.status());
    verify(hosted, never()).startUpload(any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  void pathBasedCrossRepositoryMountResolvesSourceRepositoryFromFromParameter() {
    RepositoryRuntime target = hosted(1L, "docker-target", null);
    RepositoryRuntime source = hosted(2L, "docker-source", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    AccessDecisionService access = mock(AccessDecisionService.class);
    String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    when(access.decide(any(), any(RepositoryPermission.class))).thenReturn(AccessDecision.allow());
    when(hosted.startUpload(
        eq(target),
        eq("team/app"),
        eq(digest),
        eq("library/base"),
        eq(source),
        eq("alice"),
        anyString()))
        .thenReturn(new DockerUploadService.UploadStatus(null, 0, 0, true, DockerDigest.parse(digest)));
    DockerRegistryController controller = controller(List.of(target, source), hosted, access);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v2/docker-target/team/app/blobs/uploads");
    request.setScheme("https");
    request.setServerName("repo.example.com");
    request.setServerPort(443);
    request.setRemoteAddr("127.0.0.1");
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, new AuthenticatedSubject(
        "default",
        "alice",
        null,
        null,
        new PermissionSubject("default", "alice", java.util.Set.of(), null)));

    ResponseEntity<?> response = controller.post(request, digest, "docker-source/library/base");

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals(digest, response.getHeaders().getFirst(DockerConstants.CONTENT_DIGEST_HEADER));
    assertEquals("https://repo.example.com/v2/docker-target/team/app/blobs/" + digest,
        response.getHeaders().getFirst(HttpHeaders.LOCATION));
    verify(access).decide(any(), eq(new RepositoryPermission(
        source.name(), RepositoryFormat.DOCKER, "library/base", PermissionAction.READ)));
    verify(hosted).startUpload(
        eq(target),
        eq("team/app"),
        eq(digest),
        eq("library/base"),
        eq(source),
        eq("alice"),
        anyString());
  }

  @Test
  void hostedDeleteBlobReturnsAccepted() {
    RepositoryRuntime runtime = hosted("docker-hosted", null);
    DockerHostedService hosted = mock(DockerHostedService.class);
    DockerDigest digest = DockerDigest.parse("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(hosted.deleteBlob(runtime, digest)).thenReturn(DockerResponse.noBody(202));
    DockerRegistryController controller = controller(runtime, hosted);
    MockHttpServletRequest request =
        new MockHttpServletRequest("DELETE", "/v2/docker-hosted/library/alpine/blobs/" + digest.value());

    ResponseEntity<?> response = controller.delete(request);

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    verify(hosted).deleteBlob(runtime, digest);
  }

  private static DockerRegistryController controller(RepositoryRuntime runtime, DockerHostedService hosted) {
    return controller(runtime, hosted, null);
  }

  private static DockerRegistryController controller(
      RepositoryRuntime runtime, DockerHostedService hosted, AccessDecisionService accessDecisionService) {
    return controller(runtime == null ? List.of() : List.of(runtime), hosted, mock(DockerProxyService.class),
        mock(DockerGroupService.class),
        accessDecisionService);
  }

  private static DockerRegistryController controller(
      List<RepositoryRuntime> runtimes, DockerHostedService hosted, AccessDecisionService accessDecisionService) {
    return controller(runtimes, hosted, mock(DockerProxyService.class), mock(DockerGroupService.class),
        accessDecisionService);
  }

  private static DockerRegistryController controller(
      RepositoryRuntime runtime,
      DockerHostedService hosted,
      DockerProxyService proxy,
      DockerGroupService group,
      AccessDecisionService accessDecisionService) {
    return controller(runtime == null ? List.of() : List.of(runtime), hosted, proxy, group, accessDecisionService);
  }

  private static DockerRegistryController controller(
      List<RepositoryRuntime> runtimes,
      DockerHostedService hosted,
      DockerProxyService proxy,
      DockerGroupService group,
      AccessDecisionService accessDecisionService) {
    RepositoryRuntimeRegistry registry = mock(RepositoryRuntimeRegistry.class);
    for (RepositoryRuntime runtime : runtimes) {
      when(registry.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    }
    return new DockerRegistryController(
        registry,
        hosted == null ? mock(DockerHostedService.class) : hosted,
        proxy,
        group,
        mock(DockerRangeSupport.class),
        accessDecisionService);
  }

  private static MockMvc dockerWriteMvc(RepositoryRuntime runtime) {
    DockerHostedService hosted = new DockerHostedService(
        mock(DockerBlobStore.class),
        mock(DockerManifestStore.class),
        mock(DockerUploadService.class));
    return MockMvcBuilders
        .standaloneSetup(controller(runtime, hosted))
        .setControllerAdvice(new DockerErrorAdvice())
        .build();
  }

  private static void assertUnsupportedWrite(MockMvc mvc, RequestBuilder request) throws Exception {
    var result = mvc.perform(request).andReturn();

    assertEquals(405, result.getResponse().getStatus());
    assertEquals(DockerConstants.API_VERSION,
        result.getResponse().getHeader(DockerConstants.API_VERSION_HEADER));
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"code\":\"UNSUPPORTED\""));
    assertTrue(body.contains("\"message\":\"unsupported repository type\""));
  }

  private static RepositoryRuntime hosted(String name, String connectorPublicUrl) {
    return hosted(1L, name, connectorPublicUrl, "ALLOW");
  }

  private static RepositoryRuntime hosted(long id, String name, String connectorPublicUrl) {
    return hosted(id, name, connectorPublicUrl, "ALLOW");
  }

  private static RepositoryRuntime hosted(String name, String connectorPublicUrl, String writePolicy) {
    return hosted(1L, name, connectorPublicUrl, writePolicy);
  }

  private static RepositoryRuntime hosted(long id, String name, String connectorPublicUrl, String writePolicy) {
    return repository(id, name, RepositoryType.HOSTED, "docker-hosted", writePolicy, connectorPublicUrl);
  }

  private static RepositoryRuntime repository(String name, RepositoryType type, String recipeName) {
    return repository(1L, name, type, recipeName, "ALLOW", null);
  }

  private static RepositoryRuntime repository(
      String name,
      RepositoryType type,
      String recipeName,
      String writePolicy,
      String connectorPublicUrl) {
    return repository(1L, name, type, recipeName, writePolicy, connectorPublicUrl);
  }

  private static RepositoryRuntime repository(
      long id,
      String name,
      RepositoryType type,
      String recipeName,
      String writePolicy,
      String connectorPublicUrl) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.DOCKER,
        type,
        recipeName,
        true,
        1L,
        writePolicy,
        null,
        null,
        true,
        null,
        null,
        null,
        null,
        null,
        true,
        5000,
        connectorPublicUrl,
        List.of());
  }
}
