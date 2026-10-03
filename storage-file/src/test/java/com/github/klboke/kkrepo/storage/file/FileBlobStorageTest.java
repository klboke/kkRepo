package com.github.klboke.kkrepo.storage.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.BlobFileRegion;
import com.github.klboke.kkrepo.core.BlobRangeReader;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.storage.file.admin.FileBlobStoreAdmin;
import com.github.klboke.kkrepo.storage.file.config.FileStorageProperties;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStorageTest {
  @TempDir
  Path tempDir;

  @Test
  void verifiedUploadsPreserveExistingObjectsAndRemoveFailedAttempts() throws Exception {
    FileBlobStorage storage = new FileBlobStorage(new FileBlobStoreConfig(1, "disk", "default", tempDir));
    byte[] bytes = "verified".getBytes(StandardCharsets.UTF_8);
    String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var target = storage.prepareVerifiedUpload("lfs", sha, bytes.length);
    storage.uploadVerified(target, new ByteArrayInputStream(bytes), id -> {});
    assertArrayEquals(bytes, storage.get(target).orElseThrow().readAllBytes());
    assertThrows(RuntimeException.class, () -> storage.uploadVerified(target, new ByteArrayInputStream(bytes), id -> {}));
    assertArrayEquals(bytes, storage.get(target).orElseThrow().readAllBytes());
    var duplicate = storage.prepareVerifiedUpload("lfs", sha, bytes.length);
    assertFalse(target.objectKey().equals(duplicate.objectKey()));
    storage.uploadVerified(duplicate, new ByteArrayInputStream(bytes), id -> {});
    storage.discardVerifiedUpload(duplicate, null);
    assertFalse(storage.exists(duplicate));
    assertArrayEquals(bytes, storage.get(target).orElseThrow().readAllBytes());
    var bad = storage.prepareVerifiedUpload("lfs", "f".repeat(64), bytes.length);
    assertThrows(com.github.klboke.kkrepo.core.BlobIntegrityException.class,
        () -> storage.uploadVerified(bad, new ByteArrayInputStream(bytes), id -> {}));
    assertFalse(storage.exists(bad));
    storage.discardVerifiedUpload(target, null);
    assertFalse(storage.exists(target));
  }

  @Test
  void storesReadsStatsAndDeletesBlobsFromDisk() throws Exception {
    FileBlobStorage storage = new FileBlobStorage(new FileBlobStoreConfig(1, "disk", "default", tempDir));
    byte[] bytes = "hello file blob".getBytes(StandardCharsets.UTF_8);

    BlobReference reference = storage.put(
        "maven-releases",
        "com/acme/demo.jar",
        new ByteArrayInputStream(bytes),
        bytes.length,
        "abcdef0123456789");

    assertTrue(reference.objectKey().startsWith(
        "repositories/maven-releases/blobs/v2/ab/cd/abcdef0123456789/"));
    assertArrayEquals(bytes, storage.get(reference).orElseThrow().readAllBytes());
    assertTrue(storage.stat(reference).orElseThrow().lastModified() != null);

    storage.delete(reference);

    assertFalse(storage.exists(reference));
  }

  @Test
  void exposesStagingDirectoryUnderBlobStoreRoot() {
    FileBlobStorage storage = new FileBlobStorage(new FileBlobStoreConfig(1, "disk", "default", tempDir));

    Path stagingDirectory = storage.stagingDirectory().orElseThrow();

    assertEquals(tempDir.resolve(".tmp").normalize(), stagingDirectory);
    assertTrue(Files.isDirectory(stagingDirectory));
  }

  @Test
  void rejectsObjectKeysEscapingTheStoreRoot() {
    FileBlobStorage storage = new FileBlobStorage(new FileBlobStoreConfig(1, "disk", "default", tempDir));

    assertThrows(
        IllegalArgumentException.class,
        () -> storage.exists(new BlobReference("disk", "../escape", "", 0)));
  }

  @Test
  void putFileInstallsBlobWithoutDependingOnSourceFileAfterReturn() throws Exception {
    FileBlobStorage storage = new FileBlobStorage(new FileBlobStoreConfig(1, "disk", "default", tempDir));
    Path source = tempDir.resolve("upload.bin");
    byte[] bytes = "uploaded file blob".getBytes(StandardCharsets.UTF_8);
    Files.write(source, bytes);

    BlobReference reference = storage.putFile("maven-releases", "com/acme/demo.jar", source, "1234567890abcdef");
    Files.delete(source);

    assertArrayEquals(bytes, storage.get(reference).orElseThrow().readAllBytes());
  }

  @Test
  void putFileRetriesCollisionWithoutOverwritingExistingObject() throws Exception {
    String existingKey = "repositories/maven-releases/blobs/v2/aa/bb/aabbcc/existing";
    String newKey = "repositories/maven-releases/blobs/v2/aa/bb/aabbcc/new";
    Files.createDirectories(tempDir.resolve(existingKey).getParent());
    Files.writeString(tempDir.resolve(existingKey), "existing", StandardCharsets.UTF_8);
    Path source = tempDir.resolve("upload.bin");
    Files.writeString(source, "new-content", StandardCharsets.UTF_8);
    AtomicInteger calls = new AtomicInteger();
    FileBlobStorage storage = new FileBlobStorage(
        new FileBlobStoreConfig(1, "disk", "default", tempDir),
        (repository, sha256) -> calls.getAndIncrement() == 0 ? existingKey : newKey);

    BlobReference reference = storage.putFile("maven-releases", "com/acme/demo.jar", source, "aabbcc");

    assertEquals(newKey, reference.objectKey());
    assertEquals(2, calls.get());
    assertEquals("existing", Files.readString(tempDir.resolve(existingKey), StandardCharsets.UTF_8));
    assertEquals("new-content", Files.readString(tempDir.resolve(newKey), StandardCharsets.UTF_8));
  }

  @Test
  void cachesShardDirectoryButCreatesDigestDirectoriesPerBlob() throws Exception {
    String firstKey = "repositories/maven-releases/blobs/v2/aa/bb/aabbcc/first";
    String secondKey = "repositories/maven-releases/blobs/v2/aa/bb/aabbdd/second";
    AtomicInteger createDirectoriesCalls = new AtomicInteger();
    FileBlobDirectoryCache cache = new FileBlobDirectoryCache(directory -> {
      createDirectoriesCalls.incrementAndGet();
      return Files.createDirectories(directory);
    });
    AtomicInteger keyCalls = new AtomicInteger();
    FileBlobStorage storage = new FileBlobStorage(
        new FileBlobStoreConfig(1, "disk", "default", tempDir),
        (repository, sha256) -> keyCalls.getAndIncrement() == 0 ? firstKey : secondKey,
        cache);
    Path source = tempDir.resolve("upload.bin");
    Files.writeString(source, "content", StandardCharsets.UTF_8);

    storage.putFile("maven-releases", "first.jar", source, "aabbcc");
    storage.putFile("maven-releases", "second.jar", source, "aabbdd");

    assertEquals(1, createDirectoriesCalls.get());
    assertTrue(Files.isDirectory(tempDir.resolve("repositories/maven-releases/blobs/v2/aa/bb")));
    assertTrue(Files.isDirectory(tempDir.resolve("repositories/maven-releases/blobs/v2/aa/bb/aabbcc")));
    assertTrue(Files.isDirectory(tempDir.resolve("repositories/maven-releases/blobs/v2/aa/bb/aabbdd")));
    assertTrue(Files.isRegularFile(tempDir.resolve(firstKey)));
    assertTrue(Files.isRegularFile(tempDir.resolve(secondKey)));
  }

  @Test
  void putFileCreatesMissingShardParentsWhenDirectoryCacheRacesAhead() throws Exception {
    String key = "repositories/docker-hosted/blobs/v2/bd/74/bd748bdf1590/object";
    AtomicInteger createDirectoriesCalls = new AtomicInteger();
    FileBlobDirectoryCache cache = new FileBlobDirectoryCache(directory -> {
      createDirectoriesCalls.incrementAndGet();
      return directory;
    });
    FileBlobStorage storage = new FileBlobStorage(
        new FileBlobStoreConfig(1, "disk", "default", tempDir),
        (repository, sha256) -> key,
        cache);
    Path source = tempDir.resolve("upload.bin");
    Files.writeString(source, "manifest", StandardCharsets.UTF_8);

    BlobReference reference = storage.putFile("docker-hosted", "v2/image/manifests/latest", source, "bd748bdf1590");

    assertEquals(key, reference.objectKey());
    assertEquals(1, createDirectoriesCalls.get());
    assertTrue(Files.isRegularFile(tempDir.resolve(key)));
  }

  @Test
  void putInputStreamCreatesMissingShardParentsWhenDirectoryCacheRacesAhead() throws Exception {
    String key = "repositories/docker-hosted/blobs/v2/bd/74/bd748bdf1590/object";
    AtomicInteger createDirectoriesCalls = new AtomicInteger();
    FileBlobDirectoryCache cache = new FileBlobDirectoryCache(directory -> {
      if (directory.endsWith(".tmp")) {
        return Files.createDirectories(directory);
      }
      createDirectoriesCalls.incrementAndGet();
      return directory;
    });
    FileBlobStorage storage = new FileBlobStorage(
        new FileBlobStoreConfig(1, "disk", "default", tempDir),
        (repository, sha256) -> key,
        cache);
    byte[] bytes = "manifest".getBytes(StandardCharsets.UTF_8);

    BlobReference reference = storage.put(
        "docker-hosted",
        "v2/image/manifests/latest",
        new ByteArrayInputStream(bytes),
        bytes.length,
        "bd748bdf1590");

    assertEquals(key, reference.objectKey());
    assertEquals(1, createDirectoriesCalls.get());
    assertTrue(Files.isRegularFile(tempDir.resolve(key)));
  }

  @Test
  void previousSingleDirectoryCreationWouldMissCachedShardParents() throws Exception {
    Path digestDirectory = tempDir.resolve("repositories/docker-hosted/blobs/v2/bd/74/bd748bdf1590");

    assertThrows(NoSuchFileException.class, () -> Files.createDirectory(digestDirectory));
  }

  @Test
  void readsRangesWithoutScanningFromStartWhenStoreSupportsIt() throws Exception {
    FileBlobStorage storage = new FileBlobStorage(new FileBlobStoreConfig(1, "disk", "default", tempDir));
    byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
    BlobReference reference = storage.put(
        "maven-releases",
        "com/acme/demo.jar",
        new ByteArrayInputStream(bytes),
        bytes.length,
        "0123456789abcdef");

    try (InputStream direct = storage.getRange(reference, 3, 4).orElseThrow()) {
      assertEquals("3456", new String(direct.readAllBytes(), StandardCharsets.UTF_8));
    }
    try (InputStream full = storage.get(reference).orElseThrow()) {
      assertTrue(full instanceof BlobRangeReader);
      try (InputStream direct = ((BlobRangeReader) full).openRange(7, 2)) {
        assertEquals("78", new String(direct.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void fileReadsExposeTransferableFileRegions() throws Exception {
    FileBlobStorage storage = new FileBlobStorage(new FileBlobStoreConfig(1, "disk", "default", tempDir));
    byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
    BlobReference reference = storage.put(
        "maven-releases",
        "com/acme/demo.jar",
        new ByteArrayInputStream(bytes),
        bytes.length,
        "fedcba9876543210");

    try (InputStream full = storage.get(reference).orElseThrow()) {
      assertTrue(full instanceof BlobFileRegion);
      BlobFileRegion region = (BlobFileRegion) full;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      region.transferFileRegionTo(out);

      assertTrue(Files.isRegularFile(region.path()));
      assertEquals(0L, region.position());
      assertEquals(bytes.length, region.count());
      assertArrayEquals(bytes, out.toByteArray());
    }

    try (InputStream range = storage.getRange(reference, 2, 5).orElseThrow()) {
      assertTrue(range instanceof BlobFileRegion);
      BlobFileRegion region = (BlobFileRegion) range;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      region.transferFileRegionTo(out);

      assertTrue(Files.isRegularFile(region.path()));
      assertEquals(2L, region.position());
      assertEquals(5L, region.count());
      assertEquals("23456", out.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void cleanupRemovesOnlyStaleTemporaryFiles() throws Exception {
    FileStorageProperties properties = new FileStorageProperties();
    properties.setBaseDir(tempDir.toString());
    FileBlobStorageFactory factory = new FileBlobStorageFactory(properties);
    FileBlobStoreAdmin admin = new FileBlobStoreAdmin(new FileBlobStorePathValidator(), factory);
    FileBlobStoreConfig config = new FileBlobStoreConfig(1, "disk", "default", tempDir);
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    Path oldTemp = tempDir.resolve(".tmp/blob-old.tmp");
    Path freshTemp = tempDir.resolve(".tmp/blob-fresh.tmp");
    Path liveObject = tempDir.resolve("repositories/maven-releases/blobs/v2/aa/bb/aabbcc/live");
    Files.createDirectories(oldTemp.getParent());
    Files.createDirectories(liveObject.getParent());
    Files.writeString(oldTemp, "old", StandardCharsets.UTF_8);
    Files.writeString(freshTemp, "fresh", StandardCharsets.UTF_8);
    Files.writeString(liveObject, "live", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(oldTemp, FileTime.from(now.minusSeconds(7200)));
    Files.setLastModifiedTime(freshTemp, FileTime.from(now.minusSeconds(60)));
    Files.setLastModifiedTime(liveObject, FileTime.from(now.minusSeconds(7200)));

    long deleted = admin.cleanupTemporaryFiles(config, now.minusSeconds(3600));

    assertEquals(1, deleted);
    assertFalse(Files.exists(oldTemp));
    assertTrue(Files.exists(freshTemp));
    assertTrue(Files.exists(liveObject));
  }

  @Test
  void rejectsOverlappingBlobStorePaths() {
    FileBlobStorePathValidator validator = new FileBlobStorePathValidator();
    FileBlobStoreConfig existing = new FileBlobStoreConfig(1, "existing", "existing", tempDir.resolve("existing"));
    FileBlobStoreConfig nested = new FileBlobStoreConfig(2, "nested", "existing/nested", tempDir.resolve("existing/nested"));

    assertThrows(IllegalArgumentException.class, () -> validator.validate(nested, List.of(existing)));
  }
}
