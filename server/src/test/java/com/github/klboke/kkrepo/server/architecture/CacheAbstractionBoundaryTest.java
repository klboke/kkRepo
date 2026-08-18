package com.github.klboke.kkrepo.server.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CacheAbstractionBoundaryTest {
  private static final String CAFFEINE_PACKAGE = "com.github.benmanes.caffeine";

  /**
   * Audited migration debt. New entries are forbidden; follow-up cache migration work must remove
   * entries as each class moves to LocalCacheFactory.
   */
  private static final Set<String> LEGACY_DIRECT_CAFFEINE_FILES = Set.of(
      "server/src/main/java/com/github/klboke/kkrepo/server/alpine/AlpinePublishedSnapshotCache.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/alpine/AlpineRepositorySettings.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/apt/AptPublishedSnapshotCache.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/apt/AptRepositorySettings.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/cache/AssetMetadataCache.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/cache/JdbcVersionWatermark.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/cache/NexusLikeCacheController.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/conan/ConanRemoteClient.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/maven/BlobStorageRegistry.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/maven/RepositoryRuntimeRegistry.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/nativeimage/CaffeineRuntimeHints.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/npm/NpmReleaseAgeCache.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/proxy/ProxiedHttpClientFactory.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/security/BasicAuthCache.java",
      "server/src/main/java/com/github/klboke/kkrepo/server/security/SecurityAuthorizationCache.java",
      "storage-s3/src/main/java/com/github/klboke/kkrepo/storage/s3/OssClientFactory.java",
      "storage-s3/src/main/java/com/github/klboke/kkrepo/storage/s3/S3ClientFactory.java");

  @Test
  void productionCodeDoesNotAddDirectCacheBackendDependencies() throws IOException {
    Path root = repositoryRoot();
    Set<String> directBackendUsers = new LinkedHashSet<>();
    try (Stream<Path> modules = Files.list(root)) {
      for (Path module : modules.filter(Files::isDirectory).toList()) {
        if (module.getFileName().toString().equals("cache")) {
          continue;
        }
        Path sourceRoot = module.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
          for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
            if (Files.readString(file).contains(CAFFEINE_PACKAGE)) {
              directBackendUsers.add(root.relativize(file).toString());
            }
          }
        }
      }
    }

    assertEquals(LEGACY_DIRECT_CAFFEINE_FILES, directBackendUsers,
        "Business caches must use cache-module abstractions. Remove migrated legacy entries; "
            + "do not add new direct Caffeine users.");
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("cache"))
          && Files.isDirectory(current.resolve("server"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate the repository root from "
        + System.getProperty("user.dir"));
  }
}
