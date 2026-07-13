package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.maven.MavenContentType;
import com.github.klboke.kkrepo.protocol.maven.metadata.Maven2Metadata;
import com.github.klboke.kkrepo.protocol.maven.metadata.Maven2Metadata.Snapshot;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenVersionComparator;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Rebuilds {@code maven-metadata.xml} for a GA (artifact-level) and, when applicable, for a
 * GAV-SNAPSHOT (base-version-level). Hosted PUT/DELETE usually queues rebuild work for the
 * background worker; the service can also be invoked inline when sync rebuild is enabled. The
 * output bytes are themselves stored as assets via {@link MavenAssetWriter}, which also emits the
 * four checksum siblings — matching Nexus.
 */
@Service
public class MavenMetadataService {
  private final ComponentDao componentDao;
  private final AssetDao assetDao;
  private final MavenAssetWriter writer;
  private final MavenPathParser parser = new MavenPathParser();

  public MavenMetadataService(ComponentDao componentDao, AssetDao assetDao, MavenAssetWriter writer) {
    this.componentDao = componentDao;
    this.assetDao = assetDao;
    this.writer = writer;
  }

  /** Rebuilds the GA metadata for the artifact identified by {@code coords}. */
  public void rebuildGa(RepositoryRuntime runtime, BlobStorage storage, long blobStoreId,
      String groupId, String artifactId, String createdBy, String createdByIp) {
    List<ComponentRecord> components = componentDao.listByGa(runtime.id(), groupId, artifactId);
    if (components.isEmpty()) {
      // nothing to publish; delete any existing metadata so the index reflects emptiness
      String gaPath = groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
      MavenPath p = parser.parsePath(gaPath);
      writer.deleteAsset(runtime, storage, p);
      return;
    }
    List<String> versions = new ArrayList<>(components.size());
    Instant lastUpdated = Instant.EPOCH;
    for (ComponentRecord c : components) {
      versions.add(c.version());
      if (c.lastUpdatedAt() != null && c.lastUpdatedAt().isAfter(lastUpdated)) {
        lastUpdated = c.lastUpdatedAt();
      }
    }
    versions.sort(MavenVersionComparator.INSTANCE);
    String latest = versions.get(versions.size() - 1);
    String release = null;
    for (int i = versions.size() - 1; i >= 0; i--) {
      String version = versions.get(i);
      if (!version.endsWith("SNAPSHOT")) {
        release = version;
        break;
      }
    }
    if (lastUpdated.equals(Instant.EPOCH)) {
      lastUpdated = Instant.now();
    }
    Maven2Metadata meta = Maven2Metadata.artifactLevel(
        lastUpdated, groupId, artifactId, latest, release, versions);
    byte[] xml = MavenMetadataXml.write(meta);
    String metadataPath = groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
    MavenPath path = parser.parsePath(metadataPath);
    writer.writeBytes(runtime, storage, blobStoreId, path, xml, MavenContentType.XML, createdBy, createdByIp);
  }

  /** Rebuilds GAV-SNAPSHOT metadata if {@code coords} is for a snapshot version. */
  public void rebuildBaseVersionIfSnapshot(RepositoryRuntime runtime, BlobStorage storage, long blobStoreId,
      Coordinates coords, String createdBy, String createdByIp) {
    if (coords == null || !coords.snapshot()) return;
    ComponentRecord component = componentDao.findByGav(
        runtime.id(), coords.groupId(), coords.artifactId(), coords.baseVersion()).orElse(null);
    String baseVersionPath = coords.groupId().replace('.', '/') + "/" + coords.artifactId()
        + "/" + coords.baseVersion();
    String metadataPath = baseVersionPath + "/maven-metadata.xml";
    MavenPath path = parser.parsePath(metadataPath);
    if (component == null) {
      writer.deleteAsset(runtime, storage, path);
      return;
    }
    List<AssetRecord> assets = assetDao.listAssetsByComponent(component.id()).stream()
        .filter(a -> "artifact".equals(a.kind()) || "pom".equals(a.kind()))
        .sorted(Comparator.comparing(AssetRecord::path))
        .toList();
    Coordinates latestCoordinates = null;
    Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    Instant lastUpdated = Instant.EPOCH;
    for (AssetRecord a : assets) {
      Coordinates c = parser.parsePath(a.path()).coordinates();
      if (c == null) continue;
      if (c.timestamp() == null || c.version().equals(c.baseVersion())) {
        continue;
      }
      if (latestCoordinates == null
          || MavenVersionComparator.INSTANCE.compare(c.version(), latestCoordinates.version()) > 0) {
        latestCoordinates = c;
      }
      String key = (c.classifier() == null ? "" : c.classifier()) + ":" + stripHashSuffix(c.extension());
      Snapshot existing = snapshots.get(key);
      Instant updated = a.lastUpdatedAt() == null ? Instant.now() : a.lastUpdatedAt();
      if (updated.isAfter(lastUpdated)) lastUpdated = updated;
      if (existing == null
          || MavenVersionComparator.INSTANCE.compare(c.version(), existing.version()) > 0) {
        snapshots.put(key, new Snapshot(updated, stripHashSuffix(c.extension()), c.classifier(), c.version()));
      }
    }
    if (lastUpdated.equals(Instant.EPOCH)) {
      lastUpdated = Instant.now();
    }
    Long timestamp = latestCoordinates == null ? null : latestCoordinates.timestamp();
    int buildNumber = latestCoordinates == null || latestCoordinates.buildNumber() == null
        ? 0
        : latestCoordinates.buildNumber();
    Maven2Metadata meta = Maven2Metadata.baseVersionLevel(
        lastUpdated, coords.groupId(), coords.artifactId(), coords.baseVersion(),
        timestamp, buildNumber, new ArrayList<>(snapshots.values()));
    byte[] xml = MavenMetadataXml.write(meta);
    writer.writeBytes(runtime, storage, blobStoreId, path, xml, MavenContentType.XML, createdBy, createdByIp);
  }

  private static String stripHashSuffix(String ext) {
    if (ext == null) return ext;
    for (String h : new String[]{".sha1", ".sha256", ".sha512", ".md5"}) {
      if (ext.endsWith(h)) return ext.substring(0, ext.length() - h.length());
    }
    return ext;
  }
}
