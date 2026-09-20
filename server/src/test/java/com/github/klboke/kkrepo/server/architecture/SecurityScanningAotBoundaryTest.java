package com.github.klboke.kkrepo.server.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SecurityScanningAotBoundaryTest {
  private static final String BUILD_TIME_CONDITION =
      "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

  @Test
  void runtimeSecurityScanningToggleDoesNotControlBeanPresence() throws IOException {
    Path sourceRoot = repositoryRoot().resolve(
        "server/src/main/java/com/github/klboke/kkrepo/server/securityscan");
    Set<String> conditionalBeans = new LinkedHashSet<>();
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        if (Files.readString(file).contains(BUILD_TIME_CONDITION)) {
          conditionalBeans.add(repositoryRoot().relativize(file).toString());
        }
      }
    }

    assertEquals(Set.of(), conditionalBeans,
        "Native images freeze property-based bean conditions during AOT processing; "
            + "security-scanning beans must remain registered and gate work at runtime.");
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("server"))
          && Files.isDirectory(current.resolve("security-scan"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate the repository root from "
        + System.getProperty("user.dir"));
  }
}
