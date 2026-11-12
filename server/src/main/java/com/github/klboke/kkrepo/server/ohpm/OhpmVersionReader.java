package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class OhpmVersionReader {
  private final OhpmRepositoryDao dao;
  private final ObjectMapper json;

  public OhpmVersionReader(OhpmRepositoryDao dao, ObjectMapper json) {
    this.dao = dao;
    this.json = json;
  }

  Optional<OhpmRepositoryDao.Version> find(RepositoryRuntime runtime, OhpmPackageId packageId, String path) {
    return dao.findVersion(runtime.id(), packageId.id(), version(packageId, path), runtime.isProxy());
  }

  OhpmPackageMetadata parse(OhpmPackageId packageId, String path, OhpmRepositoryDao.Version selected,
      boolean hosted) {
    String version = version(packageId, path);
    ObjectNode root = json.createObjectNode().put("name", packageId.id());
    root.putObject("dist-tags");
    ObjectNode versions = root.putObject("versions");
    if (selected.packageType() != null) root.put("packageType", selected.packageType());
    if (selected.hspType() != null) root.put("hspType", selected.hspType());
    if (!selected.manifest().isEmpty()) {
      ObjectNode manifest = json.valueToTree(selected.manifest());
      if (hosted && manifest.path("dist") instanceof ObjectNode dist) {
        String stem = "http://localhost/" + packageId.downloadPath(packageId.id() + "-" + version);
        dist.put("tarball", stem + ".har");
        if (dist.has("integrity_hsp")) dist.put("resolved_hsp", stem + ".hsp");
      }
      versions.set(version, manifest);
    }
    try {
      return OhpmPackageMetadata.parse(packageId, root);
    } catch (IllegalArgumentException error) {
      throw new OhpmException(502, "Invalid OHPM version metadata");
    }
  }

  static String version(OhpmPackageId packageId, String path) {
    String prefix = packageId.downloadPath(packageId.id() + "-");
    if (!path.startsWith(prefix) || !path.endsWith(".har") && !path.endsWith(".hsp")) {
      throw new OhpmException(404, "OHPM file does not exist");
    }
    String version = path.substring(prefix.length(), path.length() - 4);
    if (!OhpmVersions.isValid(version)) throw new OhpmException(404, "OHPM version does not exist");
    return version;
  }
}
