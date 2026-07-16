package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns Terraform's logical search components. Physical cache and metadata assets remain assets,
 * while one component represents each published module or provider version.
 */
@Service
public class TerraformComponentService {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final TerraformRegistryDao registry;
  private final TerraformPathParser paths = new TerraformPathParser();

  public TerraformComponentService(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      TerraformRegistryDao registry) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.browseNodeDao = browseNodeDao;
    this.registry = registry;
  }

  ComponentRecord moduleComponent(
      RepositoryRuntime runtime, TerraformPath path, Instant lastUpdatedAt) {
    requireTerraformVersion(runtime, path);
    if (!path.module()) {
      throw new IllegalArgumentException("Terraform module component requires a module path");
    }
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.TERRAFORM,
        path.namespace(),
        path.name(),
        path.version(),
        "terraform-module",
        PersistenceHashes.sha256(
            "terraform-module", path.namespace(), path.name(), path.system(), path.version()),
        Map.of(
            "scope", "module",
            "system", path.system(),
            "browsePath", moduleBrowsePath(path)),
        lastUpdatedAt == null ? Instant.now() : lastUpdatedAt);
  }

  ComponentRecord providerComponent(
      RepositoryRuntime runtime, TerraformPath path, Instant lastUpdatedAt) {
    requireTerraformVersion(runtime, path);
    if (!path.provider()) {
      throw new IllegalArgumentException("Terraform provider component requires a provider path");
    }
    return providerComponent(
        runtime, path.namespace(), path.name(), path.version(), lastUpdatedAt);
  }

  Optional<ComponentRecord> componentForPublicPath(
      RepositoryRuntime runtime, TerraformPath path, Instant lastUpdatedAt) {
    if (runtime == null || runtime.format() != RepositoryFormat.TERRAFORM
        || runtime.isGroup() || path == null || path.version() == null) {
      return Optional.empty();
    }
    return switch (path.kind()) {
      case MODULE_ARCHIVE -> Optional.of(moduleComponent(runtime, path, lastUpdatedAt));
      case PROVIDER_ARCHIVE, PROVIDER_SHA256SUMS, PROVIDER_SHA256SUMS_SIGNATURE ->
          Optional.of(providerComponent(runtime, path, lastUpdatedAt));
      default -> Optional.empty();
    };
  }

  @Transactional
  public void publishProvider(
      RepositoryRuntime runtime,
      TerraformRegistryDao.ProviderPlatform platform,
      TerraformRegistryDao.ProviderState state,
      Collection<String> activeAssetPaths) {
    requireTerraformRuntime(runtime);
    TerraformRegistryDao.ProviderState previousState = registry.findProviderState(
        runtime.id(), platform.namespace(), platform.type(), platform.version()).orElse(null);
    List<TerraformRegistryDao.ProviderPlatform> previousPlatforms = registry.listProviderPlatforms(
        runtime.id(), platform.namespace(), platform.type(), platform.version());

    registry.publishProvider(platform, state);

    ComponentRecord component = providerComponent(
        runtime, platform.namespace(), platform.type(), platform.version(), state.updatedAt());
    long componentId = componentDao.upsertReturningId(component);
    Set<String> active = new LinkedHashSet<>(activeAssetPaths == null ? List.of() : activeAssetPaths);

    if (previousState != null) {
      detachIfInactive(runtime, previousState.shasumsPath(), active);
      detachIfInactive(runtime, previousState.signaturePath(), active);
    }
    previousPlatforms.stream()
        .filter(existing -> existing.os().equals(platform.os())
            && existing.arch().equals(platform.arch()))
        .map(TerraformRegistryDao.ProviderPlatform::assetPath)
        .forEach(path -> detachIfInactive(runtime, path, active));

    for (String path : active) {
      AssetRecord asset = assetDao.findAssetByPath(runtime.id(), path)
          .orElseThrow(() -> new IllegalStateException(
              "Published Terraform provider asset is missing: " + path));
      bind(asset, componentId);
    }
  }

  @Transactional
  public void rebuild(RepositoryRuntime runtime) {
    requireTerraformRuntime(runtime);
    if (runtime.isGroup()) {
      return;
    }

    List<AssetRecord> assets = assetDao.listAssetsByPrefix(runtime.id(), "");
    Map<String, ProviderSnapshot> providerSnapshots = new LinkedHashMap<>();
    Map<String, RebuildGroup> groups = new LinkedHashMap<>();

    componentDao.deleteByRepositoryIdAndFormat(runtime.id(), RepositoryFormat.TERRAFORM);
    for (AssetRecord asset : assets) {
      componentForRebuild(runtime, asset, providerSnapshots).ifPresent(component -> {
        String key = HexFormat.of().formatHex(component.coordinateHash());
        groups.computeIfAbsent(key, ignored -> new RebuildGroup(component)).add(asset, component);
      });
    }

    for (RebuildGroup group : groups.values()) {
      long componentId = componentDao.upsertReturningId(group.component);
      for (AssetRecord asset : group.assets) {
        bind(asset, componentId);
      }
    }
  }

  private Optional<ComponentRecord> componentForRebuild(
      RepositoryRuntime runtime,
      AssetRecord asset,
      Map<String, ProviderSnapshot> providerSnapshots) {
    if (asset.format() != RepositoryFormat.TERRAFORM || asset.path() == null
        || asset.path().startsWith(".terraform/")) {
      return Optional.empty();
    }
    TerraformPath parsed;
    try {
      parsed = paths.parse(asset.path());
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
    Instant updatedAt = asset.lastUpdatedAt() == null ? Instant.now() : asset.lastUpdatedAt();
    if (parsed.kind() == TerraformPath.Kind.MODULE_ARCHIVE) {
      return Optional.of(moduleComponent(runtime, parsed, updatedAt));
    }
    if (parsed.kind() != TerraformPath.Kind.PROVIDER_ARCHIVE
        && parsed.kind() != TerraformPath.Kind.PROVIDER_SHA256SUMS
        && parsed.kind() != TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE) {
      return Optional.empty();
    }
    if (runtime.isProxy()) {
      return Optional.of(providerComponent(runtime, parsed, updatedAt));
    }

    String key = providerKey(parsed.namespace(), parsed.name(), parsed.version());
    ProviderSnapshot snapshot = providerSnapshots.computeIfAbsent(
        key,
        ignored -> providerSnapshot(runtime, parsed.namespace(), parsed.name(), parsed.version()));
    boolean current = switch (parsed.kind()) {
      case PROVIDER_ARCHIVE -> snapshot.platformPaths.contains(asset.path());
      case PROVIDER_SHA256SUMS -> asset.path().equals(snapshot.shasumsPath);
      case PROVIDER_SHA256SUMS_SIGNATURE -> asset.path().equals(snapshot.signaturePath);
      default -> false;
    };
    return current
        ? Optional.of(providerComponent(runtime, parsed, updatedAt))
        : Optional.empty();
  }

  private ProviderSnapshot providerSnapshot(
      RepositoryRuntime runtime, String namespace, String type, String version) {
    TerraformRegistryDao.ProviderState state = registry.findProviderState(
        runtime.id(), namespace, type, version).orElse(null);
    if (state == null) {
      return ProviderSnapshot.EMPTY;
    }
    Set<String> platformPaths = registry.listProviderPlatforms(
            runtime.id(), namespace, type, version).stream()
        .map(TerraformRegistryDao.ProviderPlatform::assetPath)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    return new ProviderSnapshot(platformPaths, state.shasumsPath(), state.signaturePath());
  }

  private ComponentRecord providerComponent(
      RepositoryRuntime runtime,
      String namespace,
      String type,
      String version,
      Instant lastUpdatedAt) {
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.TERRAFORM,
        namespace,
        type,
        version,
        "terraform-provider",
        PersistenceHashes.sha256("terraform-provider", namespace, type, version),
        Map.of(
            "scope", "provider",
            "browsePath", providerBrowsePath(namespace, type, version)),
        lastUpdatedAt == null ? Instant.now() : lastUpdatedAt);
  }

  private void detachIfInactive(
      RepositoryRuntime runtime, String path, Set<String> activePaths) {
    if (path == null || path.isBlank() || activePaths.contains(path)) {
      return;
    }
    assetDao.findAssetByPath(runtime.id(), path).ifPresent(asset -> {
      assetDao.updateAssetComponentBinding(asset.id(), null);
      browseNodeDao.upsertPathAncestors(runtime.id(), asset.path(), asset.id(), null);
    });
  }

  private void bind(AssetRecord asset, long componentId) {
    assetDao.updateAssetComponentBinding(asset.id(), componentId);
    browseNodeDao.upsertPathAncestors(
        asset.repositoryId(), asset.path(), asset.id(), componentId);
  }

  private static void requireTerraformVersion(
      RepositoryRuntime runtime, TerraformPath path) {
    requireTerraformRuntime(runtime);
    if (path == null || path.namespace() == null || path.name() == null
        || path.version() == null || path.version().isBlank()) {
      throw new IllegalArgumentException("Terraform logical component requires version coordinates");
    }
  }

  private static void requireTerraformRuntime(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.TERRAFORM) {
      throw new IllegalArgumentException("Terraform logical components require a Terraform repository");
    }
  }

  private static String moduleBrowsePath(TerraformPath path) {
    return "v1/modules/" + path.namespace() + "/" + path.name() + "/"
        + path.system() + "/" + path.version();
  }

  private static String providerBrowsePath(String namespace, String type, String version) {
    return "v1/providers/" + namespace + "/" + type + "/" + version;
  }

  private static String providerKey(String namespace, String type, String version) {
    return namespace + "\0" + type + "\0" + version;
  }

  private static final class RebuildGroup {
    private ComponentRecord component;
    private final List<AssetRecord> assets = new ArrayList<>();

    private RebuildGroup(ComponentRecord component) {
      this.component = component;
    }

    private void add(AssetRecord asset, ComponentRecord candidate) {
      assets.add(asset);
      if (candidate.lastUpdatedAt().isAfter(component.lastUpdatedAt())) {
        component = candidate;
      }
    }
  }

  private record ProviderSnapshot(
      Set<String> platformPaths, String shasumsPath, String signaturePath) {
    private static final ProviderSnapshot EMPTY =
        new ProviderSnapshot(Set.of(), null, null);
  }
}
