package com.github.klboke.kkrepo.server.nativeimage;

import com.github.klboke.kkrepo.server.cleanup.CleanupQuartzJob;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/** Reflection metadata for Quartz jobs instantiated from the clustered JDBC JobStore. */
public final class QuartzRuntimeHints implements RuntimeHintsRegistrar {
  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.reflection().registerType(
        CleanupQuartzJob.class,
        MemberCategory.ACCESS_DECLARED_FIELDS,
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
  }
}
