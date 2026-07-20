package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.util.ClassUtils;

class ApolloRuntimeHintsTest {

  @Test
  void registersConstructorsUsedByApolloGuiceInjectors() throws Exception {
    RuntimeHints hints = new RuntimeHints();
    ClassLoader classLoader = getClass().getClassLoader();
    new ApolloRuntimeHints().registerHints(hints, classLoader);

    for (String typeName : ApolloRuntimeHints.GUICE_CONSTRUCTOR_TYPES) {
      Class<?> type = ClassUtils.forName(typeName, classLoader);
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onConstructorInvocation(type.getDeclaredConstructor())
              .test(hints),
          () -> "Missing native constructor hint for " + typeName);
    }
  }

  @Test
  void registersSpringBootBootstrapReflectionUsedByApolloConfigData() {
    RuntimeHints hints = new RuntimeHints();
    ClassLoader classLoader = getClass().getClassLoader();
    new ApolloRuntimeHints().registerHints(hints, classLoader);

    assertTrue(
        RuntimeHintsPredicates.reflection()
            .onType(TypeReference.of(ApolloRuntimeHints.BOOTSTRAP_REGISTRY_TYPE))
            .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
            .test(hints));
    for (String typeName : ApolloRuntimeHints.BOOTSTRAP_METHOD_TYPES) {
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(TypeReference.of(typeName))
              .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
              .test(hints),
          () -> "Missing native method hints for " + typeName);
    }
  }

  @Test
  void registersBeansCreatedDynamicallyByApolloProcessors() throws Exception {
    RuntimeHints hints = new RuntimeHints();
    ClassLoader classLoader = getClass().getClassLoader();
    new ApolloRuntimeHints().registerHints(hints, classLoader);

    for (String typeName : ApolloRuntimeHints.DYNAMIC_SPRING_BEAN_TYPES) {
      Class<?> type = ClassUtils.forName(typeName, classLoader);
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onConstructorInvocation(type.getDeclaredConstructor())
              .test(hints),
          () -> "Missing native constructor hint for dynamic bean " + typeName);
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(type)
              .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
              .test(hints),
          () -> "Missing native method hints for dynamic bean " + typeName);
    }
  }
}
