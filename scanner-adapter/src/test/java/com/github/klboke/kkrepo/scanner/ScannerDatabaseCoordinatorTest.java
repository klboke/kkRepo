package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerDatabaseCoordinatorTest {
  @TempDir Path databaseDirectory;

  @Test
  void coordinatesReadersUpdatesAndTheSharedUpdateInterval() throws Exception {
    ScannerAdapterProperties properties = properties();
    ScannerDatabaseCoordinator reader = new ScannerDatabaseCoordinator(properties);
    ScannerDatabaseCoordinator updater = new ScannerDatabaseCoordinator(properties);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<String> heldRead = executor.submit(() -> reader.withRead(() -> {
      reading.countDown();
      try {
        assertTrue(release.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
      return "read";
    }));
    try {
      assertTrue(reading.await(1, TimeUnit.SECONDS));
      AtomicBoolean ran = new AtomicBoolean();
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.BUSY,
          updater.updateIfDue(Duration.ZERO, () -> ran.set(true)));
      assertFalse(ran.get());

      release.countDown();
      assertEquals("read", heldRead.get(2, TimeUnit.SECONDS));
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.UPDATED,
          updater.updateIfDue(Duration.ofHours(1), () -> ran.set(true)));
      assertTrue(ran.get());
      assertTrue(updater.generation() > 0);
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.NOT_DUE,
          reader.updateIfDue(
              Duration.ofHours(1),
              () -> {
                throw new AssertionError("not-due update must not run");
              }));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsReadsDuringAnExclusiveUpdateAndDoesNotMarkFailedUpdates() throws Exception {
    ScannerAdapterProperties properties = properties();
    ScannerDatabaseCoordinator updater = new ScannerDatabaseCoordinator(properties);
    ScannerDatabaseCoordinator reader = new ScannerDatabaseCoordinator(properties);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch updating = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<ScannerDatabaseCoordinator.UpdateResult> heldUpdate = executor.submit(
        () -> updater.updateIfDue(Duration.ZERO, () -> {
          updating.countDown();
          try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        }));
    try {
      assertTrue(updating.await(1, TimeUnit.SECONDS));
      ScannerRequestException unavailable = assertThrows(
          ScannerRequestException.class,
          () -> reader.withRead(() -> "read"));
      assertEquals("SCANNER_DATABASE_UPDATING", unavailable.code());
      ScannerRequestException processLocalUnavailable = assertThrows(
          ScannerRequestException.class,
          () -> updater.withRead(() -> "read"));
      assertEquals("SCANNER_DATABASE_UPDATING", processLocalUnavailable.code());
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.BUSY,
          updater.updateIfDue(Duration.ZERO, () -> {
            throw new AssertionError("busy update must not run");
          }));
    } finally {
      release.countDown();
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.UPDATED,
          heldUpdate.get(2, TimeUnit.SECONDS));
      executor.shutdownNow();
    }

    ScannerAdapterProperties failedProperties = new ScannerAdapterProperties();
    failedProperties.setVulnerabilityDatabaseDirectory(
        databaseDirectory.resolve("failed"));
    ScannerDatabaseCoordinator failed = new ScannerDatabaseCoordinator(failedProperties);
    assertThrows(
        IllegalStateException.class,
        () -> failed.updateIfDue(
            Duration.ZERO,
            () -> {
              throw new IllegalStateException("failed");
            }));
    assertEquals(0, failed.generation());
  }

  @Test
  void handlesInvalidMarkersAndCoordinationIoFailures() throws Exception {
    ScannerAdapterProperties properties = properties();
    Files.createDirectories(databaseDirectory);
    Files.writeString(databaseDirectory.resolve(".kkrepo-db-updated"), "invalid");
    AtomicBoolean ran = new AtomicBoolean();
    ScannerDatabaseCoordinator coordinator = new ScannerDatabaseCoordinator(properties);
    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        coordinator.updateIfDue(Duration.ofHours(1), () -> ran.set(true)));
    assertTrue(ran.get());
    assertEquals(
        "nested",
        coordinator.withRead(() -> coordinator.withRead(() -> "nested")));

    ScannerAdapterProperties directoryFailureProperties = new ScannerAdapterProperties();
    Path notDirectory = databaseDirectory.resolve("not-a-directory");
    Files.writeString(notDirectory, "file");
    directoryFailureProperties.setVulnerabilityDatabaseDirectory(notDirectory);
    ScannerRequestException directoryFailure = assertThrows(
        ScannerRequestException.class,
        () -> new ScannerDatabaseCoordinator(directoryFailureProperties)
            .withRead(() -> "read"));
    assertEquals("SCANNER_DATABASE_COORDINATION_IO", directoryFailure.code());

    ScannerAdapterProperties lockFailureProperties = new ScannerAdapterProperties();
    Path lockFailureDirectory = databaseDirectory.resolve("lock-failure");
    Files.createDirectories(lockFailureDirectory.resolve(".kkrepo-db.lock"));
    lockFailureProperties.setVulnerabilityDatabaseDirectory(lockFailureDirectory);
    ScannerRequestException lockFailure = assertThrows(
        ScannerRequestException.class,
        () -> new ScannerDatabaseCoordinator(lockFailureProperties)
            .withRead(() -> "read"));
    assertEquals("SCANNER_DATABASE_COORDINATION_IO", lockFailure.code());

    ScannerAdapterProperties markerFailureProperties = new ScannerAdapterProperties();
    Path markerFailureDirectory = databaseDirectory.resolve("marker-failure");
    Files.createDirectories(markerFailureDirectory.resolve(".kkrepo-db-updated.tmp"));
    markerFailureProperties.setVulnerabilityDatabaseDirectory(markerFailureDirectory);
    ScannerRequestException markerFailure = assertThrows(
        ScannerRequestException.class,
        () -> new ScannerDatabaseCoordinator(markerFailureProperties)
            .updateIfDue(Duration.ZERO, () -> {}));
    assertEquals("SCANNER_DATABASE_COORDINATION_IO", markerFailure.code());
  }

  @Test
  void preservesInterruptsAndAcceptsDefensiveTimeoutValues() {
    ScannerAdapterProperties properties = properties();
    ScannerDatabaseCoordinator coordinator = new ScannerDatabaseCoordinator(properties);

    Thread.currentThread().interrupt();
    try {
      ScannerRequestException readInterrupted = assertThrows(
          ScannerRequestException.class,
          () -> coordinator.withRead(() -> "read"));
      assertEquals("SCANNER_DATABASE_LOCK_INTERRUPTED", readInterrupted.code());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }

    Thread.currentThread().interrupt();
    try {
      ScannerRequestException updateInterrupted = assertThrows(
          ScannerRequestException.class,
          () -> coordinator.updateIfDue(Duration.ZERO, () -> {}));
      assertEquals("SCANNER_DATABASE_LOCK_INTERRUPTED", updateInterrupted.code());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }

    properties.setDatabaseLockTimeout(null);
    assertEquals("null", coordinator.withRead(() -> "null"));
    properties.setDatabaseLockTimeout(Duration.ofSeconds(-1));
    assertEquals("negative", coordinator.withRead(() -> "negative"));
    properties.setDatabaseLockTimeout(Duration.ZERO);
    assertEquals("zero", coordinator.withRead(() -> "zero"));
    properties.setDatabaseLockTimeout(Duration.ofSeconds(Long.MAX_VALUE));
    assertEquals("overflow", coordinator.withRead(() -> "overflow"));
  }

  @Test
  void preservesInterruptsWhileWaitingForSharedFileLocks() throws Exception {
    ScannerAdapterProperties properties = properties();
    properties.setDatabaseLockTimeout(Duration.ofSeconds(2));
    ScannerDatabaseCoordinator reader = new ScannerDatabaseCoordinator(properties);
    ScannerDatabaseCoordinator updater = new ScannerDatabaseCoordinator(properties);

    AtomicReference<Throwable> updateFailure = new AtomicReference<>();
    reader.withRead(() -> {
      Thread updateThread = new Thread(() -> {
        try {
          updater.updateIfDue(Duration.ZERO, () -> {});
          updateFailure.set(new AssertionError("update should have been interrupted"));
        } catch (Throwable failure) {
          updateFailure.set(failure);
        }
      });
      updateThread.start();
      interruptTimedWait(updateThread);
      return null;
    });
    ScannerRequestException interruptedUpdate =
        assertInstanceOf(ScannerRequestException.class, updateFailure.get());
    assertEquals("SCANNER_DATABASE_LOCK_INTERRUPTED", interruptedUpdate.code());

    AtomicReference<Throwable> readFailure = new AtomicReference<>();
    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        updater.updateIfDue(Duration.ZERO, () -> {
          Thread readThread = new Thread(() -> {
            try {
              reader.withRead(() -> "read");
              readFailure.set(new AssertionError("read should have been interrupted"));
            } catch (Throwable failure) {
              readFailure.set(failure);
            }
          });
          readThread.start();
          interruptTimedWait(readThread);
        }));
    ScannerRequestException interruptedRead =
        assertInstanceOf(ScannerRequestException.class, readFailure.get());
    assertEquals("SCANNER_DATABASE_LOCK_INTERRUPTED", interruptedRead.code());
  }

  private static void interruptTimedWait(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (thread.isAlive()
        && thread.getState() != Thread.State.TIMED_WAITING
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(Thread.State.TIMED_WAITING, thread.getState());
    thread.interrupt();
    try {
      thread.join(TimeUnit.SECONDS.toMillis(1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    assertFalse(thread.isAlive());
  }

  private ScannerAdapterProperties properties() {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setVulnerabilityDatabaseDirectory(databaseDirectory);
    properties.setDatabaseLockTimeout(Duration.ofMillis(50));
    return properties;
  }
}
