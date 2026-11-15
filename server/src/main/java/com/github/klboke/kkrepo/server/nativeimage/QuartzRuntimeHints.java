package com.github.klboke.kkrepo.server.nativeimage;

import com.github.klboke.kkrepo.server.cleanup.CleanupQuartzJob;
import org.quartz.impl.jdbcjobstore.PostgreSQLDelegate;
import org.quartz.impl.jdbcjobstore.StdJDBCDelegate;
import org.quartz.simpl.SimpleInstanceIdGenerator;
import org.quartz.simpl.SimpleThreadPool;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.scheduling.quartz.LocalDataSourceJobStore;

/** Reflection metadata for Quartz jobs instantiated from the clustered JDBC JobStore. */
public final class QuartzRuntimeHints implements RuntimeHintsRegistrar {
  static final Class<?>[] REFLECTIVELY_CONSTRUCTED_TYPES = {
      SimpleInstanceIdGenerator.class,
      SimpleThreadPool.class,
      LocalDataSourceJobStore.class,
      StdJDBCDelegate.class,
      PostgreSQLDelegate.class
  };

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // The JDBC JobStore resolves this class by name; our JobFactory constructs it directly.
    hints.reflection().registerType(CleanupQuartzJob.class);
    for (Class<?> type : REFLECTIVELY_CONSTRUCTED_TYPES) {
      hints.reflection().registerType(type, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
  }
}
