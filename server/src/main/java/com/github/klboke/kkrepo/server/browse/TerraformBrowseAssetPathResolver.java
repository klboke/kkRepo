package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import java.util.List;
import java.util.Optional;

final class TerraformBrowseAssetPathResolver {
  private static final TerraformPathParser PATHS = new TerraformPathParser();

  private TerraformBrowseAssetPathResolver() {}

  static Optional<ResolvedStoragePath> resolve(
      RepositoryRecord visibleRepository,
      String publicPath,
      String sourceRepositoryName,
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      TerraformRegistryDao terraformDao) {
    TerraformPath parsed;
    try {
      parsed = PATHS.parse(publicPath);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    boolean publicArchive = parsed.kind() == TerraformPath.Kind.PROVIDER_ARCHIVE
        && parsed.os() != null && parsed.arch() != null;
    boolean publicMetadata = (parsed.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS
        || parsed.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE)
        && parsed.os() != null && parsed.arch() != null;
    if (!publicArchive && !publicMetadata) {
      return Optional.empty();
    }
    List<RepositoryRecord> sources = visibleRepository.type() == RepositoryType.GROUP
        ? repositoryDao.listMembers(visibleRepository.id())
        : List.of(visibleRepository);
    if (sourceRepositoryName != null && !sourceRepositoryName.isBlank()) {
      sources = sources.stream()
          .filter(source -> source.name().equals(sourceRepositoryName))
          .toList();
    }
    for (RepositoryRecord source : sources) {
      Optional<TerraformRegistryDao.ProviderPlatform> platform = terraformDao.listProviderPlatforms(
              source.id(), parsed.namespace(), parsed.name(), parsed.version()).stream()
          .filter(row -> row.os().equals(parsed.os()) && row.arch().equals(parsed.arch()))
          .filter(row -> !publicArchive || row.filename().equals(parsed.filename()))
          .filter(row -> assetDao.findAssetByPath(source.id(), row.assetPath()).isPresent())
          .findFirst();
      if (platform.isEmpty()) {
        continue;
      }
      if (publicArchive) {
        return Optional.of(new ResolvedStoragePath(
            platform.orElseThrow().assetPath(), source.name()));
      }
      Optional<TerraformRegistryDao.ProviderState> state = terraformDao.findProviderState(
          source.id(), parsed.namespace(), parsed.name(), parsed.version());
      if (state.isPresent()) {
        String metadataPath = parsed.kind() == TerraformPath.Kind.PROVIDER_SHA256SUMS
            ? state.orElseThrow().shasumsPath()
            : state.orElseThrow().signaturePath();
        return Optional.of(new ResolvedStoragePath(metadataPath, source.name()));
      }
    }
    return Optional.empty();
  }

  record ResolvedStoragePath(String path, String sourceRepositoryName) {}
}
