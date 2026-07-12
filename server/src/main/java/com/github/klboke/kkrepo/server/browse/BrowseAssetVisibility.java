package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryFormat;

public final class BrowseAssetVisibility {
  private static final String COMPOSER_INTERNAL_PREFIX = "_composer";

  private BrowseAssetVisibility() {
  }

  public static boolean hidden(RepositoryFormat format, String path) {
    return format == RepositoryFormat.COMPOSER
        && path != null
        && (COMPOSER_INTERNAL_PREFIX.equals(path)
            || path.startsWith(COMPOSER_INTERNAL_PREFIX + "/"));
  }
}
