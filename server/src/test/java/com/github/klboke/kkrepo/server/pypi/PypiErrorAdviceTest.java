package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.RepositoryContentController;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PypiErrorAdviceTest {
  private static final String MISSING_WHEEL =
      "/repository/pypi/packages/missing/1.0.0/missing-1.0.0-py3-none-any.whl";

  @Test
  void notFoundDoesNotRequireJsonRepresentationForPypiSimpleClients() throws Exception {
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new PypiRepositoryController(
            new RepositoryRuntimeRegistry(new EmptyRepositoryDao(), 0),
            null,
            null,
            null,
            null))
        .setControllerAdvice(new PypiErrorAdvice())
        .build();

    MvcResult result = mvc.perform(get("/repository/pypi/simple/example-io/")
            .accept(MediaType.valueOf("application/vnd.pypi.simple.v1+html")))
        .andReturn();

    assertEquals(404, result.getResponse().getStatus());
    assertNull(result.getResponse().getHeader(HttpHeaders.CONTENT_TYPE));
    assertEquals("", result.getResponse().getContentAsString());
  }

  @Test
  void repositoryContentGetMapsMissingPypiPackageToNotFound() throws Exception {
    MvcResult result = repositoryContentMvc().perform(get(MISSING_WHEEL)).andReturn();

    assertEmptyNotFound(result);
  }

  @Test
  void repositoryContentHeadMapsMissingPypiPackageToNotFound() throws Exception {
    MvcResult result = repositoryContentMvc().perform(head(MISSING_WHEEL)).andReturn();

    assertEmptyNotFound(result);
  }

  private static MockMvc repositoryContentMvc() {
    RepositoryContentController controller = new RepositoryContentController(
        new RepositoryRuntimeRegistry(new SingleRepositoryDao(), 0),
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, null,
        new MissingPackageHostedService(), null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""));
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new PypiErrorAdvice())
        .build();
  }

  private static void assertEmptyNotFound(MvcResult result) throws Exception {
    assertEquals(404, result.getResponse().getStatus());
    assertNull(result.getResponse().getHeader(HttpHeaders.CONTENT_TYPE));
    assertEquals("", result.getResponse().getContentAsString());
  }

  private static final class MissingPackageHostedService extends PypiHostedService {
    private MissingPackageHostedService() {
      super(null, null, null, null, null, null, null, 0);
    }

    @Override
    public PypiResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
      throw new PypiExceptions.PypiNotFoundException(path);
    }
  }

  private static final class SingleRepositoryDao extends RepositoryDaoAdapter {
    private SingleRepositoryDao() {
      super(null, null);
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      if (!"pypi".equals(name)) {
        return Optional.empty();
      }
      return Optional.of(new RepositoryRecord(
          1L,
          "pypi",
          RepositoryFormat.PYPI,
          RepositoryType.HOSTED,
          "pypi-hosted",
          true,
          1L,
          null,
          null,
          null,
          null,
          "ALLOW",
          true,
          Map.of()));
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return List.of();
    }
  }

  private static final class EmptyRepositoryDao extends RepositoryDaoAdapter {
    EmptyRepositoryDao() {
      super(null, null);
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return Optional.empty();
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return List.of();
    }
  }
}
