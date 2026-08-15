package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.server.alpine.AlpineService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.AlpineSettings;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class RepositoriesControllerAlpineTest {

  @Test
  void exposesStatusAndRebuildsOneNamespace() {
    Fixture fixture = fixture(view(RepositoryFormat.ALPINE, RepositoryType.HOSTED));
    AlpineService.Status status = new AlpineService.Status(List.of(), null, List.of());
    when(fixture.alpine.status(fixture.runtime)).thenReturn(status);

    assertEquals(status, fixture.controller.alpineStatus("alpine", request()));
    assertEquals(204, fixture.controller.rebuildAlpine(
        "alpine", new RepositoriesController.AlpineRebuildRequest(
            "v3.23/main/x86_64"), request()).getStatusCode().value());
    verify(fixture.alpine).rebuild(fixture.runtime, "v3.23/main/x86_64");

    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rebuildAlpine("alpine", null, request()));
    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rebuildAlpine(
            "alpine", new RepositoriesController.AlpineRebuildRequest(" "), request()));
  }

  @Test
  void rotatesImportedAndGeneratedSigningKeys() {
    Fixture fixture = fixture(view(RepositoryFormat.ALPINE, RepositoryType.PROXY));
    Instant created = Instant.parse("2026-08-15T00:00:00Z");
    AlpineRegistryDao.SigningKey imported = key(2, "imported.rsa.pub", created);
    AlpineRegistryDao.SigningKey generated = key(3, "generated.rsa.pub", created.plusSeconds(1));
    when(fixture.alpine.rotateKey(
        fixture.runtime, "private", "imported.rsa.pub", "RSA256")).thenReturn(imported);
    when(fixture.alpine.rotateGeneratedKey(fixture.runtime)).thenReturn(generated);

    AlpineService.KeyStatus importedStatus = fixture.controller.rotateAlpineSigningKey(
        "alpine", new RepositoriesController.AlpineSigningKeyRequest(
            "private", "imported.rsa.pub", "RSA256", false), request());
    AlpineService.KeyStatus generatedStatus = fixture.controller.rotateAlpineSigningKey(
        "alpine", new RepositoriesController.AlpineSigningKeyRequest(
            null, null, null, true), request());

    assertEquals("imported.rsa.pub", importedStatus.filename());
    assertEquals(2, importedStatus.revision());
    assertEquals("generated.rsa.pub", generatedStatus.filename());
    verify(fixture.alpine).rotateKey(
        fixture.runtime, "private", "imported.rsa.pub", "RSA256");
    verify(fixture.alpine).rotateGeneratedKey(fixture.runtime);
  }

  @Test
  void returnsPublicKeyAndMapsReadFailures() {
    Fixture fixture = fixture(view(RepositoryFormat.ALPINE, RepositoryType.GROUP));
    byte[] key = "public-key".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    when(fixture.alpine.publicKey(fixture.runtime, false)).thenReturn(
        MavenResponse.ok(new ByteArrayInputStream(key), key.length,
            "application/x-pem-file", "etag", null)
            .withHeader("Content-Disposition", "attachment; filename=fixture.rsa.pub"));

    var response = fixture.controller.alpinePublicKey("alpine", request());
    assertEquals(200, response.getStatusCode().value());
    assertArrayEquals(key, response.getBody());
    assertEquals("application/x-pem-file", response.getHeaders().getFirst("Content-Type"));
    assertEquals("attachment; filename=fixture.rsa.pub",
        response.getHeaders().getFirst("Content-Disposition"));

    InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken key");
      }
    };
    when(fixture.alpine.publicKey(fixture.runtime, false)).thenReturn(
        MavenResponse.ok(broken, 1, "application/x-pem-file", "etag", null));
    assertStatus(HttpStatus.INTERNAL_SERVER_ERROR,
        () -> fixture.controller.alpinePublicKey("alpine", request()));
  }

  @Test
  void validatesKeyRequestsRepositoryKindAndServiceAvailability() {
    Fixture fixture = fixture(view(RepositoryFormat.ALPINE, RepositoryType.HOSTED));
    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rotateAlpineSigningKey("alpine", null, request()));
    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rotateAlpineSigningKey(
            "alpine", new RepositoriesController.AlpineSigningKeyRequest(
                null, null, null, false), request()));
    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rotateAlpineSigningKey(
            "alpine", new RepositoriesController.AlpineSigningKeyRequest(
                "private", null, null, true), request()));

    Fixture maven = fixture(view(RepositoryFormat.MAVEN2, RepositoryType.HOSTED));
    assertThrows(RepositoryValidationException.class,
        () -> maven.controller.alpineStatus("alpine", request()));

    RepositoryService service = mock(RepositoryService.class);
    when(service.get("alpine")).thenReturn(
        view(RepositoryFormat.ALPINE, RepositoryType.HOSTED));
    RepositoriesController unavailable = controller(service);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
        () -> unavailable.alpineStatus("alpine", request()));

    AlpineService alpine = mock(AlpineService.class);
    unavailable.setAlpineManagement(alpine, null);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
        () -> unavailable.alpineStatus("alpine", request()));

    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    unavailable.setAlpineManagement(alpine, runtimes);
    when(runtimes.resolve("alpine")).thenReturn(Optional.empty());
    assertThrows(RepositoryNotFoundException.class,
        () -> unavailable.alpineStatus("alpine", request()));
  }

  private static Fixture fixture(RepositoryView view) {
    RepositoryService service = mock(RepositoryService.class);
    when(service.get("alpine")).thenReturn(view);
    RepositoryRuntime runtime = runtime(view.type());
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("alpine")).thenReturn(Optional.of(runtime));
    AlpineService alpine = mock(AlpineService.class);
    RepositoriesController controller = controller(service);
    controller.setAlpineManagement(alpine, runtimes);
    return new Fixture(controller, alpine, runtime);
  }

  private static RepositoriesController controller(RepositoryService service) {
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    when(security.decide(any(PermissionSubject.class), any(String.class)))
        .thenReturn(AccessDecision.allow());
    return new RepositoriesController(service, authentication, security);
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, new AuthenticatedSubject(
        "Local", "admin", "local", null,
        new PermissionSubject("Local", "admin", Set.of(), null)));
    return request;
  }

  private static RepositoryView view(RepositoryFormat format, RepositoryType type) {
    AlpineSettings alpine = format == RepositoryFormat.ALPINE
        ? new AlpineSettings(
            List.of("v3.23"), List.of("main"), List.of("x86_64"), "RESIGN",
            true, true, "alpine.rsa.pub", "RSA", "fixture", List.of())
        : null;
    return new RepositoryView(
        1L, "alpine", "alpine-" + type.name().toLowerCase(), format, type, true,
        "default", true, "/repository/alpine/", null, null, null, null, null, null,
        null, alpine);
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "alpine", RepositoryFormat.ALPINE, type,
        "alpine-" + type.name().toLowerCase(), true, 7L, "ALLOW", null, null,
        true, null, null, null, null, null, List.of());
  }

  private static AlpineRegistryDao.SigningKey key(
      int revision, String filename, Instant createdAt) {
    return new AlpineRegistryDao.SigningKey(
        1L, revision, filename, "fingerprint", "encrypted", "public", "RSA256", true,
        createdAt);
  }

  private static void assertStatus(HttpStatus expected, Runnable invocation) {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, invocation::run);
    assertEquals(expected, error.getStatusCode());
  }

  private record Fixture(
      RepositoriesController controller, AlpineService alpine, RepositoryRuntime runtime) { }
}
