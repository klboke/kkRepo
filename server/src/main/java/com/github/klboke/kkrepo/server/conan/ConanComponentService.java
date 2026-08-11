package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conan.ConanReference;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds one cleanup/search component for a complete Conan recipe version. */
@Component
final class ConanComponentService {
  private final ComponentDao components;

  ConanComponentService(ComponentDao components) {
    this.components = components;
  }

  ComponentRecord component(RepositoryRuntime runtime, ConanReference reference, Instant updatedAt) {
    if (runtime == null || runtime.format() != RepositoryFormat.CONAN || runtime.isGroup()) {
      throw new IllegalArgumentException("Conan components require a hosted or proxy repository");
    }
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("user", reference.routeUser());
    attributes.put("channel", reference.routeChannel());
    attributes.put("recipe", reference.recipe());
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.CONAN,
        reference.namespace(),
        reference.name(),
        reference.version(),
        "conan-recipe",
        PersistenceHashes.sha256(
            "conan", reference.name(), reference.version(), reference.routeUser(),
            reference.routeChannel()),
        Map.copyOf(attributes),
        updatedAt == null ? Instant.now() : updatedAt);
  }

  void deleteIfNoAssets(long componentId) {
    if (componentId > 0) components.deleteIfNoAssets(componentId);
  }
}
