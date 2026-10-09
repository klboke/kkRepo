package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class SecurityScannerRuntimeHintsTest {
  @Test
  void coversAdapterContractAndNestedJsonRecords() throws ReflectiveOperationException {
    RuntimeHints hints = hints();
    Set<Type> visited = new HashSet<>();
    for (var method : ScannerContract.Adapter.class.getDeclaredMethods()) {
      assertBinding(hints, method.getGenericReturnType(), visited);
      for (Type parameter : method.getGenericParameterTypes()) {
        assertBinding(hints, parameter, visited);
      }
    }
  }

  @Test
  void coversErrorPayloadAndResponseSummaryMixin() throws ReflectiveOperationException {
    RuntimeHints hints = hints();
    assertBinding(hints, Class.forName(HttpSecurityScannerAdapter.class.getName()
        + "$ScannerErrorPayload"), new HashSet<>());
    for (Class<?> nested : HttpSecurityScannerAdapter.class.getDeclaredClasses()) {
      if (nested.isAnnotationPresent(JsonIgnoreProperties.class)) {
        assertTrue(RuntimeHintsPredicates.reflection().onType(nested).test(hints),
            () -> "Missing scanner JSON mixin hint: " + nested.getName());
      }
    }
  }

  private static RuntimeHints hints() {
    RuntimeHints hints = new RuntimeHints();
    new HttpSecurityScannerAdapter.ScannerRuntimeHints()
        .registerHints(hints, SecurityScannerRuntimeHintsTest.class.getClassLoader());
    return hints;
  }

  private static void assertBinding(RuntimeHints hints, Type type, Set<Type> visited)
      throws ReflectiveOperationException {
    if (!visited.add(type)) return;
    if (type instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        assertBinding(hints, argument, visited);
      }
    } else if (type instanceof Class<?> record && record.isRecord()) {
      RecordComponent[] components = record.getRecordComponents();
      Class<?>[] parameters = Arrays.stream(components)
          .map(RecordComponent::getType).toArray(Class<?>[]::new);
      assertTrue(RuntimeHintsPredicates.reflection()
              .onConstructorInvocation(record.getDeclaredConstructor(parameters)).test(hints),
          () -> "Missing scanner JSON constructor hint: " + record.getName());
      for (RecordComponent component : components) {
        assertTrue(RuntimeHintsPredicates.reflection()
                .onMethodInvocation(component.getAccessor()).test(hints),
            () -> "Missing scanner JSON accessor hint: " + component.getAccessor());
        assertBinding(hints, component.getGenericType(), visited);
      }
    }
  }
}
