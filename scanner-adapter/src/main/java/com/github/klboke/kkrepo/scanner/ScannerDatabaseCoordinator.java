package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerContract;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Publishes immutable Grype database generations and pins each scan to one generation.
 *
 * <p>Only the credential-free updater mounts the database root read-write. Scan-serving
 * containers mount the same root read-only and resolve the atomically published pointer before
 * starting Grype. An update therefore never mutates files visible to an in-flight scan and readers
 * do not need writable lock files.
 */
@Component
public class ScannerDatabaseCoordinator {
  private static final long LOCK_LENGTH = Long.MAX_VALUE;
  private static final String CURRENT_POINTER = ".kkrepo-db-current";
  private static final String COORDINATION_DIRECTORY = ".coordination";
  private static final String GENERATIONS_DIRECTORY = "generations";
  private static final String RETIRED_DIRECTORY = "retired";
  private static final Pattern GENERATION_NAME =
      Pattern.compile("^generation-[0-9]+-[0-9a-f-]{36}$");
  private static final Duration RETIRED_GENERATION_GRACE =
      Duration.ofSeconds(ScannerContract.MAX_REQUEST_TIMEOUT_SECONDS).plusMinutes(10);

  private final ScannerAdapterProperties properties;
  private final ThreadLocal<Path> pinnedGeneration = new ThreadLocal<>();

  public ScannerDatabaseCoordinator(ScannerAdapterProperties properties) {
    this.properties = properties;
  }

