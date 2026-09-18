package com.github.klboke.kkrepo.storage.s3.admin;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.storage.s3.OssClientFactory;
import com.github.klboke.kkrepo.storage.s3.OssNativeBlobStorage;
import com.github.klboke.kkrepo.storage.s3.S3BlobStorageFactory;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.S3ClientFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class S3BlobStoreAdmin {
  private final S3ClientFactory clientFactory;
  private final S3BlobStorageFactory storageFactory;
  private final OssClientFactory ossClientFactory;

  public S3BlobStoreAdmin(
      S3ClientFactory clientFactory,
      S3BlobStorageFactory storageFactory,
      OssClientFactory ossClientFactory) {
    this.clientFactory = clientFactory;
    this.storageFactory = storageFactory;
    this.ossClientFactory = ossClientFactory;
  }

  public S3BucketSummary summary(S3BlobStoreConfig config) {
    if (config.usesOssNative()) {
      return ossNativeSummary(config);
    }
    S3Client client = clientFactory.client(config);
    try {
      if (!bucketExists(client, config)) {
        return new S3BucketSummary(
            config.name(),
            config.endpoint(),
            config.region(),
            config.bucket(),
            "Missing",
            false,
            0,
            0,
            "Bucket does not exist");
      }

      return new S3BucketSummary(
          config.name(),
          config.endpoint(),
          config.region(),
          config.bucket(),
          "Started",
          true,
          0,
          0,
          "S3 API reachable");
    } catch (RuntimeException e) {
      return new S3BucketSummary(
          config.name(),
          config.endpoint(),
          config.region(),
          config.bucket(),
          "Unavailable",
          false,
          0,
          0,
          e instanceof S3Exception s3 && s3.awsErrorDetails() != null
              ? s3.awsErrorDetails().errorMessage() : e.getMessage());
    }
  }

  public S3ProbeResult probeReadWrite(S3BlobStoreConfig config) {
    S3BucketSummary current = summary(config);
    if (!current.bucketExists()) {
      return new S3ProbeResult(false, "", current.message(), current);
    }
    BlobStorage blobStorage = storageFactory.forStore(config);
    String payload = "kkrepo-rustfs-probe " + Instant.now();
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    String objectKey = "dev/probe-" + System.currentTimeMillis() + ".txt";
    BlobReference reference = blobStorage.put(
        "_system",
        objectKey,
        new ByteArrayInputStream(bytes),
        bytes.length,
        "");
    try (var stream = blobStorage.get(reference).orElseThrow()) {
      String actual = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      if (!payload.equals(actual)) {
        return new S3ProbeResult(false, reference.objectKey(), "Read content does not match written content", summary(config));
      }
      return new S3ProbeResult(true, reference.objectKey(), "Read/write probe succeeded", summary(config));
    } catch (Exception e) {
      return new S3ProbeResult(false, reference.objectKey(), e.getMessage(), summary(config));
    } finally {
      blobStorage.delete(reference);
    }
  }

  private boolean bucketExists(S3Client client, S3BlobStoreConfig config) {
    // Probe with listObjectsV2 rather than headBucket: Aliyun OSS's S3-compatible API rejects
    // HeadBucket with 403 unless the credential has bucket-level permissions, even when object
    // read/write works fine, so headBucket gives false negatives on OSS. listObjectsV2 works on
    // both AWS S3 and OSS and also confirms list access.
    try {
      client.listObjectsV2(ListObjectsV2Request.builder()
          .bucket(config.bucket())
          .maxKeys(1)
          .build());
      return true;
    } catch (NoSuchBucketException e) {
      return false;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  private S3BucketSummary ossNativeSummary(S3BlobStoreConfig config) {
    try {
      OSSClient client = ossClientFactory.client(config);
      // listObjectsV2(maxKeys=1) confirms bucket reachability + list access via the native SDK.
      client.listObjectsV2(com.aliyun.sdk.service.oss2.models.ListObjectsV2Request.newBuilder()
          .bucket(config.bucket())
          .maxKeys(1L)
          .build());
      return new S3BucketSummary(
          config.name(), config.endpoint(), config.region(), config.bucket(),
          "Started", true, 0, 0, "OSS SDK reachable");
    } catch (RuntimeException e) {
      if (OssNativeBlobStorage.isNotFound(e)) {
        return new S3BucketSummary(
            config.name(), config.endpoint(), config.region(), config.bucket(),
            "Missing", false, 0, 0, "Bucket does not exist");
      }
      return new S3BucketSummary(
          config.name(), config.endpoint(), config.region(), config.bucket(),
          "Unavailable", false, 0, 0, e.getMessage());
    }
  }

}
