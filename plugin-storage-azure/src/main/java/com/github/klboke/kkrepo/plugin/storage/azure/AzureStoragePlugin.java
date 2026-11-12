package com.github.klboke.kkrepo.plugin.storage.azure;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.storage.azure.AzureBlobStorageFactory;
import com.github.klboke.kkrepo.storage.azure.AzureBlobStoreProvider;
import com.github.klboke.kkrepo.storage.azure.AzureClientFactory;
import com.github.klboke.kkrepo.storage.azure.config.AzureStorageProperties;
import java.util.List;

/** Extracts the Azure Blob Storage backend into an optional signed plugin. */
public final class AzureStoragePlugin implements KkRepoPlugin {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "storage-azure",
        "1.0.0",
        PluginApiVersion.CURRENT,
        getClass().getName(),
        List.of("storage.backend"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerBlobStoreProvider(new AzureBlobStoreProvider(
        new AzureBlobStorageFactory(new AzureClientFactory()),
        new AzureStorageProperties()));
  }
}
