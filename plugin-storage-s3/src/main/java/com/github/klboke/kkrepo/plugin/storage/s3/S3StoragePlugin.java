package com.github.klboke.kkrepo.plugin.storage.s3;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.storage.s3.OssClientFactory;
import com.github.klboke.kkrepo.storage.s3.S3BlobStorageFactory;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreProvider;
import com.github.klboke.kkrepo.storage.s3.S3ClientFactory;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import java.util.List;

/** Extracts the S3/OSS blob-store backend into an optional signed plugin. */
public final class S3StoragePlugin implements KkRepoPlugin {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "storage-s3",
        "1.0.0",
        PluginApiVersion.CURRENT,
        getClass().getName(),
        List.of("storage.backend"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerBlobStoreProvider(new S3BlobStoreProvider(
        new S3BlobStorageFactory(new S3ClientFactory(), new OssClientFactory()),
        new S3StorageProperties()));
  }
}
