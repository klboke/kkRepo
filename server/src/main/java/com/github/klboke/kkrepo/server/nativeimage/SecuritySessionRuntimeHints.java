package com.github.klboke.kkrepo.server.nativeimage;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/** Java serialization metadata for security attributes stored in shared JDBC sessions. */
public final class SecuritySessionRuntimeHints implements RuntimeHintsRegistrar {
  static final TypeReference SESSION_SUBJECT = TypeReference.of(
      "com.github.klboke.kkrepo.server.security.SecurityAuthenticationService$SessionSubject");

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.reflection().registerType(
        SESSION_SUBJECT,
        typeHint -> typeHint.withJavaSerialization(true));
  }
}
