package com.github.klboke.kkrepo.server.migration;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.server.pub.PubRepositoryDataMigrationWriter;
import com.github.klboke.kkrepo.server.ansible.AnsibleGalaxyRepositoryDataMigrationWriter;
import com.github.klboke.kkrepo.server.swift.SwiftRepositoryDataMigrationWriter;
import com.github.klboke.kkrepo.server.terraform.TerraformRepositoryDataMigrationWriter;
import java.util.Locale;

final class RepositoryDataMigrationPaths {
  private static final String[] MAVEN_CHECKSUM_SUFFIXES = {
      ".md5", ".sha1", ".sha256", ".sha512"
  };

  private RepositoryDataMigrationPaths() {
  }

  static boolean shouldDiscoverAsset(RepositoryFormat format, String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    if (format == RepositoryFormat.MAVEN2) {
      return !isMavenChecksumPath(path);
    }
    if (format == RepositoryFormat.DOCKER) {
      return path.contains("/manifests/") || path.contains("/blobs/");
    }
    if (format == RepositoryFormat.PUB) {
      return PubRepositoryDataMigrationWriter.isMigratablePubPath(path);
    }
    if (format == RepositoryFormat.TERRAFORM) {
      return TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(path);
    }
    if (format == RepositoryFormat.SWIFT) {
      return SwiftRepositoryDataMigrationWriter.isMigratableSwiftPath(path);
    }
    if (format == RepositoryFormat.ANSIBLEGALAXY) {
      return AnsibleGalaxyRepositoryDataMigrationWriter.isMigratableAnsiblePath(path);
    }
    return true;
  }

  static boolean shouldGenerateMavenChecksumSiblings(MavenPath path) {
    return path != null
        && !path.isHash()
        && !isMavenChecksumPath(path.path());
  }

  static boolean isMavenChecksumPath(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    String normalized = path.toLowerCase(Locale.ROOT);
    for (String suffix : MAVEN_CHECKSUM_SUFFIXES) {
      if (normalized.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }
}
