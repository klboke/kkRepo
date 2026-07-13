package com.github.klboke.kkrepo.server.yum;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawGroupService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;

@Service
public class YumService {
  private static final String RPM_CONTENT_TYPE = "application/x-rpm";
  private static final String XML_CONTENT_TYPE = "application/xml";
  private static final String GZIP_CONTENT_TYPE = "application/x-gzip";

  private final RawHostedService hosted;
  private final RawProxyService proxy;
  private final RawGroupService group;
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final RepositoryIndexRebuildDao indexRebuildDao;

  public YumService(
      RawHostedService hosted,
      RawProxyService proxy,
      RawGroupService group,
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      RepositoryIndexRebuildDao indexRebuildDao) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.group = group;
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.indexRebuildDao = indexRebuildDao;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    String path = normalize(rawPath);
    if (path.isEmpty()) {
      return repositoryInfo(headOnly);
    }
    if (runtime.type() == RepositoryType.GROUP && path.startsWith("repodata/") && canAggregateGroupMetadata(runtime)) {
      return groupMetadata(runtime, path, headOnly);
    }
    return dispatchRawGet(runtime, path, headOnly);
  }

  public MavenResponse put(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String createdBy,
      String createdByIp) {
    ensureHosted(runtime);
    String path = normalize(rawPath);
    MavenResponse response;
    if (path.endsWith(".rpm")) {
      response = putRpm(runtime, path, body, contentType, createdBy, createdByIp);
    } else {
      response = hosted.put(runtime, path, body,
          blankToDefault(contentType, contentType(path)), createdBy, createdByIp);
    }
    if (path.endsWith(".rpm") || path.startsWith("repodata/")) {
      enqueueMetadataRebuild(runtime);
    }
    return normalizeHostedPutResponse(response);
  }

  private static MavenResponse normalizeHostedPutResponse(MavenResponse response) {
    return response.status() == 201 ? response.withStatus(200) : response;
  }

  private MavenResponse putRpm(
      RepositoryRuntime runtime,
      String path,
      InputStream body,
      String contentType,
      String createdBy,
      String createdByIp) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-yum-", ".rpm");
      Files.copy(body, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      RpmMetadata metadata = RpmMetadataParser.parse(tmp);
      Map<String, Object> attrs = new LinkedHashMap<>(metadata.toAttributes());
      try (InputStream input = Files.newInputStream(tmp)) {
        return hosted.putWithAttributes(runtime, path, input,
            blankToDefault(contentType, contentType(path)), attrs, createdBy, createdByIp);
      }
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.LayoutPolicyViolation("Invalid RPM package: " + path);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to store Yum RPM " + path, e);
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // best-effort temp cleanup
        }
      }
    }
  }

  public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
    ensureHosted(runtime);
    String path = normalize(rawPath);
    MavenResponse response = hosted.delete(runtime, path);
    if (response.status() == 204 && (path.endsWith(".rpm") || path.startsWith("repodata/"))) {
      enqueueMetadataRebuild(runtime);
    }
    return response;
  }

  private MavenResponse dispatchRawGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.get(runtime, rawPath, headOnly);
      case PROXY -> proxy.get(runtime, rawPath, headOnly);
      case GROUP -> group.get(runtime, rawPath, headOnly);
    };
  }

  private static boolean canAggregateGroupMetadata(RepositoryRuntime runtime) {
    for (RepositoryRuntime member : runtime.members()) {
      if (member.type() == RepositoryType.PROXY) return false;
      if (member.type() == RepositoryType.GROUP && !canAggregateGroupMetadata(member)) return false;
    }
    return true;
  }

  private void enqueueMetadataRebuild(RepositoryRuntime runtime) {
    if (indexRebuildDao != null) {
      indexRebuildDao.enqueue(runtime.id(), RepositoryIndexRebuildDao.YUM_METADATA);
    }
  }

  public void rebuildMetadata(RepositoryRuntime runtime, String createdBy, String createdByIp) {
    List<RpmAsset> rpms = rpmAssets(runtime);
    MetadataFile primary = gzipMetadata(primaryXml(rpms));
    MetadataFile filelists = gzipMetadata(filelistsXml(rpms));
    MetadataFile other = gzipMetadata(otherXml(rpms));
    String repomd = repomdXml(List.of(
        new RepomdEntry("primary", "repodata/" + primary.fileName(), primary),
        new RepomdEntry("filelists", "repodata/" + filelists.fileName(), filelists),
        new RepomdEntry("other", "repodata/" + other.fileName(), other)));
    hosted.putGenerated(runtime, "repodata/" + primary.fileName(),
        new ByteArrayInputStream(primary.bytes()), GZIP_CONTENT_TYPE, createdBy, createdByIp);
    hosted.putGenerated(runtime, "repodata/" + filelists.fileName(),
        new ByteArrayInputStream(filelists.bytes()), GZIP_CONTENT_TYPE, createdBy, createdByIp);
    hosted.putGenerated(runtime, "repodata/" + other.fileName(),
        new ByteArrayInputStream(other.bytes()), GZIP_CONTENT_TYPE, createdBy, createdByIp);
    hosted.putGenerated(runtime, "repodata/repomd.xml",
        new ByteArrayInputStream(repomd.getBytes(StandardCharsets.UTF_8)),
        XML_CONTENT_TYPE, createdBy, createdByIp);
  }

  private MavenResponse groupMetadata(RepositoryRuntime runtime, String path, boolean headOnly) {
    List<RpmAsset> rpms = rpmAssets(runtime);
    MetadataFile primary = gzipMetadata(primaryXml(rpms));
    MetadataFile filelists = gzipMetadata(filelistsXml(rpms));
    MetadataFile other = gzipMetadata(otherXml(rpms));
    List<RepomdEntry> entries = List.of(
        new RepomdEntry("primary", "repodata/" + primary.fileName(), primary),
        new RepomdEntry("filelists", "repodata/" + filelists.fileName(), filelists),
        new RepomdEntry("other", "repodata/" + other.fileName(), other));
    if ("repodata/repomd.xml".equals(path)) {
      byte[] bytes = repomdXml(entries).getBytes(StandardCharsets.UTF_8);
      return bytes(bytes, XML_CONTENT_TYPE, headOnly);
    }
    for (RepomdEntry entry : entries) {
      if (entry.href().equals(path)) {
        return bytes(entry.file().bytes(), GZIP_CONTENT_TYPE, headOnly);
      }
    }
    throw new MavenExceptions.MavenNotFoundException(path);
  }

  private List<RpmAsset> rpmAssets(RepositoryRuntime runtime) {
    if (runtime.type() == RepositoryType.GROUP) {
      List<RpmAsset> assets = new ArrayList<>();
      for (RepositoryRuntime member : runtime.members()) {
        if (member.type() == RepositoryType.PROXY) continue;
        assets.addAll(rpmAssets(member));
      }
      assets.sort(Comparator.comparing(RpmAsset::path));
      return assets;
    }
    List<RpmAsset> assets = new ArrayList<>();
    List<AssetRecord> records = assetDao.listAssetsByPrefix(runtime.id(), "");
    Map<Long, AssetBlobRecord> blobs = blobsByAssetId(records);
    for (AssetRecord asset : records) {
      if (!asset.path().endsWith(".rpm") || asset.assetBlobId() == null) continue;
      AssetBlobRecord blob = blobs.get(asset.assetBlobId());
      if (blob == null) continue;
      RpmCoordinate coordinate = rpmCoordinate(asset.path());
      Instant lastUpdated = asset.lastUpdatedAt() == null ? Instant.now() : asset.lastUpdatedAt();
      RpmMetadata metadata = rpmMetadata(blob, coordinate, lastUpdated);
      assets.add(new RpmAsset(asset.path(), metadata, blob.sha256(), blob.size(), lastUpdated));
    }
    assets.sort(Comparator.comparing(RpmAsset::path));
    return assets;
  }

  private Map<Long, AssetBlobRecord> blobsByAssetId(List<AssetRecord> assets) {
    List<Long> blobIds = assets.stream()
        .map(AssetRecord::assetBlobId)
        .filter(id -> id != null)
        .toList();
    return assetDao.findBlobsByIds(blobIds);
  }

  private RpmMetadata rpmMetadata(AssetBlobRecord blob, RpmCoordinate coordinate, Instant lastUpdated) {
    RpmMetadata metadata = RpmMetadata.fromAttributes(blob.attributes());
    if (metadata != null) {
      return metadata;
    }
    RpmMetadata parsed = parseBlobMetadata(blob);
    if (parsed != null) {
      Map<String, Object> attrs = new LinkedHashMap<>(blob.attributes());
      attrs.putAll(parsed.toAttributes());
      assetDao.updateBlobAttributes(blob.id(), attrs);
      return parsed;
    }
    return RpmMetadata.fallback(coordinate, lastUpdated);
  }

  private RpmMetadata parseBlobMetadata(AssetBlobRecord blob) {
    if (blobStorageRegistry == null || blob == null) {
      return null;
    }
    try {
      BlobStorage storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
      BlobReference reference = BlobReferenceCodec.reference(
          blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
      try (InputStream input = storage.getRange(reference, 0, RpmMetadataParser.defaultRangeReadBytes())
          .orElse(null)) {
        if (input == null) {
          return null;
        }
        return RpmMetadataParser.parse(input);
      }
    } catch (RuntimeException | IOException e) {
      return null;
    }
  }

  static String primaryXml(List<RpmAsset> rpms) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<metadata xmlns=\"http://linux.duke.edu/metadata/common\" packages=\"")
        .append(rpms.size()).append("\">\n");
    for (RpmAsset rpm : rpms) {
      xml.append("  <package type=\"rpm\">\n");
      RpmMetadata meta = rpm.metadata();
      xml.append("    <name>").append(escape(meta.name())).append("</name>\n");
      xml.append("    <arch>").append(escape(meta.arch())).append("</arch>\n");
      appendVersion(xml, meta);
      xml.append("    <checksum type=\"sha256\" pkgid=\"YES\">").append(rpm.sha256()).append("</checksum>\n");
      xml.append("    <summary>").append(escape(defaultString(meta.summary(), meta.name()))).append("</summary>\n");
      xml.append("    <description>").append(escape(defaultString(meta.description(), meta.summary()))).append("</description>\n");
      xml.append("    <packager>").append(escape(defaultString(meta.packager(), "kkrepo"))).append("</packager>\n");
      xml.append("    <url>").append(escape(meta.url())).append("</url>\n");
      xml.append("    <time file=\"").append(rpm.unixTime()).append("\" build=\"")
          .append(meta.buildTime() > 0 ? meta.buildTime() : rpm.unixTime()).append("\"/>\n");
      xml.append("    <size package=\"").append(rpm.size()).append("\" installed=\"")
          .append(meta.installedSize()).append("\" archive=\"").append(meta.archiveSize()).append("\"/>\n");
      xml.append("    <location href=\"").append(escapeAttr(rpm.path())).append("\"/>\n");
      xml.append("    <format xmlns:rpm=\"http://linux.duke.edu/metadata/rpm\">\n");
      xml.append("      <rpm:license>").append(escape(meta.license())).append("</rpm:license>\n");
      xml.append("      <rpm:vendor>").append(escape(meta.vendor())).append("</rpm:vendor>\n");
      xml.append("      <rpm:group>").append(escape(defaultString(meta.group(), "Unspecified"))).append("</rpm:group>\n");
      xml.append("      <rpm:buildhost>").append(escape(meta.buildHost())).append("</rpm:buildhost>\n");
      xml.append("      <rpm:sourcerpm>").append(escape(meta.sourceRpm())).append("</rpm:sourcerpm>\n");
      xml.append("      <rpm:header-range start=\"").append(meta.headerStart()).append("\" end=\"")
          .append(meta.headerEnd()).append("\"/>\n");
      appendDependencies(xml, "rpm:provides", meta.provides());
      appendDependencies(xml, "rpm:conflicts", meta.conflicts());
      appendDependencies(xml, "rpm:obsoletes", meta.obsoletes());
      appendDependencies(xml, "rpm:requires", meta.requires());
      appendFiles(xml, meta.files(), true);
      xml.append("    </format>\n");
      xml.append("  </package>\n");
    }
    xml.append("</metadata>\n");
    return xml.toString();
  }

  static String filelistsXml(List<RpmAsset> rpms) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<filelists xmlns=\"http://linux.duke.edu/metadata/filelists\" packages=\"")
        .append(rpms.size()).append("\">\n");
    for (RpmAsset rpm : rpms) {
      xml.append("  <package pkgid=\"").append(rpm.sha256()).append("\" name=\"")
          .append(escapeAttr(rpm.metadata().name())).append("\" arch=\"")
          .append(escapeAttr(rpm.metadata().arch())).append("\">\n");
      appendVersion(xml, rpm.metadata());
      appendFiles(xml, rpm.metadata().files(), false);
      xml.append("  </package>\n");
    }
    xml.append("</filelists>\n");
    return xml.toString();
  }

  static String otherXml(List<RpmAsset> rpms) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<otherdata xmlns=\"http://linux.duke.edu/metadata/other\" packages=\"")
        .append(rpms.size()).append("\">\n");
    for (RpmAsset rpm : rpms) {
      xml.append("  <package pkgid=\"").append(rpm.sha256()).append("\" name=\"")
          .append(escapeAttr(rpm.metadata().name())).append("\" arch=\"")
          .append(escapeAttr(rpm.metadata().arch())).append("\">\n");
      appendVersion(xml, rpm.metadata());
      xml.append("  </package>\n");
    }
    xml.append("</otherdata>\n");
    return xml.toString();
  }

  static String repomdXml(List<RepomdEntry> entries) {
    long now = Instant.now().getEpochSecond();
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<repomd xmlns=\"http://linux.duke.edu/metadata/repo\">\n");
    for (RepomdEntry entry : entries) {
      MetadataFile file = entry.file();
      xml.append("  <data type=\"").append(entry.type()).append("\">\n");
      xml.append("    <checksum type=\"sha256\">").append(file.sha256()).append("</checksum>\n");
      xml.append("    <open-checksum type=\"sha256\">").append(file.openSha256()).append("</open-checksum>\n");
      xml.append("    <location href=\"").append(entry.href()).append("\"/>\n");
      xml.append("    <timestamp>").append(now).append("</timestamp>\n");
      xml.append("    <size>").append(file.bytes().length).append("</size>\n");
      xml.append("    <open-size>").append(file.openSize()).append("</open-size>\n");
      xml.append("  </data>\n");
    }
    xml.append("</repomd>\n");
    return xml.toString();
  }

  static MetadataFile gzipMetadata(String xml) {
    byte[] open = xml.getBytes(StandardCharsets.UTF_8);
    byte[] gz;
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
        gzip.write(open);
      }
      gz = out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to gzip Yum metadata", e);
    }
    return new MetadataFile(sha256(gz) + "-" + kindName(xml) + ".xml.gz",
        gz, sha256(gz), sha256(open), open.length);
  }

  private MavenResponse repositoryInfo(boolean headOnly) {
    String body = """
        <!DOCTYPE html>
        <html><body>
        <a href="repodata/repomd.xml">repodata/repomd.xml</a>
        </body></html>
        """;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, "text/html", null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "text/html", null, null);
  }

  private static MavenResponse bytes(byte[] bytes, String contentType, boolean headOnly) {
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, contentType, null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, contentType, null, null);
  }

  private static String kindName(String xml) {
    if (xml.contains("<metadata ")) return "primary";
    if (xml.contains("<filelists ")) return "filelists";
    return "other";
  }

  static RpmCoordinate rpmCoordinate(String path) {
    String file = path.substring(path.lastIndexOf('/') + 1);
    if (file.endsWith(".rpm")) file = file.substring(0, file.length() - 4);
    int archSep = file.lastIndexOf('.');
    String arch = archSep > 0 && archSep < file.length() - 1 ? file.substring(archSep + 1) : "noarch";
    String nameVersionRelease = archSep > 0 ? file.substring(0, archSep) : file;
    int releaseSep = nameVersionRelease.lastIndexOf('-');
    int versionSep = releaseSep <= 0 ? -1 : nameVersionRelease.lastIndexOf('-', releaseSep - 1);
    if (versionSep <= 0 || releaseSep <= versionSep) {
      return new RpmCoordinate(file, "0", "0", arch);
    }
    return new RpmCoordinate(
        nameVersionRelease.substring(0, versionSep),
        nameVersionRelease.substring(versionSep + 1, releaseSep),
        nameVersionRelease.substring(releaseSep + 1),
        arch);
  }

  private static String contentType(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".rpm")) return RPM_CONTENT_TYPE;
    if (lower.endsWith(".xml")) return XML_CONTENT_TYPE;
    if (lower.endsWith(".gz")) return GZIP_CONTENT_TYPE;
    return "application/octet-stream";
  }

  private static String normalize(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim().replaceAll("/+", "/");
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
    return path;
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static void appendVersion(StringBuilder xml, RpmMetadata meta) {
    xml.append("    <version epoch=\"").append(escapeAttr(defaultString(meta.epoch(), "0")))
        .append("\" ver=\"").append(escapeAttr(meta.version()))
        .append("\" rel=\"").append(escapeAttr(meta.release())).append("\"/>\n");
  }

  private static void appendDependencies(StringBuilder xml, String element, List<RpmDependency> dependencies) {
    if (dependencies == null || dependencies.isEmpty()) {
      return;
    }
    xml.append("      <").append(element).append(">\n");
    for (RpmDependency dependency : dependencies) {
      if (dependency.name() == null || dependency.name().isBlank()) continue;
      xml.append("        <rpm:entry name=\"").append(escapeAttr(dependency.name())).append("\"");
      if (dependency.flags() != null && !dependency.flags().isBlank()) {
        xml.append(" flags=\"").append(escapeAttr(dependency.flags())).append("\"");
        if (dependency.epoch() != null && !dependency.epoch().isBlank()) {
          xml.append(" epoch=\"").append(escapeAttr(dependency.epoch())).append("\"");
        }
        if (dependency.version() != null && !dependency.version().isBlank()) {
          xml.append(" ver=\"").append(escapeAttr(dependency.version())).append("\"");
        }
        if (dependency.release() != null && !dependency.release().isBlank()) {
          xml.append(" rel=\"").append(escapeAttr(dependency.release())).append("\"");
        }
      }
      if (dependency.pre()) {
        xml.append(" pre=\"1\"");
      }
      xml.append("/>\n");
    }
    xml.append("      </").append(element).append(">\n");
  }

  private static void appendFiles(StringBuilder xml, List<RpmFile> files, boolean primaryOnly) {
    if (files == null || files.isEmpty()) {
      return;
    }
    for (RpmFile file : files) {
      if (file.path() == null || file.path().isBlank()) continue;
      if (primaryOnly && !RpmMetadataParser.isPrimaryFile(file.path())) continue;
      xml.append(primaryOnly ? "      " : "    ");
      xml.append("<file");
      if (file.type() != null && !file.type().isBlank() && !"file".equals(file.type())) {
        xml.append(" type=\"").append(escapeAttr(file.type())).append("\"");
      }
      xml.append(">").append(escape(file.path())).append("</file>\n");
    }
  }

  private static String escape(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private static String escapeAttr(String value) {
    return escape(value).replace("\"", "&quot;");
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing SHA-256", e);
    }
  }

  private static void ensureHosted(RepositoryRuntime runtime) {
    if (runtime.type() != RepositoryType.HOSTED) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on hosted Yum repositories");
    }
  }

  record RpmAsset(
      String path,
      RpmMetadata metadata,
      String sha256,
      long size,
      Instant lastUpdated) {
    long unixTime() {
      return lastUpdated.getEpochSecond();
    }
  }

  record RpmCoordinate(String name, String version, String release, String arch) {
  }

  record MetadataFile(
      String fileName,
      byte[] bytes,
      String sha256,
      String openSha256,
      long openSize) {
  }

  record RepomdEntry(String type, String href, MetadataFile file) {
  }
}
