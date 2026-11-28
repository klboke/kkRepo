package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.r.RPath;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.server.browse.BrowseController.BrowseEntry;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/** Projects published R snapshots into Browse without creating indexes or browse nodes. */
@Service
public class RHostedBrowseIndexes {
  private static final RPathParser PATHS = new RPathParser();
  private static final String INDEX = "PACKAGES.gz";
  private final RRegistryDao registry;
  private final AssetDao assets;

  public RHostedBrowseIndexes(RRegistryDao registry, AssetDao assets) {
    this.registry = registry;
    this.assets = assets;
  }

  static boolean isHostedR(RepositoryRecord repo) {
    return repo.format() == RepositoryFormat.R && repo.type() == RepositoryType.HOSTED;
  }

  static Optional<String> indexNamespace(String path) {
    RPath parsed = PATHS.parse(path);
    return parsed.kind() == RPath.Kind.PACKAGES_GZIP && path.equals(parsed.normalized())
        ? Optional.of(parsed.namespace()) : Optional.empty();
  }

  static Optional<String> publishedPath(RRegistryDao registry, long repositoryId, String namespace) {
    return registry.findPublishedSnapshot(repositoryId, namespace)
        .map(snapshot -> snapshot.manifest().get(namespace + "/" + INDEX));
  }

  public List<BrowseEntry> children(
      RepositoryRecord repo, String parent, Predicate<String> directoryExists) {
    if (!isHostedR(repo)) return List.of();
    if (RPathParser.packageExtension(parent) != null) {
      String path = parent + "/" + INDEX;
      return publishedPath(registry, repo.id(), parent)
          .flatMap(hidden -> assets.findAssetByPath(repo.id(), hidden))
          .map(asset -> new BrowseEntry(INDEX, path, repo.name(), true, asset.size(),
              asset.contentType(), null, asset.lastUpdatedAt(), BrowseDownloadUrls.asset(repo, path), true))
          .stream().toList();
    }

    for (int end = parent.lastIndexOf('/'); end > 0; end = parent.lastIndexOf('/', end - 1)) {
      if (RPathParser.packageExtension(parent.substring(0, end)) != null) return List.of();
    }
    // An empty published index must remain reachable even after the last package was deleted.
    String prefix = parent.isEmpty() ? "" : parent + "/";
    return registry.listSuites(repo.id()).stream()
        .filter(suite -> suite.lastPublishedAt() != null)
        .map(RRegistryDao.SuiteState::distribution)
        .filter(namespace -> namespace.startsWith(prefix) && RPathParser.packageExtension(namespace) != null)
        .map(namespace -> namespace.substring(prefix.length()).split("/", 2)[0])
        .distinct()
        .filter(name -> !directoryExists.test(prefix + name))
        .map(name -> new BrowseEntry(name, prefix + name, repo.name(), false, null, null, null, null, null))
        .toList();
  }
}
