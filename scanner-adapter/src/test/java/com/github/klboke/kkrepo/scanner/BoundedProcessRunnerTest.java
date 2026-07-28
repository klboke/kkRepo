package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void returnsBoundedOutputAndSanitizesNonZeroProcessErrors() throws Exception {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setWorkDirectory(directory);
    properties.setVulnerabilityDatabaseDirectory(directory.resolve("db"));
    BoundedProcessRunner runner = new BoundedProcessRunner(properties);
    Path output = directory.resolve("success.out");

    BoundedProcessRunner.Result success = runner.run(
        List.of("/bin/sh", "-c", "printf success"),
        directory,
        output,
        Duration.ofSeconds(2),
        Map.of("SYFT_LOG_QUIET", "true", "UNSAFE_SECRET", "must-not-be-forwarded"));

    assertEquals(0, success.exitCode());
    assertEquals(7, success.outputBytes());
    assertEquals("success", Files.readString(output));

    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> runner.run(
            List.of("/bin/sh", "-c", "printf 'bad\\nerror\\t' >&2; exit 7"),
            directory,
            directory.resolve("failure.out"),
            Duration.ofSeconds(2),
            Map.of()));
    assertEquals("SCANNER_PROCESS_FAILED", failure.code());
    assertTrue(failure.getMessage().contains("exit 7"));
    assertFalseControlCharacters(failure.getMessage());
  }

  @Test
  void handlesTimeoutInvalidCommandAndProcessIo() {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    BoundedProcessRunner runner = new BoundedProcessRunner(properties);

    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(
            List.of(), directory, directory.resolve("empty"), Duration.ofSeconds(1), Map.of()));
    ScannerRequestException timeout = assertThrows(
        ScannerRequestException.class,
        () -> runner.run(
            List.of("/bin/sh", "-c", "sleep 2"),
            directory,
            directory.resolve("timeout"),
            Duration.ofMillis(50),
            Map.of()));
    assertEquals("SCANNER_TIMEOUT", timeout.code());
    ScannerRequestException io = assertThrows(
        ScannerRequestException.class,
        () -> runner.run(
            List.of(directory.resolve("missing-executable").toString()),
            directory,
            directory.resolve("missing"),
            Duration.ofSeconds(1),
            Map.of()));
    assertEquals("SCANNER_PROCESS_IO", io.code());
  }

  @Test
  void interruptionTerminatesTheActiveScannerProcessTree() throws Exception {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    BoundedProcessRunner runner = new BoundedProcessRunner(properties);
    Path pidFile = directory.resolve("scanner.pid");
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread scanThread = Thread.ofPlatform().start(() -> {
      try {
        runner.run(
            List.of(
                "/bin/sh",
                "-c",
                "sleep 30 & child=$!; printf '%s %s' $$ \"$child\" > '"
                    + pidFile + "'; wait"),
            directory,
            directory.resolve("interrupted.out"),
            Duration.ofSeconds(30),
            Map.of());
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    for (int attempt = 0; attempt < 100 && !Files.exists(pidFile); attempt++) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(pidFile));
    List<Long> processIds = java.util.Arrays.stream(Files.readString(pidFile).split(" "))
        .map(Long::parseLong)
        .toList();

    scanThread.interrupt();
    scanThread.join(5000);

    assertFalse(scanThread.isAlive());
    ScannerRequestException interrupted =
        (ScannerRequestException) failure.get();
    assertEquals("SCANNER_INTERRUPTED", interrupted.code());
    for (Long processId : processIds) {
      assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false));
    }
  }

  @Test
  void readsVersionAndRejectsOversizedFiles() throws Exception {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setWorkDirectory(directory);
    BoundedProcessRunner runner = new BoundedProcessRunner(properties);

    assertEquals(
        "{\"version\":\"1\"}\n",
        new String(runner.versionOutput(
            "/bin/echo", List.of("{\"version\":\"1\"}"))));
    assertArrayEquals(
        new byte[0],
        BoundedProcessRunner.readBounded(directory.resolve("absent"), 10));

    Path oversized = directory.resolve("oversized");
    Files.write(oversized, new byte[11]);
    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> BoundedProcessRunner.readBounded(oversized, 10));
    assertEquals("SCANNER_OUTPUT_TOO_LARGE", failure.code());

    byte[] stderr = {1, 2};
    BoundedProcessRunner.Result result = new BoundedProcessRunner.Result(0, 0, stderr);
    stderr[0] = 9;
    assertArrayEquals(new byte[] {1, 2}, result.stderr());
    assertArrayEquals(new byte[0], new BoundedProcessRunner.Result(0, 0, null).stderr());
  }

  private static void assertFalseControlCharacters(String value) {
    assertTrue(value.chars().noneMatch(character ->
        character == '\n' || character == '\r' || character == '\t'));
  }
}
