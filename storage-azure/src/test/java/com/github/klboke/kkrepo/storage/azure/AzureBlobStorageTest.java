package com.github.klboke.kkrepo.storage.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.azure.storage.blob.options.BlockBlobCommitBlockListOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.github.klboke.kkrepo.core.BlobIntegrityException;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.VerifiedBlobDigests;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AzureBlobStorageTest {
  private static final String HELLO = "hello";
  private static final String HELLO_SHA256 =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
  private static final String EMPTY_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private BlobServiceClient service;
  private BlobClient blob;
  private BlockBlobClient block;
  private AzureBlobStorage storage;

  @BeforeEach
  void setUp() {
    service = mock(BlobServiceClient.class);
    BlobContainerClient container = mock(BlobContainerClient.class);
    blob = mock(BlobClient.class);
    block = mock(BlockBlobClient.class);
    when(service.getBlobContainerClient(anyString())).thenReturn(container);
    when(container.getBlobClient(anyString())).thenReturn(blob);
    when(blob.getBlockBlobClient()).thenReturn(block);
    storage = new AzureBlobStorage(service, config(""));
  }

  @Test
  void preparedUploadKeysAreContentAddressedShardedAndAttemptUnique() {
    BlobReference first = storage.prepareVerifiedUpload("maven-releases", HELLO_SHA256, 5);
    BlobReference second = storage.prepareVerifiedUpload("maven-releases", HELLO_SHA256, 5);

    assertEquals("artifacts", first.bucket());
    assertTrue(first.objectKey().startsWith("repositories/maven-releases/blobs/v2/2c/f2/" + HELLO_SHA256 + "/"),
        first.objectKey());
    assertNotEquals(first.objectKey(), second.objectKey());
  }

  @Test
  void preparedUploadKeysHonourTheStorePrefix() {
    AzureBlobStorage prefixed = new AzureBlobStorage(service, config("team/eu"));

    assertTrue(prefixed.prepareVerifiedUpload("npm", HELLO_SHA256, 5).objectKey()
        .startsWith("team/eu/repositories/npm/blobs/v2/"));
  }

  @Test
  void verifiedUploadStagesBlocksThenCommitsThem() {
    BlobReference target = new BlobReference("artifacts", "key", HELLO_SHA256, HELLO.length());

    VerifiedBlobDigests digests =
        storage.uploadVerified(target, stream(HELLO), id -> { });

    assertEquals(HELLO_SHA256, digests.sha256());
    assertEquals(HELLO.length(), digests.size());
    ArgumentCaptor<BlockBlobCommitBlockListOptions> committed =
        ArgumentCaptor.forClass(BlockBlobCommitBlockListOptions.class);
    verify(block).commitBlockListWithResponse(committed.capture(), any(), any());
    assertEquals(1, committed.getValue().getBase64BlockIds().size());
    assertEquals(HELLO_SHA256, committed.getValue().getMetadata().get("sha256"));
  }

  @Test
  void theUploadHandleIsPublishedBeforeAnyBlockIsStaged() {
    BlobReference target = new BlobReference("artifacts", "key", HELLO_SHA256, HELLO.length());
    List<String> handles = new ArrayList<>();

    storage.uploadVerified(target, stream(HELLO), handles::add);

    assertEquals(1, handles.size());
    assertTrue(handles.getFirst() != null && !handles.getFirst().isBlank());
    // The observer must persist the handle before any data is sent, so a crashed attempt is
    // always recoverable by the caller.
    var ordered = inOrder(block);
    ordered.verify(block).stageBlock(anyString(), any(), anyLong());
    ordered.verify(block).commitBlockListWithResponse(any(), any(), any());
  }

  @Test
  void anEmptyBlobCommitsAnEmptyBlockListWithoutStagingAnything() {
    BlobReference target = new BlobReference("artifacts", "key", EMPTY_SHA256, 0);

    VerifiedBlobDigests digests = storage.uploadVerified(target, stream(""), id -> { });

    assertEquals(EMPTY_SHA256, digests.sha256());
    verify(block, never()).stageBlock(anyString(), any(), anyLong());
    ArgumentCaptor<BlockBlobCommitBlockListOptions> committed =
        ArgumentCaptor.forClass(BlockBlobCommitBlockListOptions.class);
    verify(block).commitBlockListWithResponse(committed.capture(), any(), any());
    assertTrue(committed.getValue().getBase64BlockIds().isEmpty());
  }

  @Test
  void contentThatDoesNotMatchTheExpectedDigestIsNeverCommitted() {
    BlobReference target = new BlobReference("artifacts", "key", HELLO_SHA256, HELLO.length());

    assertThrows(BlobIntegrityException.class,
        () -> storage.uploadVerified(target, stream("world"), id -> { }));

    verify(block, never()).commitBlockListWithResponse(any(), any(), any());
    // The unpublished attempt key is removed so a retry never inherits partial data.
    verify(blob).deleteIfExists();
  }

  @Test
  void discardingAnAttemptDeletesOnlyThatAttemptsKey() {
    BlobReference target = new BlobReference("artifacts", "attempt-key", HELLO_SHA256, 5);

    storage.discardVerifiedUpload(target, "attempt-id");

    verify(blob).deleteIfExists();
  }

  @Test
  void statMapsAzurePropertiesOntoBlobMetadata() {
    OffsetDateTime modified = OffsetDateTime.parse("2024-05-04T10:15:30Z");
    BlobProperties properties = mock(BlobProperties.class);
    when(properties.getBlobSize()).thenReturn(5L);
    when(properties.getETag()).thenReturn("\"0x8DC\"");
    when(properties.getContentType()).thenReturn("application/octet-stream");
    when(properties.getLastModified()).thenReturn(modified);
    when(properties.getMetadata()).thenReturn(Map.of("sha256", HELLO_SHA256));
    when(blob.getProperties()).thenReturn(properties);

    Optional<BlobObjectMetadata> stat =
        storage.stat(new BlobReference("artifacts", "key", "", 0));

    assertTrue(stat.isPresent());
    assertEquals(5L, stat.get().reference().size());
    assertEquals(HELLO_SHA256, stat.get().reference().sha256());
    assertEquals("\"0x8DC\"", stat.get().eTag());
    assertEquals(modified.toInstant(), stat.get().lastModified());
  }

  @Test
  void rangeReadsRejectNegativeBoundsAndShortCircuitEmptyRanges() {
    BlobReference reference = new BlobReference("artifacts", "key", HELLO_SHA256, 5);

    assertThrows(IllegalArgumentException.class, () -> storage.getRange(reference, -1, 5));
    assertThrows(IllegalArgumentException.class, () -> storage.getRange(reference, 0, -5));
    assertTrue(storage.getRange(reference, 0, 0).isPresent());
  }

  @Test
  void blockIdsAreEqualLengthBase64AndUniquePerIndex() {
    String attempt = "0123456789abcdef0123456789abcdef";

    String first = AzureBlobStorage.blockId(attempt, 0);
    String second = AzureBlobStorage.blockId(attempt, 1);
    String large = AzureBlobStorage.blockId(attempt, 49_999);

    assertNotEquals(first, second);
    assertEquals(first.length(), second.length());
    assertEquals(first.length(), large.length());
    assertEquals(attempt + "-000000",
        new String(java.util.Base64.getDecoder().decode(first), StandardCharsets.UTF_8));
  }

  @Test
  void blockIdsRefuseToExceedTheAzureBlockLimit() {
    assertThrows(IllegalArgumentException.class,
        () -> AzureBlobStorage.blockId("attempt", AzureBlobStoreConfig.MAX_BLOCKS));
  }

  @Test
  void metadataValuesAreReducedToPrintableAscii() {
    assertEquals("org/example_artifact", AzureBlobStorage.safeMetadata("org/example\nartifact"));
    assertEquals("caf_", AzureBlobStorage.safeMetadata("caf\u00e9"));
    assertEquals("", AzureBlobStorage.safeMetadata(null));
  }

  @Test
  void deleteIsIdempotentForMissingBlobs() {
    when(blob.deleteIfExists()).thenReturn(false);

    storage.delete(new BlobReference("artifacts", "key", HELLO_SHA256, 5));

    verify(blob).deleteIfExists();
  }

  @Test
  void streamedPutsAreSpooledToDiskAndTheSpoolFileIsRemoved() {
    List<String> uploaded = new ArrayList<>();
    when(blob.uploadFromFileWithResponse(any(BlobUploadFromFileOptions.class), any(), any()))
        .thenAnswer(invocation -> {
          BlobUploadFromFileOptions options = invocation.getArgument(0);
          // The SDK reads a complete, re-readable file instead of buffering the stream in heap.
          uploaded.add(Files.readString(Path.of(options.getFilePath())));
          assertEquals(HELLO_SHA256, options.getMetadata().get("sha256"));
          assertEquals("org/example/a.jar", options.getMetadata().get("logicalpath"));
          return null;
        });

    BlobReference reference =
        storage.put("maven-releases", "org/example/a.jar", stream(HELLO), HELLO.length(), HELLO_SHA256);

    assertEquals(List.of(HELLO), uploaded);
    assertEquals("artifacts", reference.bucket());
    assertEquals(HELLO.length(), reference.size());
    ArgumentCaptor<BlobUploadFromFileOptions> options =
        ArgumentCaptor.forClass(BlobUploadFromFileOptions.class);
    verify(blob).uploadFromFileWithResponse(options.capture(), any(), any());
    assertFalse(Files.exists(Path.of(options.getValue().getFilePath())));
  }

  @Test
  void referencesWithoutAContainerFallBackToTheStoreContainer() {
    storage.delete(new BlobReference("", "key", HELLO_SHA256, 5));

    verify(service).getBlobContainerClient(eq("artifacts"));
  }

  private static ByteArrayInputStream stream(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }

  private static AzureBlobStoreConfig config(String prefix) {
    return new AzureBlobStoreConfig(
        1, "azure-store", "", "kkrepo", "artifacts", prefix, AzureCredentialType.DEFAULT,
        "", "", "", "", "", "", 3, 5000, 120000, 64L * 1024 * 1024, 16L * 1024 * 1024, 4);
  }
}
