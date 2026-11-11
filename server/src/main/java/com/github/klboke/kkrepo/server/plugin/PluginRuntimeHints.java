package com.github.klboke.kkrepo.server.plugin;

import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for the optional plugin descriptor. Dynamic plugin JAR loading remains a JVM
 * feature; these hints only keep the runtime classpath buildable in native mode.
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
  }
}
