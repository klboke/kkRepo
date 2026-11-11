package com.github.klboke.kkrepo.server.plugin;

import com.github.klboke.kkrepo.plugin.api.PluginException;
import com.github.klboke.kkrepo.plugin.loader.PluginLoader;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Optional plugin wiring. Plugins are disabled unless {@code kkrepo.plugins.enabled=true} and both
 * the plugin directory and the truststore are configured. Existing deployments are unaffected.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(PluginRuntimeHints.class)
public class PluginConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "kkrepo.plugins", name = "enabled", havingValue = "true")
  public PluginRuntime pluginRuntime(
      @Value("${kkrepo.plugins.directory:plugins}") String directory,
      @Value("${kkrepo.plugins.truststore:}") String truststore,
      @Value("${kkrepo.plugins.truststore-password:}") String truststorePassword)
      throws PluginException {
    if (truststore == null || truststore.isBlank()) {
      throw new PluginException(
          "kkrepo.plugins.truststore is required when kkrepo.plugins.enabled=true");
    }
    PluginLoader loader = new PluginLoader(
        Path.of(directory), Path.of(truststore), truststorePassword.toCharArray());
    return new PluginRuntime(loader.load());
  }
}
