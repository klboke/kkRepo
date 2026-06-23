package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MavenHtmlListingServiceTest {

  @Test
  void browseHtmlEscapesDisplayNamesAndHrefAttributes() {
    MavenHtmlListingService service = new MavenHtmlListingService(
        new FakeRepositoryDao(),
        new FakeBrowseNodeDao(),
        new AssetDao(null, null),
        new ComponentDao(null, null));

    String html = service.renderBrowse("maven-hosted", "").orElseThrow();

    assertTrue(html.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;.jar"));
    assertTrue(html.contains("/repository/maven-hosted/com/example/&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;.jar"));
    assertFalse(html.contains("<script>alert(\"x\")</script>"));
  }

  private static class FakeRepositoryDao extends RepositoryDao {
    FakeRepositoryDao() {
      super(null, null);
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return Optional.of(new RepositoryRecord(
          10L,
          name,
          RepositoryFormat.MAVEN2,
          RepositoryType.HOSTED,
          "maven2-hosted",
          true,
          1L,
          null,
          null,
          "RELEASE",
          "STRICT",
          "ALLOW",
          true,
          Map.of()));
    }
  }

  private static class FakeBrowseNodeDao extends BrowseNodeDao {
    FakeBrowseNodeDao() {
      super(null);
    }

    @Override
    public List<BrowseChild> listChildren(long repositoryId, String parentPath) {
      return List.of(new BrowseChild(
          1L,
          "com/example/<script>alert(\"x\")</script>.jar",
          "<script>alert(\"x\")</script>.jar",
          0,
          11L,
          null,
          123L,
          "application/java-archive",
          "sha1",
          null,
          false,
          true));
    }
  }
}
