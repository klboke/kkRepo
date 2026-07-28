package com.github.klboke.kkrepo.scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Runs scanner binaries directly, without a shell, with bounded output and wall-clock timeout. */
@Component
public class BoundedProcessRunner {
  private final ScannerAdapterProperties properties;
  private final FileSizeReader fileSizeReader;

  @Autowired
  public BoundedProcessRunner(ScannerAdapterProperties properties) {
    this(properties, BoundedProcessRunner::defaultFileSize);
  }

  BoundedProcessRunner(
      ScannerAdapterProperties properties, FileSizeReader fileSizeReader) {
    this.properties = properties;
    this.fileSizeReader = fileSizeReader;
  }

  public Result run(
      List<String> command,
      Path workingDirectory,
      Path stdout,
      Duration timeout,
      Map<String, String> scannerEnvironment) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("Scanner command must not be empty");
    }
    Process process = null;
    try {
      Files.createDirectories(workingDirectory);
      Path stderr = workingDirectory.resolve("stderr.log");
      ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command))
          .directory(workingDirectory.toFile())
          .redirectOutput(stdout.toFile())
          .redirectError(stderr.toFile());
      Map<String, String> environment = builder.environment();
      String path = environment.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
      String sslCertFile = environment.get("SSL_CERT_FILE");
      String sslCertDir = environment.get("SSL_CERT_DIR");
      environment.clear();
      environment.put("PATH", path);
      environment.put("HOME", workingDirectory.toString());
      environment.put("TMPDIR", workingDirectory.toString());
      environment.put("SYFT_CHECK_FOR_APP_UPDATE", "false");
      environment.put("GRYPE_CHECK_FOR_APP_UPDATE", "false");
      environment.put("GRYPE_DB_AUTO_UPDATE", "false");
      environment.put(
          "GRYPE_DB_CACHE_DIR", properties.getVulnerabilityDatabaseDirectory().toString());
      if (sslCertFile != null) environment.put("SSL_CERT_FILE", sslCertFile);
      if (sslCertDir != null) environment.put("SSL_CERT_DIR", sslCertDir);
      if (scannerEnvironment != null) {
        scannerEnvironment.forEach((key, value) -> {
          if (key != null && value != null && allowedEnvironmentKey(key)) {
            environment.put(key, value);
          }
        });
      }
      process = builder.start();
      waitForBounded(process, stdout, stderr, timeout);
      byte[] stderrBytes = readBounded(stderr, properties.getMaxStderrBytes());
      if (process.exitValue() != 0) {
        throw processFailure(command, process.exitValue(), stderrBytes);
      }
      long outputBytes = fileSize(stdout);
      return new Result(process.exitValue(), outputBytes, stderrBytes);
    } catch (InterruptedException e) {
      terminateAfterFailure(process);
      Thread.currentThread().interrupt();
      throw new ScannerRequestException(
          "SCANNER_INTERRUPTED", "Scanner process was interrupted", 503, true, e);
    } catch (IOException e) {
      terminateAfterFailure(process);
      throw new ScannerRequestException(
          "SCANNER_PROCESS_IO", "Unable to start or inspect scanner process", 503, true, e);
    }
  }

  private void waitForBounded(
      Process process, Path stdout, Path stderr, Duration timeout)
      throws InterruptedException, IOException {
    long timeoutNanos;
    try {
      timeoutNanos = Math.max(1, timeout.toNanos());
    } catch (ArithmeticException e) {
      timeoutNanos = Long.MAX_VALUE;
    }
    long started = System.nanoTime();
    while (true) {
      if (exceeds(stdout, properties.getMaxOutputBytes())) {
        terminate(process);
        throw new ScannerRequestException(
            "SCANNER_OUTPUT_TOO_LARGE", "Scanner output exceeded its configured limit", 413, false);
      }
      if (exceeds(stderr, properties.getMaxStderrBytes())) {
        terminate(process);
        throw new ScannerRequestException(
            "SCANNER_STDERR_TOO_LARGE", "Scanner stderr exceeded its configured limit", 413, false);
      }
      long elapsed = System.nanoTime() - started;
      if (elapsed >= timeoutNanos) {
        terminate(process);
        throw new ScannerRequestException(
            "SCANNER_TIMEOUT", "Scanner process exceeded its time limit", 504, true);
      }
      long remainingMillis =
          Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeoutNanos - elapsed));
      if (process.waitFor(Math.min(100, remainingMillis), TimeUnit.MILLISECONDS)) {
        if (exceeds(stdout, properties.getMaxOutputBytes())) {
          throw new ScannerRequestException(
              "SCANNER_OUTPUT_TOO_LARGE",
              "Scanner output exceeded its configured limit",
              413,
              false);
        }
        if (exceeds(stderr, properties.getMaxStderrBytes())) {
          throw new ScannerRequestException(
              "SCANNER_STDERR_TOO_LARGE",
              "Scanner stderr exceeded its configured limit",
              413,
              false);
        }
        return;
      }
    }
  }

  private boolean exceeds(Path path, long maximum) throws IOException {
    return fileSize(path) > maximum;
  }

  private long fileSize(Path path) throws IOException {
    return fileSizeReader.size(path);
  }

  private static long defaultFileSize(Path path) throws IOException {
    return Files.exists(path) ? Files.size(path) : 0;
  }

  private static void terminate(Process process) throws InterruptedException {
    List<ProcessHandle> descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    process.destroy();
    boolean parentExited = process.waitFor(2, TimeUnit.SECONDS);
    if (!parentExited || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
      descendants.forEach(ProcessHandle::destroyForcibly);
      if (process.isAlive()) process.destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
    }
  }

  private static void terminateAfterFailure(Process process) {
    if (process == null) return;
    boolean interrupted = Thread.interrupted();
    List<ProcessHandle> descendants = process.descendants().toList();
    try {
      descendants.forEach(ProcessHandle::destroy);
      if (process.isAlive()) process.destroy();
      boolean cleanlyExited = false;
      try {
        boolean parentExited =
            !process.isAlive() || process.waitFor(2, TimeUnit.SECONDS);
        cleanlyExited =
            parentExited && descendants.stream().noneMatch(ProcessHandle::isAlive);
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
      if (!cleanlyExited) {
        descendants.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        try {
          if (process.isAlive()) process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  @FunctionalInterface
  interface FileSizeReader {
    long size(Path path) throws IOException;
  }

  public byte[] versionOutput(String executable, List<String> arguments) {
    Path directory = null;
    try {
      Files.createDirectories(properties.getWorkDirectory());
      directory = Files.createTempDirectory(properties.getWorkDirectory(), "version-");
      Path stdout = directory.resolve("stdout.json");
      List<String> command = new ArrayList<>();
      command.add(executable);
      command.addAll(arguments);
      run(command, directory, stdout, Duration.ofSeconds(15), Map.of());
      return readBounded(stdout, properties.getMaxOutputBytes());
    } catch (IOException e) {
      throw new ScannerRequestException(
          "SCANNER_VERSION_IO", "Unable to inspect scanner version", 503, true, e);
    } finally {
      TempDirectories.deleteRecursively(directory);
    }
  }

  static byte[] readBounded(Path path, long maxBytes) throws IOException {
    if (!Files.exists(path)) return new byte[0];
    try (InputStream input = Files.newInputStream(path)) {
      int limit = (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(1, maxBytes));
      byte[] bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) {
        throw new ScannerRequestException(
            "SCANNER_OUTPUT_TOO_LARGE", "Scanner output exceeded its configured limit", 413, false);
      }
      return bytes;
    }
  }

  private static boolean allowedEnvironmentKey(String key) {
    return key.startsWith("SYFT_REGISTRY_")
        || key.equals("SYFT_LOG_QUIET")
        || key.equals("GRYPE_DB_CACHE_DIR")
        || key.equals("GRYPE_DB_AUTO_UPDATE");
  }

  private static String sanitizeStderr(byte[] bytes) {
    String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        .replaceAll("[\\r\\n\\t]+", " ")
        .replaceAll("[\\p{Cntrl}]", "?")
        .trim();
    if (value.length() > 1000) value = value.substring(0, 1000);
    return value;
  }

  static ScannerRequestException processFailure(
      List<String> command, int exitCode, byte[] stderrBytes) {
    String stderr = sanitizeStderr(stderrBytes);
    String message = "Scanner process failed (exit " + exitCode + "): " + stderr;
    if (isOciRegistryScan(command)) {
      if (isConfirmedPlatformAbsence(stderr)) {
        return new ScannerRequestException(
            "SCANNER_PLATFORM_NOT_FOUND", message, 422, false);
      }
      // Syft uses the same non-zero exit for registry transport, authorization, token-service,
      // and server failures. Those failures must retry rather than publishing a false PARTIAL.
      return new ScannerRequestException(
          "OCI_REGISTRY_SCAN_FAILED", message, 503, true);
    }
    return new ScannerRequestException(
        "SCANNER_PROCESS_FAILED", message, 422, false);
  }

  private static boolean isOciRegistryScan(List<String> command) {
    return command != null
        && command.contains("--platform")
        && command.stream().anyMatch(value -> value != null && value.startsWith("registry:"));
  }

  /**
   * These phrases come from the platform-resolution errors emitted by Syft's stereoscope and
   * go-containerregistry dependencies after an index or image has been resolved successfully.
   * Do not broaden this allowlist: generic 404/auth/network text is not proof that a platform is
   * absent.
   */
  private static boolean isConfirmedPlatformAbsence(String stderr) {
    String normalized = stderr == null
        ? "" : stderr.toLowerCase(java.util.Locale.ROOT);
    return (normalized.contains("no child with platform ")
            && normalized.contains(" in index "))
        || normalized.contains("mismatched platform (expected ");
  }

  public record Result(int exitCode, long outputBytes, byte[] stderr) {
    public Result {
      stderr = stderr == null ? new byte[0] : stderr.clone();
    }
  }
}
