package com.github.klboke.kkrepo.storage.azure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.klboke.kkrepo.core.BlobIntegrityException;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.VerifiedBlobDigests;
import com.github.klboke.kkrepo.storage.azure.admin.AzureBlobStoreAdmin;
import com.github.klboke.kkrepo.storage.azure.admin.AzureContainerSummary;
import com.github.klboke.kkrepo.storage.azure.admin.AzureProbeResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the adapter against Azurite, Microsoft's Azure Storage emulator, so the block-blob
 * upload path, range reads and metadata handling are verified against a real implementation of the
 * Blob REST API rather than mocks.
 */
class AzureBlobStorageAzuriteIntegrationTest {
  /** Well-known Azurite development account; not a credential for any real storage account. */
  private static final String ACCOUNT = "devstoreaccount1";
  private static final String ACCOUNT_KEY =
      "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
  private static final String CONTAINER = "artifacts";
  private static final int BLOB_PORT = 10000;

  private static GenericContainer<?> azurite;
  private static AzureClientFactory clientFactory;
  private static AzureBlobStorageFactory storageFactory;
  private static AzureBlobStoreConfig config;

  @TempDir
  Path directory;

  @BeforeAll
  static void startAzurite() {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for the Azurite integration test");
    azurite = new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
        .withExposedPorts(BLOB_PORT)
        .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
        .waitingFor(Wait.forListeningPort());
    azurite.start();

    config = config("");
    clientFactory = new AzureClientFactory();
    storageFactory = new AzureBlobStorageFactory(clientFactory);
    clientFactory.client(config).createBlobContainerIfNotExists(CONTAINER);
  }

  @AfterAll
  static void stopAzurite() {
    if (azurite != null) {
      azurite.stop();
    }
  }

  @Test
  void writesAndReadsABlobThroughTheGenericBlobApi() throws Exception {
    BlobStorage storage = storageFactory.forStore(config);
    byte[] content = "kkrepo azurite payload".getBytes(StandardCharsets.UTF_8);

    BlobReference reference = storage.put(
        "maven-releases", "org/example/artifact.jar", new ByteArrayInputStream(content),
        content.length, sha256(content));

    assertEquals(CONTAINER, reference.bucket());
    assertTrue(storage.exists(reference));
    try (InputStream stream = storage.get(reference).orElseThrow()) {
      assertArrayEquals(content, stream.readAllBytes());
    }
  }

  @Test
  void statReportsSizeAndTheStoredDigest() {
    BlobStorage storage = storageFactory.forStore(config);
    byte[] content = "stat payload".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(content);
    BlobReference reference = storage.put(
        "npm-hosted", "pkg.tgz", new ByteArrayInputStream(content), content.length, digest);

    BlobObjectMetadata metadata = storage.stat(reference).orElseThrow();

    assertEquals(content.length, metadata.reference().size());
    assertEquals(digest, metadata.reference().sha256());
    assertEquals("application/octet-stream", metadata.contentType());
  }

