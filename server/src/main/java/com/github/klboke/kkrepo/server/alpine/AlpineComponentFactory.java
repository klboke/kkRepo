package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePackageInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Creates typed component and Browse identities from verified APK metadata. */
@Component
final class AlpineComponentFactory {
  ComponentRecord component(
      RepositoryRuntime runtime,
      String distribution,
      String channel,
      String repositoryArchitecture,
      AlpinePackageInfo info,
      String filename,
      String assetPath,
      String identity,
      String sha256,
      Instant updatedAt) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("distribution", distribution);
    attributes.put("channel", channel);
    attributes.put("repositoryArchitecture", repositoryArchitecture);
    attributes.put("packageArchitecture", info.architecture());
    attributes.put("filename", filename);
    attributes.put("assetPath", assetPath);
    attributes.put("apkIdentity", identity);
    attributes.put("sha256", sha256);
    attributes.put("installedSize", info.installedSize());
    put(attributes, "origin", info.origin());
    put(attributes, "license", info.license());
    put(attributes, "maintainer", info.maintainer());
    String namespace = distribution + "/" + channel + "/" + repositoryArchitecture;
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.ALPINE,
        namespace,
        info.name(),
        info.version(),
        "alpine-apk-v2",
        PersistenceHashes.sha256(
            "alpine", distribution, channel, repositoryArchitecture,
            info.name(), info.version(), info.architecture()),
        Map.copyOf(attributes),
        updatedAt == null ? Instant.now() : updatedAt);
  }

  String browsePath(
      String distribution,
      String channel,
      String repositoryArchitecture,
      AlpinePackageInfo info,
      String filename) {
    return distribution + "/" + channel + "/" + repositoryArchitecture + "/"
        + info.name() + "/" + info.version() + "/" + filename;
  }

  private static void put(Map<String, Object> target, String key, Object value) {
    if (value != null && !value.toString().isBlank()) target.put(key, value);
  }
}
