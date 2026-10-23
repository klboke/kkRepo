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
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RepositoryListingPrefixTest {
  @ParameterizedTest
  @EnumSource(value = RepositoryFormat.class, names = {"NPM", "PYPI", "HELM"})
  void projectedDownloadLinksKeepStoragePathsUnderTheConfiguredPrefix(RepositoryFormat format) {
    var request = new MockHttpServletRequest();
    new RepositoryUrlPrefix("/artifacts/content").onStartup(request.getServletContext());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      var repositories = mock(RepositoryDao.class);
      when(repositories.findByName("hosted")).thenReturn(Optional.of(new RepositoryRecord(
          10L, "hosted", format, RepositoryType.HOSTED, "unused", true, 1L,
          null, null, "RELEASE", "STRICT", "ALLOW", true, Map.of())));
      var nodes = mock(BrowseNodeDao.class);
      var assets = mock(AssetDao.class);
      String path;
      String browsePath = "";
      if (format == RepositoryFormat.NPM) {
        browsePath = "pkg";
        path = "pkg/-/pkg-1.0.0.tgz";
        when(nodes.listChildren(10L, "pkg")).thenReturn(List.of(new BrowseNodeDao.BrowseChild(
            1L, "pkg/-", "-", 1, null, null, null, null, null, null, true, true)));
        when(nodes.listChildren(10L, "pkg/-")).thenReturn(List.of(leaf(path)));
      } else if (format == RepositoryFormat.PYPI) {
        path = "packages/demo-1.0.tar.gz";
        when(nodes.listChildren(10L, "packages")).thenReturn(List.of(leaf(path)));
      } else {
        path = "index.yaml";
        when(assets.findAssetByPath(10L, path)).thenReturn(Optional.of(new AssetRecord(
            11L, 10L, null, 12L, format, path, null, path, "index", "text/yaml",
            20L, null, Instant.EPOCH, Map.of())));
      }
      var service = new MavenHtmlListingService(repositories, nodes, assets, mock(ComponentDao.class));
      String html = service.renderBrowse("hosted", browsePath).orElseThrow();
      assertTrue(html.contains("href=\"/artifacts/content/hosted/" + path + "\""), html);
      assertFalse(html.contains("/repository/hosted/"), html);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private static BrowseNodeDao.BrowseChild leaf(String path) {
    return new BrowseNodeDao.BrowseChild(2L, path, path.substring(path.lastIndexOf('/') + 1),
        2, 11L, null, 20L, "application/octet-stream", null, Instant.EPOCH, false, true);
  }
}
