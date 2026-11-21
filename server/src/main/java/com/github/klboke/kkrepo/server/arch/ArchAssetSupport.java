package com.github.klboke.kkrepo.server.arch;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.arch.ArchDesc;
import com.github.klboke.kkrepo.protocol.arch.ArchMediaTypes;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Blob/asset plumbing for pacman repositories. */
@Component
final class ArchAssetSupport {
  static final String KIND = "arch-package";

  private final AssetDao assets;
  private final RawHostedService hosted;

  ArchAssetSupport(
      AssetDao assets,
      RawHostedService hosted) {
    this.assets = assets;
    this.hosted = hosted;
  }

  ComponentRecord store(
      RepositoryRuntime runtime,
      ArchPackageInspector.Inspected inspected,
      Path file,
      String path,
      String md5,
      String sha1,
      String sha256,
      long size,
      String actor,
      String ip) {
    ComponentRecord component = component(runtime, inspected, md5, sha1, sha256);
    hosted.putInternalWithComponentFileAtBrowsePath(
        runtime, path, file, ArchMediaTypes.forPackage(path), component.attributes(), actor, ip,
        component, path);
    return component;
  }

  ComponentRecord component(
      RepositoryRuntime runtime,
      ArchPackageInspector.Inspected inspected,
      String md5,
      String sha1,
      String sha256) {
    ArchDesc desc = inspected.desc();
    String versionKey = desc.version() + "@" + desc.architecture();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("archFileName", desc.fileName());
    attributes.put("archName", desc.name());
    attributes.put("archBase", desc.base());
    attributes.put("archVersion", desc.version());
    attributes.put("archArchitecture", desc.architecture());
    attributes.put("archDescription", desc.description());
    attributes.put("archCsize", desc.compressedSize());
    attributes.put("archIsize", desc.installedSize());
    attributes.put("archMd5", md5);
    attributes.put("archSha1", sha1);
    attributes.put("archSha256", sha256);
    attributes.put("archBuildDate", desc.buildDate());
    attributes.put("archPackager", desc.packager());
    attributes.put("archUrl", desc.url());
    attributes.put("archDesc", desc.render());
    attributes.put("archCompression", inspected.filename().compression());
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.ARCH,
        null,
        desc.name(),
        versionKey,
        KIND,
        PersistenceHashes.componentCoordinateHash(null, desc.name(), versionKey),
        java.util.Collections.unmodifiableMap(attributes),
        Instant.now());
  }

  MavenResponse serve(RepositoryRuntime runtime, String path, boolean headOnly) {
    return hosted.getInternal(runtime, path, headOnly);
  }

  void storeSignature(
      RepositoryRuntime runtime,
      String path,
      byte[] signature,
      String actor,
      String ip) {
    hosted.putInternalUnindexed(
        runtime, path, new java.io.ByteArrayInputStream(signature),
        ArchMediaTypes.SIGNATURE, Map.of("archSignature", true), actor, ip);
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  void delete(RepositoryRuntime runtime, String path) {
    hosted.deleteInternal(runtime, path);
  }
}
