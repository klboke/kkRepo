package com.github.klboke.kkrepo.server.maintenance;

import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.server.scripts.ScriptRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Registry of maintenance tasks contributed by plugins, scripts or built-in beans. */
@Component
public class MaintenanceTaskRegistry {
  private final Map<String, MaintenanceTask> tasks = new ConcurrentHashMap<>();

  @Autowired
  public MaintenanceTaskRegistry(
      ObjectProvider<MaintenanceTask> tasks, ObjectProvider<ScriptRegistry> scripts) {
    this(tasks.orderedStream().toList());
    scripts.ifAvailable(registry -> registry.maintenanceTasks().forEach(this::register));
  }

  MaintenanceTaskRegistry(List<MaintenanceTask> tasks) {
    if (tasks != null) {
      tasks.forEach(this::register);
    }
  }

  public void register(MaintenanceTask task) {
    if (task == null || task.id() == null || task.id().isBlank()) {
      throw new IllegalArgumentException("Maintenance task and id are required");
    }
    String id = task.id().trim();
    if (tasks.putIfAbsent(id, task) != null) {
      throw new IllegalStateException("Duplicate maintenance task: " + id);
    }
  }

  public Optional<MaintenanceTask> find(String id) {
    return id == null || id.isBlank()
        ? Optional.empty()
        : Optional.ofNullable(tasks.get(id.trim()));
  }

  public List<MaintenanceTask> all() {
    return List.copyOf(tasks.values());
  }
}
