package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.routing.ohpm.OhpmProtocolHandler;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

class OhpmProtocolHandlerTest {
  private final OhpmHostedService hosted = mock(OhpmHostedService.class);
  private final OhpmProxyService proxy = mock(OhpmProxyService.class);
  private final OhpmGroupService group = mock(OhpmGroupService.class);
  private final OhpmAccess access = mock(OhpmAccess.class);
  private final ForwardedHeaderPolicy forwarded = mock(ForwardedHeaderPolicy.class);
  private final OhpmProtocolHandler handler = new OhpmProtocolHandler(hosted, proxy, group, access, new ObjectMapper(), forwarded);
  private final RepositoryRuntime runtime = mock(RepositoryRuntime.class);

  @Test
  void securityPolicyRejectionIsNotRewrittenAsAnUnexpectedStorageFailure() throws Exception {
    var context = request(false);
    var rejection = mock(com.github.klboke.kkrepo.server.securityscan.ArtifactPolicyException.class);
    doThrow(rejection).when(hosted).publish(any(), any(), any(), anyString(), any(), any());
    assertSame(rejection, assertThrows(com.github.klboke.kkrepo.server.securityscan.ArtifactPolicyException.class,
        () -> handler.handle(context)));
  }

  @Test
  void publicationRequiresJsonAndMalformedTagsNeverReachTheService() {
    var context = request(false);
    ((MockHttpServletRequest) context.servletRequest()).setContentType("text/plain");
    assertEquals(400, handler.handle(context).getStatusCode().value());
    String path = "-/package/@scope/pkg/dist-tags/beta";
    for (String body : new String[] {"{}", "{\"version\":1}", "{\"version\":\"1.0.0\",\"extra\":true}"}) {
      var request = new MockHttpServletRequest("PUT", "/repository/ohpm/" + path);
      request.setContentType("application/json");
      request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(400, handler.handle(new RepositoryProtocolRequest(runtime, "ohpm", path,
          HttpMethod.PUT, request, request.getContentType(), null)).getStatusCode().value());
    }
    verifyNoInteractions(hosted);
  }

  @ParameterizedTest
  @ValueSource(strings = {"login", "login_pss"})
  void optionalLoginRoutesAreUnavailableByDefault(String path) {
    when(runtime.name()).thenReturn("ohpm");
    var request = new MockHttpServletRequest("POST", "/repository/ohpm/" + path);
    var response = handler.handle(new RepositoryProtocolRequest(runtime, "ohpm", path,
        HttpMethod.POST, request, null, null));
    assertEquals(404, response.getStatusCode().value());
    if (path.equals("login")) assertEquals(false, ((Map<?, ?>) response.getBody()).get("success"));
    verifyNoInteractions(hosted, proxy, group);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void metadataUsesOnlyTrustedExternalHeadersAndPreservesTheContextPath(boolean trusted) throws Exception {
    var request = new MockHttpServletRequest("GET", "/kk/repository/ohpm/@scope%2fpkg");
    request.setContextPath("/kk");
    request.setRemoteAddr("127.0.0.1");
    request.setScheme("http");
    request.setServerName("private");
    request.setServerPort(8080);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "repo.example.com");
    when(runtime.online()).thenReturn(true);
    when(runtime.name()).thenReturn("ohpm");
    when(hosted.metadata(any(), any(), anyString(), eq(false))).thenReturn(
        com.github.klboke.kkrepo.server.maven.MavenResponse.ok(
            new java.io.ByteArrayInputStream(new byte[] {'{', '}'}), 2, "application/json", null, null));
    var handler = new OhpmProtocolHandler(hosted, proxy, group, access, new ObjectMapper(),
        new ForwardedHeaderPolicy(trusted ? "127.0.0.1" : ""));
    var response = handler.handle(new RepositoryProtocolRequest(runtime, "ohpm", "@scope%2fpkg",
        HttpMethod.GET, request, null, null));
    assertEquals(200, response.getStatusCode().value());
    verify(hosted).metadata(eq(runtime), any(), eq((trusted ? "https://repo.example.com" : "http://private:8080")
        + "/kk/repository/ohpm"), eq(false));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void onlyTemporaryPublicationFailuresReturn598AndAllowExplicitRetry(boolean stream) throws Exception {
    var request = request(stream);
    var failure = new CannotAcquireLockException("Temporary transaction failure");
    if (stream) {
      doThrow(failure).doNothing().when(hosted).publishStream(any(), any(), any(), anyString(), any(), any());
    } else {
      doThrow(failure).doNothing().when(hosted).publish(any(), any(), any(), anyString(), any(), any());
    }
    var response = handler.handle(request);
    assertEquals(598, response.getStatusCode().value());
    assertEquals(598, ((Map<?, ?>) response.getBody()).get("code"));
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals(200, handler.handle(request).getStatusCode().value());
  }

  @Test
  void unexpectedFailuresDoNotInstructTheClientToRetry() throws Exception {
    var request = request(false);
    doThrow(new IllegalStateException("Storage failure")).when(hosted)
        .publish(any(), any(), any(), anyString(), any(), any());
    assertEquals(500, handler.handle(request).getStatusCode().value());
  }

  @Test
  void malformedStreamContentTypeIsRejectedBeforeAccessingParts() {
    var context = request(true);
    ((MockHttpServletRequest) context.servletRequest()).setContentType("application/json");
    assertEquals(400, handler.handle(context).getStatusCode().value());
    verifyNoInteractions(hosted);
  }

  @Test
  void servletMultipartLimitReturns413BeforePublication() throws Exception {
    var context = request(true);
    var request = spy((MockHttpServletRequest) context.servletRequest());
    doThrow(new IllegalStateException("Multipart size limit exceeded")).when(request).getParts();
    var response = handler.handle(new RepositoryProtocolRequest(runtime, context.repositoryName(),
        context.path(), context.method(), request, context.contentType(), null));
    assertEquals(413, response.getStatusCode().value());
    verifyNoInteractions(hosted);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void proxyRejectsBothPublicationModes(boolean stream) {
    var context = request(stream);
    when(runtime.isProxy()).thenReturn(true);
    assertEquals(405, handler.handle(context).getStatusCode().value());
    verifyNoInteractions(hosted, proxy);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupRejectsBothPublicationModes(boolean stream) {
    var context = request(stream);
    when(runtime.isGroup()).thenReturn(true);
    assertEquals(405, handler.handle(context).getStatusCode().value());
    verifyNoInteractions(hosted, proxy, group);
  }

  private RepositoryProtocolRequest request(boolean stream) {
    when(runtime.online()).thenReturn(true);
    when(runtime.name()).thenReturn("ohpm");
    String path = stream ? "stream/@scope/pkg" : "@scope/pkg";
    HttpMethod method = stream ? HttpMethod.POST : HttpMethod.PUT;
    var request = new MockHttpServletRequest(method.name(), "/repository/ohpm/" + path);
    request.setContentType(stream ? "multipart/form-data; boundary=test" : "application/json");
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, new AuthenticatedSubject(
        "Local", "alice", "api-key", 1L, new PermissionSubject("Local", "alice", Set.of(), "1")));
    return new RepositoryProtocolRequest(runtime, "ohpm", path, method, request, request.getContentType(), null);
  }
}
