package com.github.klboke.kkrepo.storage.azure.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.storage.azure.AzureBlobStorageFactory;
import com.github.klboke.kkrepo.storage.azure.AzureBlobStoreConfig;
import com.github.klboke.kkrepo.storage.azure.AzureClientFactory;
import com.github.klboke.kkrepo.storage.azure.AzureCredentialType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Failure handling of the admin checks; the happy path runs against Azurite in the IT. */
class AzureBlobStoreAdminTest {
  private static final BlobReference PROBE = new BlobReference("artifacts", "dev/probe.txt", "", 1);

  private AzureClientFactory clients;
  private AzureBlobStorageFactory storages;
  private BlobContainerClient container;
  private BlobStorage storage;
  private AzureBlobStoreAdmin admin;

  @BeforeEach
  void setUp() {
    clients = mock(AzureClientFactory.class);
    storages = mock(AzureBlobStorageFactory.class);
    BlobServiceClient service = mock(BlobServiceClient.class);
    container = mock(BlobContainerClient.class);
    storage = mock(BlobStorage.class);
    when(clients.client(any())).thenReturn(service);
    when(service.getBlobContainerClient(anyString())).thenReturn(container);
    when(storages.forStore(any())).thenReturn(storage);
    when(storage.put(eq("_system"), anyString(), any(), anyLong(), eq(""))).thenReturn(PROBE);
    admin = new AzureBlobStoreAdmin(clients, storages);
  }

  @Test
  void aMissingContainerIsReportedAsMissing() {
    listingFails(storageError(404, "ContainerNotFound"));

    AzureContainerSummary summary = admin.summary(config("kkrepo"));

    assertEquals("Missing", summary.state());
    assertFalse(summary.containerExists());
    assertEquals("Container does not exist", summary.message());
  }

  @Test
  void aPermissionFailureIsUnavailableWithTheServiceMessage() {
    listingFails(storageError(403, "AuthorizationPermissionMismatch"));

    AzureContainerSummary summary = admin.summary(config("kkrepo"));

    assertEquals("Unavailable", summary.state());
    assertEquals("AuthorizationPermissionMismatch", summary.message());
  }

  @Test
  void aClientThatCannotBeBuiltIsUnavailableEvenWithoutAnEndpoint() {
    when(clients.client(any())).thenThrow(new IllegalStateException());

    // No account and no endpoint: the summary still renders instead of failing.
    AzureContainerSummary summary = admin.summary(config(""));

    assertEquals("Unavailable", summary.state());
    assertEquals("IllegalStateException", summary.message());
    assertEquals("", summary.endpoint());
  }

  @Test
  void theProbeStopsBeforeWritingWhenTheContainerIsUnreachable() {
    listingFails(storageError(404, "ContainerNotFound"));

    AzureProbeResult result = admin.probeReadWrite(config("kkrepo"));

    assertFalse(result.ok());
    assertEquals("", result.objectKey());
    verify(storages, never()).forStore(any());
  }

  @Test
  void theProbeReportsContentThatReadsBackDifferently() {
    listingSucceeds();
    when(storage.get(PROBE)).thenReturn(Optional.of(
        new ByteArrayInputStream("something else".getBytes(StandardCharsets.UTF_8))));

    AzureProbeResult result = admin.probeReadWrite(config("kkrepo"));

    assertFalse(result.ok());
    assertEquals("Read content does not match written content", result.message());
    verify(storage).delete(PROBE);
  }

  @Test
  void theProbeReportsABlobThatCannotBeReadBackAndStillCleansUp() {
    listingSucceeds();
    when(storage.get(PROBE)).thenReturn(Optional.empty());

    AzureProbeResult result = admin.probeReadWrite(config("kkrepo"));

    assertFalse(result.ok());
    assertEquals("dev/probe.txt", result.objectKey());
    verify(storage).delete(PROBE);
  }

  @Test
  void aReachableContainerIsStarted() {
    listingSucceeds();

    AzureContainerSummary summary = admin.summary(config("kkrepo"));

    assertTrue(summary.containerExists());
    assertEquals("https://kkrepo.blob.core.windows.net", summary.endpoint());
  }

  @SuppressWarnings("unchecked")
  private void listingSucceeds() {
    PagedIterable<BlobItem> page = mock(PagedIterable.class);
    when(page.iterator()).thenReturn(List.<BlobItem>of().iterator());
    when(container.listBlobs(any(ListBlobsOptions.class), any(Duration.class))).thenReturn(page);
  }

  private void listingFails(RuntimeException failure) {
    when(container.listBlobs(any(ListBlobsOptions.class), any(Duration.class))).thenThrow(failure);
  }

  private static BlobStorageException storageError(int status, String message) {
    BlobStorageException error = mock(BlobStorageException.class);
    when(error.getStatusCode()).thenReturn(status);
    when(error.getMessage()).thenReturn(message);
    return error;
  }

  private static AzureBlobStoreConfig config(String accountName) {
    return new AzureBlobStoreConfig(
        1, "azure-store", "", accountName, "artifacts", "", AzureCredentialType.DEFAULT,
        "", "", "", "", "", "", 3, 5000, 120000, 64L * 1024 * 1024, 16L * 1024 * 1024, 4);
  }
}
