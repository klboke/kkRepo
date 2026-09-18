package com.github.klboke.kkrepo.storage.s3.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.S3ClientFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

class S3BlobStoreAdminTest {
  @Test
  void missingRoleCredentialsAreReportedByHealthCheckWithoutClaimingBucketIsMissing() {
    var config = S3BlobStoreConfig.of(1, "role", "https://s3.us-east-1.amazonaws.com",
        "us-east-1", "bucket", "", Map.of());
    var factory = mock(S3ClientFactory.class);
    var client = mock(S3Client.class);
    when(factory.client(config)).thenReturn(client);
    when(client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenThrow(SdkClientException.create("Unable to load credentials from the default provider chain"));
    var result = new S3BlobStoreAdmin(factory, null, null).probeReadWrite(config);
    assertFalse(result.ok());
    assertEquals("Unavailable", result.summary().state());
    assertEquals("Unable to load credentials from the default provider chain", result.message());
  }
}
