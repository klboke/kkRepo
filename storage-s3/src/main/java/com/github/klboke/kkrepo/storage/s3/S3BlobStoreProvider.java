package com.github.klboke.kkrepo.storage.s3;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreTypes;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exposes the S3/OSS adapter through the generic provider SPI. Values missing from the blob-store
 * row fall back to {@code kkrepo.storage.s3.*} so pre-existing rows keep their effective settings.
 */
@Component
public class S3BlobStoreProvider implements BlobStoreProvider {
  private static final Set<String> ALIASES =
      Set.of("s3", "aws", "aws-s3", "oss", "oss-native", "oss-sdk", "oss-v2");

  private final S3BlobStorageFactory factory;
  private final S3StorageProperties fallback;

  public S3BlobStoreProvider(S3BlobStorageFactory factory, S3StorageProperties fallback) {
    this.factory = factory;
    this.fallback = fallback;
  }

  @Override
  public String type() {
    return BlobStoreTypes.S3;
  }

  @Override
  public boolean supports(BlobStoreDescriptor descriptor) {
    return ALIASES.contains(descriptor.normalizedType()) || ALIASES.contains(descriptor.normalizedEngine());
  }

  @Override
  public String signature(BlobStoreDescriptor descriptor) {
    return configFor(descriptor).signature();
  }

  @Override
  public BlobStorage create(BlobStoreDescriptor descriptor) {
    if (factory == null) {
      throw new IllegalStateException("S3 blob storage is not available on this node");
    }
    return factory.forStore(configFor(descriptor));
  }

  @Override
  public String engine(BlobStoreDescriptor descriptor) {
    return configFor(descriptor).engine();
  }

  @Override
  public Set<Long> cachedStoreIdsMissingFrom(Set<Long> activeStoreIds) {
    return factory == null ? Set.of() : factory.cachedStoreIdsMissingFrom(activeStoreIds);
  }

  @Override
  public void invalidate(long storeId) {
    if (factory != null) {
      factory.invalidate(storeId);
    }
  }

  public S3BlobStoreConfig configFor(BlobStoreDescriptor descriptor) {
    return new S3BlobStoreConfig(
        descriptor.id(),
        descriptor.name(),
        descriptor.stringAttr("engine", fallback.getEngine()),
        valueOrDefault(descriptor.endpoint(), fallback.getEndpoint()),
        valueOrDefault(descriptor.region(), fallback.getRegion()),
        valueOrDefault(descriptor.bucket(), fallback.getBucket()),
        descriptor.prefix(),
        S3BlobStoreConfig.credentialAttribute(descriptor.attributes(), "accessKey", fallback.getAccessKey()),
        S3BlobStoreConfig.credentialAttribute(descriptor.attributes(), "secretKey", fallback.getSecretKey()),
        descriptor.boolAttr("pathStyleAccess", fallback.isPathStyleAccess()),
        descriptor.intAttr("maxConnections", fallback.getMaxConnections()),
        descriptor.intAttr("connectionTimeoutMs", fallback.getConnectionTimeoutMs()),
        descriptor.intAttr("socketTimeoutMs", fallback.getSocketTimeoutMs()),
        descriptor.intAttr("connectionAcquisitionTimeoutMs", fallback.getConnectionAcquisitionTimeoutMs()),
        descriptor.boolAttr("tcpKeepAlive", fallback.isTcpKeepAlive()),
        descriptor.longAttr("multipartThresholdBytes", fallback.getMultipartThresholdBytes()),
        descriptor.longAttr("multipartPartSizeBytes", fallback.getMultipartPartSizeBytes()),
        descriptor.intAttr("multipartConcurrency", fallback.getMultipartConcurrency()));
  }

  private static String valueOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
