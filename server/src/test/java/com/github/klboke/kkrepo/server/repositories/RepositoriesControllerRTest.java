package com.github.klboke.kkrepo.server.repositories;

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
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.r.RService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class RepositoriesControllerRTest {

  @Test
  void exposesStatusAndRebuildsRRepository() {
    Fixture fixture = fixture(view(RepositoryFormat.R, RepositoryType.GROUP));
    RService.Status status = new RService.Status(List.of(), List.of());
    when(fixture.r.status(fixture.runtime)).thenReturn(status);

    assertEquals(status, fixture.controller.rStatus("cran", request()));
    assertEquals(204, fixture.controller.rebuildR("cran", request())
        .getStatusCode().value());
    verify(fixture.r).rebuild(fixture.runtime);
  }

  @Test
  void validatesRepositoryKindRuntimeAndServiceAvailability() {
    Fixture maven = fixture(view(RepositoryFormat.MAVEN2, RepositoryType.HOSTED));
    assertThrows(RepositoryValidationException.class,
        () -> maven.controller.rStatus("cran", request()));

    RepositoryService service = mock(RepositoryService.class);
    when(service.get("cran")).thenReturn(view(RepositoryFormat.R, RepositoryType.HOSTED));
    RepositoriesController unavailable = controller(service);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
        () -> unavailable.rStatus("cran", request()));

    RService r = mock(RService.class);
    unavailable.setRManagement(r, null);
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
        () -> unavailable.rStatus("cran", request()));

    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    unavailable.setRManagement(r, runtimes);
    when(runtimes.resolve("cran")).thenReturn(Optional.empty());
    assertThrows(RepositoryNotFoundException.class,
        () -> unavailable.rStatus("cran", request()));
  }

  private static Fixture fixture(RepositoryView view) {
    RepositoryService service = mock(RepositoryService.class);
    when(service.get("cran")).thenReturn(view);
    RepositoryRuntime runtime = runtime(view.type());
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("cran")).thenReturn(Optional.of(runtime));
    RService r = mock(RService.class);
    RepositoriesController controller = controller(service);
    controller.setRManagement(r, runtimes);
    return new Fixture(controller, r, runtime);
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
    return new RepositoryView(
        1L, "cran", "r-" + type.name().toLowerCase(), format, type, true,
        "default", true, "/repository/cran/", null, null, null, null, null, null);
  }

  private static RepositoryRuntime runtime(RepositoryType type) {
    return new RepositoryRuntime(
        1L, "cran", RepositoryFormat.R, type, "r-" + type.name().toLowerCase(),
        true, 7L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  }

  private static void assertStatus(HttpStatus expected, Runnable invocation) {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, invocation::run);
    assertEquals(expected, error.getStatusCode());
  }

  private record Fixture(
      RepositoriesController controller, RService r, RepositoryRuntime runtime) { }
}
