package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPublishMetadata;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class OhpmImportSource {
  private final OhpmArchiveUpload archives;
  private final HttpRemoteFetcher fetcher;

  OhpmImportSource(HttpRemoteFetcher fetcher,
      @Value("${kkrepo.ohpm.max-file-bytes:1073741824}") long maxFileBytes) {
    this.fetcher = fetcher;
    this.archives = new OhpmArchiveUpload(maxFileBytes, null);
  }

  OhpmPackageMetadata metadata(RepositoryRuntime source, OhpmPackageId packageId) throws IOException {
    if (source.format() != RepositoryFormat.OHPM || !source.isProxy() || !source.online()) {
      throw new OhpmException(400, "Import source requires an online OHPM proxy configuration");
    }
    String url = RemoteUrlBuilder.repositoryPathString(source.proxyRemoteUrl(), packageId.id());
    try (var result = fetcher.fetch(OhpmProxyService.remote(source, url, HttpRemoteFetcher.TimeoutProfile.METADATA))) {
      requireSuccess(result);
      return OhpmPackageMetadata.read(packageId, result.body());
    }
  }

  OhpmPublishParser.Upload registry(RepositoryRuntime source, OhpmPackageId packageId, String version)
      throws IOException {
    OhpmPackageMetadata metadata = metadata(source, packageId);
    var downloads = metadata.downloads().stream().filter(download -> download.version().equals(version)).toList();
    var har = downloads.stream().filter(download -> !download.hsp()).findFirst()
        .orElseThrow(() -> new OhpmException(404, "Source version is missing"));
    var hsp = downloads.stream().filter(OhpmPackageMetadata.Download::hsp).findFirst().orElse(null);
    var harRequest = OhpmProxyService.remote(source, har.remoteUrl(), HttpRemoteFetcher.TimeoutProfile.CONTENT);
    var hspRequest = hsp == null ? null
        : OhpmProxyService.remote(source, hsp.remoteUrl(), HttpRemoteFetcher.TimeoutProfile.CONTENT);
    try (var harResult = fetcher.fetch(harRequest);
         var hspResult = hspRequest == null ? null : fetcher.fetch(hspRequest)) {
      requireSuccess(harResult);
      if (hspResult != null) requireSuccess(hspResult);
      var upload = directory(harResult.body(), hspResult == null ? null : hspResult.body(), false, metadata);
      if (!version.equals(upload.metadata().version())) {
        upload.close();
        throw new OhpmException(409, "Downloaded HAR does not contain the requested source version");
      }
      return upload;
    }
  }

  OhpmPublishParser.Upload directory(InputStream har, InputStream hsp, boolean bundle,
      OhpmPackageMetadata metadata) throws IOException {
    if (bundle && hsp != null) throw new OhpmException(400, "HSP bundle must not have a separate HSP file");
    var parsed = bundle ? archives.parseBundle(har, null) : archives.parse(har, hsp, null);
    try {
      var identity = parsed.metadata();
      ObjectNode root;
      if (metadata == null) {
        root = identity.metadata().deepCopy();
        root.putObject("dist-tags");
      } else {
        root = metadata.original();
        if (!identity.packageId().id().equals(root.path("name").textValue())) {
          throw new OhpmException(409, "Source metadata and HAR package identity differ");
        }
        ObjectNode manifest = metadata.manifest(identity.version());
        root.putObject("versions").set(identity.version(), manifest);
        ObjectNode tags = root.putObject("dist-tags");
        metadata.original().path("dist-tags").fields().forEachRemaining(entry -> {
          if (identity.version().equals(entry.getValue().textValue())) tags.set(entry.getKey(), entry.getValue());
        });
        root.put("_id", identity.packageId().id());
        root.remove(List.of("packageType", "hspType", "time", "_rev"));
        if (identity.isHsp()) manifest.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
      }
      ObjectNode manifest = (ObjectNode) root.path("versions").path(identity.version());
      ObjectNode dist = (ObjectNode) manifest.path("dist");
      dist.remove(List.of("tarball", "resolved_hsp"));
      OhpmPublishMetadata publication = OhpmPublishMetadata.forImport(identity.packageId(), root);
      if (!identity.harIntegrity().equals(publication.harIntegrity())
          || !java.util.Objects.equals(identity.hspIntegrity(), publication.hspIntegrity())) {
        throw new OhpmException(409, "Source metadata checksum does not match archive content");
      }
      return new OhpmPublishParser.Upload(publication, parsed.har(), parsed.hsp());
    } catch (RuntimeException error) {
      parsed.close();
      throw error;
    }
  }

  private static void requireSuccess(HttpRemoteFetcher.Result result) {
    if (result.status() != 200) {
      throw new OhpmException(result.status() == 404 ? 404 : 502,
          "OHPM import source returned HTTP " + result.status());
    }
  }
}
