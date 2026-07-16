package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryFormat;

public final class BrowseAssetVisibility {
  private static final String COMPOSER_INTERNAL_PREFIX = "_composer";
  private static final String TERRAFORM_INTERNAL_PREFIX = ".terraform";

  private BrowseAssetVisibility() {
  }

  public static boolean hidden(RepositoryFormat format, String path) {
    if (path == null) {
      return false;
    }
    return (format == RepositoryFormat.COMPOSER && under(path, COMPOSER_INTERNAL_PREFIX))
        || (format == RepositoryFormat.TERRAFORM
            && (under(path, TERRAFORM_INTERNAL_PREFIX) || terraformProviderInternal(path)));
  }

  private static boolean terraformProviderInternal(String path) {
    String[] parts = path.split("/");
    return parts.length >= 6
        && "v1".equals(parts[0])
        && "providers".equals(parts[1])
        && ("package".equals(parts[5]) || parts[5].startsWith("metadata-"));
  }

  private static boolean under(String path, String prefix) {
    return prefix.equals(path) || path.startsWith(prefix + "/");
  }
}
