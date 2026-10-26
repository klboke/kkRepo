package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.routingrules.RepositoryRoutingPolicy;
import com.github.klboke.kkrepo.server.routingrules.RoutingRuleErrorAdvice;
import java.util.List;
import java.util.Optional;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PypiRoutingRuleTest {
  @Test void mvcMatrixParametersCannotBypassProjectIndexRules() throws Exception {
    var runtime = mock(RepositoryRuntime.class);
    when(runtime.id()).thenReturn(1L);
    when(runtime.format()).thenReturn(RepositoryFormat.PYPI);
    when(runtime.type()).thenReturn(RepositoryType.PROXY);
    when(runtime.isProxy()).thenReturn(true);
    var registry = mock(RepositoryRuntimeRegistry.class);
    when(registry.resolve("p")).thenReturn(Optional.of(runtime));
    var rules = mock(RoutingRuleDao.class);
    when(rules.bindings()).thenReturn(List.of(new RoutingRuleDao.Binding(1,
        new RoutingRuleDao.Rule(1, "private", "", "BLOCK", List.of("/simple/private/.*", "/simple/private-name/.*")))));
    var proxy = mock(PypiProxyService.class);
    when(proxy.getIndex(eq(runtime), anyString(), anyBoolean())).thenReturn(PypiResponse.noBody(200));
    var controller = new PypiRepositoryController(registry, null, proxy, null, null);
    controller.setRoutingPolicy(initializedPolicy(rules));
    var mvc = MockMvcBuilders.standaloneSetup(controller)
        .addPlaceholderValue("kkrepo.compatibility.repository-url-prefix", "/repository")
        .setControllerAdvice(new RoutingRuleErrorAdvice()).build();
    for (String path : List.of("/repository/p/simple/private/", "/repository/p/simple/private;x=1/",
        "/repository/p/simple;x=1/private;x=2/", "/repository;x=1/p/simple/private/",
        "/%72epository/p/simple/private/", "/repository/%70/simple/private/",
        "/repository/p/simple/private", "/repository/p/simple/PRIVATE/",
        "/repository/p/simple/Private__Name", "/repository/p/simple/PRIVATE..NAME/")) {
      assertEquals(403, mvc.perform(get(URI.create(path))).andReturn().getResponse().getStatus(), path);
      assertEquals(403, mvc.perform(head(URI.create(path))).andReturn().getResponse().getStatus(), path);
    }
    verifyNoInteractions(proxy);
  }
  private static RepositoryRoutingPolicy initializedPolicy(RoutingRuleDao rules) {
    var policy = new RepositoryRoutingPolicy(rules);
    policy.initialize();
    return policy;
  }
}
