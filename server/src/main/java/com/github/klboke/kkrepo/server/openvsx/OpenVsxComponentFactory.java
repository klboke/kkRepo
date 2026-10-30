package com.github.klboke.kkrepo.server.openvsx;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxCoordinates;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable component projection shared by hosted publication and proxy re-indexing. */
final class OpenVsxComponentFactory {
  static final String KIND = "openvsx-extension";

  ComponentRecord component(
      RepositoryRuntime runtime,
      OpenVsxVsixInspector.Inspected inspected,
      String path,
      String sha1,
      String sha256,
      long size,
      Instant timestamp) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("openvsxNamespace", inspected.namespace());
    attributes.put("openvsxExtension", inspected.name());
    attributes.put("openvsxVersion", inspected.version());
    if (inspected.targetPlatform() != null && !inspected.targetPlatform().isBlank()) {
      attributes.put("openvsxTargetPlatform", inspected.targetPlatform());
    }
    attributes.put("openvsxPrerelease", inspected.prerelease());
    attributes.put("openvsxFileName", inspected.fileName());
    attributes.put("openvsxPath", path);
    attributes.put("openvsxPackageJson", inspected.packageJson());
    attributes.put("openvsxSha1", sha1);
    attributes.put("openvsxSha256", sha256);
    attributes.put("openvsxSize", size);
    attributes.put("openvsxTimestamp", timestamp.toString());
    if (inspected.displayName() != null) {
      attributes.put("openvsxDisplayName", inspected.displayName());
    }
    if (inspected.description() != null) {
      attributes.put("openvsxDescription", inspected.description());
    }
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.OPENVSX,
        inspected.namespace(),
        OpenVsxCoordinates.normalizeName(inspected.name()),
        versionKey(inspected.version(), inspected.targetPlatform()),
        KIND,
        PersistenceHashes.componentCoordinateHash(
            inspected.namespace(), OpenVsxCoordinates.normalizeName(inspected.name()),
            versionKey(inspected.version(), inspected.targetPlatform())),
        Map.copyOf(attributes),
        timestamp);
  }

  static String versionKey(String version, String targetPlatform) {
    if (targetPlatform == null || targetPlatform.isBlank() || "universal".equals(targetPlatform)) {
      return version;
    }
    return version + "@" + targetPlatform;
  }

  static String storagePath(OpenVsxVsixInspector.Inspected inspected) {
    return storagePath(inspected.namespace(), inspected.name(), inspected.version(),
        inspected.targetPlatform(), inspected.fileName());
  }

  static String storagePath(
      String namespace, String name, String version, String targetPlatform, String fileName) {
    String file = fileName == null || fileName.isBlank()
        ? OpenVsxCoordinates.normalizeName(name) + "-" + version
            + (targetPlatform == null || targetPlatform.isBlank() ? "" : "-" + targetPlatform)
            + ".vsix"
        : fileName;
    return namespace + "/" + OpenVsxCoordinates.normalizeName(name) + "/" + version + "/" + file;
  }
}
