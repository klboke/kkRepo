package com.github.klboke.kkrepo.server.browse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.swift.SwiftPath;
import com.github.klboke.kkrepo.protocol.swift.SwiftPathParser;
import com.github.klboke.kkrepo.protocol.swift.SwiftToolsVersions;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.npm.NpmFormatAttributes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BrowseAssetDetailService {
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final int MAX_PACKAGE_JSON_BYTES = 1024 * 1024;
  private static final ComposerPathParser COMPOSER_PATHS = new ComposerPathParser();
  private static final SwiftPathParser SWIFT_PATHS = new SwiftPathParser();
  private static final TerraformPathParser TERRAFORM_PATHS = new TerraformPathParser();

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final DockerRegistryDao dockerDao;
  private final TerraformRegistryDao terraformDao;
  private final SwiftRegistryDao swiftDao;
  private AnsibleGalaxyRegistryDao ansibleDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final ObjectMapper objectMapper;

  @Autowired
  public BrowseAssetDetailService(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      DockerRegistryDao dockerDao,
      TerraformRegistryDao terraformDao,
      SwiftRegistryDao swiftDao,
      BlobStorageRegistry blobStorageRegistry,
      ObjectMapper objectMapper) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.dockerDao = dockerDao;
    this.terraformDao = terraformDao;
    this.swiftDao = swiftDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.objectMapper = objectMapper;
  }

  @Autowired(required = false)
  void setAnsibleGalaxyRegistryDao(AnsibleGalaxyRegistryDao ansibleDao) {
    this.ansibleDao = ansibleDao;
  }

  public BrowseAssetDetailService(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      DockerRegistryDao dockerDao,
      TerraformRegistryDao terraformDao,
      BlobStorageRegistry blobStorageRegistry,
      ObjectMapper objectMapper) {
    this(repositoryDao, assetDao, dockerDao, terraformDao, null, blobStorageRegistry, objectMapper);
  }

  public BrowseAssetDetailService(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      DockerRegistryDao dockerDao,
      BlobStorageRegistry blobStorageRegistry,
      ObjectMapper objectMapper) {
    this(repositoryDao, assetDao, dockerDao, null, null, blobStorageRegistry, objectMapper);
  }

  public BrowseAssetDetailService(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      ObjectMapper objectMapper) {
    this(repositoryDao, assetDao, null, null, null, blobStorageRegistry, objectMapper);
  }

  public BrowseAssetDetail detail(
      RepositoryRecord visibleRepository,
      String path,
      String sourceRepositoryName) {
    String publicPath = normalize(path);
    if (BrowseAssetVisibility.hidden(visibleRepository.format(), publicPath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
    }
    if (visibleRepository.format() == RepositoryFormat.SWIFT
        && SwiftBrowseService.isVirtualManifestPath(publicPath)) {
      Optional<BrowseAssetDetail> manifest = swiftManifestBrowseDetail(
          visibleRepository, publicPath, sourceRepositoryName);
      if (manifest.isPresent()) {
        return manifest.orElseThrow();
      }
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Swift manifest not found");
    }
    if (visibleRepository.format() == RepositoryFormat.SWIFT && swiftDao != null) {
      Optional<BrowseAssetDetail> releaseMetadata = swiftReleaseMetadataDetail(
          visibleRepository, publicPath, sourceRepositoryName);
      if (releaseMetadata.isPresent()) {
        return releaseMetadata.orElseThrow();
      }
      rejectSwiftTombstoneForPhysicalPath(visibleRepository, publicPath, sourceRepositoryName);
    }
    ResolvedStoragePath resolvedPath = storagePath(
        visibleRepository, publicPath, sourceRepositoryName);
    String storagePath = resolvedPath.path();
    sourceRepositoryName = resolvedPath.sourceRepositoryName();
    if (isTerraformProviderVersionsDocument(visibleRepository, publicPath)) {
      if (!terraformProviderExists(visibleRepository, publicPath)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Terraform provider not found");
      }
      return terraformProviderVersionsDetail(visibleRepository, publicPath);
    }
    if (isCargoDynamicConfig(visibleRepository, storagePath)) {
      return cargoDynamicConfigDetail(visibleRepository);
    }
    ResolvedAsset resolved = resolveSourceAsset(visibleRepository, sourceRepositoryName, storagePath);
    RepositoryRecord source = resolved.source();
    AssetRecord asset = resolved.asset();
    AnsibleGalaxyRegistryDao.CollectionVersion ansibleVersion =
        requireMatchingAnsiblePublicPath(visibleRepository, publicPath, source, asset);
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assetDao.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset blob not found");
    }

    LinkedHashMap<String, Object> checksum = checksum(blob);
    LinkedHashMap<String, Object> content = content(asset);
    LinkedHashMap<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("hashes_not_verified", false);
    Map<String, Object> npm = source.format() == RepositoryFormat.NPM ? npmAttributes(source, asset, blob) : Map.of();
    Map<String, Object> docker = source.format() == RepositoryFormat.DOCKER
        ? dockerAttributes(source, asset, blob, path, resolved.dockerManifest())
        : Map.of();
    Map<String, Object> pub = source.format() == RepositoryFormat.PUB ? pubAttributes(asset) : Map.of();
    Map<String, Object> composer = source.format() == RepositoryFormat.COMPOSER
        ? composerAttributes(asset)
        : Map.of();
    Map<String, Object> swift = source.format() == RepositoryFormat.SWIFT
        ? swiftAttributes(source, asset)
        : Map.of();
    Map<String, Object> ansible = source.format() == RepositoryFormat.ANSIBLEGALAXY
        ? ansibleAttributes(source, asset, ansibleVersion)
        : Map.of();
    String displayName = storagePath.equals(publicPath)
        ? asset.name()
        : publicPath.substring(publicPath.lastIndexOf('/') + 1);
    String downloadPath = visibleRepository.format() == RepositoryFormat.TERRAFORM
            && !storagePath.equals(publicPath)
        ? publicPath
        : storagePath;

    return new BrowseAssetDetail(
        visibleRepository.name(),
        source.name(),
        publicPath,
        displayName,
        asset.size(),
        asset.contentType(),
        asset.lastUpdatedAt(),
        BrowseDownloadUrls.asset(visibleRepository, downloadPath),
        text(firstPresent(asset.attributes().get("createdBy"), blob.createdBy())),
        text(firstPresent(asset.attributes().get("createdByIp"), blob.createdByIp())),
        checksum,
        content,
        docker,
        npm,
        pub,
        composer,
        swift,
        ansible,
        provenance);
  }

  private Map<String, Object> ansibleAttributes(
      RepositoryRecord source,
      AssetRecord asset,
      AnsibleGalaxyRegistryDao.CollectionVersion version) {
    LinkedHashMap<String, Object> ansible = new LinkedHashMap<>();
    putNonBlank(ansible, "asset_kind", asset.kind());
    putNonBlank(ansible, "source_repository", source.name());
    putNonBlank(ansible, "namespace", version.namespaceDisplay());
    putNonBlank(ansible, "name", version.nameDisplay());
    putNonBlank(ansible, "version", version.versionOriginal());
    putNonBlank(ansible, "artifact_filename", version.artifactFilename());
    putNonBlank(ansible, "artifact_sha256", version.artifactSha256());
    ansible.put("artifact_size", version.artifactSize());
    putNonBlank(ansible, "requires_ansible", version.requiresAnsible());
    putNonBlank(ansible, "source_kind", version.sourceKind());
    if (version.dependencies() != null && !version.dependencies().isEmpty()) {
      ansible.put("dependencies", version.dependencies());
    }
    for (String key : List.of("authors", "tags", "license", "description")) {
      Object value = version.metadata().get(key);
      if (value != null) ansible.put(key, value);
    }
    int signatures = ansibleDao.listSignatures(version.id()).size();
    ansible.put("signature_count", signatures);
    ansible.put("signature_status", signatures == 0 ? "unsigned" : "signed");
    return Map.copyOf(ansible);
  }

  private AnsibleGalaxyRegistryDao.CollectionVersion requireMatchingAnsiblePublicPath(
      RepositoryRecord visibleRepository,
      String publicPath,
      RepositoryRecord source,
      AssetRecord asset) {
    if (visibleRepository.format() != RepositoryFormat.ANSIBLEGALAXY) {
      return null;
    }
    if (ansibleDao == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Ansible collection identity not found");
    }
    AnsibleGalaxyRegistryDao.CollectionVersion version = ansibleDao
        .findVersionByArtifactFilename(source.id(), asset.name())
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Ansible collection identity not found"));
    String expectedPath = version.namespaceDisplay() + "/" + version.nameDisplay() + "/"
        + version.versionOriginal() + "/" + version.artifactFilename();
    if (!expectedPath.equals(publicPath)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Ansible collection path does not match artifact identity");
    }
    return version;
  }

  private Map<String, Object> swiftAttributes(RepositoryRecord source, AssetRecord asset) {
    SwiftPath path = swiftPath(asset.path());
    if (path == null) {
      return Map.of();
    }
    String scope = path.scope();
    String name = path.name();
    String version = path.version();
    LinkedHashMap<String, Object> swift = new LinkedHashMap<>();
    putNonBlank(swift, "scope", scope);
    putNonBlank(swift, "name", name);
    putNonBlank(swift, "version", version);
    putNonBlank(swift, "asset_kind", asset.attributes() == null ? asset.kind() : asset.attributes().get("swiftKind"));
    putNonBlank(swift, "swift_tools_version",
        asset.attributes() == null ? null : asset.attributes().get("swiftToolsVersion"));
    putNonBlank(swift, "declared_swift_tools_version",
        asset.attributes() == null ? null : asset.attributes().get("declaredSwiftToolsVersion"));
    putNonBlank(swift, "source_repository", source.name());
    if (swiftDao == null) {
      return Map.copyOf(swift);
    }
    swiftDao.findRelease(source.id(), scope.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT), version)
        .ifPresent(release -> {
          putNonBlank(swift, "archive_sha256", release.archiveSha256());
          putNonBlank(swift, "source_kind", release.sourceKind());
          putNonBlank(swift, "signature_status", release.signatureFormat() == null ? "unsigned" : "signed");
          putNonBlank(swift, "signature_format", release.signatureFormat());
          List<String> toolsVersions = swiftDao.listManifests(release.id()).stream()
              .map(SwiftRegistryDao.Manifest::toolsVersion)
              .filter(value -> value != null && !value.isBlank())
              .distinct()
              .sorted()
              .toList();
          if (!toolsVersions.isEmpty()) {
            swift.put("swift_tools_versions", toolsVersions);
          }
          List<String> repositoryUrls = swiftDao.listRepositoryUrls(release.id()).stream()
              .map(SwiftRegistryDao.RepositoryUrl::displayUrl)
              .filter(value -> value != null && !value.isBlank())
              .distinct()
              .toList();
          if (!repositoryUrls.isEmpty()) {
            swift.put("repository_urls", repositoryUrls);
          }
        });
    return Map.copyOf(swift);
  }

  private Optional<BrowseAssetDetail> swiftReleaseMetadataDetail(
      RepositoryRecord visibleRepository,
      String publicPath,
      String sourceRepositoryName) {
    SwiftPath parsed;
    try {
      parsed = SWIFT_PATHS.parseReleaseMetadata(publicPath);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (parsed.kind() != SwiftPath.Kind.RELEASE_METADATA) {
      return Optional.empty();
    }
    List<RepositoryRecord> sources = visibleRepository.type() == RepositoryType.GROUP
        ? BrowseRepositorySources.swiftSources(visibleRepository, repositoryDao)
        : List.of(visibleRepository);
    if (sourceRepositoryName != null && !sourceRepositoryName.isBlank()) {
      sources = sources.stream()
          .filter(source -> source.name().equals(sourceRepositoryName))
          .toList();
    }
    for (RepositoryRecord source : sources) {
      if (swiftDao.findTombstone(
          source.id(),
          parsed.scope().toLowerCase(Locale.ROOT),
          parsed.name().toLowerCase(Locale.ROOT),
          parsed.version()).isPresent()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Swift release not found");
      }
      Optional<SwiftRegistryDao.Release> release = swiftDao.findRelease(
          source.id(),
          parsed.scope().toLowerCase(Locale.ROOT),
          parsed.name().toLowerCase(Locale.ROOT),
          parsed.version());
      if (release.isEmpty()) {
        continue;
      }
      SwiftRegistryDao.Release row = release.orElseThrow();
      return Optional.of(new BrowseAssetDetail(
          visibleRepository.name(),
          source.name(),
          publicPath,
          parsed.version(),
          null,
          "text/plain",
          row.updatedAt(),
          BrowseDownloadUrls.asset(visibleRepository, publicPath),
          "kkrepo",
          null,
          Map.of(),
          Map.of("generated", true, "format", "swift-release-metadata"),
          Map.of(),
          Map.of(),
          Map.of(),
          Map.of(),
          swiftReleaseAttributes(source, row),
          Map.of(),
          Map.of("dynamic", true, "hashes_not_verified", false)));
    }
    return Optional.empty();
  }

  private void rejectSwiftTombstoneForPhysicalPath(
      RepositoryRecord visibleRepository,
      String publicPath,
      String sourceRepositoryName) {
    SwiftPath parsed = SWIFT_PATHS.parse(publicPath);
    if (parsed.kind() != SwiftPath.Kind.SOURCE_ARCHIVE
        && parsed.kind() != SwiftPath.Kind.MANIFEST) {
      return;
    }
    List<RepositoryRecord> sources = visibleRepository.type() == RepositoryType.GROUP
        ? BrowseRepositorySources.swiftSources(visibleRepository, repositoryDao)
        : List.of(visibleRepository);
    if (sourceRepositoryName != null && !sourceRepositoryName.isBlank()) {
      sources = sources.stream()
          .filter(source -> source.name().equals(sourceRepositoryName))
          .toList();
    }
    for (RepositoryRecord source : sources) {
      if (swiftDao.findTombstone(
          source.id(),
          parsed.scope().toLowerCase(Locale.ROOT),
          parsed.name().toLowerCase(Locale.ROOT),
          parsed.version()).isPresent()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Swift release not found");
      }
    }
  }

  private Optional<BrowseAssetDetail> swiftManifestBrowseDetail(
      RepositoryRecord visibleRepository,
      String publicPath,
      String sourceRepositoryName) {
    String[] parts = publicPath.split("/");
    if (parts.length != 5) {
      return Optional.empty();
    }
    String releasePath = parts[0] + "/" + parts[1] + "/" + parts[2];
    String filename = parts[4];
    List<RepositoryRecord> sources = visibleRepository.type() == RepositoryType.GROUP
        ? BrowseRepositorySources.swiftSources(visibleRepository, repositoryDao)
        : List.of(visibleRepository);
    if (sourceRepositoryName != null && !sourceRepositoryName.isBlank()) {
      sources = sources.stream()
          .filter(source -> source.name().equals(sourceRepositoryName))
          .toList();
    }
    for (RepositoryRecord source : sources) {
      if (swiftDao.findTombstone(
          source.id(),
          parts[0].toLowerCase(Locale.ROOT),
          parts[1].toLowerCase(Locale.ROOT),
          parts[2]).isPresent()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Swift release not found");
      }
      Optional<SwiftRegistryDao.Release> release = swiftDao.findRelease(
          source.id(),
          parts[0].toLowerCase(Locale.ROOT),
          parts[1].toLowerCase(Locale.ROOT),
          parts[2]);
      if (release.isEmpty()) {
        continue;
      }
      SwiftRegistryDao.Release row = release.orElseThrow();
      Optional<SwiftRegistryDao.Manifest> manifest = swiftDao.listManifests(row.id()).stream()
          .filter(candidate -> candidate.filename().equals(filename))
          .findFirst();
      if (manifest.isEmpty()) {
        continue;
      }
      SwiftRegistryDao.Manifest manifestRow = manifest.orElseThrow();
      AssetRecord asset = assetDao.findAssetById(manifestRow.assetId()).orElse(null);
      LinkedHashMap<String, Object> checksum = new LinkedHashMap<>();
      putNonBlank(checksum, "sha256", manifestRow.sha256());
      return Optional.of(new BrowseAssetDetail(
          visibleRepository.name(),
          source.name(),
          publicPath,
          filename,
          asset == null ? null : asset.size(),
          asset == null ? "text/x-swift" : asset.contentType(),
          asset == null ? row.updatedAt() : asset.lastUpdatedAt(),
          null,
          "kkrepo",
          null,
          Map.copyOf(checksum),
          Map.of("generated", true, "format", "swift-manifest-browse"),
          Map.of(),
          Map.of(),
          Map.of(),
          Map.of(),
          swiftManifestAttributes(source, row, manifestRow),
          Map.of(),
          Map.of("dynamic", true, "hashes_not_verified", false)));
    }
    return Optional.empty();
  }

  private Map<String, Object> swiftManifestAttributes(
      RepositoryRecord source,
      SwiftRegistryDao.Release release,
      SwiftRegistryDao.Manifest manifest) {
    LinkedHashMap<String, Object> swift = new LinkedHashMap<>();
    putNonBlank(swift, "scope", release.scopeDisplay());
    putNonBlank(swift, "name", release.nameDisplay());
    putNonBlank(swift, "version", release.version());
    putNonBlank(swift, "asset_kind", "manifest");
    putNonBlank(swift, "manifest_filename", manifest.filename());
    putNonBlank(
        swift,
        "swift_tools_version",
        manifest.toolsVersion() == null || manifest.toolsVersion().isBlank()
            ? "default"
            : manifest.toolsVersion());
    putNonBlank(swift, "source_repository", source.name());
    return Map.copyOf(swift);
  }

  private Map<String, Object> swiftReleaseAttributes(
      RepositoryRecord source,
      SwiftRegistryDao.Release release) {
    LinkedHashMap<String, Object> swift = new LinkedHashMap<>();
    putNonBlank(swift, "scope", release.scopeDisplay());
    putNonBlank(swift, "name", release.nameDisplay());
    putNonBlank(swift, "version", release.version());
    putNonBlank(swift, "asset_kind", "release-metadata");
    putNonBlank(swift, "source_repository", source.name());
    putNonBlank(swift, "archive_sha256", release.archiveSha256());
    putNonBlank(swift, "source_kind", release.sourceKind());
    putNonBlank(
        swift,
        "signature_status",
        release.signatureFormat() == null ? "unsigned" : "signed");
    if (release.signatureFormat() != null) {
      putNonBlank(swift, "signature_format", release.signatureFormat());
    }
    List<String> toolsVersions = swiftDao.listManifests(release.id()).stream()
        .map(SwiftRegistryDao.Manifest::toolsVersion)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .sorted()
        .toList();
    if (!toolsVersions.isEmpty()) {
      swift.put("swift_tools_versions", toolsVersions);
    }
    List<String> repositoryUrls = swiftDao.listRepositoryUrls(release.id()).stream()
        .map(SwiftRegistryDao.RepositoryUrl::displayUrl)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .toList();
    if (!repositoryUrls.isEmpty()) {
      swift.put("repository_urls", repositoryUrls);
    }
    return Map.copyOf(swift);
  }

  private static SwiftPath swiftPath(String path) {
    String normalized = normalize(path);
    SwiftPath parsed;
    try {
      parsed = SWIFT_PATHS.parse(normalized);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (parsed.kind() == SwiftPath.Kind.UNKNOWN) {
      String[] segments = normalized.split("/", -1);
      if (segments.length == 4
          && SwiftToolsVersions.fromManifestFilename(segments[3]).isPresent()) {
        parsed = SWIFT_PATHS.parse(
            segments[0] + "/" + segments[1] + "/" + segments[2] + "/Package.swift");
      }
    }
    return parsed.kind() == SwiftPath.Kind.SOURCE_ARCHIVE
            || parsed.kind() == SwiftPath.Kind.MANIFEST
        ? parsed
        : null;
  }

  private static boolean isCargoDynamicConfig(RepositoryRecord repository, String storagePath) {
    return repository.format() == RepositoryFormat.CARGO && "config.json".equals(storagePath);
  }

  private static boolean isTerraformProviderVersionsDocument(
      RepositoryRecord repository, String publicPath) {
    if (repository.format() != RepositoryFormat.TERRAFORM
        || !publicPath.endsWith("/versions.json")) {
      return false;
    }
    try {
      return TERRAFORM_PATHS.parse(publicPath).kind() == TerraformPath.Kind.PROVIDER_VERSIONS;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private boolean terraformProviderExists(
      RepositoryRecord visibleRepository, String publicPath) {
    TerraformPath versions = TERRAFORM_PATHS.parse(publicPath);
    String prefix = "v1/providers/" + versions.namespace() + "/" + versions.name() + "/";
    List<RepositoryRecord> sources = visibleRepository.type() == RepositoryType.GROUP
        ? repositoryDao.listMembers(visibleRepository.id())
        : List.of(visibleRepository);
    return sources.stream().anyMatch(source ->
        !assetDao.listAssetsByPrefix(source.id(), prefix).isEmpty());
  }

  private static BrowseAssetDetail terraformProviderVersionsDetail(
      RepositoryRecord repository, String publicPath) {
    LinkedHashMap<String, Object> content = new LinkedHashMap<>();
    content.put("generated", true);
    content.put("last_modified", null);
    LinkedHashMap<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("source", "kkrepo-terraform-provider-registry");
    provenance.put("dynamic", true);
    provenance.put("hashes_not_verified", false);
    return new BrowseAssetDetail(
        repository.name(),
        repository.name(),
        publicPath,
        "versions.json",
        null,
        "application/json",
        null,
        BrowseDownloadUrls.asset(repository, publicPath),
        "kkrepo",
        null,
        Map.of(),
        content,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        provenance);
  }

  private static BrowseAssetDetail cargoDynamicConfigDetail(RepositoryRecord repository) {
    LinkedHashMap<String, Object> content = new LinkedHashMap<>();
    content.put("generated", true);
    content.put("last_modified", null);
    LinkedHashMap<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("source", "kkrepo-cargo-sparse-registry");
    provenance.put("dynamic", true);
    provenance.put("hashes_not_verified", false);
    return new BrowseAssetDetail(
        repository.name(),
        repository.name(),
        "config.json",
        "config.json",
        null,
        "application/json",
        null,
        "/repository/" + repository.name() + "/config.json",
        "kkrepo",
        null,
        Map.of(),
        content,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        provenance);
  }

  private static Map<String, Object> composerAttributes(AssetRecord asset) {
    Map<String, Object> attributes = asset.attributes() == null ? Map.of() : asset.attributes();
    String packageName = text(firstPresent(attributes.get("packageName"), attributes.get("composerPackage")));
    String version = text(firstPresent(attributes.get("version"), attributes.get("composerVersion")));
    String path = normalize(asset.path());
    ComposerPath parsedPath = COMPOSER_PATHS.parse(path);
    if (parsedPath.kind() == ComposerPath.Kind.DIST) {
      if (packageName == null) packageName = parsedPath.packageName();
      if (version == null) version = parsedPath.version();
    }
    if (packageName == null && path.startsWith("p2/") && path.endsWith(".json")) {
      String coordinate = path.substring("p2/".length(), path.length() - ".json".length());
      if (coordinate.endsWith("~dev")) {
        coordinate = coordinate.substring(0, coordinate.length() - "~dev".length());
      }
      if (coordinate.indexOf('/') > 0) {
        packageName = coordinate;
      }
    }
    LinkedHashMap<String, Object> composer = new LinkedHashMap<>();
    put(composer, "asset_kind", parsedPath.kind() == ComposerPath.Kind.DIST
        ? "DIST" : "METADATA");
    put(composer, "package_name", packageName);
    put(composer, "version", version);
    return composer;
  }

  private ResolvedAsset resolveSourceAsset(
      RepositoryRecord visibleRepository,
      String sourceRepositoryName,
      String storagePath) {
    if (visibleRepository.type() != RepositoryType.GROUP) {
      if (visibleRepository.format() == RepositoryFormat.DOCKER) {
        return resolveDockerSourceAsset(visibleRepository, storagePath);
      }
      AssetRecord asset = assetDao.findAssetByPath(visibleRepository.id(), storagePath)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
      return new ResolvedAsset(visibleRepository, asset, null);
    }
    List<RepositoryRecord> members = visibleRepository.format() == RepositoryFormat.SWIFT
        ? BrowseRepositorySources.swiftSources(visibleRepository, repositoryDao)
        : visibleRepository.format() == RepositoryFormat.ANSIBLEGALAXY
            ? BrowseRepositorySources.ansibleSources(visibleRepository, repositoryDao)
            : repositoryDao.listMembers(visibleRepository.id());
    if (sourceRepositoryName != null && !sourceRepositoryName.isBlank()) {
      RepositoryRecord source = members.stream()
          .filter(member -> member.name().equals(sourceRepositoryName))
          .findFirst()
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source repository not found"));
      if (source.format() == RepositoryFormat.DOCKER) {
        return resolveDockerSourceAsset(source, storagePath);
      }
      AssetRecord asset = assetDao.findAssetByPath(source.id(), storagePath)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
      return new ResolvedAsset(source, asset, null);
    }
    // Single round trip across all members instead of N sequential point queries; iterate the
    // member list so the original first-win ordering is preserved.
    List<Long> memberIds = members.stream().map(RepositoryRecord::id).toList();
    Map<Long, AssetRecord> assetsByRepository = assetDao.findAssetsByPathHash(
        memberIds, PersistenceHashes.pathHash(storagePath));
    for (RepositoryRecord member : members) {
      if (member.format() == RepositoryFormat.DOCKER) {
        Optional<ResolvedAsset> docker = findDockerSourceAsset(member, storagePath);
        if (docker.isPresent()) {
          return docker.get();
        }
      }
      AssetRecord asset = assetsByRepository.get(member.id());
      if (asset != null) {
        return new ResolvedAsset(member, asset, null);
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found in group members");
  }

  private record ResolvedAsset(
      RepositoryRecord source,
      AssetRecord asset,
      DockerManifestRecord dockerManifest) {}

  private ResolvedAsset resolveDockerSourceAsset(RepositoryRecord source, String path) {
    return findDockerSourceAsset(source, path)
        .or(() -> assetDao.findAssetByPath(source.id(), path).map(asset -> new ResolvedAsset(source, asset, null)))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Docker manifest not found"));
  }

  private Optional<ResolvedAsset> findDockerSourceAsset(RepositoryRecord source, String path) {
    if (dockerDao == null) {
      return Optional.empty();
    }
    Optional<DockerManifestRecord> manifest = dockerDao.findBrowseManifestByReferencePath(source.id(), path);
    if (manifest.isEmpty()) {
      return Optional.empty();
    }
    return assetDao.findAssetById(manifest.get().assetId())
        .filter(asset -> asset.repositoryId() == source.id())
        .map(asset -> new ResolvedAsset(source, asset, manifest.get()));
  }

  private ResolvedStoragePath storagePath(
      RepositoryRecord visibleRepository,
      String normalized,
      String sourceRepositoryName) {
    if (visibleRepository.format() == RepositoryFormat.TERRAFORM && terraformDao != null) {
      Optional<TerraformBrowseAssetPathResolver.ResolvedStoragePath> terraform =
          TerraformBrowseAssetPathResolver.resolve(
              visibleRepository,
              normalized,
              sourceRepositoryName,
              repositoryDao,
              assetDao,
              terraformDao);
      if (terraform.isPresent()) {
        TerraformBrowseAssetPathResolver.ResolvedStoragePath resolved = terraform.orElseThrow();
        return new ResolvedStoragePath(resolved.path(), resolved.sourceRepositoryName());
      }
    }
    if (visibleRepository.format() == RepositoryFormat.PYPI
        && !normalized.isEmpty()
        && !normalized.equals("simple")
        && !normalized.startsWith("simple/")
        && !normalized.startsWith("packages/")) {
      return new ResolvedStoragePath("packages/" + normalized, sourceRepositoryName);
    }
    if (visibleRepository.format() == RepositoryFormat.ANSIBLEGALAXY) {
      String[] segments = normalized.split("/", -1);
      if (segments.length == 4
          && AnsibleGalaxyPathParser.isArtifactFilename(segments[3])) {
        return new ResolvedStoragePath(
            AnsibleGalaxyPathParser.ARTIFACT_BASE + segments[3], sourceRepositoryName);
      }
    }
    return new ResolvedStoragePath(normalized, sourceRepositoryName);
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String value = path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private record ResolvedStoragePath(String path, String sourceRepositoryName) {}

  private LinkedHashMap<String, Object> checksum(AssetBlobRecord blob) {
    LinkedHashMap<String, Object> checksum = new LinkedHashMap<>();
    put(checksum, "sha1", blob.sha1());
    put(checksum, "sha256", blob.sha256());
    put(checksum, "md5", blob.md5());
    if (blob.attributes() != null) {
      put(checksum, "sha512", blob.attributes().get("sha512"));
    }
    return checksum;
  }

  private LinkedHashMap<String, Object> content(AssetRecord asset) {
    LinkedHashMap<String, Object> content = new LinkedHashMap<>();
    put(content, "last_modified", asset.lastUpdatedAt());
    return content;
  }

  private Map<String, Object> npmAttributes(RepositoryRecord source, AssetRecord asset, AssetBlobRecord blob) {
    if (!isNpmTarball(asset)) {
      return Map.of();
    }
    Optional<Map<String, Object>> packageJson = npmVersionDocument(source, asset)
        .or(() -> packageJsonFromTarball(blob));
    if (packageJson.isEmpty()) {
      return Map.of("asset_kind", "TARBALL");
    }
    LinkedHashMap<String, Object> npm = new LinkedHashMap<>(NpmFormatAttributes.extract(packageJson.get()));
    npm.put("asset_kind", "TARBALL");
    return npm;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> pubAttributes(AssetRecord asset) {
    Map<String, Object> attributes = asset.attributes() == null ? Map.of() : asset.attributes();
    Object rawPubspec = attributes.get("pubspec");
    Map<String, Object> pubspec = rawPubspec instanceof Map<?, ?> map
        ? (Map<String, Object>) map
        : Map.of();
    LinkedHashMap<String, Object> pub = new LinkedHashMap<>();
    putNonBlank(pub, "asset_kind", asset.kind());
    putNonBlank(pub, "package_name", firstPresent(attributes.get("packageName"), pubspec.get("name")));
    putNonBlank(pub, "version", firstPresent(attributes.get("version"), pubspec.get("version")));
    putNonBlank(pub, "archive_sha256", attributes.get("archiveSha256"));
    put(pub, "archive_size_bytes", attributes.get("archiveSize"));
    putNonBlank(pub, "published_at", attributes.get("publishedAt"));
    putNonBlank(pub, "publish_source", attributes.get("publishSource"));
    putNonBlank(pub, "published_by", attributes.get("publishedBy"));
    put(pub, "publish_api_key_id", attributes.get("publishApiKeyId"));
    putNonBlank(pub, "upload_session_id", attributes.get("uploadSessionId"));
    putNonBlank(pub, "source_client", attributes.get("sourceClient"));
    putNonBlank(pub, "cache_source", attributes.get("cacheSource"));
    putNonBlank(pub, "source_repository", attributes.get("sourceRepository"));
    copyPubspec(pub, pubspec, "description");
    copyPubspec(pub, pubspec, "homepage");
    copyPubspec(pub, pubspec, "repository");
    copyPubspec(pub, pubspec, "issue_tracker");
    copyPubspec(pub, pubspec, "environment");
    copyPubspec(pub, pubspec, "dependencies");
    copyPubspec(pub, pubspec, "dev_dependencies");
    copyPubspec(pub, pubspec, "executables");
    copyPubspec(pub, pubspec, "topics");
    return Collections.unmodifiableMap(pub);
  }

  private static void copyPubspec(Map<String, Object> target, Map<String, Object> pubspec, String key) {
    Object value = pubspec.get(key);
    if (value instanceof String text && text.isBlank()) {
      return;
    }
    put(target, key, value);
  }

  private Map<String, Object> dockerAttributes(AssetRecord asset, AssetBlobRecord blob) {
    LinkedHashMap<String, Object> docker = new LinkedHashMap<>();
    docker.put("asset_kind", asset.kind());
    Map<String, Object> attributes = asset.attributes() == null ? Map.of() : asset.attributes();
    Object rawDocker = attributes.get("docker");
    if (rawDocker instanceof Map<?, ?> dockerMap) {
      putNonBlank(docker, "image_name", dockerMap.get("imageName"));
      putNonBlank(docker, "reference", dockerMap.get("reference"));
      putNonBlank(docker, "digest", dockerMap.get("digest"));
      putNonBlank(docker, "raw_bytes_digest", dockerMap.get("rawBytesDigest"));
      putNonBlank(docker, "media_type", dockerMap.get("mediaType"));
      putNonBlank(docker, "artifact_type", dockerMap.get("artifactType"));
      putNonBlank(docker, "subject_digest", dockerMap.get("subjectDigest"));
      putNonBlank(docker, "kind", dockerMap.get("kind"));
    }
    if (!docker.containsKey("digest") && blob.sha256() != null && !blob.sha256().isBlank()) {
      docker.put("digest", "sha256:" + blob.sha256());
    }
    return Collections.unmodifiableMap(docker);
  }

  private Map<String, Object> dockerAttributes(
      RepositoryRecord source,
      AssetRecord asset,
      AssetBlobRecord blob,
      String publicPath,
      DockerManifestRecord resolvedManifest) {
    LinkedHashMap<String, Object> docker = new LinkedHashMap<>(dockerAttributes(asset, blob));
    DockerPathReference reference = DockerPathReference.parse(publicPath);
    if (reference != null) {
      docker.put("image_name", reference.imageName());
      docker.put("reference", reference.reference());
    }
    if ("MANIFEST".equalsIgnoreCase(asset.kind())) {
      put(docker, "manifest_size_bytes", asset.size() == null ? blob.size() : asset.size());
      enrichDockerManifestMetadata(source, docker, resolvedManifest);
    } else if ("BLOB".equalsIgnoreCase(asset.kind())) {
      put(docker, "blob_size_bytes", asset.size() == null ? blob.size() : asset.size());
    }
    return Collections.unmodifiableMap(docker);
  }

  private void enrichDockerManifestMetadata(
      RepositoryRecord source,
      LinkedHashMap<String, Object> docker,
      DockerManifestRecord resolvedManifest) {
    if (dockerDao == null) {
      return;
    }
    DockerManifestRecord manifest = resolvedManifest;
    String imageName = text(docker.get("image_name"));
    String digest = text(docker.get("digest"));
    if (manifest == null && imageName != null && digest != null) {
      manifest = dockerDao.findManifestByDigest(source.id(), imageName, digest).orElse(null);
    }
    if (manifest == null) {
      return;
    }
    putNonBlank(docker, "digest", manifest.digest());
    putNonBlank(docker, "media_type", manifest.mediaType());
    putNonBlank(docker, "artifact_type", manifest.artifactType());
    putNonBlank(docker, "subject_digest", manifest.subjectDigest());
    List<DockerTagRecord> tags = dockerDao.listTagsForManifest(manifest.id());
    if (!tags.isEmpty()) {
      docker.put("tags", tags.stream().map(DockerTagRecord::tag).toList());
    }
    List<DockerManifestReferenceRecord> references = dockerDao.listReferences(manifest.id());
    List<DockerManifestReferenceRecord> layers = referencesByKind(references, "LAYER");
    List<DockerManifestReferenceRecord> configs = referencesByKind(references, "CONFIG");
    List<DockerManifestReferenceRecord> manifests = referencesByKind(references, "MANIFEST");
    List<DockerManifestRecord> referrers = dockerDao.listReferrers(source.id(), manifest.digest(), null);

    putDescriptorList(docker, "config_descriptors", configs);
    putDescriptorList(docker, "layer_descriptors", layers);
    putDescriptorList(docker, "manifest_descriptors", manifests);
    putManifestDescriptorList(docker, "referrers", referrers);
    putCount(docker, "referrer_count", referrers.size());
    if (references.isEmpty()) {
      return;
    }

    docker.put("descriptor_count", references.size());
    putCount(docker, "layer_count", layers.size());
    putCount(docker, "config_count", configs.size());
    putSize(docker, "layer_size_bytes", descriptorSize(layers));
    putSize(docker, "config_size_bytes", descriptorSize(configs));
    putSize(docker, "referenced_size_bytes", descriptorSize(references));
    if (!layers.isEmpty()) {
      putSize(docker, "image_size_bytes", descriptorSize(layers));
      CachedLayerSummary cached = cachedLayerSummary(source.id(), layers);
      putCount(docker, "cached_layer_count", cached.count());
      putSize(docker, "cached_layer_size_bytes", cached.sizeBytes());
    }
    if (!manifests.isEmpty()) {
      enrichDockerIndexMetadata(source, docker, manifest, manifests);
    }
  }

  private void enrichDockerIndexMetadata(
      RepositoryRecord source,
      LinkedHashMap<String, Object> docker,
      DockerManifestRecord parent,
      List<DockerManifestReferenceRecord> manifests) {
    List<Map<String, Object>> platforms = new ArrayList<>();
    int runnablePlatforms = 0;
    int cachedPlatforms = 0;
    Long firstCachedSize = null;
    String firstCachedPlatform = null;
    for (DockerManifestReferenceRecord reference : manifests) {
      LinkedHashMap<String, Object> platform = new LinkedHashMap<>();
      String label = platformLabel(reference.platform());
      putNonBlank(platform, "platform", label);
      putNonBlank(platform, "digest", reference.digest());
      putNonBlank(platform, "media_type", reference.mediaType());
      putSize(platform, "manifest_size_bytes", reference.size());
      if (isRunnablePlatform(reference.platform())) {
        runnablePlatforms++;
      }
      DockerManifestRecord child = dockerDao
          .findManifestByDigest(source.id(), parent.imageName(), reference.digest())
          .orElse(null);
      if (child != null) {
        CachedLayerSummary cached = cachedLayerSummary(source.id(), referencesByKind(dockerDao.listReferences(child.id()), "LAYER"));
        putSize(platform, "cached_image_size_bytes", cached.sizeBytes());
        putCount(platform, "cached_layer_count", cached.count());
        if (cached.sizeBytes() != null && cached.sizeBytes() > 0) {
          cachedPlatforms++;
          if (firstCachedSize == null) {
            firstCachedSize = cached.sizeBytes();
            firstCachedPlatform = label;
          }
        }
      }
      platforms.add(Collections.unmodifiableMap(platform));
    }
    docker.put("manifest_descriptor_count", manifests.size());
    docker.put("platform_count", runnablePlatforms > 0 ? runnablePlatforms : manifests.size());
    docker.put("platform_summary", platformSummary(platforms));
    docker.put("platforms", List.copyOf(platforms));
    putCount(docker, "cached_platform_count", cachedPlatforms);
    if (cachedPlatforms == 1) {
      putSize(docker, "cached_image_size_bytes", firstCachedSize);
      putNonBlank(docker, "cached_image_platform", firstCachedPlatform);
    }
  }

  private CachedLayerSummary cachedLayerSummary(long repositoryId, List<DockerManifestReferenceRecord> layers) {
    long size = 0L;
    int count = 0;
    for (DockerManifestReferenceRecord layer : layers) {
      String sha256 = sha256Hex(layer.digest());
      if (sha256 == null) {
        continue;
      }
      Optional<AssetRecord> asset = assetDao.findDockerBlobAssetBySha256(repositoryId, sha256);
      if (asset.isEmpty() || asset.get().assetBlobId() == null) {
        continue;
      }
      Optional<AssetBlobRecord> blob = assetDao.findBlobById(asset.get().assetBlobId());
      if (blob.isEmpty()) {
        continue;
      }
      count++;
      size += blob.get().size();
    }
    return count == 0 ? new CachedLayerSummary(0, null) : new CachedLayerSummary(count, size);
  }

  private static List<DockerManifestReferenceRecord> referencesByKind(
      List<DockerManifestReferenceRecord> references,
      String kind) {
    return references.stream()
        .filter(reference -> kind.equalsIgnoreCase(reference.referenceKind()))
        .toList();
  }

  private static void putDescriptorList(
      Map<String, Object> map,
      String key,
      List<DockerManifestReferenceRecord> references) {
    if (references == null || references.isEmpty()) {
      return;
    }
    map.put(key, references.stream()
        .map(BrowseAssetDetailService::descriptorMap)
        .toList());
  }

  private static Map<String, Object> descriptorMap(DockerManifestReferenceRecord reference) {
    LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
    putNonBlank(descriptor, "kind", reference.referenceKind());
    putNonBlank(descriptor, "digest", reference.digest());
    putNonBlank(descriptor, "media_type", reference.mediaType());
    putSize(descriptor, "size_bytes", reference.size());
    putNonBlank(descriptor, "platform", platformLabel(reference.platform()));
    if (reference.platform() != null && !reference.platform().isEmpty()) {
      descriptor.put("platform_detail", reference.platform());
    }
    if (reference.annotations() != null && !reference.annotations().isEmpty()) {
      descriptor.put("annotations", reference.annotations());
    }
    return Collections.unmodifiableMap(descriptor);
  }

  private static void putManifestDescriptorList(
      Map<String, Object> map,
      String key,
      List<DockerManifestRecord> manifests) {
    if (manifests == null || manifests.isEmpty()) {
      return;
    }
    map.put(key, manifests.stream()
        .map(BrowseAssetDetailService::manifestDescriptorMap)
        .toList());
  }

  private static Map<String, Object> manifestDescriptorMap(DockerManifestRecord manifest) {
    LinkedHashMap<String, Object> descriptor = new LinkedHashMap<>();
    putNonBlank(descriptor, "image_name", manifest.imageName());
    putNonBlank(descriptor, "digest", manifest.digest());
    putNonBlank(descriptor, "media_type", manifest.mediaType());
    putNonBlank(descriptor, "artifact_type", manifest.artifactType());
    putSize(descriptor, "size_bytes", manifest.size());
    putNonBlank(descriptor, "updated_at", manifest.updatedAt());
    return Collections.unmodifiableMap(descriptor);
  }

  private static Long descriptorSize(List<DockerManifestReferenceRecord> references) {
    long size = 0L;
    boolean present = false;
    for (DockerManifestReferenceRecord reference : references) {
      if (reference.size() == null) {
        continue;
      }
      present = true;
      size += reference.size();
    }
    return present ? size : null;
  }

  private static boolean isRunnablePlatform(Map<String, Object> platform) {
    String os = text(platform == null ? null : platform.get("os"));
    String architecture = text(platform == null ? null : platform.get("architecture"));
    return os != null
        && architecture != null
        && !"unknown".equalsIgnoreCase(os)
        && !"unknown".equalsIgnoreCase(architecture);
  }

  private static String platformLabel(Map<String, Object> platform) {
    String os = text(platform == null ? null : platform.get("os"));
    String architecture = text(platform == null ? null : platform.get("architecture"));
    String variant = text(platform == null ? null : platform.get("variant"));
    if (os == null && architecture == null) {
      return null;
    }
    String label = (os == null ? "unknown" : os) + "/" + (architecture == null ? "unknown" : architecture);
    return variant == null ? label : label + "/" + variant;
  }

  private static String platformSummary(List<Map<String, Object>> platforms) {
    List<String> labels = platforms.stream()
        .map(platform -> text(platform.get("platform")))
        .filter(label -> label != null && !label.startsWith("unknown/"))
        .distinct()
        .toList();
    if (labels.isEmpty()) {
      labels = platforms.stream()
          .map(platform -> text(platform.get("platform")))
          .filter(label -> label != null)
          .distinct()
          .toList();
    }
    if (labels.isEmpty()) {
      return null;
    }
    int limit = Math.min(labels.size(), 6);
    String summary = String.join(", ", labels.subList(0, limit));
    return labels.size() > limit ? summary + " +" + (labels.size() - limit) : summary;
  }

  private static void putCount(Map<String, Object> map, String key, int count) {
    if (count > 0) {
      map.put(key, count);
    }
  }

  private static void putSize(Map<String, Object> map, String key, Long size) {
    if (size != null) {
      map.put(key, size);
    }
  }

  private static String sha256Hex(String digest) {
    if (digest == null || !digest.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
      return null;
    }
    return digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
  }

  private record CachedLayerSummary(int count, Long sizeBytes) {}

  private record DockerPathReference(String imageName, String reference) {
    static DockerPathReference parse(String path) {
      String normalized = normalize(path);
      if (normalized.startsWith("docker/manifests/")) {
        normalized = normalized.substring("docker/manifests/".length());
      }
      int marker = normalized.lastIndexOf("/manifests/");
      if (marker <= 0 || marker + "/manifests/".length() >= normalized.length()) {
        return null;
      }
      String imageName = normalized.substring(0, marker);
      String reference = normalized.substring(marker + "/manifests/".length());
      return imageName.isBlank() || reference.isBlank() ? null : new DockerPathReference(imageName, reference);
    }
  }

  private boolean isNpmTarball(AssetRecord asset) {
    return "tarball".equals(asset.kind()) || asset.path().endsWith(".tgz");
  }

  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> npmVersionDocument(RepositoryRecord source, AssetRecord asset) {
    NpmTarballPath tarball = NpmTarballPath.parse(asset.path()).orElse(null);
    if (tarball == null) {
      return Optional.empty();
    }
    Optional<AssetRecord> packageRootAsset = assetDao.findAssetByPath(source.id(), tarball.packageId().id());
    if (packageRootAsset.isEmpty() || packageRootAsset.get().assetBlobId() == null) {
      return Optional.empty();
    }
    Optional<AssetBlobRecord> rootBlob = assetDao.findBlobById(packageRootAsset.get().assetBlobId());
    if (rootBlob.isEmpty()) {
      return Optional.empty();
    }
    try (InputStream body = openBody(rootBlob.get())) {
      Map<String, Object> root = objectMapper.readValue(body, MAP_TYPE);
      String version = NpmMetadata.findVersionForTarball(root, tarball.tarballName());
      Object explicitVersion = asset.attributes() == null ? null : asset.attributes().get("version");
      if (version == null && explicitVersion != null) {
        version = explicitVersion.toString();
      }
      Object raw = version == null ? null : NpmMetadata.versions(root).get(version);
      if (raw instanceof Map<?, ?> versionDocument) {
        return Optional.of((Map<String, Object>) versionDocument);
      }
    } catch (IOException | RuntimeException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<Map<String, Object>> packageJsonFromTarball(AssetBlobRecord blob) {
    try (InputStream raw = openBody(blob);
         GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
         TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextTarEntry()) != null) {
        if (!entry.isFile() || !"package/package.json".equals(entry.getName())) {
          continue;
        }
        byte[] bytes = readLimited(tar, MAX_PACKAGE_JSON_BYTES);
        return Optional.of(objectMapper.readValue(new ByteArrayInputStream(bytes), MAP_TYPE));
      }
    } catch (IOException | RuntimeException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private InputStream openBody(AssetBlobRecord blob) {
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    return storage.get(BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blob content not found"));
  }

  private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = in.read(buffer)) >= 0) {
      total += read;
      if (total > maxBytes) {
        throw new IOException("package.json is too large");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static void put(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static void putNonBlank(Map<String, Object> map, String key, Object value) {
    String text = text(value);
    if (text != null) {
      map.put(key, text);
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString();
    return text.isBlank() ? null : text;
  }

  private static Object firstPresent(Object first, Object second) {
    return text(first) != null ? first : second;
  }

  private record NpmTarballPath(NpmPackageId packageId, String tarballName) {
    static Optional<NpmTarballPath> parse(String path) {
      int marker = path == null ? -1 : path.indexOf("/-/");
      if (marker < 0) {
        return Optional.empty();
      }
      String packageId = path.substring(0, marker);
      String tarballName = path.substring(marker + 3);
      if (packageId.isBlank() || tarballName.isBlank()) {
        return Optional.empty();
      }
      try {
        return Optional.of(new NpmTarballPath(NpmPackageId.parse(packageId), tarballName));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }
  }

  public record BrowseAssetDetail(
      String repository,
      String sourceRepository,
      String path,
      String name,
      Long size,
      String contentType,
      Instant lastUpdatedAt,
      String downloadUrl,
      String uploader,
      String uploaderIp,
      Map<String, Object> checksum,
      Map<String, Object> content,
      Map<String, Object> docker,
      Map<String, Object> npm,
      Map<String, Object> pub,
      Map<String, Object> composer,
      Map<String, Object> swift,
      Map<String, Object> ansible,
      Map<String, Object> provenance) {}
}
