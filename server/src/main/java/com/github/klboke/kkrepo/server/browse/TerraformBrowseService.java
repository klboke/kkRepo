package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Projects revisioned Terraform provider assets onto Nexus-compatible public browse paths. */
@Component
final class TerraformBrowseService {
  private static final String PROVIDER_PREFIX = "v1/providers/";

  private final TerraformRegistryDao registry;
  private final AssetDao assets;

  TerraformBrowseService(TerraformRegistryDao registry, AssetDao assets) {
    this.registry = registry;
    this.assets = assets;
  }

  Optional<List<BrowseController.BrowseEntry>> list(
      RepositoryRecord visibleRepository,
      List<RepositoryRecord> sources,
      String parent) {
    ProviderPath path = ProviderPath.parse(parent);
    if (path == null || path.version() == null) {
      return Optional.empty();
    }
    return switch (path.depth()) {
      case VERSION -> hasPublishedPlatform(sources, path)
          ? Optional.of(List.of(directory(visibleRepository, parent + "/download", "download")))
          : Optional.empty();
      case DOWNLOAD -> directories(
          visibleRepository,
          parent,
          platformValues(sources, path, TerraformRegistryDao.ProviderPlatform::os));
      case OS -> directories(
          visibleRepository,
          parent,
          platformValues(
              sources,
              path,
              platform -> platform.os().equals(path.os()) ? platform.arch() : null));
      case PLATFORM -> platformEntries(visibleRepository, sources, path);
      default -> Optional.empty();
    };
  }

  BrowseController.BrowseEntry versionsJson(RepositoryRecord visibleRepository, String parent) {
    String path = parent + "/versions.json";
    return new BrowseController.BrowseEntry(
        "versions.json",
        path,
        null,
        true,
        null,
        "application/json",
        null,
        null,
        BrowseDownloadUrls.asset(visibleRepository, path));
  }

  private Optional<List<BrowseController.BrowseEntry>> platformEntries(
      RepositoryRecord visibleRepository,
      List<RepositoryRecord> sources,
      ProviderPath path) {
    for (RepositoryRecord source : sources) {
      Optional<TerraformRegistryDao.ProviderPlatform> platform = publishedPlatforms(source, path).stream()
          .filter(row -> row.os().equals(path.os()) && row.arch().equals(path.arch()))
          .findFirst();
      Optional<TerraformRegistryDao.ProviderState> state = registry.findProviderState(
          source.id(), path.namespace(), path.type(), path.version());
      if (platform.isEmpty() || state.isEmpty()) {
        continue;
      }
      AssetRecord archive = assets.findAssetByPath(source.id(), platform.orElseThrow().assetPath())
          .orElse(null);
      AssetRecord shasums = assets.findAssetByPath(source.id(), state.orElseThrow().shasumsPath())
          .orElse(null);
      AssetRecord signature = assets.findAssetByPath(source.id(), state.orElseThrow().signaturePath())
          .orElse(null);
      if (archive == null || shasums == null || signature == null) {
        continue;
      }
      String base = path.publicPath();
      return Optional.of(List.of(
          asset(visibleRepository, source, archive, base + "/" + platform.orElseThrow().filename(),
              platform.orElseThrow().filename()),
          asset(visibleRepository, source, shasums, base + "/SHA256SUMS", "SHA256SUMS"),
          asset(visibleRepository, source, signature, base + "/SHA256SUMS.sig", "SHA256SUMS.sig")));
    }
    return Optional.empty();
  }

  private Optional<List<BrowseController.BrowseEntry>> directories(
      RepositoryRecord visibleRepository,
      String parent,
      Set<String> names) {
    if (names.isEmpty()) {
      return Optional.empty();
    }
    List<BrowseController.BrowseEntry> entries = names.stream()
        .sorted()
        .map(name -> directory(visibleRepository, parent + "/" + name, name))
        .toList();
    return Optional.of(entries);
  }

  private Set<String> platformValues(
      List<RepositoryRecord> sources,
      ProviderPath path,
      java.util.function.Function<TerraformRegistryDao.ProviderPlatform, String> value) {
    Set<String> values = new LinkedHashSet<>();
    for (RepositoryRecord source : sources) {
      for (TerraformRegistryDao.ProviderPlatform platform : publishedPlatforms(source, path)) {
        String candidate = value.apply(platform);
        if (candidate != null && !candidate.isBlank()) {
          values.add(candidate);
        }
      }
    }
    return values;
  }

  private boolean hasPublishedPlatform(List<RepositoryRecord> sources, ProviderPath path) {
    return sources.stream().anyMatch(source -> !publishedPlatforms(source, path).isEmpty());
  }

  private List<TerraformRegistryDao.ProviderPlatform> publishedPlatforms(
      RepositoryRecord source, ProviderPath path) {
    List<TerraformRegistryDao.ProviderPlatform> published = new ArrayList<>();
    for (TerraformRegistryDao.ProviderPlatform platform : registry.listProviderPlatforms(
        source.id(), path.namespace(), path.type(), path.version())) {
      if (assets.findAssetByPath(source.id(), platform.assetPath()).isPresent()) {
        published.add(platform);
      }
    }
    return published;
  }

  private BrowseController.BrowseEntry asset(
      RepositoryRecord visibleRepository,
      RepositoryRecord sourceRepository,
      AssetRecord asset,
      String publicPath,
      String displayName) {
    String sha1 = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).map(AssetBlobRecord::sha1).orElse(null);
    return new BrowseController.BrowseEntry(
        displayName,
        publicPath,
        sourceRepository.name(),
        true,
        asset.size(),
        asset.contentType(),
        sha1,
        asset.lastUpdatedAt(),
        BrowseDownloadUrls.asset(visibleRepository, publicPath));
  }

  private static BrowseController.BrowseEntry directory(
      RepositoryRecord visibleRepository, String path, String displayName) {
    return new BrowseController.BrowseEntry(
        displayName,
        path,
        visibleRepository.name(),
        false,
        null,
        null,
        null,
        null,
        null);
  }

  private enum Depth {
    VERSION,
    DOWNLOAD,
    OS,
    PLATFORM
  }

  private record ProviderPath(
      String namespace,
      String type,
      String version,
      String os,
      String arch,
      Depth depth) {

    static ProviderPath parse(String rawPath) {
      String value = rawPath == null ? "" : rawPath;
      if (!value.startsWith(PROVIDER_PREFIX)) {
        return null;
      }
      String[] parts = value.split("/");
      if (parts.length < 5 || parts.length > 8) {
        return null;
      }
      String namespace = parts[2];
      String type = parts[3];
      String version = parts[4];
      if (parts.length == 5) {
        return new ProviderPath(namespace, type, version, null, null, Depth.VERSION);
      }
      if (!"download".equals(parts[5])) {
        return null;
      }
      if (parts.length == 6) {
        return new ProviderPath(namespace, type, version, null, null, Depth.DOWNLOAD);
      }
      if (parts.length == 7) {
        return new ProviderPath(namespace, type, version, parts[6], null, Depth.OS);
      }
      return new ProviderPath(namespace, type, version, parts[6], parts[7], Depth.PLATFORM);
    }

    String publicPath() {
      return PROVIDER_PREFIX + namespace + "/" + type + "/" + version
          + "/download/" + os + "/" + arch;
    }
  }
}