  public <T> T withRead(Supplier<T> action) {
    Path previous = pinnedGeneration.get();
    Path selected = previous == null ? currentGenerationDirectory() : previous;
    pinnedGeneration.set(selected);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        pinnedGeneration.remove();
      } else {
        pinnedGeneration.set(previous);
      }
    }
  }

  /**
   * Returns the immutable database directory selected for the current operation.
   *
   * <p>Updater commands explicitly override this environment value with their private staging
   * directory. Serving commands call this method while {@link #withRead(Supplier)} has pinned the
   * active generation.
   */
  Path databaseDirectoryForProcess() {
    Path selected = pinnedGeneration.get();
    if (selected != null) return selected;
    PublishedGeneration current = readCurrentGeneration(false);
    return current == null ? databaseRoot() : current.directory();
  }

  public UpdateResult updateIfDue(
      Duration interval, Duration acquisitionTimeout, CheckedDatabaseUpdate update) {
    ensureWritableLayout();
    long timeoutNanos = timeoutNanos(acquisitionTimeout);
    try (FileChannel channel = openUpdateLockChannel()) {
      FileLock updateLock = tryFileLock(channel, timeoutNanos);
      if (updateLock == null) return UpdateResult.BUSY;
      try (updateLock) {
        Instant now = Instant.now();
        PublishedGeneration current = readCurrentGeneration(true);
        if (current != null
            && interval != null
            && !interval.isNegative()
            && !interval.isZero()
            && current.publishedAt().plus(interval).isAfter(now)) {
          return UpdateResult.NOT_DUE;
        }
        Path staging = stagingDirectory();
        try {
          Files.createDirectory(staging);
          update.run(staging);
          Instant publishedAt = Instant.now();
          publish(staging, publishedAt);
          staging = null;
          cleanupRetiredGenerations(publishedAt);
          return UpdateResult.UPDATED;
        } catch (IOException e) {
          throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
        } finally {
          TempDirectories.deleteRecursively(staging);
        }
      }
    } catch (IOException e) {
      throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
    }
  }

  public long generation() {
    try {
      PublishedGeneration current = readCurrentGeneration(false);
      if (current == null) return 0;
      int uuidStart = current.name().indexOf('-', "generation-".length()) + 1;
      UUID identifier = UUID.fromString(current.name().substring(uuidStart));
      return current.publishedAt().toEpochMilli()
          ^ identifier.getMostSignificantBits()
          ^ identifier.getLeastSignificantBits();
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private void publish(Path staging, Instant publishedAt) throws IOException {
    String generationName =
        "generation-" + publishedAt.toEpochMilli() + "-" + UUID.randomUUID();
    Path generation = generationsPath().resolve(generationName);
    moveGeneration(staging, generation);
    boolean pointerPublished = false;
    try {
      Path temporaryPointer =
          databaseRoot().resolve(CURRENT_POINTER + ".tmp-" + UUID.randomUUID());
      Files.writeString(
          temporaryPointer,
          generationName + System.lineSeparator() + publishedAt + System.lineSeparator(),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      try {
        replacePointerAtomically(temporaryPointer);
        pointerPublished = true;
      } finally {
        Files.deleteIfExists(temporaryPointer);
      }
    } finally {
      if (!pointerPublished) TempDirectories.deleteRecursively(generation);
    }
  }

  private Path currentGenerationDirectory() {
    PublishedGeneration current = readCurrentGeneration(true);
    if (current == null) {
      throw unavailable("SCANNER_DATABASE_UNAVAILABLE", null);
    }
    return current.directory();
  }

  private PublishedGeneration readCurrentGeneration(boolean requireDirectory) {
    Path pointer = pointerPath();
    if (!Files.exists(pointer)) return null;
    try {
      List<String> lines = Files.readAllLines(pointer);
      if (lines.size() < 2 || !GENERATION_NAME.matcher(lines.getFirst()).matches()) {
        throw unavailable("SCANNER_DATABASE_POINTER_INVALID", null);
      }
      Path directory = generationsPath().resolve(lines.getFirst()).normalize();
      if (!directory.getParent().equals(generationsPath())
          || (requireDirectory && !Files.isDirectory(directory))) {
        throw unavailable("SCANNER_DATABASE_POINTER_INVALID", null);
      }
      return new PublishedGeneration(
          lines.getFirst(), directory, Instant.parse(lines.get(1)));
    } catch (IOException | DateTimeParseException e) {
      throw unavailable("SCANNER_DATABASE_POINTER_INVALID", e);
    }
  }

  private void cleanupRetiredGenerations(Instant now) {
    PublishedGeneration current = readCurrentGeneration(true);
    Duration configuredInterval = properties.getVulnerabilityDatabaseUpdateInterval();
    Duration retention = RETIRED_GENERATION_GRACE;
    if (configuredInterval != null && !configuredInterval.isNegative()) {
      try {
        Duration twoIntervals = configuredInterval.multipliedBy(2);
        if (twoIntervals.compareTo(retention) > 0) retention = twoIntervals;
      } catch (ArithmeticException ignored) {
        retention = Duration.ofDays(30);
      }
    }
    try (Stream<Path> generations = Files.list(generationsPath())) {
      List<Path> retired = generations
          .filter(Files::isDirectory)
          .filter(path -> GENERATION_NAME.matcher(path.getFileName().toString()).matches())
          .filter(path -> !path.getFileName().toString().equals(current.name()))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
      for (Path generation : retired) {
        Path marker = retiredPath().resolve(generation.getFileName().toString());
        if (!Files.exists(marker)) {
          Files.writeString(
              marker,
              now.toString(),
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE);
          continue;
        }
        Instant retiredAt;
        try {
          retiredAt = Instant.parse(Files.readString(marker).trim());
        } catch (DateTimeParseException invalidMarker) {
          retiredAt = now;
          Files.writeString(
              marker,
              now.toString(),
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
        }
        if (!retiredAt.plus(retention).isAfter(now)) {
          deleteGeneration(generation);
          Files.deleteIfExists(marker);
        }
      }
    } catch (IOException ignored) {
      // A published generation is already usable. Cleanup remains retryable on the next update.
    }
  }

  private void ensureWritableLayout() {
    try {
      Files.createDirectories(generationsPath());
      Files.createDirectories(retiredPath());
    } catch (IOException e) {
      throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
    }
  }

  private FileChannel openUpdateLockChannel() {
    try {
      return FileChannel.open(
          coordinationPath().resolve(".kkrepo-db-update.lock"),
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
    }
  }

  private FileLock tryFileLock(FileChannel channel, long timeoutNanos)
      throws IOException {
    long started = System.nanoTime();
    while (true) {
      try {
        FileLock lock = channel.tryLock(0, LOCK_LENGTH, false);
        if (lock != null) return lock;
      } catch (java.nio.channels.OverlappingFileLockException ignored) {
        // Another updater in this JVM owns the cross-process update lock.
      }
      long elapsed = System.nanoTime() - started;
      if (elapsed >= timeoutNanos) return null;
      try {
        TimeUnit.NANOSECONDS.sleep(
            Math.min(TimeUnit.MILLISECONDS.toNanos(25), timeoutNanos - elapsed));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw unavailable("SCANNER_DATABASE_LOCK_INTERRUPTED", e);
      }
    }
  }

  private Path stagingDirectory() {
    return generationsPath().resolve(".staging-" + UUID.randomUUID());
  }

  private Path databaseRoot() {
    return properties.getVulnerabilityDatabaseDirectory().toAbsolutePath().normalize();
  }

  private Path generationsPath() {
    return databaseRoot().resolve(GENERATIONS_DIRECTORY);
  }

  private Path coordinationPath() {
    return databaseRoot().resolve(COORDINATION_DIRECTORY);
  }

  private Path retiredPath() {
    return coordinationPath().resolve(RETIRED_DIRECTORY);
  }

  private Path pointerPath() {
    return databaseRoot().resolve(CURRENT_POINTER);
  }

  private static void moveGeneration(Path source, Path target) throws IOException {
    try {
      Files.move(
          source,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void replacePointerAtomically(Path temporaryPointer) throws IOException {
    try {
      Files.move(
          temporaryPointer,
          pointerPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      throw new IOException(
          "Scanner database storage must support atomic pointer replacement", e);
    }
  }

  private static void deleteGeneration(Path generation) throws IOException {
    Files.walkFileTree(generation, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
          throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path directory, IOException failure)
          throws IOException {
        if (failure != null) throw failure;
        Files.delete(directory);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static long timeoutNanos(Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) return 1;
    try {
      return Math.max(
          1, Math.min(timeout.toNanos(), TimeUnit.HOURS.toNanos(1)));
    } catch (ArithmeticException e) {
      return TimeUnit.HOURS.toNanos(1);
    }
  }

  private static ScannerRequestException unavailable(String code, Throwable cause) {
    String message = switch (code) {
      case "SCANNER_DATABASE_UNAVAILABLE" ->
          "No immutable scanner vulnerability database generation has been published";
      case "SCANNER_DATABASE_POINTER_INVALID" ->
          "Scanner vulnerability database generation pointer is invalid";
      default -> "Scanner vulnerability database coordination is unavailable";
    };
    return cause == null
        ? new ScannerRequestException(code, message, 503, true)
        : new ScannerRequestException(code, message, 503, true, cause);
  }

  private record PublishedGeneration(
      String name, Path directory, Instant publishedAt) {
  }

  public enum UpdateResult {
    UPDATED,
    NOT_DUE,
    BUSY
  }

  @FunctionalInterface
  public interface CheckedDatabaseUpdate {
    void run(Path databaseDirectory) throws IOException;
  }
}
