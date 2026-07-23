package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnsibleCollectionArchiveInspectorTest {

  @Test
  void validatesManifestInventoryIdentityDependenciesAndRequiresAnsible() throws Exception {
    byte[] archive = AnsibleCollectionTestArchive.valid("acme", "tools", "1.2.3");
    AnsibleCollectionArchiveInspector inspector =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), 1024 * 1024, 1024 * 1024, 100);

    AnsibleCollectionArchiveInspector.InspectedCollection inspected =
        inspector.inspect(new ByteArrayInputStream(archive));
    try {
      assertEquals("acme", inspected.namespace());
      assertEquals("tools", inspected.name());
      assertEquals("1.2.3", inspected.version());
      assertEquals("acme-tools-1.2.3.tar.gz", inspected.filename());
      assertEquals(archive.length, inspected.size());
      assertEquals(AnsibleCollectionTestArchive.sha256(archive), inspected.sha256());
      assertEquals(">=1.0.0", inspected.dependencies().get("acme.base"));
      assertEquals(">=2.15", inspected.requiresAnsible());
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }

  @Test
  void rejectsChecksumDriftUnsafePathsAndCompressedLimit() throws Exception {
    AnsibleCollectionArchiveInspector inspector =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), 1024 * 1024, 1024 * 1024, 100);

    AnsibleGalaxyExceptions.BadRequest mismatch = assertThrows(
        AnsibleGalaxyExceptions.BadRequest.class,
        () -> inspector.inspect(new ByteArrayInputStream(
            AnsibleCollectionTestArchive.checksumMismatch())));
    assertTrue(mismatch.getMessage().contains("checksum mismatch"));

    assertThrows(
        AnsibleGalaxyExceptions.BadRequest.class,
        () -> inspector.inspect(new ByteArrayInputStream(AnsibleCollectionTestArchive.unsafePath())));

    byte[] archive = AnsibleCollectionTestArchive.valid("acme", "tools", "1.0.0");
    AnsibleCollectionArchiveInspector bounded =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), archive.length - 1L, 1024 * 1024, 100);
    assertThrows(
        AnsibleGalaxyExceptions.ContentTooLarge.class,
        () -> bounded.inspect(new ByteArrayInputStream(archive)));
  }

  @Test
  void acceptsSafeCollectionSymlinksAndRejectsArchiveEscapes() throws Exception {
    AnsibleCollectionArchiveInspector inspector =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), 1024 * 1024, 1024 * 1024, 100);

    AnsibleCollectionArchiveInspector.InspectedCollection inspected =
        inspector.inspect(new ByteArrayInputStream(AnsibleCollectionTestArchive.withSymbolicLink()));
    Files.deleteIfExists(inspected.file());

    AnsibleGalaxyExceptions.BadRequest escape = assertThrows(
        AnsibleGalaxyExceptions.BadRequest.class,
        () -> inspector.inspect(new ByteArrayInputStream(
            AnsibleCollectionTestArchive.escapingSymbolicLink())));
    assertTrue(escape.getMessage().contains("escapes the archive"));
  }

  @Test
  void boundsConcurrentArchiveInspectionInsteadOfQueuingUnboundedWork() throws Exception {
    AnsibleCollectionArchiveInspector inspector = new AnsibleCollectionArchiveInspector(
        new ObjectMapper(), 1024 * 1024, 1024 * 1024, 1024 * 1024, 100,
        200, 120, 1, 20);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread first = Thread.ofPlatform().start(() -> {
      try {
        inspector.inspect(new BlockingInputStream(entered, release));
      } catch (Throwable failure) {
        firstFailure.set(failure);
      }
    });
    assertTrue(entered.await(1, TimeUnit.SECONDS));

    assertThrows(
        AnsibleGalaxyExceptions.ServiceUnavailable.class,
        () -> inspector.inspect(new ByteArrayInputStream(new byte[] {1, 2, 3})));

    release.countDown();
    first.join(1000);
    assertTrue(firstFailure.get() instanceof AnsibleGalaxyExceptions.BadRequest);
  }

  @Test
  void preservesInterruptStatusWhileWaitingForInspectionCapacity() {
    AnsibleCollectionArchiveInspector inspector = new AnsibleCollectionArchiveInspector(
        new ObjectMapper(), 1024, 1024, 1024, 10, 200, 120, 1, 20);
    Thread.currentThread().interrupt();

    try {
      assertThrows(
          AnsibleGalaxyExceptions.ServiceUnavailable.class,
          () -> inspector.inspect(new ByteArrayInputStream(new byte[] {1})));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static final class BlockingInputStream extends InputStream {
    private final CountDownLatch entered;
    private final CountDownLatch release;

    private BlockingInputStream(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      entered.countDown();
      awaitRelease();
      return -1;
    }

    @Override
    public int read() {
      entered.countDown();
      awaitRelease();
      return -1;
    }

    private void awaitRelease() {
      try {
        if (!release.await(1, TimeUnit.SECONDS)) throw new AssertionError("timed out");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
    }
  }
}
