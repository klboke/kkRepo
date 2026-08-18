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
  private static final String CAFFEINE_GROUP_ID =
      "<groupId>com.github.ben-manes.caffeine</groupId>";

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

    assertEquals(Set.of(), directBackendUsers,
        "Production caches outside the cache module must use cache-module abstractions; "
            + "direct Caffeine dependencies are forbidden.");
  }

  @Test
  void businessModulesDoNotDeclareDirectCacheBackendDependencies() throws IOException {
    Path root = repositoryRoot();
    Set<String> directBackendDependencies = new LinkedHashSet<>();
    try (Stream<Path> modules = Files.list(root)) {
      for (Path module : modules.filter(Files::isDirectory).toList()) {
        if (module.getFileName().toString().equals("cache")) {
          continue;
        }
        Path pom = module.resolve("pom.xml");
        if (Files.isRegularFile(pom) && Files.readString(pom).contains(CAFFEINE_GROUP_ID)) {
          directBackendDependencies.add(root.relativize(pom).toString());
        }
      }
    }

    assertEquals(Set.of(), directBackendDependencies,
        "Business modules must depend on the cache module rather than a cache backend directly.");
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
