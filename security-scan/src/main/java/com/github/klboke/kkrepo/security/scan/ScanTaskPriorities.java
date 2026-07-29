package com.github.klboke.kkrepo.security.scan;

import java.util.List;

/**
 * Fixed priority classes used by the durable scan-task scheduler.
 *
 * <p>A finite set lets every scheduler poll use one bounded eligibility range per class instead of
 * walking future high-priority rows to find lower-priority work that is ready now.
 */
public final class ScanTaskPriorities {
  public static final int CONTENT = 0;
  public static final int POLICY = 20;
  public static final int VULNERABILITY_DATABASE = 25;
  public static final int MANUAL = 100;

  private static final List<Integer> DESCENDING =
      List.of(MANUAL, VULNERABILITY_DATABASE, POLICY, CONTENT);

  private ScanTaskPriorities() {}

  public static List<Integer> descending() {
    return DESCENDING;
  }

  public static int requireSupported(int priority) {
    if (!DESCENDING.contains(priority)) {
      throw new IllegalArgumentException("Unsupported security scan task priority: " + priority);
    }
    return priority;
  }
}
