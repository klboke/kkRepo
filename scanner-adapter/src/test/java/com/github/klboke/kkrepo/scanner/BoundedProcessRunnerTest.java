package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedProcessRunnerTest {
  @TempDir
  Path directory;

  @Test
  void terminatesAProcessWhileStdoutExceedsTheLimit() {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setMaxOutputBytes(1024);
    properties.setMaxStderrBytes(1024);
    BoundedProcessRunner runner = new BoundedProcessRunner(properties);
    Instant started = Instant.now();

    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> runner.run(
            List.of(
                "/bin/sh",
                "-c",
                "dd if=/dev/zero bs=2048 count=1 2>/dev/null; sleep 10"),
            directory,
            directory.resolve("stdout"),
            Duration.ofSeconds(10),
            Map.of()));

    assertEquals("SCANNER_OUTPUT_TOO_LARGE", failure.code());
    assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(3)) < 0);
  }

  @Test
  void terminatesAProcessWhileStderrExceedsTheLimit() {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setMaxOutputBytes(1024);
    properties.setMaxStderrBytes(1024);
    BoundedProcessRunner runner = new BoundedProcessRunner(properties);

    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> runner.run(
            List.of(
                "/bin/sh",
                "-c",
                "dd if=/dev/zero bs=2048 count=1 1>&2 2>/dev/null; sleep 10"),
            directory,
            directory.resolve("stdout"),
            Duration.ofSeconds(10),
            Map.of()));

    assertEquals("SCANNER_STDERR_TOO_LARGE", failure.code());
  }
}