  @Test
  void rangeReadsReturnOnlyTheRequestedBytes() throws Exception {
    BlobStorage storage = storageFactory.forStore(config);
    byte[] content = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    BlobReference reference = storage.put(
        "docker", "layer", new ByteArrayInputStream(content), content.length, sha256(content));

    try (InputStream stream = storage.getRange(reference, 4, 6).orElseThrow()) {
      assertEquals("456789", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    try (InputStream stream = storage.getRange(reference, 10, 6).orElseThrow()) {
      assertEquals("abcdef", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void missingBlobsReadAsEmptyRatherThanFailing() {
    BlobStorage storage = storageFactory.forStore(config);
    BlobReference missing = new BlobReference(CONTAINER, "absent/key", sha256(new byte[0]), 0);

    assertFalse(storage.exists(missing));
    assertEquals(Optional.empty(), storage.stat(missing));
    assertEquals(Optional.empty(), storage.get(missing));
    // Deleting an absent blob must stay idempotent for cleanup workers.
    storage.delete(missing);
  }

  @Test
  void verifiedUploadsStageAndCommitBlockBlobs() throws Exception {
    BlobStorage storage = storageFactory.forStore(config);
    byte[] content = "verified block blob content".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(content);
    BlobReference target = storage.prepareVerifiedUpload("maven-releases", digest, content.length);
    AtomicReference<String> handle = new AtomicReference<>();

    VerifiedBlobDigests digests =
        storage.uploadVerified(target, new ByteArrayInputStream(content), handle::set);

    assertEquals(digest, digests.sha256());
    assertEquals(content.length, digests.size());
    assertTrue(handle.get() != null && !handle.get().isBlank(), "the upload handle must be published");
    try (InputStream stream = storage.get(target).orElseThrow()) {
      assertArrayEquals(content, stream.readAllBytes());
    }
  }

  @Test
  void aVerifiedUploadLargerThanOneBlockIsCommittedIntact() throws Exception {
    BlobStorage storage = storageFactory.forStore(config);
    // VerifiedBlobReader emits 8 MiB parts, so this crosses the multi-block boundary.
    byte[] content = new byte[9 * 1024 * 1024];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) (i % 251);
    }
    String digest = sha256(content);
    BlobReference target = storage.prepareVerifiedUpload("raw", digest, content.length);

    VerifiedBlobDigests digests =
        storage.uploadVerified(target, new ByteArrayInputStream(content), id -> { });

    assertEquals(digest, digests.sha256());
    try (InputStream stream = storage.get(target).orElseThrow()) {
      assertArrayEquals(content, stream.readAllBytes());
    }
  }

  @Test
  void anEmptyVerifiedUploadCommitsAnEmptyBlob() throws Exception {
    BlobStorage storage = storageFactory.forStore(config);
    String digest = sha256(new byte[0]);
    BlobReference target = storage.prepareVerifiedUpload("raw", digest, 0);

    storage.uploadVerified(target, new ByteArrayInputStream(new byte[0]), id -> { });

    assertTrue(storage.exists(target));
    try (InputStream stream = storage.get(target).orElseThrow()) {
      assertEquals(0, stream.readAllBytes().length);
    }
  }

  @Test
  void contentThatContradictsTheDigestIsNeverPublished() {
    BlobStorage storage = storageFactory.forStore(config);
    byte[] declared = "expected".getBytes(StandardCharsets.UTF_8);
    BlobReference target =
        storage.prepareVerifiedUpload("raw", sha256(declared), declared.length);

    assertThrows(BlobIntegrityException.class, () -> storage.uploadVerified(
        target, new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8)), id -> { }));

    assertFalse(storage.exists(target), "a failed attempt must leave nothing readable behind");
  }

  @Test
  void discardingAnAttemptRemovesTheUnpublishedBlob() {
    BlobStorage storage = storageFactory.forStore(config);
    byte[] content = "discarded".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(content);
    BlobReference target = storage.prepareVerifiedUpload("raw", digest, content.length);
    storage.uploadVerified(target, new ByteArrayInputStream(content), id -> { });
    assertTrue(storage.exists(target));

    storage.discardVerifiedUpload(target, "attempt-id");

    assertFalse(storage.exists(target));
  }

  @Test
  void filesAreUploadedFromDiskWithTheirDigestMetadata() throws Exception {
    BlobStorage storage = storageFactory.forStore(config);
    byte[] content = "file upload payload".getBytes(StandardCharsets.UTF_8);
    Path file = Files.write(directory.resolve("artifact.bin"), content);

    BlobReference reference = storage.putFile("maven-releases", "artifact.bin", file, sha256(content));

    assertEquals(content.length, reference.size());
    try (InputStream stream = storage.get(reference).orElseThrow()) {
      assertArrayEquals(content, stream.readAllBytes());
    }
  }

  @Test
  void theStorePrefixIsAppliedToStoredKeys() throws Exception {
    AzureBlobStoreConfig prefixed = config("team/eu");
    BlobStorage storage = storageFactory.forStore(prefixed);
    byte[] content = "prefixed".getBytes(StandardCharsets.UTF_8);

    BlobReference reference = storage.put(
        "raw", "a.txt", new ByteArrayInputStream(content), content.length, sha256(content));

    assertTrue(reference.objectKey().startsWith("team/eu/repositories/raw/"), reference.objectKey());
    try (InputStream stream = storage.get(reference).orElseThrow()) {
      assertArrayEquals(content, stream.readAllBytes());
    }
  }

  @Test
  void theAdminProbeReportsAReachableContainer() {
    AzureBlobStoreAdmin admin = new AzureBlobStoreAdmin(clientFactory, storageFactory);

    AzureContainerSummary summary = admin.summary(config);
    AzureProbeResult probe = admin.probeReadWrite(config);

    assertTrue(summary.containerExists(), summary.message());
    assertEquals("Started", summary.state());
    assertTrue(probe.ok(), probe.message());
  }

  @Test
  void theAdminProbeReportsAMissingContainer() {
    AzureBlobStoreAdmin admin = new AzureBlobStoreAdmin(clientFactory, storageFactory);
    AzureBlobStoreConfig missing = new AzureBlobStoreConfig(
        99, "azure-missing", endpoint(), ACCOUNT, "does-not-exist", "",
        AzureCredentialType.ACCOUNT_KEY, ACCOUNT_KEY, "", "", "", "", "",
        3, 5000, 120000, 64L * 1024 * 1024, 16L * 1024 * 1024, 4);

    AzureContainerSummary summary = admin.summary(missing);

    assertFalse(summary.containerExists());
    assertEquals("Missing", summary.state());
  }

  private static AzureBlobStoreConfig config(String prefix) {
    return new AzureBlobStoreConfig(
        1, "azure-azurite", endpoint(), ACCOUNT, CONTAINER, prefix,
        AzureCredentialType.ACCOUNT_KEY, ACCOUNT_KEY, "", "", "", "", "",
        3, 5000, 120000, 64L * 1024 * 1024, 16L * 1024 * 1024, 4);
  }

  private static String endpoint() {
    return "http://" + azurite.getHost() + ":" + azurite.getMappedPort(BLOB_PORT) + "/" + ACCOUNT;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
