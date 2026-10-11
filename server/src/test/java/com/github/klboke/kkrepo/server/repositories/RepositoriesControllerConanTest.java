package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.server.conan.ConanManifestPolicy;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class RepositoriesControllerConanTest {
  @Test
  void exposesResolvedDefaultsWithoutRequiringAnExistingRepository() throws Exception {
    RepositoriesController controller = controller(mock(RepositoryService.class));
    controller.setConanManifestPolicy(new ConanManifestPolicy(
        50000, 16777216));
    var mvc = standaloneSetup(controller).build();
    mvc.perform(get("/internal/repositories/conan/defaults")
        .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE,
            request().getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE)))
        .andExpect(status().isOk())
        .andExpect(result -> assertEquals(
            "{\"manifestMaxEntries\":50000,\"manifestMaxBytes\":16777216}",
            result.getResponse().getContentAsString()));
    mvc.perform(get("/internal/repositories/conan/defaults"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void exposesDurableConanRepositoryStatusToRepositoryAdministrators() {
    RepositoryService service = mock(RepositoryService.class);
    when(service.get("conan-hosted")).thenReturn(view(RepositoryFormat.CONAN));
    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    ConanRegistryDao.RepositoryStatus status = new ConanRegistryDao.RepositoryStatus(
        7L, 3L, 4L, 5L, 6L, 12L, 1L, 2L);
    when(registry.status(1L)).thenReturn(status);
    RepositoriesController controller = controller(service);
    controller.setConanManagement(registry);

    assertEquals(status, controller.conanStatus("conan-hosted", request()));
    verify(registry).status(1L);
  }

  @Test
  void rejectsNonConanRepositoriesAndUnavailableConanPersistence() {
    RepositoryService nonConanService = mock(RepositoryService.class);
    when(nonConanService.get("maven")).thenReturn(view(RepositoryFormat.MAVEN2));
    RepositoriesController nonConan = controller(nonConanService);
    assertThrows(RepositoryValidationException.class,
        () -> nonConan.conanStatus("maven", request()));

    RepositoryService conanService = mock(RepositoryService.class);
    when(conanService.get("conan-hosted")).thenReturn(view(RepositoryFormat.CONAN));
    RepositoriesController unavailable = controller(conanService);
    ResponseStatusException failure = assertThrows(ResponseStatusException.class,
        () -> unavailable.conanStatus("conan-hosted", request()));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
  }

  private static RepositoriesController controller(RepositoryService service) {
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    when(security.decide(any(PermissionSubject.class), any(String.class)))
        .thenReturn(AccessDecision.allow());
    return new RepositoriesController(service, authentication, security);
  }

  private static RepositoryView view(RepositoryFormat format) {
    return new RepositoryView(
        1L, format == RepositoryFormat.CONAN ? "conan-hosted" : "maven",
        format == RepositoryFormat.CONAN ? "conan-hosted" : "maven2-hosted",
        format, RepositoryType.HOSTED, true, "default", true,
        "/repository/repo/", null, null, null, null, null, null, null);
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, new AuthenticatedSubject(
        "Local", "admin", "local", null,
        new PermissionSubject("Local", "admin", Set.of(), null)));
    return request;
  }
}
