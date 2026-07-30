package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerDatabaseCoordinatorTest {
  @TempDir Path databaseDirectory;

  @Test
  void publishesImmutableGenerationsAndPinsInflightReaders() throws Exception {
    ScannerAdapterProperties properties = properties();
    ScannerDatabaseCoordinator updater = new ScannerDatabaseCoordinator(properties);
    ScannerDatabaseCoordinator reader = new ScannerDatabaseCoordinator(properties);

    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        publish(updater, "v1"));
    long firstGeneration = updater.generation();
    Path first = reader.withRead(reader::databaseDirectoryForProcess);
    assertEquals("v1", Files.readString(first.resolve("database")));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch pinned = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<Path> heldRead = executor.submit(() -> reader.withRead(() -> {
      Path selected = reader.databaseDirectoryForProcess();
      pinned.countDown();
      try {
        assertTrue(release.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
      assertEquals("v1", uncheckedRead(selected.resolve("database")));
      return selected;
    }));
    try {
      assertTrue(pinned.await(1, TimeUnit.SECONDS));
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.UPDATED,
          publish(updater, "v2"));
      assertNotEquals(firstGeneration, updater.generation());
      Path second = reader.withRead(reader::databaseDirectoryForProcess);
      assertNotEquals(first, second);
      assertEquals("v2", Files.readString(second.resolve("database")));
      assertEquals(
          second,
          reader.withRead(
              () -> reader.withRead(reader::databaseDirectoryForProcess)));
      assertTrue(Files.isDirectory(first));
      release.countDown();
      assertEquals(first, heldRead.get(1, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void serializesUpdatersWithoutBlockingReaders() throws Exception {
    ScannerAdapterProperties properties = properties();
    ScannerDatabaseCoordinator holder = new ScannerDatabaseCoordinator(properties);
    ScannerDatabaseCoordinator contender = new ScannerDatabaseCoordinator(properties);
    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        publish(holder, "initial"));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch updating = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<ScannerDatabaseCoordinator.UpdateResult> held = executor.submit(
        () -> holder.updateIfDue(
            Duration.ZERO,
            Duration.ofSeconds(2),
            directory -> {
              Files.writeString(directory.resolve("database"), "next");
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
      assertEquals(
          "initial",
          contender.withRead(
              () -> uncheckedRead(
                  contender.databaseDirectoryForProcess().resolve("database"))));
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.BUSY,
          contender.updateIfDue(
              Duration.ZERO,
              Duration.ofMillis(25),
              directory -> {
                throw new AssertionError("busy updater must not run");
              }));
    } finally {
      release.countDown();
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.UPDATED,
          held.get(1, TimeUnit.SECONDS));
      executor.shutdownNow();
    }
  }

  @Test
  void enforcesTheSharedUpdateIntervalAndCleansRetiredGenerations() throws Exception {
    ScannerAdapterProperties properties = properties();
    properties.setVulnerabilityDatabaseUpdateInterval(
        Duration.ofSeconds(Long.MAX_VALUE));
    ScannerDatabaseCoordinator coordinator = new ScannerDatabaseCoordinator(properties);

    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        publish(coordinator, "v1"));
    Path first = coordinator.withRead(coordinator::databaseDirectoryForProcess);
    assertNotEquals(0, coordinator.generation());
    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.NOT_DUE,
        coordinator.updateIfDue(
            Duration.ofHours(1),
            Duration.ofSeconds(1),
            directory -> {
              throw new AssertionError("not-due update must not run");
            }));

    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        publish(coordinator, "v2"));
    Path retiredMarker = databaseDirectory
        .resolve(".coordination/retired")
        .resolve(first.getFileName().toString());
    assertTrue(Files.exists(retiredMarker));
    Files.writeString(retiredMarker, "not-an-instant");

    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        publish(coordinator, "v3"));
    assertTrue(Files.isDirectory(first));
    assertFalse(Files.readString(retiredMarker).contains("not-an-instant"));
    Files.writeString(retiredMarker, Instant.EPOCH.toString());

    assertEquals(
        ScannerDatabaseCoordinator.UpdateResult.UPDATED,
        publish(coordinator, "v4"));
    assertFalse(Files.exists(first));
  }

  @Test
  void failedUpdatesDoNotPublishOrLeaveMutableStagingData() throws Exception {
    ScannerDatabaseCoordinator coordinator =
        new ScannerDatabaseCoordinator(properties());
    assertThrows(
        IllegalStateException.class,
        () -> coordinator.updateIfDue(
            Duration.ZERO,
            Duration.ofSeconds(1),
            directory -> {
              Files.writeString(directory.resolve("partial"), "data");
              throw new IllegalStateException("failed");
            }));
    ScannerRequestException ioFailure = assertThrows(
        ScannerRequestException.class,
        () -> coordinator.updateIfDue(
            Duration.ZERO,
            Duration.ofSeconds(1),
            directory -> {
              throw new java.io.IOException("failed");
            }));
    assertEquals("SCANNER_DATABASE_COORDINATION_IO", ioFailure.code());
    assertEquals(0, coordinator.generation());
    ScannerRequestException unavailable = assertThrows(
        ScannerRequestException.class,
        () -> coordinator.withRead(() -> "read"));
    assertEquals("SCANNER_DATABASE_UNAVAILABLE", unavailable.code());
    try (var paths = Files.list(databaseDirectory.resolve("generations"))) {
      assertTrue(paths.noneMatch(
          path -> path.getFileName().toString().startsWith(".staging-")));
    }
  }

  @Test
  void rejectsInvalidPointersAndReportsWritableLayoutFailures() throws Exception {
    Files.createDirectories(databaseDirectory.resolve("generations"));
    Files.writeString(
        databaseDirectory.resolve(".kkrepo-db-current"),
        "../escape\n" + Instant.now() + "\n");
    ScannerDatabaseCoordinator coordinator =
        new ScannerDatabaseCoordinator(properties());
    assertEquals(0, coordinator.generation());
    ScannerRequestException invalid = assertThrows(
        ScannerRequestException.class,
        () -> coordinator.withRead(() -> "read"));
    assertEquals("SCANNER_DATABASE_POINTER_INVALID", invalid.code());

    String missingGeneration = "generation-1-" + java.util.UUID.randomUUID();
    Files.writeString(
        databaseDirectory.resolve(".kkrepo-db-current"),
        missingGeneration + "\n" + Instant.now() + "\n");
    ScannerRequestException missing = assertThrows(
        ScannerRequestException.class,
        () -> coordinator.withRead(() -> "read"));
    assertEquals("SCANNER_DATABASE_POINTER_INVALID", missing.code());

    Files.createDirectory(
        databaseDirectory.resolve("generations").resolve(missingGeneration));
    Files.writeString(
        databaseDirectory.resolve(".kkrepo-db-current"),
        missingGeneration + "\nnot-an-instant\n");
    assertEquals(0, coordinator.generation());

    Path notDirectory = databaseDirectory.resolve("not-a-directory");
    Files.writeString(notDirectory, "file");
    ScannerAdapterProperties failedProperties = new ScannerAdapterProperties();
    failedProperties.setVulnerabilityDatabaseDirectory(notDirectory);
    ScannerRequestException failure = assertThrows(
        ScannerRequestException.class,
        () -> new ScannerDatabaseCoordinator(failedProperties)
            .updateIfDue(Duration.ZERO, Duration.ofSeconds(1), directory -> {}));
    assertEquals("SCANNER_DATABASE_COORDINATION_IO", failure.code());
  }

  @Test
  void preservesInterruptsWhileWaitingForTheUpdaterLock() throws Exception {
    ScannerAdapterProperties properties = properties();
    ScannerDatabaseCoordinator holder = new ScannerDatabaseCoordinator(properties);
    ScannerDatabaseCoordinator waiter = new ScannerDatabaseCoordinator(properties);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch updating = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<ScannerDatabaseCoordinator.UpdateResult> held = executor.submit(
        () -> holder.updateIfDue(
            Duration.ZERO,
            Duration.ofSeconds(2),
            directory -> {
              Files.writeString(directory.resolve("database"), "held");
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
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread thread = new Thread(() -> {
        try {
          waiter.updateIfDue(
              Duration.ZERO,
              Duration.ofSeconds(2),
              directory -> {});
        } catch (Throwable error) {
          failure.set(error);
        }
      });
      thread.start();
      interruptTimedWait(thread);
      ScannerRequestException interrupted =
          assertInstanceOf(ScannerRequestException.class, failure.get());
      assertEquals("SCANNER_DATABASE_LOCK_INTERRUPTED", interrupted.code());
    } finally {
      release.countDown();
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.UPDATED,
          held.get(1, TimeUnit.SECONDS));
      executor.shutdownNow();
    }
  }

  @Test
  void acceptsDefensiveTimeoutValues() {
    ScannerDatabaseCoordinator coordinator =
        new ScannerDatabaseCoordinator(properties());
    List<Duration> values = java.util.Arrays.asList(
        null,
        Duration.ofSeconds(-1),
        Duration.ZERO,
        Duration.ofSeconds(Long.MAX_VALUE));
    int revision = 0;
    for (Duration timeout : values) {
      String value = "v" + revision++;
      assertEquals(
          ScannerDatabaseCoordinator.UpdateResult.UPDATED,
          coordinator.updateIfDue(
              Duration.ZERO,
              timeout,
              directory -> Files.writeString(directory.resolve("database"), value)));
    }
  }

  private ScannerDatabaseCoordinator.UpdateResult publish(
      ScannerDatabaseCoordinator coordinator, String value) {
    return coordinator.updateIfDue(
        Duration.ZERO,
        Duration.ofSeconds(1),
        directory -> Files.writeString(directory.resolve("database"), value));
  }

  private ScannerAdapterProperties properties() {
    ScannerAdapterProperties properties = new ScannerAdapterProperties();
    properties.setVulnerabilityDatabaseDirectory(databaseDirectory);
    properties.setDatabaseUpdateLockTimeout(Duration.ofSeconds(1));
    return properties;
  }

  private static String uncheckedRead(Path path) {
    try {
      return Files.readString(path);
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
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
}
