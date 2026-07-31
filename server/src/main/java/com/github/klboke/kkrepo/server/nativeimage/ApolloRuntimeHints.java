package com.github.klboke.kkrepo.server.nativeimage;

import java.util.List;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/** Reflection metadata for types instantiated by Apollo's Guice injectors. */
public final class ApolloRuntimeHints implements RuntimeHintsRegistrar {
  static final String BOOTSTRAP_REGISTRY_TYPE =
      "org.springframework.boot.bootstrap.BootstrapRegistry";
  static final String INSTANCE_SUPPLIER_TYPE = BOOTSTRAP_REGISTRY_TYPE + "$InstanceSupplier";

  static final List<String> GUICE_CONSTRUCTOR_TYPES =
      List.of(
          "com.ctrip.framework.apollo.internals.ConfigServiceLocator",
          "com.ctrip.framework.apollo.internals.DefaultConfigManager",
          "com.ctrip.framework.apollo.internals.RemoteConfigLongPollService",
          "com.ctrip.framework.apollo.monitor.internal.ApolloClientMonitorContext",
          "com.ctrip.framework.apollo.monitor.internal.DefaultConfigMonitor",
          "com.ctrip.framework.apollo.monitor.internal.exporter.impl.DefaultApolloClientMetricsExporterFactory",
          "com.ctrip.framework.apollo.spi.DefaultConfigFactory",
          "com.ctrip.framework.apollo.spi.DefaultConfigFactoryManager",
          "com.ctrip.framework.apollo.spi.DefaultConfigRegistry",
          "com.ctrip.framework.apollo.spring.config.ConfigPropertySourceFactory",
          "com.ctrip.framework.apollo.spring.property.PlaceholderHelper",
          "com.ctrip.framework.apollo.spring.property.SpringValueRegistry",
          "com.ctrip.framework.apollo.util.ConfigUtil",
          "com.ctrip.framework.apollo.util.factory.DefaultPropertiesFactory",
          "com.ctrip.framework.apollo.util.http.DefaultHttpClient",
          "com.ctrip.framework.apollo.util.yaml.YamlParser");

  static final List<String> BOOTSTRAP_METHOD_TYPES =
      List.of(
          "org.springframework.boot.bootstrap.BootstrapContext",
          INSTANCE_SUPPLIER_TYPE,
          "org.springframework.boot.context.config.ConfigDataLoaderContext",
          "org.springframework.boot.context.event.ApplicationStartingEvent");

  static final List<String> DYNAMIC_SPRING_BEAN_TYPES =
      List.of(
          "org.springframework.context.support.PropertySourcesPlaceholderConfigurer",
          "com.ctrip.framework.apollo.spring.annotation.ApolloAnnotationProcessor",
          "com.ctrip.framework.apollo.spring.annotation.SpringValueProcessor",
          "com.ctrip.framework.apollo.spring.config.ConfigPropertySourcesProcessor",
          "com.ctrip.framework.apollo.spring.config.PropertySourcesProcessor",
          "com.ctrip.framework.apollo.spring.property.AutoUpdateConfigChangeListener",
          "com.ctrip.framework.apollo.spring.property.SpringValueDefinitionProcessor");

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    GUICE_CONSTRUCTOR_TYPES.forEach(
        type ->
            hints
                .reflection()
                .registerTypeIfPresent(
                    classLoader, type, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));

    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            BOOTSTRAP_REGISTRY_TYPE,
            MemberCategory.INVOKE_PUBLIC_METHODS);
    BOOTSTRAP_METHOD_TYPES.forEach(
        type ->
            hints
                .reflection()
                .registerTypeIfPresent(classLoader, type, MemberCategory.INVOKE_PUBLIC_METHODS));
    DYNAMIC_SPRING_BEAN_TYPES.forEach(
        type ->
            hints
                .reflection()
                .registerTypeIfPresent(
                    classLoader,
                    type,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS));
  }
}
