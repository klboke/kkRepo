package com.github.klboke.kkrepo.server.scripts;

import com.github.klboke.kkrepo.plugin.api.PluginException;
import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.script.ScriptMaintenanceHost;
import com.github.klboke.kkrepo.server.routing.PluginProtocolDispatcher;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional JSR-223 script wiring. Disabled by default. When enabled, every {@code module.json}
 * found under {@code kkrepo.scripts.directory} contributes cleanup rules, maintenance tasks and
 * neutral repository handlers.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kkrepo.scripts", name = "enabled", havingValue = "true")
public class ScriptConfiguration {

  @Bean
  public ScriptMaintenanceHost scriptMaintenanceHost() {
    return new ScriptMaintenanceHost();
  }

  @Bean
  public ScriptRegistry scriptRegistry(
      ScriptMaintenanceHost host,
      @Value("${kkrepo.scripts.directory:scripts}") String directory,
      @Value("${kkrepo.scripts.language:js}") String language)
      throws PluginException {
    return ScriptRegistry.load(Path.of(directory), language, host);
  }

  @Bean
  public PluginProtocolDispatcher scriptProtocolDispatcher(
      ScriptRegistry registry, List<PluginHostService> hostServices) {
    return new PluginProtocolDispatcher(registry.protocolHandlers(), hostServices);
  }
}
