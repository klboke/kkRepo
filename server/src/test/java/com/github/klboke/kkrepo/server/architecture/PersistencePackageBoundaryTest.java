package com.github.klboke.kkrepo.server.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PersistencePackageBoundaryTest {
  private static final List<String> UPPER_LAYER_FORBIDDEN_IMPORTS = List.of(
      "com.github.klboke.kkrepo.persistence.jdbc.internal",
      "com.github.klboke.kkrepo.persistence.jdbc.spi",
      "com.github.klboke.kkrepo.persistence.mysql",
      "com.github.klboke.kkrepo.persistence.postgresql",
      "org.springframework.jdbc",
      "org.postgresql",
      "com.mysql");

  @Test
  void upperLayersDependOnlyOnPersistenceApi() throws IOException {
    Path root = repositoryRoot();
    List<Path> sourceRoots = new ArrayList<>(List.of(
        root.resolve("server/src/main/java"),
        root.resolve("server/src/test/java"),
        root.resolve("migration-nexus/src/main/java"),
        root.resolve("migration-nexus/src/test/java")));
    try (Stream<Path> modules = Files.list(root)) {
      modules.filter(path -> path.getFileName().toString().startsWith("protocol-"))
          .map(path -> path.resolve("src/main/java"))
          .filter(Files::isDirectory)
          .forEach(sourceRoots::add);
    }

    assertNoForbiddenImports(sourceRoots, importName ->
        UPPER_LAYER_FORBIDDEN_IMPORTS.stream().anyMatch(importName::startsWith));
  }

  @Test
  void publicApiDoesNotExposeJdbcOrBackendTypes() throws IOException {
    Path api = repositoryRoot().resolve(
        "persistence-jdbc/src/main/java/com/github/klboke/kkrepo/persistence/jdbc/api");
    assertNoForbiddenImports(List.of(api), importName ->
        importName.startsWith("com.github.klboke.kkrepo.persistence.jdbc.internal")
            || importName.startsWith("com.github.klboke.kkrepo.persistence.jdbc.spi")
            || importName.startsWith("com.github.klboke.kkrepo.persistence.mysql")
            || importName.startsWith("com.github.klboke.kkrepo.persistence.postgresql")
            || importName.startsWith("org.springframework.jdbc")
            || importName.startsWith("org.postgresql")
            || importName.startsWith("com.mysql"));
  }

  @Test
  void jdbcAndBackendDependencyDirectionsStayAcyclic() throws IOException {
    Path root = repositoryRoot();
    Path internal = root.resolve(
        "persistence-jdbc/src/main/java/com/github/klboke/kkrepo/persistence/jdbc/internal");
    assertNoForbiddenImports(List.of(internal), importName ->
        importName.startsWith("com.github.klboke.kkrepo.persistence.mysql")
            || importName.startsWith("com.github.klboke.kkrepo.persistence.postgresql"));

    List<Path> backendRoots = new ArrayList<>();
    try (Stream<Path> modules = Files.list(root)) {
      modules.filter(path -> path.getFileName().toString().startsWith("persistence-"))
          .filter(path -> !path.getFileName().toString().equals("persistence-jdbc"))
          .map(path -> path.resolve("src/main/java"))
          .filter(Files::isDirectory)
          .forEach(backendRoots::add);
    }
    assertNoForbiddenImports(backendRoots, importName ->
        importName.startsWith("com.github.klboke.kkrepo.persistence.jdbc.internal"));
  }

  @Test
  void sharedJdbcImplementationContainsNoDatabaseBranchesOrExtractedMySqlSql() throws IOException {
    Path internal = repositoryRoot().resolve(
        "persistence-jdbc/src/main/java/com/github/klboke/kkrepo/persistence/jdbc/internal");
    List<String> forbiddenFragments = List.of(
        "DatabaseType.",
        "LAST_INSERT_ID(",
        "MATCH(",
        "AGAINST (",
        "INSERT IGNORE",
        "TIMESTAMPDIFF(",
        "JSON_UNQUOTE(",
        "JSON_EXTRACT(",
        "JSON_SET(");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(internal)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        int lineNumber = 0;
        for (String line : Files.readAllLines(file)) {
          lineNumber++;
          for (String fragment : forbiddenFragments) {
            if (line.contains(fragment)) {
              violations.add(repositoryRoot().relativize(file) + ":" + lineNumber
                  + " contains " + fragment);
            }
          }
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> "Database-specific JDBC fragments:\n"
        + String.join("\n", violations));
  }

  private static void assertNoForbiddenImports(
      List<Path> sourceRoots,
      Predicate<String> forbidden) throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path sourceRoot : sourceRoots) {
      if (!Files.isDirectory(sourceRoot)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(sourceRoot)) {
        for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
          int lineNumber = 0;
          for (String line : Files.readAllLines(file)) {
            lineNumber++;
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
              continue;
            }
            String importName = trimmed.substring("import ".length())
                .replace("static ", "")
                .replace(";", "")
                .trim();
            if (forbidden.test(importName)) {
              violations.add(repositoryRoot().relativize(file) + ":" + lineNumber
                  + " imports " + importName);
            }
          }
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> "Forbidden persistence imports:\n"
        + String.join("\n", violations));
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("persistence-jdbc"))
          && Files.isDirectory(current.resolve("server"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate the repository root from "
        + System.getProperty("user.dir"));
  }
}
