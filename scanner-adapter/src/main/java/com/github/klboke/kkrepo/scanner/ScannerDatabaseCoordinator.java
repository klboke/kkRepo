package com.github.klboke.kkrepo.scanner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Coordinates Grype database reads and updates in-process and across adapter replicas sharing a
 * database volume.
 */
@Component
public class ScannerDatabaseCoordinator {
  private static final long LOCK_LENGTH = Long.MAX_VALUE;

  private final ScannerAdapterProperties properties;
  private final ReentrantReadWriteLock localLock = new ReentrantReadWriteLock(true);
  private final Object readersMonitor = new Object();
  private int localReaders;
  private FileChannel sharedReadChannel;
  private FileLock sharedReadLock;

  public ScannerDatabaseCoordinator(ScannerAdapterProperties properties) {
    this.properties = properties;
  }

  public <T> T withRead(Supplier<T> action) {
    Lock read = localLock.readLock();
    long timeoutNanos = timeoutNanos(properties.getDatabaseLockTimeout());
    boolean localAcquired;
    try {
      localAcquired = read.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("SCANNER_DATABASE_LOCK_INTERRUPTED", e);
    }
    if (!localAcquired) throw unavailable("SCANNER_DATABASE_UPDATING", null);
    boolean registered = false;
    try {
      registerReader(System.nanoTime() + timeoutNanos);
      registered = true;
      return action.get();
    } finally {
      if (registered) unregisterReader();
      read.unlock();
    }
  }

  public UpdateResult updateIfDue(Duration interval, CheckedRunnable update) {
    return updateIfDue(interval, properties.getDatabaseLockTimeout(), update);
  }

  public UpdateResult updateIfDue(
      Duration interval, Duration acquisitionTimeout, CheckedRunnable update) {
    ensureDirectory();
    long timeoutNanos = timeoutNanos(acquisitionTimeout);
    long deadlineNanos = System.nanoTime() + timeoutNanos;
    try (FileChannel gateChannel = openWriterGateChannel()) {
      FileLock writerGate = tryFileLock(gateChannel, false, timeoutNanos);
      if (writerGate == null) return UpdateResult.BUSY;
      try (writerGate) {
        return updateWhileWriterGateHeld(interval, deadlineNanos, update);
      }
    } catch (IOException e) {
      throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
    }
  }

