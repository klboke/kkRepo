package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PypiErrorAdviceTest {

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
