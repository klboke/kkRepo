package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;

final class BrowseDownloadUrls {
  private BrowseDownloadUrls() {
  }

  static String asset(RepositoryRecord visible, String path) {
    String prefix = "/repository/" + visible.name() + "/";
    return prefix + path;
  }
}
