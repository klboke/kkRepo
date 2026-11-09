package com.github.klboke.kkrepo.storage.azure.admin;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.storage.azure.AzureBlobStorageFactory;
import com.github.klboke.kkrepo.storage.azure.AzureBlobStoreConfig;
import com.github.klboke.kkrepo.storage.azure.AzureClientFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Administrative reachability and read/write checks for Azure blob stores. */
@Service
public class AzureBlobStoreAdmin {
  private final AzureClientFactory clientFactory;
  private final AzureBlobStorageFactory storageFactory;

  public AzureBlobStoreAdmin(
      AzureClientFactory clientFactory, AzureBlobStorageFactory storageFactory) {
    this.clientFactory = clientFactory;
    this.storageFactory = storageFactory;
  }

  public AzureContainerSummary summary(AzureBlobStoreConfig config) {
    try {
      BlobContainerClient container =
          clientFactory.client(config).getBlobContainerClient(config.container());
      // A bounded list confirms both that the container exists and that the identity may read it,
      // which a plain exists() check cannot distinguish from a permission failure.
      container.listBlobs(new ListBlobsOptions().setMaxResultsPerPage(1), Duration.ofSeconds(30))
          .iterator()
          .hasNext();
      return summary(config, "Started", true, "Azure Blob service reachable");
    } catch (BlobStorageException e) {
      if (e.getStatusCode() == 404) {
        return summary(config, "Missing", false, "Container does not exist");
      }
      return summary(config, "Unavailable", false, message(e));
    } catch (RuntimeException e) {
      return summary(config, "Unavailable", false, message(e));
    }
  }

  public AzureProbeResult probeReadWrite(AzureBlobStoreConfig config) {
    AzureContainerSummary current = summary(config);
    if (!current.containerExists()) {
      return new AzureProbeResult(false, "", current.message(), current);
    }
    BlobStorage blobStorage = storageFactory.forStore(config);
    String payload = "kkrepo-azure-probe " + Instant.now();
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    String objectKey = "dev/probe-" + System.currentTimeMillis() + ".txt";
    BlobReference reference =
        blobStorage.put("_system", objectKey, new ByteArrayInputStream(bytes), bytes.length, "");
    try (var stream = blobStorage.get(reference).orElseThrow()) {
      String actual = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      if (!payload.equals(actual)) {
        return new AzureProbeResult(
            false, reference.objectKey(), "Read content does not match written content", summary(config));
      }
      return new AzureProbeResult(
          true, reference.objectKey(), "Read/write probe succeeded", summary(config));
    } catch (Exception e) {
      return new AzureProbeResult(false, reference.objectKey(), e.getMessage(), summary(config));
    } finally {
      blobStorage.delete(reference);
    }
  }

  private static AzureContainerSummary summary(
      AzureBlobStoreConfig config, String state, boolean exists, String message) {
    return new AzureContainerSummary(
        config.name(),
        endpointQuietly(config),
        config.resolvedAccountName(),
        config.container(),
        state,
        exists,
        0,
        0,
        message);
  }

  private static String endpointQuietly(AzureBlobStoreConfig config) {
    try {
      return config.serviceEndpoint();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static String message(RuntimeException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
