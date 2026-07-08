package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.pub.PubPath;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.pub.PubGroupService;
import com.github.klboke.kkrepo.server.pypi.PypiRepositoryController;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RepositoryContentControllerPubRoutingTest {
  @Test
  void pubArchivePackagesPathIsHandledByPubControllerNotPypiController() throws Exception {
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    repositories.repository(repository("pub-group", RepositoryFormat.PUB, RepositoryType.GROUP));
    RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(repositories, 0);
    CapturingPubGroupService pubGroup = new CapturingPubGroupService();
    RepositoryContentController contentController = new RepositoryContentController(
        registry,
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null,
        null, null,
        null, null, null,
        null, null, null,
        null, null, pubGroup,
        null, null, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""));
    PypiRepositoryController pypiController =
        new PypiRepositoryController(registry, null, null, null, null);
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(contentController, pypiController)
        .build();

    mvc.perform(get("/repository/pub-group/packages/demo_pkg/versions/1.0.0.tar.gz"))
        .andExpect(status().isOk())
        .andExpect(content().bytes("pub archive".getBytes(StandardCharsets.UTF_8)));
    mvc.perform(get("/repository/pub-group/api/archives/demo_pkg-1.0.0.tar.gz"))
        .andExpect(status().isOk());
    mvc.perform(get("/repository/pub-group/demo_pkg/1.0.0/demo_pkg-1.0.0.tar.gz"))
        .andExpect(status().isOk());
    mvc.perform(get("/repository/pub-group/demo_pkg/1.0.0/version.json"))
        .andExpect(status().isOk());
    assertEquals(List.of(
        "packages/demo_pkg/versions/1.0.0.tar.gz:demo_pkg:1.0.0",
        "api/archives/demo_pkg-1.0.0.tar.gz:demo_pkg:1.0.0",
        "demo_pkg/1.0.0/demo_pkg-1.0.0.tar.gz:demo_pkg:1.0.0",
        "demo_pkg/1.0.0/version.json:demo_pkg:1.0.0"),
        pubGroup.seen);
  }

  private static RepositoryRecord repository(String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L,
        name,
        format,
        type,
        format.name().toLowerCase() + "-" + type.name().toLowerCase(),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static final class FakeRepositoryDao extends RepositoryDao {
    private RepositoryRecord repository;

    FakeRepositoryDao() {
      super(null, null);
    }

    void repository(RepositoryRecord repository) {
      this.repository = repository;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return repository != null && repository.name().equals(name) ? Optional.of(repository) : Optional.empty();
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return List.of();
    }
  }

  private static final class CapturingPubGroupService extends PubGroupService {
    private final List<String> seen = new ArrayList<>();

    CapturingPubGroupService() {
      super(null, null, new ObjectMapper());
    }

    @Override
    public MavenResponse get(RepositoryRuntime runtime, PubPath path, String baseUrl, boolean headOnly) {
      if (path.kind() != PubPath.Kind.ARCHIVE && path.kind() != PubPath.Kind.VERSION_JSON) {
        throw new AssertionError("Expected Pub archive or version.json path but got " + path.kind());
      }
      if (!"demo_pkg".equals(path.packageName()) || !"1.0.0".equals(path.version())) {
        throw new AssertionError("Unexpected Pub archive coordinate: " + path.packageName() + " " + path.version());
      }
      seen.add(path.rawPath() + ":" + path.packageName() + ":" + path.version());
      if (path.kind() == PubPath.Kind.VERSION_JSON) {
        byte[] bytes = "{\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
        return MavenResponse.ok(() -> new ByteArrayInputStream(bytes), bytes.length,
            "application/json", null, null);
      }
      byte[] bytes = "pub archive".getBytes(StandardCharsets.UTF_8);
      return MavenResponse.ok(() -> new ByteArrayInputStream(bytes), bytes.length,
          "application/octet-stream", null, null);
    }
  }
}
