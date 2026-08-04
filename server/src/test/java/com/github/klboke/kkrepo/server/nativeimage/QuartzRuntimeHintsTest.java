package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.cleanup.CleanupQuartzJob;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class QuartzRuntimeHintsTest {
  @Test
  void registersCleanupJobConstructionAndAutowiredFields() {
    RuntimeHints hints = new RuntimeHints();
    new QuartzRuntimeHints().registerHints(hints, getClass().getClassLoader());

    assertTrue(RuntimeHintsPredicates.reflection()
        .onType(CleanupQuartzJob.class)
        .test(hints));
    assertTrue(RuntimeHintsPredicates.reflection()
        .onType(CleanupQuartzJob.class)
        .withMemberCategory(org.springframework.aot.hint.MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
        .test(hints));
    assertTrue(RuntimeHintsPredicates.reflection()
        .onType(CleanupQuartzJob.class)
        .withMemberCategory(org.springframework.aot.hint.MemberCategory.ACCESS_DECLARED_FIELDS)
        .test(hints));
  }
}
