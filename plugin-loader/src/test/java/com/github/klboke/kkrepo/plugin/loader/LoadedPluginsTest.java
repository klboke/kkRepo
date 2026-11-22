package com.github.klboke.kkrepo.plugin.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoadedPluginsTest {

  @Test
  void compatibilityConstructorsDefaultEveryMissingCollection() {
    LoadedPlugins allNull = new LoadedPlugins(null, null, null, null, null, null);
    assertTrue(allNull.plugins().isEmpty());
    assertTrue(allNull.remoteTransports().isEmpty());
    assertTrue(allNull.repositoryFormats().isEmpty());
    assertTrue(allNull.repositoryProtocolHandlers().isEmpty());
    assertTrue(allNull.blobStoreProviders().isEmpty());
    assertTrue(allNull.classLoaders().isEmpty());

    LoadedPlugins legacyStorage = new LoadedPlugins(null, null, null, null, null);
    assertTrue(legacyStorage.blobStoreProviders().isEmpty());
    assertTrue(legacyStorage.classLoaders().isEmpty());

    LoadedPlugins legacyHandlers = new LoadedPlugins(null, null, null, null);
    assertTrue(legacyHandlers.repositoryProtocolHandlers().isEmpty());
    assertTrue(legacyHandlers.classLoaders().isEmpty());
    assertEquals(List.of(), legacyHandlers.plugins());
  }
}
