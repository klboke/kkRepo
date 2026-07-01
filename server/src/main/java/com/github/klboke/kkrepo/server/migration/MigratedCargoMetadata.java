package com.github.klboke.kkrepo.server.migration;

import com.github.klboke.kkrepo.server.cargo.CargoPackageMetadata;
import com.github.klboke.kkrepo.server.cargo.CargoCrateInspector;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

record MigratedCargoMetadata(CargoPackageMetadata metadata) {

  static MigratedCargoMetadata fromCrate(Path crateFile) {
    CargoCrateInspector.Manifest manifest = CargoCrateInspector.inspect(crateFile);
    return new MigratedCargoMetadata(CargoPackageMetadata.fromManifest(manifest));
  }

  String normalizedName() {
    return metadata.normalizedName();
  }

  String versionKey() {
    return metadata.versionKey();
  }

  Map<String, Object> componentAttributes(String checksum, String cratePath, boolean yanked) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("crateName", metadata.name());
    attributes.put("normalizedName", metadata.normalizedName());
    attributes.put("version", metadata.version());
    attributes.put("versionKey", metadata.versionKey());
    attributes.put("cratePath", cratePath);
    putText(attributes, "description", metadata.description());
    putText(attributes, "homepage", metadata.publishJson().get("homepage"));
    putText(attributes, "documentation", metadata.publishJson().get("documentation"));
    putText(attributes, "repository", metadata.publishJson().get("repository"));
    attributes.put("indexEntry", metadata.indexEntry(checksum, yanked));
    return attributes;
  }

  Map<String, Object> assetAttributes() {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("crateName", metadata.name());
    attributes.put("normalizedName", metadata.normalizedName());
    attributes.put("version", metadata.version());
    attributes.put("versionKey", metadata.versionKey());
    putText(attributes, "description", metadata.description());
    putText(attributes, "homepage", metadata.publishJson().get("homepage"));
    putText(attributes, "documentation", metadata.publishJson().get("documentation"));
    putText(attributes, "repository", metadata.publishJson().get("repository"));
    return attributes;
  }

  private static void putText(Map<String, Object> target, String key, Object value) {
    String text = text(value);
    if (text != null) {
      target.put(key, text);
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }
}
