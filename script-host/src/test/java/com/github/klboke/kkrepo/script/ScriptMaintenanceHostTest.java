package com.github.klboke.kkrepo.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.util.List;
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
