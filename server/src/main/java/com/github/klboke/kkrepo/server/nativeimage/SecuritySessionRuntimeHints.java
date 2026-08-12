package com.github.klboke.kkrepo.server.nativeimage;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/** Java serialization metadata for security attributes stored in shared JDBC sessions. */
public final class SecuritySessionRuntimeHints implements RuntimeHintsRegistrar {
  static final TypeReference SESSION_SUBJECT = TypeReference.of(
      "com.github.klboke.kkrepo.server.security.SecurityAuthenticationService$SessionSubject");
  static final List<TypeReference> JAVA_SERIALIZATION_TYPES = List.of(
      SESSION_SUBJECT,
      TypeReference.of(LinkedHashSet.class),
      TypeReference.of(HashSet.class));

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // SessionSubject deliberately stores role IDs in a LinkedHashSet. GraalVM requires
    // explicit serialization metadata for both the runtime collection and its HashSet
    // superclass because HashSet implements custom writeObject/readObject methods.
    JAVA_SERIALIZATION_TYPES.forEach(type -> hints.reflection().registerType(
        type,
        typeHint -> typeHint.withJavaSerialization(true)));
  }
}
