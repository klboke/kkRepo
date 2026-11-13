package com.github.klboke.kkrepo.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginException;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * Loads JSR-223 scripts and exposes them as {@link CleanupRule} or {@link MaintenanceTask}.
 *
 * <p>The engine is supplied by the runtime (GraalJS in production, another JSR-223 engine in
 * tests). Scripts receive a JSON request and must return a JSON response:
 *
 * <pre>
 * candidates(requestJson) -&gt; [{"repository":"...","path":"...","reason":"...","sizeBytes":42}]
 * run(requestJson)        -&gt; {"proposed":1,"deleted":1,"messages":["..."]}
 * </pre>
 */
public final class ScriptMaintenanceHost {
  private final ScriptEngineManager engines;
  private final ObjectMapper mapper = new ObjectMapper();

  public ScriptMaintenanceHost() {
    this(new ScriptEngineManager());
  }

  ScriptMaintenanceHost(ScriptEngineManager engines) {
    this.engines = Objects.requireNonNull(engines, "engines");
  }

  public CleanupRule loadCleanupRule(String id, Path script, String language)
      throws PluginException {
    String ruleId = requireId(id);
    ScriptEngine engine = engine(script, language);
    return new CleanupRule() {
      @Override
      public String id() {
        return ruleId;
      }

      @Override
      public List<CleanupCandidate> candidates(CleanupRequest request) {
        return invoke(engine, "candidates", request,
            new TypeReference<List<CleanupCandidate>>() {}, "cleanup rule " + ruleId);
      }
    };
  }

  public MaintenanceTask loadMaintenanceTask(String id, Path script, String language)
      throws PluginException {
    String taskId = requireId(id);
    ScriptEngine engine = engine(script, language);
    return new MaintenanceTask() {
      @Override
      public String id() {
        return taskId;
      }

      @Override
      public MaintenanceResult run(MaintenanceRequest request) {
        return invoke(engine, "run", request,
            new TypeReference<MaintenanceResult>() {}, "maintenance task " + taskId);
      }
    };
  }

  public RepositoryProtocolHandler loadRepositoryProtocolHandler(
      String id,
      Path script,
      String language,
      Collection<RepositoryProtocolRoute> routes) throws PluginException {
    String handlerId = requireId(id);
    if (routes == null || routes.isEmpty() || routes.stream().anyMatch(Objects::isNull)) {
      throw new PluginException("Repository handler routes are required: " + handlerId);
    }
    List<RepositoryProtocolRoute> registered = List.copyOf(routes);
    ScriptEngine engine = engine(script, language);
    return new RepositoryProtocolHandler() {
      @Override
      public Collection<RepositoryProtocolRoute> routes() {
        return registered;
      }

      @Override
      public RepositoryResponse handle(RepositoryRequest request) {
        return invoke(engine, "handle", scriptPayload(request),
            new TypeReference<RepositoryResponse>() {}, "repository handler " + handlerId);
      }
    };
  }

  /** Scripts receive portable JSON; host services stay in-process and are never serialized. */
  private static Map<String, Object> scriptPayload(RepositoryRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("repository", request.repository());
    payload.put("repositoryName", request.repositoryName());
    payload.put("path", request.path());
    payload.put("method", request.method());
    payload.put("headers", request.headers());
    payload.put("queryParameters", request.queryParameters());
    payload.put("body", request.body());
    payload.put("multipart", request.multipart());
    payload.put("attributes", request.attributes());
    return payload;
  }

  private ScriptEngine engine(Path script, String language) throws PluginException {
    if (script == null || !Files.isRegularFile(script)) {
      throw new PluginException("Script file not found: " + script);
    }
    if (language == null || language.isBlank()) {
      throw new PluginException("Script language is required");
    }
    ScriptEngine engine = engines.getEngineByName(language);
    if (engine == null) {
      throw new PluginException("No JSR-223 engine for language: " + language);
    }
    try {
      engine.eval(Files.readString(script));
    } catch (IOException | ScriptException invalid) {
      throw new PluginException("Unable to load script: " + script, invalid);
    }
    return engine;
  }

  private <T> T invoke(
      ScriptEngine engine, String function, Object request, TypeReference<T> type, String description) {
    if (!(engine instanceof Invocable invocable)) {
      throw new IllegalStateException(
          "Script engine does not support function invocation: "
              + engine.getFactory().getEngineName());
    }
    synchronized (engine) {
      try {
        String payload = mapper.writeValueAsString(request);
        Object result = invocable.invokeFunction(function, payload);
        if (result == null) {
          throw new IllegalStateException("Script function returned null: " + function);
        }
        return mapper.readValue(String.valueOf(result), type);
      } catch (ReflectiveOperationException | IOException | ScriptException failure) {
        throw new IllegalStateException("Script " + description + " failed", failure);
      }
    }
  }

  private static String requireId(String id) throws PluginException {
    if (id == null || id.isBlank()) {
      throw new PluginException("Script id is required");
    }
    return id.trim();
  }
}
