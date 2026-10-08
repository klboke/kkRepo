package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.util.ClassUtils;

class JacksonRuntimeHintsTest {

  @Test
  void registersRecordConstructorsAndAccessorsForDynamicJsonValues() {
    ClassLoader classLoader = getClass().getClassLoader();
    RuntimeHints hints = new RuntimeHints();
    new JacksonRuntimeHints().registerHints(hints, classLoader);

    for (String typeName : JacksonRuntimeHints.BINDING_TYPES) {
      Class<?> type = ClassUtils.resolveClassName(typeName, classLoader);
      assertTrue(type.isRecord(), () -> "Expected dynamic JSON binding type to be a record: " + typeName);
      assertConstructorHint(hints, type);
      for (RecordComponent component : type.getRecordComponents()) {
        assertTrue(
            RuntimeHintsPredicates.reflection()
                .onMethodInvocation(component.getAccessor())
                .test(hints),
            () -> "Missing native accessor hint for " + typeName + "." + component.getName());
      }
    }
  }

  private static void assertConstructorHint(RuntimeHints hints, Class<?> type) {
    Class<?>[] parameters =
        Arrays.stream(type.getRecordComponents())
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new);
    try {
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onConstructorInvocation(type.getDeclaredConstructor(parameters))
              .test(hints),
          () -> "Missing native canonical constructor hint for " + type.getName());
    } catch (NoSuchMethodException e) {
      throw new AssertionError("Missing canonical record constructor for " + type.getName(), e);
    }
  }
}
