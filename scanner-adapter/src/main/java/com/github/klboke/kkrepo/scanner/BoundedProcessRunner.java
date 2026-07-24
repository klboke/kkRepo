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
import org.springframework.stereotype.Component;

/** Runs scanner binaries directly, without a shell, with bounded output and wall-clock timeout. */
@Component
public class BoundedProcessRunner {
  private final ScannerAdapterProperties properties;

  public BoundedProcessRunner(ScannerAdapterProperties properties) {
    this.properties = properties;
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
      Process process = builder.start();
      waitForBounded(process, stdout, stderr, timeout);
      byte[] stderrBytes = readBounded(stderr, properties.getMaxStderrBytes());
      if (process.exitValue() != 0) {
        throw new ScannerRequestException(
            "SCANNER_PROCESS_FAILED",
            "Scanner process failed (exit " + process.exitValue() + "): "
                + sanitizeStderr(stderrBytes),
            422,
            false);
      }
      long outputBytes = fileSize(stdout);
      return new Result(process.exitValue(), outputBytes, stderrBytes);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScannerRequestException(
          "SCANNER_INTERRUPTED", "Scanner process was interrupted", 503, true, e);
    } catch (IOException e) {
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

  private static boolean exceeds(Path path, long maximum) throws IOException {
    return fileSize(path) > maximum;
  }

  private static long fileSize(Path path) throws IOException {
    return Files.exists(path) ? Files.size(path) : 0;
  }

  private static void terminate(Process process) throws InterruptedException {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroy();
    if (process.waitFor(2, TimeUnit.SECONDS)) return;
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    process.waitFor(2, TimeUnit.SECONDS);
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

  public record Result(int exitCode, long outputBytes, byte[] stderr) {
    public Result {
      stderr = stderr == null ? new byte[0] : stderr.clone();
    }
  }
}
