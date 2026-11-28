package com.github.klboke.kkrepo.server.plugin;

import com.github.klboke.kkrepo.plugin.api.PluginException;
import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.loader.PluginLoader;
import com.github.klboke.kkrepo.server.routing.PluginProtocolDispatcher;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Plugin wiring. Built-in plugins shipped on the application classpath (Git LFS) are always
 * discovered through {@link java.util.ServiceLoader} and registered first. Signed external plugin
 * JARs are only loaded when {@code kkrepo.plugins.enabled=true}, which also requires the plugin
 * directory and the truststore. A duplicate plugin id, format or route fails startup.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(PluginRuntimeHints.class)
public class PluginConfiguration {

  @Bean(destroyMethod = "close")
  public PluginRuntime pluginRuntime(
      @Value("${kkrepo.plugins.enabled:false}") boolean externalPluginsEnabled,
      @Value("${kkrepo.plugins.directory:plugins}") String directory,
      @Value("${kkrepo.plugins.truststore:}") String truststore,
      @Value("${kkrepo.plugins.truststore-password:}") String truststorePassword)
      throws PluginException {
    ClassLoader builtins = PluginConfiguration.class.getClassLoader();
    if (!externalPluginsEnabled) {
      return new PluginRuntime(PluginLoader.loadBuiltins(builtins));
    }
    if (truststore == null || truststore.isBlank()) {
      throw new PluginException(
          "kkrepo.plugins.truststore is required when kkrepo.plugins.enabled=true");
    }
    PluginLoader loader = new PluginLoader(
        Path.of(directory), Path.of(truststore), truststorePassword.toCharArray());
    return new PluginRuntime(loader.load(builtins));
  }

  @Bean
  public PluginProtocolDispatcher pluginProtocolDispatcher(
      PluginRuntime runtime, List<PluginHostService> hostServices) {
    return new PluginProtocolDispatcher(runtime.repositoryProtocolHandlers(), hostServices);
  }
}
