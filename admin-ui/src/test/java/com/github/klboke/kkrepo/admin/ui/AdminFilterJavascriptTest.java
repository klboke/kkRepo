package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdminFilterJavascriptTest {
  @Test
  void wildcardFilterJavascriptContract() throws Exception {
    assumeTrue(nodeIsAvailable(), "Node.js is required for admin-ui JavaScript tests");

    Process process = new ProcessBuilder(
        "node",
        "--test",
        Path.of("src/test/js/admin-filter.test.js").toString())
        .redirectErrorStream(true)
        .start();

    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      fail("Timed out running admin-ui JavaScript tests:\n" + output);
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), () -> output);
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
