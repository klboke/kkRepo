package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

class FrontendJavascriptTest {
  @Test
  @Timeout(120)
  void runAllJavascriptSuites(@TempDir Path temp) throws Exception {
    List<String> command = new ArrayList<>(List.of("node", "--test"));
    try (var files = Files.list(Path.of("src/test/js"))) {
      var suites = files.filter(file -> file.toString().endsWith(".test.js")).sorted().toList();
      assertFalse(suites.isEmpty());
      suites.forEach(file -> command.add(file.toString()));
    }
    Path output = temp.resolve("node-tests.log");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertEquals(0, process.waitFor(), () -> readOutput(output));
    } finally {
      process.destroyForcibly();
    }
  }
  private static String readOutput(Path output) {
    try {
      return Files.readString(output);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
