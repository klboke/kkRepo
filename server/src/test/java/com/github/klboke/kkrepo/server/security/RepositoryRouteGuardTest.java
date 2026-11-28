package com.github.klboke.kkrepo.server.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(RepositoryRouteGuardTest.TestConfig.class)
@ActiveProfiles("repository-route-guard-test")
@TestPropertySource(properties = "kkrepo.compatibility.repository-url-prefix=/packages/internal")
class RepositoryRouteGuardTest {
  private static final String CONTEXT = "/app";
  private static final String ROOT = CONTEXT + "/packages/internal/example";
  private static final String BROWSE = CONTEXT + "/service/rest/repository/browse/example";
  private final RepositoryRecord checked = mock(RepositoryRecord.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp(@Autowired WebApplicationContext context) {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    when(checked.name()).thenReturn("example");
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "HEAD", "PUT", "DELETE", "POST"})
  void contentRequiresTheMatchingRepository(String method) throws Exception {
    var url = URI.create(ROOT + "/metadata");
    mvc.perform(request(HttpMethod.valueOf(method), url).contextPath(CONTEXT))
        .andExpect(status().isNotFound());
    mvc.perform(request(HttpMethod.valueOf(method), url).contextPath(CONTEXT)
        .requestAttr(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, checked))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsMismatchedRepositoryAndProtectsDirectoryRoots() throws Exception {
    for (String path : new String[]{ROOT, BROWSE, BROWSE + "/library"}) {
      mvc.perform(get(path).contextPath(CONTEXT)).andExpect(status().isNotFound());
      mvc.perform(get(path).contextPath(CONTEXT)
          .requestAttr(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, checked))
          .andExpect(status().isOk());
    }
    when(checked.name()).thenReturn("another");
    mvc.perform(get(ROOT).contextPath(CONTEXT)
        .requestAttr(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, checked))
        .andExpect(status().isNotFound());
  }

  @Test
  void checksRoutingSegmentsAndLeavesProtocolPathsAndOtherRoutesAlone() throws Exception {
    mvc.perform(get(URI.create(CONTEXT + "/packages/internal/%65xample/index")).contextPath(CONTEXT)
        .requestAttr(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, checked))
        .andExpect(status().isNotFound());
    mvc.perform(get(URI.create(ROOT + "/library/a%20b;revision")).contextPath(CONTEXT)
        .requestAttr(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, checked))
        .andExpect(status().isOk());
    mvc.perform(get(CONTEXT + "/status").contextPath(CONTEXT)).andExpect(status().isOk());
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("repository-route-guard-test")
  @EnableWebMvc
  @Import({RepositoryRouteGuard.class, Routes.class})
  static class TestConfig {}

  @Profile("repository-route-guard-test")
  @RestController
  static class Routes {
    @RequestMapping({"/packages/internal/{name}/**", "/service/rest/repository/browse/{repository}/**", "/status"})
    String handle() {
      return "ok";
    }
  }
}
