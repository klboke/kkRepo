package com.github.klboke.kkrepo.plugin.storage.file;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.storage.file.FileBlobStorageFactory;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreProvider;
import com.github.klboke.kkrepo.storage.file.config.FileStorageProperties;
import java.util.List;

/**
 * Extracts the filesystem blob-store backend into an optional plugin. The provider registers the
 * same {@code file} type as the built-in adapter; the server gives plugin providers precedence, so
 * installing this signed JAR selects this copy without changing repository rows.
 */
public final class FileStoragePlugin implements KkRepoPlugin {
  private static final String BASE_DIR_PROPERTY = "kkrepo.storage.file.base-dir";
  private static final String BASE_DIR_ENV = "KKREPO_STORAGE_FILE_BASE_DIR";

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "storage-file",
        "1.0.0",
        PluginApiVersion.CURRENT,
        getClass().getName(),
        List.of("storage.backend"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerBlobStoreProvider(new FileBlobStoreProvider(new FileBlobStorageFactory(
        properties())));
  }

  private static FileStorageProperties properties() {
    FileStorageProperties properties = new FileStorageProperties();
    String baseDir = System.getProperty(BASE_DIR_PROPERTY);
    if (baseDir == null || baseDir.isBlank()) {
      baseDir = System.getenv(BASE_DIR_ENV);
    }
    if (baseDir != null && !baseDir.isBlank()) {
      properties.setBaseDir(baseDir);
    }
    return properties;
  }
}
