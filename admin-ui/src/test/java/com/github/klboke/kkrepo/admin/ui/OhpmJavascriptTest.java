package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OhpmJavascriptTest {
  @TempDir Path directory;

  @Test
  void importAndSigningKeyManagementContracts() throws Exception {
    assumeTrue(nodeIsAvailable(), "Node.js is required for admin-ui JavaScript tests");
    Path output = directory.resolve("ohpm-ui.log");
    Process process = new ProcessBuilder("node", "--test",
        "src/test/js/ohpm-import.test.js", "src/test/js/ohpm-login.test.js")
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    assertTrue(finished, "OHPM JavaScript tests timed out");
    assertEquals(0, process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
  }

  private static boolean nodeIsAvailable() throws InterruptedException {
    try {
      Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
      boolean finished = process.waitFor(5, TimeUnit.SECONDS);
      if (!finished) process.destroyForcibly();
      return finished && process.exitValue() == 0;
    } catch (IOException unavailable) {
      return false;
    }
  }
}
