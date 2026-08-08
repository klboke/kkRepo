package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.apt.AptPackageControl;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Creates Nexus-style logical components for APT binary packages. */
@Component
final class AptComponentFactory {
  ComponentRecord component(
      RepositoryRuntime runtime,
      String distribution,
      String component,
      AptPackageControl control,
      String filename,
      String assetPath,
      Instant updatedAt) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("distribution", distribution);
    attributes.put("component", component);
    attributes.put("architecture", control.architecture());
    attributes.put("filename", filename);
    attributes.put("assetPath", assetPath);
    if (control.source() != null) attributes.put("sourcePackage", control.source());
    String namespace = distribution + "/" + component;
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.APT,
        namespace,
        control.packageName(),
        control.version(),
        "apt-package",
        PersistenceHashes.sha256(
            "apt", distribution, component, control.packageName(), control.version()),
        Map.copyOf(attributes),
        updatedAt == null ? Instant.now() : updatedAt);
  }

  String browsePath(
      String distribution,
      String component,
      AptPackageControl control,
      String filename) {
    return distribution + "/" + component + "/" + control.packageName() + "/"
        + control.version() + "/" + control.architecture() + "/" + filename;
  }
}
