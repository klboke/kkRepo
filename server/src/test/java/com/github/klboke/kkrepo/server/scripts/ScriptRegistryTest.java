package com.github.klboke.kkrepo.server.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginException;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.script.ScriptMaintenanceHost;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptRegistryTest {
  private static final String SCRIPT = """
      function candidates(requestJson) {
        return JSON.stringify([{
          repository: "repo",
          path: "snapshots/a",
          reason: "stale",
          sizeBytes: 7
        }]);
      }
      function run(requestJson) {
        var request = JSON.parse(requestJson);
        return JSON.stringify({
          proposed: 1,
          deleted: request.dryRun ? 0 : 1,
          messages: ["ok"]
        });
      }
      function handle(requestJson) {
        return JSON.stringify({status: 200, headers: {}, body: "b2s="});
      }
      """;

  @Test
  void loadsAndRunsAllScriptCapabilities(@TempDir Path temp) throws Exception {
    writeModule(temp, "module-1", List.of(
        "cleanup.rule", "maintenance.task", "repository.handler"),
        "{\"format\":\"raw\",\"type\":\"HOSTED\",\"method\":\"GET\","
            + "\"pathPattern\":\"**\",\"order\":100}");

    ScriptRegistry registry = ScriptRegistry.load(temp, "js", new ScriptMaintenanceHost());

    assertEquals(1, registry.cleanupRules().size());
    assertEquals(1, registry.maintenanceTasks().size());
    assertEquals(1, registry.protocolHandlers().size());

    CleanupRule rule = registry.cleanupRules().getFirst();
    List<CleanupCandidate> candidates =
        rule.candidates(new CleanupRequest("repo", "", 30, 10));
    assertEquals(1, candidates.size());
    assertEquals("snapshots/a", candidates.getFirst().path());

    MaintenanceTask task = registry.maintenanceTasks().getFirst();
    MaintenanceResult dryRun = task.run(new MaintenanceRequest(true, 10));
    assertEquals(1, dryRun.proposed());
    assertEquals(0, dryRun.deleted());

    RepositoryProtocolHandler handler = registry.protocolHandlers().getFirst();
    assertEquals(1, handler.routes().size());
    RepositoryResponse response = handler.handle(request());
    assertEquals(200, response.status());
    assertEquals("ok", new String(response.body(), StandardCharsets.UTF_8));
  }

  @Test
  void rejectsDuplicateModuleIds(@TempDir Path temp) throws Exception {
    writeModule(temp, "module-1", List.of("cleanup.rule"), null);
    Path second = Files.createDirectory(temp.resolve("second"));
    Files.writeString(second.resolve("module.json"), """
        {"id":"module-1","language":"js","entrypoint":"handler.js",
         "capabilities":["cleanup.rule"]}
        """);
    Files.writeString(second.resolve("handler.js"), SCRIPT);

    assertThrows(PluginException.class,
        () -> ScriptRegistry.load(temp, "js", new ScriptMaintenanceHost()));
  }

  @Test
  void rejectsEntrypointOutsideTheScriptDirectory(@TempDir Path temp) throws Exception {
    Files.writeString(temp.resolve("module.json"), """
        {"id":"escape","language":"js","entrypoint":"../outside.js",
         "capabilities":["cleanup.rule"]}
        """);
    Files.writeString(temp.getParent().resolve("outside.js"), SCRIPT);

    assertThrows(PluginException.class,
        () -> ScriptRegistry.load(temp, "js", new ScriptMaintenanceHost()));
  }

  @Test
  void rejectsUnknownCapability(@TempDir Path temp) throws Exception {
    writeModule(temp, "module-1", List.of("network.filter"), null);

    assertThrows(PluginException.class,
        () -> ScriptRegistry.load(temp, "js", new ScriptMaintenanceHost()));
  }

  @Test
  void emptyDirectoryProducesAnEmptyRegistry(@TempDir Path temp) throws Exception {
    ScriptRegistry registry = ScriptRegistry.load(temp, "js", new ScriptMaintenanceHost());

    assertTrue(registry.cleanupRules().isEmpty());
    assertTrue(registry.maintenanceTasks().isEmpty());
    assertTrue(registry.protocolHandlers().isEmpty());
  }

  private static void writeModule(
      Path root, String id, List<String> capabilities, String route) throws Exception {
    StringBuilder json = new StringBuilder()
        .append("{\"id\":\"").append(id)
        .append("\",\"language\":\"js\",\"entrypoint\":\"handler.js\",\"capabilities\":[");
    for (int i = 0; i < capabilities.size(); i++) {
      if (i > 0) json.append(',');
      json.append('"').append(capabilities.get(i)).append('"');
    }
    json.append(']');
    if (route != null) {
      json.append(",\"routes\":[").append(route).append(']');
    }
    json.append('}');
    Files.writeString(root.resolve("module.json"), json.toString());
    Files.writeString(root.resolve("handler.js"), SCRIPT);
  }

  private static RepositoryRequest request() {
    return new RepositoryRequest(
        new RepositoryDescriptor(
            1L,
            "repo",
            new FormatId("raw"),
            RepositoryKind.HOSTED,
            "raw-hosted",
            true,
            1L,
            null,
            null,
            null,
            null,
            Map.of()),
        "repo",
        "snapshots/a",
        "GET",
        Map.of(),
        Map.of(),
        new byte[0],
        null,
        Map.of());
  }
}
