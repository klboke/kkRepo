package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class LdapRuntimeHintsTest {
  @Test
  void keepsJndiLdapFactoriesConstructibleInNativeImage() throws Exception {
    RuntimeHints hints = new RuntimeHints();
    new LdapRuntimeHints().registerHints(hints, getClass().getClassLoader());

    for (var type : LdapRuntimeHints.JNDI_FACTORIES) {
      // The names must stay real JDK classes, otherwise the hint silently does nothing.
      Class.forName(type.getName());
      assertTrue(RuntimeHintsPredicates.reflection()
          .onType(type).withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS).test(hints),
          () -> "Missing constructor hint for " + type.getName());
    }
    assertTrue(RuntimeHintsPredicates.reflection()
        .onMethodInvocation(org.springframework.util.ReflectionUtils.findMethod(
            javax.net.ssl.SSLSocketFactory.class, "getDefault")).test(hints),
        "Missing ldaps SSLSocketFactory.getDefault() hint");
  }
}
