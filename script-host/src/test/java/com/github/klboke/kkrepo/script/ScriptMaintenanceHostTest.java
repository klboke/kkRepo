package com.github.klboke.kkrepo.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptMaintenanceHostTest {

  @Test
  void loadsCleanupRuleFromScriptEngine(@TempDir Path temp) throws Exception {
    ScriptMaintenanceHost host = host();
    Path script = temp.resolve("rule.fake");
    Files.writeString(script, "// fake script");

    CleanupRule rule = host.loadCleanupRule("rule-1", script, "fake");
    List<CleanupCandidate> candidates =
        rule.candidates(new CleanupRequest("repo", "snapshots/", 30, 10));

    assertEquals("rule-1", rule.id());
    assertEquals(1, candidates.size());
    assertEquals("repo", candidates.getFirst().repository());
    assertEquals("snapshots/a", candidates.getFirst().path());
    assertEquals("stale", candidates.getFirst().reason());
    assertEquals(42, candidates.getFirst().sizeBytes());
  }

  @Test
  void loadsMaintenanceTaskFromScriptEngine(@TempDir Path temp) throws Exception {
    ScriptMaintenanceHost host = host();
    Path script = temp.resolve("task.fake");
    Files.writeString(script, "// fake script");

    MaintenanceTask task = host.loadMaintenanceTask("task-1", script, "fake");
    MaintenanceResult result = task.run(new MaintenanceRequest(true, 10));

    assertEquals("task-1", task.id());
    assertEquals(1, result.proposed());
    assertEquals(1, result.deleted());
    assertEquals(List.of("ok"), result.messages());
  }

  @Test
  void loadsRepositoryHandlerFromScriptEngine(@TempDir Path temp) throws Exception {
    ScriptMaintenanceHost host = host();
    Path script = temp.resolve("handler.fake");
    Files.writeString(script, "// fake script");

    RepositoryProtocolHandler handler = host.loadRepositoryProtocolHandler(
        "handler-1",
        script,
        "fake",
        List.of(RepositoryProtocolRoute.anyPath(
            new FormatId("raw"), RepositoryKind.HOSTED, "GET", 10)));

    assertEquals(1, handler.routes().size());
    RepositoryResponse response = handler.handle(new RepositoryRequest(
        new RepositoryDescriptor(
            1, "repo", new FormatId("raw"), RepositoryKind.HOSTED, "raw",
            true, 1L, null, null, null, null, Map.of()),
        "repo", "asset", "GET", Map.of(), Map.of(), new byte[0], null, Map.of()));
    assertEquals(200, response.status());
    assertEquals("ok", new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void rejectsRepositoryHandlerWithoutRoutes(@TempDir Path temp) throws Exception {
    ScriptMaintenanceHost host = host();
    Path script = temp.resolve("handler.fake");
    Files.writeString(script, "// fake script");

    assertThrows(PluginException.class,
        () -> host.loadRepositoryProtocolHandler("handler-1", script, "fake", List.of()));
  }

  @Test
  void rejectsUnknownScriptEngine(@TempDir Path temp) throws Exception {
    ScriptMaintenanceHost host = host();
    Path script = temp.resolve("rule.fake");
    Files.writeString(script, "// fake script");

    assertThrows(PluginException.class,
        () -> host.loadCleanupRule("rule-1", script, "missing-engine"));
  }

  private static ScriptMaintenanceHost host() {
    ScriptEngineManager manager = new ScriptEngineManager();
    manager.registerEngineName("fake", new FakeScriptEngineFactory());
    return new ScriptMaintenanceHost(manager);
  }

  private static final class FakeScriptEngine extends AbstractScriptEngine
      implements Invocable {
    private final ScriptEngineFactory factory;

    private FakeScriptEngine(ScriptEngineFactory factory) {
      this.factory = factory;
    }

    @Override
    public Object eval(String script, ScriptContext context) {
      return null;
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) {
      return null;
    }

    @Override
    public Bindings createBindings() {
      return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
      return factory;
    }

    @Override
    public Object invokeFunction(String name, Object... args) throws NoSuchMethodException {
      return switch (name) {
        case "candidates" -> """
            [{"repository":"repo","path":"snapshots/a","reason":"stale","sizeBytes":42}]
            """;
        case "run" -> """
            {"proposed":1,"deleted":1,"messages":["ok"]}
            """;
        case "handle" -> """
            {"status":200,"headers":{},"body":"b2s="}
            """;
        default -> throw new NoSuchMethodException(name);
      };
    }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args)
        throws NoSuchMethodException {
      return invokeFunction(name, args);
    }

    @Override
    public <T> T getInterface(Class<T> clasz) {
      return null;
    }

    @Override
    public <T> T getInterface(Object thiz, Class<T> clasz) {
      return null;
    }
  }

  private static final class FakeScriptEngineFactory implements ScriptEngineFactory {
    @Override
    public String getEngineName() {
      return "fake";
    }

    @Override
    public String getEngineVersion() {
      return "1";
    }

    @Override
    public List<String> getExtensions() {
      return List.of("fake");
    }

    @Override
    public List<String> getMimeTypes() {
      return List.of("text/fake");
    }

    @Override
    public List<String> getNames() {
      return List.of("fake");
    }

    @Override
    public String getLanguageName() {
      return "fake";
    }

    @Override
    public String getLanguageVersion() {
      return "1";
    }

    @Override
    public Object getParameter(String key) {
      return null;
    }

    @Override
    public String getMethodCallSyntax(String obj, String method, String... args) {
      return "";
    }

    @Override
    public String getOutputStatement(String toDisplay) {
      return "";
    }

    @Override
    public String getProgram(String... statements) {
      return "";
    }

    @Override
    public ScriptEngine getScriptEngine() {
      return new FakeScriptEngine(this);
    }
  }
}
