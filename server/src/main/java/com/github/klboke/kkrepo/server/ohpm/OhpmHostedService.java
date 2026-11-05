package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmBrowsePath;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPublishMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPathParser;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmUnpublishRequest;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmVersions;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OhpmHostedService {
  private static final String METADATA = "ohpmMetadata";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final AssetDao assets;
  private final RawHostedService content;
  private final TransientTransactionRetry transactions;
  private final ObjectMapper json;
  private final BlobStorageRegistry storages;
  private final ArtifactDownloadPolicy downloads;
  private final OhpmPublishParser parser;
  private final OhpmStreamParser streamParser;
  private final OhpmArchiveUpload archiveParser;

  public OhpmHostedService(AssetDao assets, RawHostedService content, TransientTransactionRetry transactions,
      ObjectMapper json, BlobStorageRegistry storages, ArtifactDownloadPolicy downloads,
      @Value("${kkrepo.ohpm.max-file-bytes:1073741824}") long maxFileBytes) {
    this.assets = assets;
    this.content = content;
    this.transactions = transactions;
    this.json = json;
    this.storages = storages;
    this.downloads = downloads;
    this.parser = new OhpmPublishParser(maxFileBytes, null);
    this.streamParser = new OhpmStreamParser(maxFileBytes, null);
    this.archiveParser = new OhpmArchiveUpload(maxFileBytes, null);
  }

  public void publish(RepositoryRuntime runtime, OhpmPackageId packageId, InputStream body,
      String actor, String remoteAddress, java.util.function.Consumer<Boolean> authorizePublication) throws IOException {
    requireHosted(runtime, true);
    try (var upload = parser.parse(packageId, body)) {
      publish(runtime, upload, actor, remoteAddress, authorizePublication);
    }
  }

  public void publishStream(RepositoryRuntime runtime, OhpmPackageId packageId,
      java.util.Collection<jakarta.servlet.http.Part> parts, String actor, String remoteAddress,
      java.util.function.Consumer<Boolean> authorizePublication) throws IOException {
    requireHosted(runtime, true);
    try (var upload = streamParser.parse(packageId, parts)) {
      publish(runtime, upload, actor, remoteAddress, authorizePublication);
    }
  }

  public List<String> publishArchives(RepositoryRuntime runtime, InputStream har, InputStream hsp, String tag,
      String actor, String remoteAddress,
      java.util.function.BiConsumer<OhpmPackageId, Boolean> authorizePublication) throws IOException {
    requireHosted(runtime, true);
    try (var upload = archiveParser.parse(har, hsp, tag)) {
      return publishArchive(runtime, upload, actor, remoteAddress, authorizePublication);
    }
  }

  public List<String> publishArchiveBundle(RepositoryRuntime runtime, InputStream bundle, String tag,
      String actor, String remoteAddress,
      java.util.function.BiConsumer<OhpmPackageId, Boolean> authorizePublication) throws IOException {
    requireHosted(runtime, true);
    try (var upload = archiveParser.parseBundle(bundle, tag)) {
      return publishArchive(runtime, upload, actor, remoteAddress, authorizePublication);
    }
  }

  private List<String> publishArchive(RepositoryRuntime runtime, OhpmPublishParser.Upload upload,
      String actor, String remoteAddress, java.util.function.BiConsumer<OhpmPackageId, Boolean> authorizePublication) {
    OhpmPackageId packageId = upload.metadata().packageId();
    publish(runtime, upload, actor, remoteAddress, existing -> authorizePublication.accept(packageId, existing));
    String basename = packageId.id() + "-" + upload.metadata().version();
    return upload.hsp() == null ? List.of(packageId.downloadPath(basename + ".har"))
        : List.of(packageId.downloadPath(basename + ".har"), packageId.downloadPath(basename + ".hsp"));
  }

  private void publish(RepositoryRuntime runtime, OhpmPublishParser.Upload upload,
      String actor, String remoteAddress, java.util.function.Consumer<Boolean> authorizePublication) {
    OhpmPackageId packageId = upload.metadata().packageId();
    transactions.execute("Publish OHPM " + packageId.id(), () -> {
      Instant now = Instant.now();
      AssetRecord root = lock(runtime, packageId, true, now);
      ObjectNode metadata = metadata(root, packageId);
      ObjectNode versions = (ObjectNode) metadata.path("versions");
      String version = upload.metadata().version();
      if (versions.has(version)) throw new OhpmException(400, "OHPM version already exists");
      authorizePublication.accept(!versions.isEmpty());
      storePublication(runtime, upload, actor, remoteAddress, root, metadata, now);
      return null;
    });
  }

  private void storePublication(RepositoryRuntime runtime, OhpmPublishParser.Upload upload,
      String actor, String remoteAddress, AssetRecord root, ObjectNode metadata, Instant now) {
    OhpmPackageId packageId = upload.metadata().packageId();
    String version = upload.metadata().version();
    ObjectNode incoming = upload.metadata().metadata();
    ObjectNode manifest = ((ObjectNode) incoming.path("versions").path(version)).deepCopy();
    ObjectNode dist = (ObjectNode) manifest.path("dist");
    dist.put("tarball", packageId.downloadPath(file(packageId, version, ".har")));
    if (upload.metadata().isHsp()) {
      dist.put("resolved_hsp", packageId.downloadPath(file(packageId, version, ".hsp")));
      manifest.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
    } else {
      dist.remove("resolved_hsp");
    }
    ComponentRecord component = new ComponentRecord(
        null, runtime.id(), RepositoryFormat.OHPM, packageId.scope(), packageId.name(), version,
        "ohpm-package", PersistenceHashes.componentCoordinateHash(packageId.scope(), packageId.name(), version),
        json.convertValue(manifest, MAP), now);
    storeFile(runtime, packageId, version, ".har", upload.har(), actor, remoteAddress, component);
    if (upload.hsp() != null) {
      storeFile(runtime, packageId, version, ".hsp", upload.hsp(), actor, remoteAddress, component);
    }
    metadata.remove(List.of("packageType", "hspType"));
    incoming.fields().forEachRemaining(entry -> {
      if (!List.of("versions", "dist-tags", "time", "_attachments", "_rev").contains(entry.getKey())) {
        metadata.set(entry.getKey(), entry.getValue().deepCopy());
      }
    });
    if (upload.metadata().isHsp()) metadata.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
    ((ObjectNode) metadata.path("versions")).set(version, manifest);
    ((ObjectNode) metadata.path("dist-tags")).setAll((ObjectNode) incoming.path("dist-tags"));
    ObjectNode time = (ObjectNode) metadata.path("time");
    if (!time.has("created")) time.put("created", now.toString());
    time.put(version, now.toString());
    save(root, metadata, now);
  }

  ImportCheck inspectImport(RepositoryRuntime runtime, OhpmPublishMetadata incoming) throws IOException {
    requireHosted(runtime, true);
    ObjectNode metadata = assets.findAssetByPath(runtime.id(), incoming.packageId().id())
        .map(root -> metadata(root, incoming.packageId())).orElseGet(() -> empty(incoming.packageId()));
    return inspectImport(runtime, incoming, metadata);
  }

  ImportCheck importVersion(RepositoryRuntime runtime, OhpmPublishParser.Upload upload, String actor,
      String remoteAddress, java.util.function.Consumer<Boolean> authorizePublication) throws IOException {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("OHPM import must share the result transaction");
    }
    requireHosted(runtime, true);
    Instant now = Instant.now();
    var root = lock(runtime, upload.metadata().packageId(), true, now);
    var metadata = metadata(root, upload.metadata().packageId());
    authorizePublication.accept(!metadata.path("versions").isEmpty());
    ImportCheck check = inspectImport(runtime, upload.metadata(), metadata);
    if (check.conflict() != null) return check;
    if (check.existing()) {
      ObjectNode tags = (ObjectNode) metadata.path("dist-tags");
      ObjectNode incomingTags = (ObjectNode) upload.metadata().metadata().path("dist-tags");
      if (incomingTags.properties().stream().anyMatch(entry -> !entry.getValue().equals(tags.get(entry.getKey())))) {
        tags.setAll(incomingTags);
        save(root, metadata, now);
      }
    } else {
      storePublication(runtime, upload, actor, remoteAddress, root, metadata, now);
    }
    return check;
  }

  private ImportCheck inspectImport(RepositoryRuntime runtime, OhpmPublishMetadata incoming,
      ObjectNode metadata) throws IOException {
    String version = incoming.version();
    var tags = incoming.metadata().path("dist-tags").fields();
    while (tags.hasNext()) {
      var tag = tags.next();
      if (metadata.path("dist-tags").has(tag.getKey())
          && !tag.getValue().equals(metadata.path("dist-tags").path(tag.getKey()))) {
        return new ImportCheck(false, "Target distribution tag conflicts: " + tag.getKey());
      }
    }
    var existing = metadata.path("versions").path(version);
    if (existing.isMissingNode()) return new ImportCheck(false, null);
    var dist = existing.path("dist");
    if (!incoming.harIntegrity().value().equals(dist.path("integrity").textValue())
        || incoming.isHsp() != dist.has("integrity_hsp")
        || incoming.isHsp() && !incoming.hspIntegrity().value().equals(dist.path("integrity_hsp").textValue())) {
      return new ImportCheck(true, "Target version has different content");
    }
    ObjectNode actualManifest = ((ObjectNode) existing).deepCopy();
    ObjectNode expectedManifest = ((ObjectNode) incoming.metadata().path("versions").path(version)).deepCopy();
    var generatedFields = List.of("_id", "_nodeVersion", "_ohpmVersion", "dist");
    actualManifest.remove(generatedFields);
    expectedManifest.remove(generatedFields);
    if (!json.readTree(json.writeValueAsBytes(actualManifest))
        .equals(json.readTree(json.writeValueAsBytes(expectedManifest)))) {
      return new ImportCheck(true, "Target version has different publication metadata");
    }
    try {
      if (!importFileMatches(runtime, incoming.packageId(), version, ".har", incoming.harIntegrity())
          || incoming.isHsp()
              && !importFileMatches(runtime, incoming.packageId(), version, ".hsp", incoming.hspIntegrity())) {
        return new ImportCheck(true, "Target version content is missing or corrupt");
      }
    } catch (OhpmException error) {
      if (error.status() != 404) throw error;
      return new ImportCheck(true, "Target version content is missing");
    }
    return new ImportCheck(true, null);
  }

  private boolean importFileMatches(RepositoryRuntime runtime, OhpmPackageId packageId, String version,
      String extension, OhpmIntegrity expected) throws IOException {
    MavenResponse response = download(runtime, packageId.downloadPath(file(packageId, version, extension)), false);
    if (!OhpmContentIntegrity.matches(response, expected)) return false;
    try (var input = response.body()) {
      return OhpmIntegrity.compute(input).equals(expected);
    }
  }

  record ImportCheck(boolean existing, String conflict) {}

  private void storeFile(RepositoryRuntime runtime, OhpmPackageId packageId, String version, String extension,
      OhpmPublishParser.Attachment attachment, String actor, String remoteAddress, ComponentRecord component) {
    boolean created = content.putInternalWithComponentFileAtBrowsePathIfAbsent(
        runtime, packageId.downloadPath(file(packageId, version, extension)), attachment.file(),
        "application/octet-stream", Map.of(), actor, remoteAddress, component,
        packageId.id() + "/" + version + "/" + packageId.name() + "-" + version + extension);
    if (!created) throw new OhpmException(400, "OHPM version content already exists");
  }

  public MavenResponse metadata(RepositoryRuntime runtime, OhpmPackageId packageId,
      String repositoryBaseUrl, boolean head) throws IOException {
    requireHosted(runtime, false);
    AssetRecord root = assets.findAssetByPath(runtime.id(), packageId.id()).orElseThrow(OhpmHostedService::missing);
    ObjectNode metadata = metadata(root, packageId);
    requirePublished(metadata);
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    metadata.path("versions").fields().forEachRemaining(entry -> {
      ObjectNode dist = (ObjectNode) entry.getValue().path("dist");
      dist.put("tarball", base + packageId.downloadPath(file(packageId, entry.getKey(), ".har")));
      if (dist.has("integrity_hsp")) {
        dist.put("resolved_hsp", base + packageId.downloadPath(file(packageId, entry.getKey(), ".hsp")));
      }
    });
    byte[] bytes = json.writeValueAsBytes(metadata);
    String etag = java.util.HexFormat.of().formatHex(PersistenceHashes.sha256(
        new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
    return (head
        ? MavenResponse.noBody(200, bytes.length, "application/json", etag, root.lastUpdatedAt())
        : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", etag, root.lastUpdatedAt()))
        .withHeader("Cache-Control", "private, no-cache");
  }

  public MavenResponse download(RepositoryRuntime runtime, String path, boolean head) {
    requireHosted(runtime, false);
    AssetRecord asset = assets.findAssetByPath(runtime.id(), path)
        .filter(value -> value.format() == RepositoryFormat.OHPM && value.assetBlobId() != null)
        .orElseThrow(OhpmHostedService::missing);
    var blob = assets.findBlobById(asset.assetBlobId()).orElseThrow(OhpmHostedService::missing);
    downloads.beforeReadFromRepository(asset.id(), blob.id(), runtime.id());
    if (head) {
      return OhpmContentIntegrity.attach(
          MavenResponse.noBody(200, blob.size(), "application/octet-stream", blob.sha256(), asset.lastUpdatedAt()),
          blob.attributes().get("sha512"));
    }
    var reference = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    return OhpmContentIntegrity.attach(MavenResponse.ok(() -> storages.forBlobStoreId(blob.blobStoreId()).get(reference)
        .orElseThrow(OhpmHostedService::missing),
        blob.size(), "application/octet-stream", blob.sha256(), asset.lastUpdatedAt())
        .withHeader("Cache-Control", "private, no-cache"), blob.attributes().get("sha512"));
  }

  public void tag(RepositoryRuntime runtime, OhpmPackageId packageId, String tag, String version, boolean delete) {
    requireHosted(runtime, true);
    if (!OhpmPathParser.isValidTag(tag) || !delete && !OhpmVersions.isValid(version)) {
      throw new OhpmException(400, "Invalid OHPM tag or version");
    }
    transactions.execute("Update OHPM tag " + packageId.id(), () -> {
      Instant now = Instant.now();
      AssetRecord root = lock(runtime, packageId, false, now);
      ObjectNode metadata = metadata(root, packageId);
      requirePublished(metadata);
      ObjectNode tags = (ObjectNode) metadata.path("dist-tags");
      if (delete) {
        if (tags.remove(tag) == null) throw missing();
      } else {
        if (!metadata.path("versions").has(version)) throw new OhpmException(400, "OHPM tag version does not exist");
        tags.put(tag, version);
      }
      save(root, metadata, now);
      return null;
    });
  }

  public void unpublish(RepositoryRuntime runtime, OhpmUnpublishRequest request) {
    unpublish(runtime, request, null);
  }

  public int deleteBrowse(RepositoryRuntime runtime, OhpmBrowsePath path) {
    return unpublish(runtime, new OhpmUnpublishRequest(path.packageId(), path.version()),
        path.filename() == null ? null : path.storagePath());
  }

  private int unpublish(RepositoryRuntime runtime, OhpmUnpublishRequest request, String requiredFile) {
    requireHosted(runtime, true);
    return transactions.execute("Unpublish OHPM " + request.packageId().id(), () -> {
      Instant now = Instant.now();
      AssetRecord root = lock(runtime, request.packageId(), false, now);
      ObjectNode metadata = metadata(root, request.packageId());
      requirePublished(metadata);
      ObjectNode versions = (ObjectNode) metadata.path("versions");
      if (requiredFile != null && assets.findAssetByPath(runtime.id(), requiredFile).isEmpty()) throw missing();
      List<String> removed = new ArrayList<>();
      if (request.version() == null) versions.fieldNames().forEachRemaining(removed::add);
      else if (versions.has(request.version())) removed.add(request.version());
      else throw missing();
      int deletedAssets = 0;
      for (String version : removed) {
        if (content.deleteInternal(runtime,
            request.packageId().downloadPath(file(request.packageId(), version, ".har"))).status() == 204) deletedAssets++;
        if (versions.path(version).path("dist").has("integrity_hsp")) {
          if (content.deleteInternal(runtime,
              request.packageId().downloadPath(file(request.packageId(), version, ".hsp"))).status() == 204) deletedAssets++;
        }
        versions.remove(version);
        ((ObjectNode) metadata.path("time")).remove(version);
      }
      ObjectNode tags = (ObjectNode) metadata.path("dist-tags");
      List<String> removedTags = new ArrayList<>();
      tags.fields().forEachRemaining(entry -> {
        if (removed.contains(entry.getValue().asText())) removedTags.add(entry.getKey());
      });
      tags.remove(removedTags);
      if (versions.isEmpty()) metadata = empty(request.packageId());
      save(root, metadata, now);
      return deletedAssets;
    });
  }

  private AssetRecord lock(RepositoryRuntime runtime, OhpmPackageId packageId, boolean create, Instant now) {
    if (create) {
      assets.tryInsertAsset(new AssetRecord(
          null, runtime.id(), null, null, RepositoryFormat.OHPM, packageId.id(),
          PersistenceHashes.pathHash(packageId.id()), packageId.name(), "ohpm-metadata", "application/json",
          null, null, now, Map.of(METADATA, json.convertValue(empty(packageId), MAP))));
    }
    return assets.findAssetByPathForUpdate(runtime.id(), packageId.id()).orElseThrow(OhpmHostedService::missing);
  }

  private ObjectNode metadata(AssetRecord root, OhpmPackageId packageId) {
    Object value = root.attributes().get(METADATA);
    if (root.format() != RepositoryFormat.OHPM || value == null) {
      throw new IllegalStateException("Invalid OHPM package metadata");
    }
    ObjectNode result = json.valueToTree(value);
    if (!packageId.id().equals(result.path("name").asText())
        || !result.path("versions").isObject() || !result.path("dist-tags").isObject()
        || !result.path("time").isObject()) {
      throw new IllegalStateException("Invalid OHPM package metadata");
    }
    return result;
  }

  private ObjectNode empty(OhpmPackageId packageId) {
    ObjectNode root = json.createObjectNode().put("name", packageId.id()).put("_id", packageId.id());
    root.putObject("versions");
    root.putObject("dist-tags");
    root.putObject("time");
    return root;
  }

  private void save(AssetRecord root, ObjectNode metadata, Instant now) {
    ((ObjectNode) metadata.path("time")).put("modified", now.toString());
    assets.touchAssetLastUpdatedAndAttributes(root.id(), now, Map.of(METADATA, json.convertValue(metadata, MAP)));
  }

  private static String file(OhpmPackageId packageId, String version, String extension) {
    return packageId.id() + "-" + version + extension;
  }

  private static void requirePublished(ObjectNode metadata) {
    if (metadata.path("versions").isEmpty()) throw missing();
  }

  private static OhpmException missing() {
    return new OhpmException(404, "OHPM package, version or file does not exist");
  }

  private static void requireHosted(RepositoryRuntime runtime, boolean mutation) {
    if (runtime.format() != RepositoryFormat.OHPM || !runtime.isHosted()) {
      throw new OhpmException(405, "Operation requires an OHPM hosted repository");
    }
    if (!runtime.online()) throw new OhpmException(503, "Repository is offline");
    if (mutation && "DENY".equals(runtime.writePolicy())) {
      throw new OhpmException(400, "Repository write policy forbids changes");
    }
  }
}
