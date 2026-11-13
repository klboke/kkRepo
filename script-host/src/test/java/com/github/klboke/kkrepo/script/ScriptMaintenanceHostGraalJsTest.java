package com.github.klboke.kkrepo.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptMaintenanceHostGraalJsTest {

  @Test
  void runsRealJavascriptCleanupAndMaintenance(@TempDir Path temp) throws Exception {
    ScriptEngineManager manager = new ScriptEngineManager();
    ScriptEngine engine = manager.getEngineByName("js");
    if (engine == null) {
      engine = manager.getEngineByName("graal.js");
    }
    assertNotNull(engine, "GraalJS JSR-223 engine is available");

    ScriptMaintenanceHost host = new ScriptMaintenanceHost();
    Path script = temp.resolve("maintenance.js");
    Files.writeString(script, """
        function candidates(requestJson) {
          var request = JSON.parse(requestJson);
          return JSON.stringify([{
            repository: request.repository,
            path: "snapshots/a",
            reason: "stale",
            sizeBytes: 42
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
        """);

    CleanupRule rule = host.loadCleanupRule("js-rule", script, "js");
    List<CleanupCandidate> candidates =
        rule.candidates(new CleanupRequest("repo", "snapshots/", 30, 10));
    assertEquals(1, candidates.size());
    assertEquals("repo", candidates.getFirst().repository());
    assertEquals("snapshots/a", candidates.getFirst().path());

    MaintenanceTask task = host.loadMaintenanceTask("js-task", script, "js");
    MaintenanceResult dryRun = task.run(new MaintenanceRequest(true, 10));
    assertEquals(1, dryRun.proposed());
    assertEquals(0, dryRun.deleted());
    assertEquals(List.of("ok"), dryRun.messages());

    MaintenanceResult run = task.run(new MaintenanceRequest(false, 10));
    assertEquals(1, run.deleted());
  }
}
