package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.cargo.CargoCrateName;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.protocol.cargo.CargoVersions;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CargoHostedService {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final CargoAssetWriter writer;
  private final CargoAssetReader reader;
  private final AssetMetadataCache assetMetadataCache;
  private final ObjectMapper objectMapper;

  public CargoHostedService(
      AssetDao assetDao,
      ComponentDao componentDao,
      BlobStorageRegistry blobStorageRegistry,
      CargoAssetWriter writer,
      CargoAssetReader reader,
      AssetMetadataCache assetMetadataCache,
      ObjectMapper objectMapper) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.assetMetadataCache = assetMetadataCache;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      CargoPath path,
      String baseUrl,
      CargoSearchQuery search,
      boolean headOnly) {
    ensureHosted(runtime);
    return switch (path.kind()) {
      case ROOT, CONFIG -> config(runtime, baseUrl, headOnly);
      case INDEX -> index(runtime, path.crateName(), headOnly);
      case DOWNLOAD -> download(runtime, path.crateName(), path.version(), headOnly);
      case SEARCH -> search(runtime, search, headOnly);
      case OWNERS -> CargoResponses.json(objectMapper, Map.of("users", List.of()), 200, headOnly);
      default -> throw new CargoExceptions.CargoNotFoundException(path.rawPath());
    };
  }

  public MavenResponse publish(
      RepositoryRuntime runtime,
      InputStream body,
      String createdBy,
      String createdByIp) {
    ensureHosted(runtime);
    enforcePublishPolicy(runtime);
    try (CargoPublishPayload payload = CargoPublishPayload.read(objectMapper, body)) {
      CargoPackageMetadata metadata = payload.metadata();
      if (componentDao.findByNameAndVersion(runtime.id(), metadata.normalizedName(), metadata.versionKey()).isPresent()) {
        throw new CargoExceptions.WritePolicyDenied(
            "Cargo crate version already exists: " + metadata.name() + " " + metadata.version());
      }
      writer.writeHostedCrate(
          runtime,
          blobStorage(runtime),
          requireBlobStore(runtime),
          metadata,
          payload.crateFile(),
          createdBy,
          createdByIp);
      return CargoResponses.json(objectMapper, Map.of(
          "warnings", Map.of(
              "invalid_categories", List.of(),
              "invalid_badges", List.of(),
              "other", List.of())), 200, false);
    }
  }

  public String uploadCrate(
      RepositoryRuntime runtime,
      InputStream body,
      String createdBy,
      String createdByIp) {
    ensureHosted(runtime);
    enforcePublishPolicy(runtime);
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-cargo-upload-", ".crate");
      Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
      CargoPackageMetadata metadata = CargoPackageMetadata.fromManifest(CargoCrateInspector.inspect(tmp));
      if (componentDao.findByNameAndVersion(runtime.id(), metadata.normalizedName(), metadata.versionKey()).isPresent()) {
        throw new CargoExceptions.WritePolicyDenied(
            "Cargo crate version already exists: " + metadata.name() + " " + metadata.version());
      }
      CargoAssetWriter.Stored stored = writer.writeHostedCrate(
          runtime,
          blobStorage(runtime),
          requireBlobStore(runtime),
          metadata,
          tmp,
          createdBy,
          createdByIp);
      stored.discardBody();
      return stored.asset().path();
    } catch (IOException e) {
      throw new CargoExceptions.BadRequestException("Invalid .crate upload", e);
    } finally {
      com.github.klboke.kkrepo.server.blob.TempBlobFiles.deleteQuietly(tmp);
    }
  }

  public MavenResponse yank(RepositoryRuntime runtime, String crateName, String version, boolean yanked) {
    ensureHosted(runtime);
    enforcePublishPolicy(runtime);
    ComponentRecord component = component(runtime, crateName, version)
        .orElseThrow(() -> new CargoExceptions.CargoNotFoundException(crateName + " " + version));
    Map<String, Object> attrs = new LinkedHashMap<>(component.attributes() == null ? Map.of() : component.attributes());
    Map<String, Object> entry = new LinkedHashMap<>(indexEntry(component));
    entry.put("yanked", yanked);
    attrs.put("indexEntry", entry);
    componentDao.updateAttributes(component.id(), attrs, Instant.now());
    return CargoResponses.json(objectMapper, Map.of("ok", true), 200, false);
  }

  MavenResponse index(RepositoryRuntime runtime, String crateName, boolean headOnly) {
    String normalizedName = CargoCrateName.parse(crateName).lowerDashUnderscoreKey();
    List<ComponentRecord> components = componentDao.listByName(runtime.id(), normalizedName);
    if (components.isEmpty()) {
      throw new CargoExceptions.CargoIndexNotFoundException(crateName);
    }
    StringBuilder body = new StringBuilder();
    for (ComponentRecord component : components) {
      Map<String, Object> entry = indexEntry(component);
      try {
        body.append(objectMapper.writeValueAsString(entry)).append('\n');
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to serialize Cargo index entry", e);
      }
    }
    return CargoResponses.text(body.toString(), null, latestUpdatedAt(components), headOnly);
  }

  MavenResponse search(RepositoryRuntime runtime, CargoSearchQuery query, boolean headOnly) {
    List<ComponentRecord> components = componentDao.searchComponentsByRepositoryIds(
        List.of(runtime.id()),
        RepositoryFormat.CARGO,
        query.query(),
        query.localScanLimit());
    return CargoSearchResults.fromComponents(objectMapper, components, query, headOnly);
  }

  MavenResponse download(RepositoryRuntime runtime, String crateName, String version, boolean headOnly) {
    ComponentRecord component = component(runtime, crateName, version)
        .orElseThrow(() -> new CargoExceptions.CargoNotFoundException(crateName + " " + version));
    String path = text(component.attributes(), "cratePath");
    if (path == null) {
      path = CargoAssetWriter.cratePath(crateName, version);
    }
    String assetPath = path;
    CachedAssetMetadata snapshot = lookupCached(runtime, assetPath)
        .orElseThrow(() -> new CargoExceptions.CargoNotFoundException(assetPath));
    return reader.serveSnapshot(snapshot, headOnly, assetPath);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private Optional<ComponentRecord> component(RepositoryRuntime runtime, String crateName, String version) {
    return componentDao.findByNameAndVersion(
        runtime.id(),
        CargoCrateName.parse(crateName).lowerDashUnderscoreKey(),
        CargoVersions.uniquenessKey(version));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> indexEntry(ComponentRecord component) {
    Object raw = component.attributes() == null ? null : component.attributes().get("indexEntry");
    if (raw instanceof Map<?, ?> map) {
      return new LinkedHashMap<>((Map<String, Object>) map);
    }
    throw new IllegalStateException("Cargo component is missing indexEntry: " + component.id());
  }

  private Instant latestUpdatedAt(List<ComponentRecord> components) {
    return components.stream()
        .map(ComponentRecord::lastUpdatedAt)
        .filter(value -> value != null)
        .max(Instant::compareTo)
        .orElse(null);
  }

  private MavenResponse config(RepositoryRuntime runtime, String baseUrl, boolean headOnly) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("dl", baseUrl + "/crates");
    config.put("api", baseUrl + "/");
    config.put("auth-required", true);
    return CargoResponses.jsonWithBodyEtag(objectMapper, config, 200, headOnly);
  }

  private void enforcePublishPolicy(RepositoryRuntime runtime) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new CargoExceptions.WritePolicyDenied("Write policy DENY forbids Cargo publish");
    }
  }

  private void ensureHosted(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.CARGO || !runtime.isHosted()) {
      throw new CargoExceptions.MethodNotAllowed("Operation is only valid on hosted Cargo repositories");
    }
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Cargo repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static String text(Map<String, Object> attrs, String key) {
    Object value = attrs == null ? null : attrs.get(key);
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }
}
