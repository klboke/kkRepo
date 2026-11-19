package com.github.klboke.kkrepo.server.plugin;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import java.util.ServiceLoader;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for plugins. Built-in plugins are discovered with {@link ServiceLoader} at
 * runtime, so the service descriptors and the public constructor of every provider found on the
 * build classpath are registered. Dynamic plugin JAR loading remains a JVM feature; the descriptor
 * hints only keep the runtime classpath buildable in native mode.
 */
public class PluginRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.reflection().registerType(
        PluginDescriptor.class,
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_DECLARED_METHODS,
        MemberCategory.ACCESS_DECLARED_FIELDS);
    hints.resources().registerPattern("META-INF/kkrepo-plugin.json");
    hints.resources().registerPattern("META-INF/services/*");
    ClassLoader builtins = classLoader != null ? classLoader : PluginRuntimeHints.class.getClassLoader();
    ServiceLoader.load(KkRepoPlugin.class, builtins).stream().forEach(provider -> hints.reflection()
        .registerType(provider.type(), MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
  }
}
