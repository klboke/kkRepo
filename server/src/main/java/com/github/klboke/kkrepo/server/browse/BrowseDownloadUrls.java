package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;

final class BrowseDownloadUrls {
  private BrowseDownloadUrls() {
  }

  static String asset(RepositoryRecord visible, String path) {
    String prefix = RepositoryUrlPrefix.currentPath() + visible.name() + "/";
    return prefix + path;
  }
}
