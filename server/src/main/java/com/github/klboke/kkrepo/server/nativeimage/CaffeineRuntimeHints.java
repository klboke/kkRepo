package com.github.klboke.kkrepo.server.nativeimage;

import java.util.List;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/** Reflection metadata for Caffeine's configuration-specific cache implementations. */
public final class CaffeineRuntimeHints implements RuntimeHintsRegistrar {
  static final String CACHE_PACKAGE = "com.github.benmanes.caffeine.cache.";

  static final List<String> BOUNDED_CACHE_TYPES =
      List.of(
          CACHE_PACKAGE + "SSMSA",
          CACHE_PACKAGE + "SSMWA",
          CACHE_PACKAGE + "SSMSW",
          CACHE_PACKAGE + "SSLA");

  static final List<String> NODE_TYPES =
      List.of(CACHE_PACKAGE + "PSWMS", CACHE_PACKAGE + "PSAMW", CACHE_PACKAGE + "PSA");

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    BOUNDED_CACHE_TYPES.forEach(
        type -> registerDynamicType(hints, classLoader, type));
    NODE_TYPES.forEach(type -> registerDynamicType(hints, classLoader, type));
  }

  private static void registerDynamicType(
      RuntimeHints hints, ClassLoader classLoader, String type) {
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            type,
            MemberCategory.ACCESS_DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
  }
}
