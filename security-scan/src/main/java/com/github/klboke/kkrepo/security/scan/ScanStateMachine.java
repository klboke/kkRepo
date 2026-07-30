package com.github.klboke.kkrepo.security.scan;

import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Explicit transition rules shared by workers, APIs, and tests. */
public final class ScanStateMachine {
  private static final Map<TaskStatus, Set<TaskStatus>> TASK_TRANSITIONS = Map.of(
      TaskStatus.PENDING, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
      TaskStatus.RUNNING, EnumSet.of(
          TaskStatus.SUCCEEDED, TaskStatus.RETRY_WAIT, TaskStatus.FAILED, TaskStatus.CANCELLED),
      TaskStatus.RETRY_WAIT, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
      TaskStatus.SUCCEEDED, EnumSet.noneOf(TaskStatus.class),
      TaskStatus.FAILED, EnumSet.of(TaskStatus.PENDING),
      TaskStatus.CANCELLED, EnumSet.of(TaskStatus.PENDING));

  private ScanStateMachine() {}

  public static boolean canTransition(TaskStatus from, TaskStatus to) {
    return from == to || TASK_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
  }

  public static void requireTransition(TaskStatus from, TaskStatus to) {
    if (!canTransition(from, to)) {
      throw new IllegalStateException("Invalid security scan task transition: " + from + " -> " + to);
    }
  }
}
