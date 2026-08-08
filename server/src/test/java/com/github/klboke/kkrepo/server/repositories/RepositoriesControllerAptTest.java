package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class RepositoriesControllerAptTest {

  @Test
  void exposesStatusAndRebuildsAllOrOneDistribution() {
    Fixture fixture = fixture(aptView(RepositoryType.HOSTED));
    AptService.Status status = new AptService.Status(List.of(), null, List.of());
    when(fixture.apt.status(fixture.runtime)).thenReturn(status);

    assertEquals(status, fixture.controller.aptStatus("apt", request()));
    assertEquals(204, fixture.controller.rebuildApt("apt", null, request())
        .getStatusCode().value());
    assertEquals(204, fixture.controller.rebuildApt(
        "apt", new RepositoriesController.AptRebuildRequest("bookworm"), request())
        .getStatusCode().value());

    verify(fixture.apt).rebuild(fixture.runtime, null);
    verify(fixture.apt).rebuild(fixture.runtime, "bookworm");
  }

  @Test
  void rotatesImportedAndGeneratedSigningKeys() {
    Fixture fixture = fixture(aptView(RepositoryType.PROXY));
    Instant created = Instant.parse("2026-08-08T00:00:00Z");
    AptRegistryDao.SigningKey imported = key(2, "IMPORTED", created);
    AptRegistryDao.SigningKey generated = key(3, "GENERATED", created.plusSeconds(1));
    when(fixture.apt.rotateKey(fixture.runtime, "private", "secret")).thenReturn(imported);
    when(fixture.apt.rotateGeneratedKey(fixture.runtime)).thenReturn(generated);

    AptService.KeyStatus importedStatus = fixture.controller.rotateAptSigningKey(
        "apt", new RepositoriesController.AptSigningKeyRequest(
            "private", "secret", false), request());
    AptService.KeyStatus generatedStatus = fixture.controller.rotateAptSigningKey(
        "apt", new RepositoriesController.AptSigningKeyRequest(null, null, true), request());

    assertEquals("IMPORTED", importedStatus.keyId());
    assertEquals(2, importedStatus.revision());
    assertEquals("GENERATED", generatedStatus.keyId());
    verify(fixture.apt).rotateKey(fixture.runtime, "private", "secret");
    verify(fixture.apt).rotateGeneratedKey(fixture.runtime);
  }

  @Test
  void validatesSigningKeyRequestsAndRepositoryKind() {
    Fixture fixture = fixture(aptView(RepositoryType.HOSTED));

    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rotateAptSigningKey("apt", null, request()));
    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rotateAptSigningKey(
            "apt", new RepositoriesController.AptSigningKeyRequest(" ", null, false), request()));
    assertThrows(RepositoryValidationException.class,
        () -> fixture.controller.rotateAptSigningKey(
            "apt", new RepositoriesController.AptSigningKeyRequest("private", null, true), request()));

    Fixture maven = fixture(view(RepositoryFormat.MAVEN2, RepositoryType.HOSTED));
    assertThrows(RepositoryValidationException.class,
        () -> maven.controller.aptStatus("apt", request()));
    Fixture group = fixture(aptView(RepositoryType.GROUP));
    assertThrows(RepositoryValidationException.class,
        () -> group.controller.aptStatus("apt", request()));
  }

  @Test
  void failsClosedWhenAptServiceRuntimeOrRepositoryIsUnavailable() {
    RepositoryService service = mock(RepositoryService.class);
    when(service.get("apt")).thenReturn(aptView(RepositoryType.HOSTED));
    RepositoriesController unavailable = controller(service);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
        () -> unavailable.aptStatus("apt", request()));

    AptService apt = mock(AptService.class);
    unavailable.setAptManagement(apt, null);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
        () -> unavailable.aptStatus("apt", request()));

    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    unavailable.setAptManagement(apt, runtimes);
    when(runtimes.resolve("apt")).thenReturn(Optional.empty());
    assertThrows(RepositoryNotFoundException.class,
        () -> unavailable.aptStatus("apt", request()));
  }

  private static Fixture fixture(RepositoryView view) {
    RepositoryService service = mock(RepositoryService.class);
    when(service.get("apt")).thenReturn(view);
    RepositoryRuntime runtime = runtime(view.type());
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("apt")).thenReturn(Optional.of(runtime));
    AptService apt = mock(AptService.class);
    RepositoriesController controller = controller(service);
    controller.setAptManagement(apt, runtimes);
    return new Fixture(controller, apt, runtime);
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

  private static RepositoryView aptView(RepositoryType type) {
    return view(RepositoryFormat.APT, type);
  }

  private static RepositoryView view(RepositoryFormat format, RepositoryType type) {
    return new RepositoryView(
        1L, "apt", "apt-" + type.name().toLowerCase(), format, type, true,
        "default", true, "/repository/apt/", null, null, null, null, null, null, null);
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "apt", RepositoryFormat.APT, type, "apt-" + type.name().toLowerCase(),
        true, 7L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  }

  private static AptRegistryDao.SigningKey key(int revision, String keyId, Instant createdAt) {
    return new AptRegistryDao.SigningKey(
        1L, revision, keyId, "F".repeat(40), "encrypted", "public", true, createdAt);
  }

  private static void assertStatus(HttpStatus expected, Runnable invocation) {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, invocation::run);
    assertEquals(expected, error.getStatusCode());
  }

  private record Fixture(
      RepositoriesController controller, AptService apt, RepositoryRuntime runtime) { }
}
