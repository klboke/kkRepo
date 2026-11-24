package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationPreflightJavascriptTest {
  @TempDir Path directory;

  @Test
  void migrationPreflightJavascriptContract() throws Exception {
    assumeTrue(nodeIsAvailable(), "Node.js is required for admin-ui JavaScript tests");
    // Node writes to a file, not a pipe: an undrained pipe can block it until the timeout.
    Path output = directory.resolve("migration-preflight.log");
    Process process = new ProcessBuilder("node", "--test", "src/test/js/migration-preflight.test.js")
        .redirectErrorStream(true)
        .redirectOutput(output.toFile())
        .start();

    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      fail("Timed out running admin-ui JavaScript tests:\n" + Files.readString(output, StandardCharsets.UTF_8));
    }
    assertEquals(0, process.exitValue(), () -> read(output));
  }

  private static String read(Path output) {
    try {
      return Files.readString(output, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      return "unable to read Node output: " + exception;
    }
  }

  private static boolean nodeIsAvailable() {
    try {
      Process process = new ProcessBuilder("node", "--version")
          .redirectErrorStream(true)
          .start();
      boolean exited = process.waitFor(5, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException exception) {
      return false;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
