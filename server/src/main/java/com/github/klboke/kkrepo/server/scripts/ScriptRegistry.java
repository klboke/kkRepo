package com.github.klboke.kkrepo.server.scripts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginException;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.script.ScriptMaintenanceHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads script modules from a directory. A module is a {@code module.json} descriptor next to its
 * entrypoint. Scripts only contribute read-only cleanup candidates, maintenance tasks and neutral
 * repository handlers; the runtime keeps locks, quotas, policy and audit.
 */
public final class ScriptRegistry {
  private static final Set<String> CAPABILITIES = Set.of(
      "cleanup.rule", "maintenance.task", "repository.handler");

  private final List<RepositoryProtocolHandler> protocolHandlers;
  private final List<CleanupRule> cleanupRules;
  private final List<MaintenanceTask> maintenanceTasks;

  private ScriptRegistry(
      List<RepositoryProtocolHandler> protocolHandlers,
      List<CleanupRule> cleanupRules,
      List<MaintenanceTask> maintenanceTasks) {
    this.protocolHandlers = List.copyOf(protocolHandlers);
    this.cleanupRules = List.copyOf(cleanupRules);
    this.maintenanceTasks = List.copyOf(maintenanceTasks);
  }

  public static ScriptRegistry empty() {
    return new ScriptRegistry(List.of(), List.of(), List.of());
  }

  public static ScriptRegistry load(
      Path directory, String defaultLanguage, ScriptMaintenanceHost host) throws PluginException {
    if (directory == null) {
      throw new PluginException("Script directory is required");
    }
    Path root = directory.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new PluginException("Script directory not found: " + root);
    }
    ObjectMapper mapper = new ObjectMapper();
    List<RepositoryProtocolHandler> protocolHandlers = new ArrayList<>();
    List<CleanupRule> cleanupRules = new ArrayList<>();
    List<MaintenanceTask> maintenanceTasks = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    try (Stream<Path> files = Files.walk(root)) {
      List<Path> descriptors = files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().equals("module.json"))
          .sorted()
          .toList();
      for (Path descriptorFile : descriptors) {
        ScriptModuleDescriptor descriptor;
        try {
          descriptor = mapper.readValue(descriptorFile.toFile(), ScriptModuleDescriptor.class);
        } catch (IOException invalid) {
          throw new PluginException("Invalid script descriptor: " + descriptorFile, invalid);
        }
        if (!ids.add(descriptor.id())) {
          throw new PluginException("Duplicate script module id: " + descriptor.id());
        }
        Path entrypoint = descriptorFile.getParent()
            .resolve(descriptor.entrypoint())
            .normalize();
        if (!entrypoint.startsWith(root) || !Files.isRegularFile(entrypoint)) {
          throw new PluginException(
              "Script entrypoint is missing or outside the script directory: " + entrypoint);
        }
        String language = descriptor.language() == null || descriptor.language().isBlank()
            ? defaultLanguage
            : descriptor.language();
        for (String capability : descriptor.capabilities()) {
          if (!CAPABILITIES.contains(capability)) {
            throw new PluginException(
                "Unsupported script capability '" + capability + "' in " + descriptorFile);
          }
          switch (capability) {
            case "cleanup.rule" -> cleanupRules.add(
                host.loadCleanupRule(descriptor.id(), entrypoint, language));
            case "maintenance.task" -> maintenanceTasks.add(
                host.loadMaintenanceTask(descriptor.id(), entrypoint, language));
            case "repository.handler" -> protocolHandlers.add(
                host.loadRepositoryProtocolHandler(
                    descriptor.id(), entrypoint, language, routes(descriptor, descriptorFile)));
            default -> throw new PluginException(
                "Unsupported script capability '" + capability + "' in " + descriptorFile);
          }
        }
      }
    } catch (IOException invalid) {
      throw new PluginException("Unable to scan script directory: " + root, invalid);
    }
    return new ScriptRegistry(protocolHandlers, cleanupRules, maintenanceTasks);
  }

  public List<RepositoryProtocolHandler> protocolHandlers() {
    return protocolHandlers;
  }

  public List<CleanupRule> cleanupRules() {
    return cleanupRules;
  }

  public List<MaintenanceTask> maintenanceTasks() {
    return maintenanceTasks;
  }

  private static List<RepositoryProtocolRoute> routes(
      ScriptModuleDescriptor descriptor, Path descriptorFile) throws PluginException {
    if (descriptor.routes().isEmpty()) {
      throw new PluginException(
          "Repository handler script declares no route: " + descriptorFile);
    }
    List<RepositoryProtocolRoute> routes = new ArrayList<>();
    for (ScriptModuleDescriptor.Route route : descriptor.routes()) {
      try {
        routes.add(new RepositoryProtocolRoute(
            new FormatId(route.format()),
            RepositoryKind.from(route.type()),
            route.method(),
            route.pathPattern(),
            route.order()));
      } catch (IllegalArgumentException invalid) {
        throw new PluginException(
            "Invalid route in " + descriptorFile + ": " + route, invalid);
      }
    }
    return routes;
  }
}
