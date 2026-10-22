package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RepositoryUrlPrefixTest {
  @Test
  void configuredRouteResolvesAtMvcBoundaryAndDoesNotKeepTheOldRoute() throws Exception {
    var dispatcher = mock(RepositoryProtocolDispatcher.class);
    doReturn(ResponseEntity.noContent().build()).when(dispatcher)
        .dispatchRead(eq("raw"), any(), eq(HttpMethod.GET));
    var mvc = MockMvcBuilders.standaloneSetup(new RepositoryProtocolController(dispatcher))
        .addPlaceholderValue("kkrepo.compatibility.repository-url-prefix", "/r").build();
    new RepositoryUrlPrefix("/r").onStartup(mvc.getDispatcherServlet().getServletContext());
    mvc.perform(get("/r/raw/file.txt")).andExpect(status().isNoContent());
    mvc.perform(get("/repository/raw/file.txt")).andExpect(status().isNotFound());
  }

  @Test
  void requestConfigurationIsIsolatedBetweenApplicationContexts() {
    MockServletContext first = new MockServletContext();
    MockServletContext second = new MockServletContext();
    new RepositoryUrlPrefix("/r").onStartup(first);
    new RepositoryUrlPrefix("/artifacts/content").onStartup(second);
    assertEquals("/r/", RepositoryUrlPrefix.path(new MockHttpServletRequest(first)));
    assertEquals("/artifacts/content/", RepositoryUrlPrefix.path(new MockHttpServletRequest(second)));
    assertEquals("/repository/", RepositoryUrlPrefix.path(new MockHttpServletRequest()));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(first)));
    try {
      assertEquals("/r/", RepositoryUrlPrefix.currentPath());
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    assertEquals("/repository/", RepositoryUrlPrefix.currentPath());
  }

  @Test
  void rejectsAmbiguousPrefixesAndManagementRouteCollisions() {
    for (String prefix : new String[] {"/", "r", "/r/", "/r//x", "/r/../internal", "/%72",
        "/internal/content", "/service", "/admin", "/v2", "/r?x=1", "/r;param"}) {
      assertThrows(IllegalArgumentException.class, () -> new RepositoryUrlPrefix(prefix), prefix);
    }
  }
}