  private UpdateResult updateWhileWriterGateHeld(
      Duration interval, long deadlineNanos, CheckedRunnable update) {
    Lock write = localLock.writeLock();
    boolean localAcquired;
    try {
      localAcquired =
          write.tryLock(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("SCANNER_DATABASE_LOCK_INTERRUPTED", e);
    }
    if (!localAcquired) return UpdateResult.BUSY;
    try {
      try (FileChannel channel = openLockChannel()) {
        FileLock exclusive =
            tryFileLock(channel, false, remainingNanos(deadlineNanos));
        if (exclusive == null) return UpdateResult.BUSY;
        try (exclusive) {
          Instant lastUpdated = lastSuccessfulUpdate();
          Instant now = Instant.now();
          if (lastUpdated != null
              && interval != null
              && !interval.isNegative()
              && !interval.isZero()
              && lastUpdated.plus(interval).isAfter(now)) {
            return UpdateResult.NOT_DUE;
          }
          update.run();
          writeMarker(now);
          return UpdateResult.UPDATED;
        }
      } catch (IOException e) {
        throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
      }
    } finally {
      write.unlock();
    }
  }

  public long generation() {
    try {
      Path marker = markerPath();
      return Files.exists(marker) ? Files.getLastModifiedTime(marker).toMillis() : 0;
    } catch (IOException e) {
      return 0;
    }
  }

  private void registerReader(long deadlineNanos) {
    synchronized (readersMonitor) {
      ensureDirectory();
      try (FileChannel gateChannel = openWriterGateChannel()) {
        long gateTimeout = Math.max(1, deadlineNanos - System.nanoTime());
        FileLock readerGate = tryFileLock(gateChannel, true, gateTimeout);
        if (readerGate == null) {
          throw unavailable("SCANNER_DATABASE_UPDATING", null);
        }
        try (readerGate) {
          if (localReaders == 0) {
            sharedReadChannel = openLockChannel();
            long readTimeout = Math.max(1, deadlineNanos - System.nanoTime());
            sharedReadLock = tryFileLock(sharedReadChannel, true, readTimeout);
            if (sharedReadLock == null) {
              closeSharedRead();
              throw unavailable("SCANNER_DATABASE_UPDATING", null);
            }
          }
        }
        localReaders++;
      } catch (IOException e) {
        closeSharedRead();
        throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
      }
    }
  }

  private void unregisterReader() {
    synchronized (readersMonitor) {
      localReaders--;
      if (localReaders == 0) closeSharedRead();
    }
  }

  private void closeSharedRead() {
    try {
      if (sharedReadLock != null) sharedReadLock.close();
    } catch (IOException ignored) {
      // The process-local lock still prevents an updater from entering this critical section.
    } finally {
      sharedReadLock = null;
    }
    try {
      if (sharedReadChannel != null) sharedReadChannel.close();
    } catch (IOException ignored) {
      // The next request opens a fresh channel.
    } finally {
      sharedReadChannel = null;
    }
  }

  private FileChannel openLockChannel() {
    return openCoordinationChannel(lockPath());
  }

  private FileChannel openWriterGateChannel() {
    return openCoordinationChannel(writerGatePath());
  }

  private FileChannel openCoordinationChannel(Path path) {
    try {
      return FileChannel.open(
          path,
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
    }
  }

  private FileLock tryFileLock(
      FileChannel channel, boolean shared, long timeoutNanos)
      throws IOException {
    long started = System.nanoTime();
    while (true) {
      try {
        FileLock lock = channel.tryLock(0, LOCK_LENGTH, shared);
        if (lock != null) return lock;
      } catch (java.nio.channels.OverlappingFileLockException ignored) {
        // Another coordinator in this JVM still owns a shared or exclusive lock.
      }
      long elapsed = System.nanoTime() - started;
      if (elapsed >= timeoutNanos) return null;
      try {
        long remaining = timeoutNanos - elapsed;
        TimeUnit.NANOSECONDS.sleep(
            Math.min(TimeUnit.MILLISECONDS.toNanos(25), remaining));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw unavailable("SCANNER_DATABASE_LOCK_INTERRUPTED", e);
      }
    }
  }

  private void ensureDirectory() {
    try {
      Files.createDirectories(properties.getVulnerabilityDatabaseDirectory());
    } catch (IOException e) {
      throw unavailable("SCANNER_DATABASE_COORDINATION_IO", e);
    }
  }

  private Instant lastSuccessfulUpdate() {
    Path marker = markerPath();
    if (!Files.exists(marker)) return null;
    try {
      return Instant.parse(Files.readString(marker).trim());
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private void writeMarker(Instant updatedAt) throws IOException {
    Path temporary = properties.getVulnerabilityDatabaseDirectory()
        .resolve(".kkrepo-db-updated.tmp");
    Files.writeString(
        temporary,
        updatedAt.toString(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.move(
          temporary,
          markerPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(temporary, markerPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Path lockPath() {
    return properties.getVulnerabilityDatabaseDirectory().resolve(".kkrepo-db.lock");
  }

  private Path writerGatePath() {
    return properties.getVulnerabilityDatabaseDirectory().resolve(".kkrepo-db.writer-gate");
  }

  private Path markerPath() {
    return properties.getVulnerabilityDatabaseDirectory().resolve(".kkrepo-db-updated");
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

  private static long remainingNanos(long deadlineNanos) {
    return Math.max(0, deadlineNanos - System.nanoTime());
  }

  private static ScannerRequestException unavailable(String code, Throwable cause) {
    String message = "SCANNER_DATABASE_UPDATING".equals(code)
        ? "Scanner vulnerability database is being updated"
        : "Scanner vulnerability database coordination is unavailable";
    return cause == null
        ? new ScannerRequestException(code, message, 503, true)
        : new ScannerRequestException(code, message, 503, true, cause);
  }

  public enum UpdateResult {
    UPDATED,
    NOT_DUE,
    BUSY
  }

  @FunctionalInterface
  public interface CheckedRunnable {
    void run();
  }
}
