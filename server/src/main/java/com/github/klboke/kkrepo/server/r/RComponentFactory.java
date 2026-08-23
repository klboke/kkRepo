package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.r.RPackageMetadata;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Creates typed Components and Browse identities from verified R package metadata. */
@Component
final class RComponentFactory {
  ComponentRecord component(
      RepositoryRuntime runtime,
      RPackageMetadata metadata,
      String filename,
      String assetPath,
      String md5,
      String sha256,
      long size,
      Instant updatedAt) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("namespace", RService.SOURCE_NAMESPACE);
    attributes.put("filename", filename);
    attributes.put("assetPath", assetPath);
    attributes.put("md5", md5);
    attributes.put("sha256", sha256);
    attributes.put("size", size);
    copy(metadata.fields(), attributes, "License");
    copy(metadata.fields(), attributes, "Depends");
    copy(metadata.fields(), attributes, "Imports");
    copy(metadata.fields(), attributes, "LinkingTo");
    copy(metadata.fields(), attributes, "Suggests");
    copy(metadata.fields(), attributes, "Enhances");
    copy(metadata.fields(), attributes, "NeedsCompilation");
    copy(metadata.fields(), attributes, "SystemRequirements");
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.R,
        RService.SOURCE_NAMESPACE,
        metadata.packageName(),
        metadata.version(),
        "r-source-package",
        PersistenceHashes.sha256(
            "r", RService.SOURCE_NAMESPACE, metadata.packageName(), metadata.version()),
        Map.copyOf(attributes),
        updatedAt == null ? Instant.now() : updatedAt);
  }

  String browsePath(String packageName, String version, String filename) {
    return RService.SOURCE_NAMESPACE + "/" + packageName + "/" + version + "/" + filename;
  }

  private static void copy(
      Map<String, String> source, Map<String, Object> target, String name) {
    String value = source.get(name);
    if (value != null && !value.isBlank()) target.put(name, value);
  }
}
