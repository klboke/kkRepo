package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;

public final class BrowseAssetVisibility {
  private static final String COMPOSER_INTERNAL_PREFIX = "_composer";
  private static final String TERRAFORM_INTERNAL_PREFIX = ".terraform";
  private static final String SWIFT_INTERNAL_PREFIX = ".swift";
  private static final String ANSIBLE_INTERNAL_PREFIX = ".ansible";

  private BrowseAssetVisibility() {
  }

  public static boolean hidden(RepositoryFormat format, String path) {
    if (path == null) {
      return false;
    }
    return (format == RepositoryFormat.COMPOSER && under(path, COMPOSER_INTERNAL_PREFIX))
        || (format == RepositoryFormat.TERRAFORM
            && (under(path, TERRAFORM_INTERNAL_PREFIX) || terraformProviderInternal(path)))
        || (format == RepositoryFormat.SWIFT && under(path, SWIFT_INTERNAL_PREFIX))
        || (format == RepositoryFormat.ANSIBLEGALAXY
            && (under(path, ANSIBLE_INTERNAL_PREFIX) || ansibleArtifactInternal(path)));
  }

  private static boolean ansibleArtifactInternal(String path) {
    String prefix = AnsibleGalaxyPathParser.ARTIFACT_BASE;
    return path.equals(prefix.substring(0, prefix.length() - 1)) || path.startsWith(prefix);
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
