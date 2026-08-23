package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import com.github.klboke.kkrepo.server.RepositoryProtocolControllerTestSupport;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ComposerRepositoryControllerPathTest {
  @Test
  void getUsesFilterCanonicalPathWithoutDecodingItAgain() {
    RecordingHostedService hosted = new RecordingHostedService();
    RepositoryProtocolController controller = controller(hosted);
    MockHttpServletRequest request = request(
        "/repository/composer/company/example/1.0.0/"
            + "company-example-1.0.0%252Bbuild.zip");
    String canonicalPath =
        "company/example/1.0.0/company-example-1.0.0%2Bbuild.zip";
    request.setAttribute(
        RepositorySecurityFilter.NORMALIZED_REPOSITORY_PATH_ATTRIBUTE,
        canonicalPath);

    controller.get("composer", request);

    assertEquals(canonicalPath, hosted.requestedPath.distPath());
  }

  @Test
  void headCanonicalizesDirectRequestOnceWhenFilterIsAbsent() {
    RecordingHostedService hosted = new RecordingHostedService();
    RepositoryProtocolController controller = controller(hosted);

    controller.head("composer", request(
        "/repository/composer/company/example/1.0.0/"
            + "company-example-1.0.0%2Bbuild.zip"));

    assertEquals(
        "company/example/1.0.0/company-example-1.0.0+build.zip",
        hosted.requestedPath.distPath());
  }

  private static RepositoryProtocolController controller(ComposerHostedService hosted) {
    return RepositoryProtocolControllerTestSupport.controller(
        new RepositoryRuntimeRegistry(new SingleRepositoryDao(), 0),
        null, null, null,
        null, null,
        null, null,
        null,
        null, null, null, null, null,
        null, null, null,
        null, null, null,
        null, null, null,
        hosted, null, null,
        null, null, null,
        null, null, null,
        new ObjectMapper(),
        new ForwardedHeaderPolicy(""));
  }

  private static MockHttpServletRequest request(String uri) {
    return new MockHttpServletRequest("GET", uri);
  }

  private static final class RecordingHostedService extends ComposerHostedService {
    private ComposerPath requestedPath;

    private RecordingHostedService() {
      super(null, null, null, null, null);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime,
        ComposerPath path,
        String baseUrl,
        String filter,
        boolean headOnly) {
      requestedPath = path;
      return MavenResponse.noBody(200, 0, "application/zip", null, null);
    }
  }

  private static final class SingleRepositoryDao extends RepositoryDaoAdapter {
    private SingleRepositoryDao() {
      super(null, null);
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      if (!"composer".equals(name)) return Optional.empty();
      return Optional.of(new RepositoryRecord(
          1L,
          name,
          RepositoryFormat.COMPOSER,
          RepositoryType.HOSTED,
          "composer-hosted",
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
}
