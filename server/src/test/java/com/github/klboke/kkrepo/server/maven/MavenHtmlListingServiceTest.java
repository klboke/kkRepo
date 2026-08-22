package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.BrowseNodeDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.time.Instant;
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
        new AssetDaoAdapter(null, null),
        new ComponentDaoAdapter(null, null));

    String html = service.renderBrowse("maven-hosted", "").orElseThrow();

    assertTrue(html.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;.jar"));
    assertTrue(html.contains("/repository/maven-hosted/com/example/&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;.jar"));
    assertFalse(html.contains("<script>alert(\"x\")</script>"));
  }

  @Test
  void composerBrowseHtmlHidesInternalCachePaths() {
    MavenHtmlListingService service = new MavenHtmlListingService(
        new FakeRepositoryDao(RepositoryFormat.COMPOSER, RepositoryType.PROXY, "composer-proxy"),
        new ComposerBrowseNodeDao(),
        new AssetDaoAdapter(null, null),
        new ComponentDaoAdapter(null, null));

    String html = service.renderBrowse("composer-proxy", "").orElseThrow();

    assertFalse(html.contains("_composer"));
    assertTrue(html.contains("symfony/"));
    assertTrue(html.contains("/repository/composer-proxy/packages.json"));
    assertTrue(service.renderBrowse("composer-proxy", "_composer").isEmpty());
  }

  @Test
  void terraformBrowseHtmlHidesInternalCachePaths() {
    MavenHtmlListingService service = new MavenHtmlListingService(
        new FakeRepositoryDao(RepositoryFormat.TERRAFORM, RepositoryType.PROXY, "terraform-proxy"),
        new TerraformBrowseNodeDao(),
        new AssetDaoAdapter(null, null),
        new ComponentDaoAdapter(null, null));

    String html = service.renderBrowse("terraform-proxy", "").orElseThrow();

    assertFalse(html.contains(".terraform"));
    assertTrue(html.contains("v1/"));
    assertTrue(service.renderBrowse("terraform-proxy", ".terraform/routes").isEmpty());
  }

  @Test
  void huggingFaceBrowseHtmlLinksToTheCanonicalResolveRoute() {
    String commit = "a".repeat(40);
    String parent = "org/model/" + commit;
    String browsePath = parent + "/model.safetensors";
    String storagePath = "org/model/resolve/" + commit + "/model.safetensors";
    BrowseNodeDao browseNodes = mock(BrowseNodeDao.class);
    when(browseNodes.listChildren(10L, parent)).thenReturn(List.of(
        new BrowseNodeDao.BrowseChild(
            1L, browsePath, "model.safetensors", 3, 11L, 12L, 1024L,
            "application/octet-stream", "b".repeat(40), Instant.EPOCH, false, true)));
    AssetDao assets = mock(AssetDao.class);
    when(assets.findAssetsByIds(List.of(11L))).thenReturn(Map.of(
        11L,
        new AssetRecord(
            11L, 10L, 12L, 13L, RepositoryFormat.HUGGINGFACE,
            storagePath, null, "model.safetensors", "huggingface-model-file",
            "application/octet-stream", 1024L, null, Instant.EPOCH, Map.of())));
    MavenHtmlListingService service = new MavenHtmlListingService(
        new FakeRepositoryDao(
            RepositoryFormat.HUGGINGFACE, RepositoryType.PROXY, "huggingface-proxy"),
        browseNodes,
        assets,
        new ComponentDaoAdapter(null, null));

    String html = service.renderBrowse("hf-proxy", parent).orElseThrow();

    assertTrue(html.contains(
        "href=\"/repository/hf-proxy/" + storagePath + "\""));
    assertFalse(html.contains(
        "href=\"/repository/hf-proxy/" + browsePath + "\""));
  }

  @Test
  void rBrowseHtmlKeepsTheNexusProjectionAndLinksToTheCanonicalPackageRoute() {
    String parent = "src/contrib/demo/1.2.3";
    String browsePath = parent + "/demo_1.2.3.tar.gz";
    String storagePath = "src/contrib/demo_1.2.3.tar.gz";
    BrowseNodeDao browseNodes = mock(BrowseNodeDao.class);
    when(browseNodes.listChildren(10L, parent)).thenReturn(List.of(
        new BrowseNodeDao.BrowseChild(
            1L, browsePath, "demo_1.2.3.tar.gz", 4, 11L, 12L, 1024L,
            "application/x-gzip", "b".repeat(40), Instant.EPOCH, false, true)));
    AssetDao assets = mock(AssetDao.class);
    when(assets.findAssetsByIds(List.of(11L))).thenReturn(Map.of(
        11L,
        new AssetRecord(
            11L, 10L, 12L, 13L, RepositoryFormat.R,
            storagePath, null, "demo_1.2.3.tar.gz", "r-source-package",
            "application/x-gzip", 1024L, null, Instant.EPOCH, Map.of())));
    MavenHtmlListingService service = new MavenHtmlListingService(
        new FakeRepositoryDao(RepositoryFormat.R, RepositoryType.HOSTED, "r-hosted"),
        browseNodes,
        assets,
        new ComponentDaoAdapter(null, null));

    String html = service.renderBrowse("r-hosted", parent).orElseThrow();

    assertTrue(html.contains(
        "href=\"/repository/r-hosted/" + storagePath + "\""));
    assertFalse(html.contains(
        "href=\"/repository/r-hosted/" + browsePath + "\""));
  }

  private static class FakeRepositoryDao extends RepositoryDaoAdapter {
    private final RepositoryFormat format;
    private final RepositoryType type;
    private final String recipe;

    FakeRepositoryDao() {
      this(RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven2-hosted");
    }

    FakeRepositoryDao(RepositoryFormat format, RepositoryType type, String recipe) {
      super(null, null);
      this.format = format;
      this.type = type;
      this.recipe = recipe;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return Optional.of(new RepositoryRecord(
          10L,
          name,
          format,
          type,
          recipe,
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

  private static class ComposerBrowseNodeDao extends BrowseNodeDaoAdapter {
    ComposerBrowseNodeDao() {
      super(null);
    }

    @Override
    public List<BrowseChild> listChildren(long repositoryId, String parentPath) {
      return List.of(
          directory(1L, "_composer"),
          directory(2L, "symfony"),
          new BrowseChild(
              3L,
              "packages.json",
              "packages.json",
              0,
              12L,
              null,
              888L,
              "application/json",
              "sha1",
              null,
              false,
              true));
    }

    private static BrowseChild directory(long id, String path) {
      return new BrowseChild(
          id, path, path, 0, null, null, null, null, null, null, true, true);
    }
  }

  private static class TerraformBrowseNodeDao extends BrowseNodeDaoAdapter {
    TerraformBrowseNodeDao() {
      super(null);
    }

    @Override
    public List<BrowseChild> listChildren(long repositoryId, String parentPath) {
      return List.of(
          directory(1L, ".terraform"),
          directory(2L, "v1"));
    }

    private static BrowseChild directory(long id, String path) {
      return new BrowseChild(
          id, path, path, 0, null, null, null, null, null, null, true, true);
    }
  }

  private static class FakeBrowseNodeDao extends BrowseNodeDaoAdapter {
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
