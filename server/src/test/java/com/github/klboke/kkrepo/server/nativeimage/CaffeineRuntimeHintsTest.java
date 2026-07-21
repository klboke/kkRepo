package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class CaffeineRuntimeHintsTest {

  @Test
  void registersImplementationsSelectedByKkRepoCacheConfigurations() throws Exception {
    List<Cache<Object, Object>> caches =
        List.of(
            Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfter(
                    Expiry.creating((Object key, Object value) -> Duration.ofMinutes(1)))
                .build(),
            Caffeine.newBuilder()
                .maximumWeight(10)
                .weigher((Object key, Object value) -> 1)
                .expireAfterAccess(Duration.ofMinutes(1))
                .build(),
            Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build(),
            Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(1))
                .removalListener((Object key, Object value, RemovalCause cause) -> {})
                .build());
    Set<String> selectedTypes =
        caches.stream().map(cache -> cache.asMap().getClass().getName()).collect(Collectors.toSet());
    Set<String> selectedNodeTypes = new HashSet<>();
    for (Cache<Object, Object> cache : caches) {
      selectedNodeTypes.add(nodeFactoryType(cache));
    }

    assertEquals(Set.copyOf(CaffeineRuntimeHints.BOUNDED_CACHE_TYPES), selectedTypes);
    assertEquals(Set.copyOf(CaffeineRuntimeHints.NODE_TYPES), selectedNodeTypes);

    RuntimeHints hints = new RuntimeHints();
    new CaffeineRuntimeHints().registerHints(hints, getClass().getClassLoader());
    Set<String> dynamicTypes = new HashSet<>(selectedTypes);
    dynamicTypes.addAll(selectedNodeTypes);
    for (String typeName : dynamicTypes) {
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(TypeReference.of(typeName))
              .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS)
              .test(hints),
          () -> "Missing native field hints for Caffeine cache " + typeName);
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(TypeReference.of(typeName))
              .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
              .test(hints),
          () -> "Missing native constructor hints for Caffeine cache " + typeName);
    }
  }

  private static String nodeFactoryType(Cache<?, ?> cache) throws Exception {
    Class<?> type = cache.asMap().getClass();
    while (type != null) {
      try {
        Field field = type.getDeclaredField("nodeFactory");
        field.setAccessible(true);
        return field.get(cache.asMap()).getClass().getName();
      } catch (NoSuchFieldException ignored) {
        type = type.getSuperclass();
      }
    }
    throw new IllegalStateException("Caffeine nodeFactory field was not found");
  }
}
