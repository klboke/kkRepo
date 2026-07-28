package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves member content and group policy scopes without using node-local state. */
@Component
public class SecurityScanRepositoryScope {
  private final SecurityScanDao scans;
  private final RepositoryDao repositories;

  public SecurityScanRepositoryScope(SecurityScanDao scans, RepositoryDao repositories) {
    this.scans = scans;
    this.repositories = repositories;
  }

  public List<RepositoryScanConfig> effectiveConfigsForSource(long sourceRepositoryId) {
    RepositoryRecord source = repositories.findById(sourceRepositoryId).orElse(null);
    if (source == null || source.type() == RepositoryType.GROUP) return List.of();
    List<RepositoryScanConfig> configs = new ArrayList<>();
    ArrayDeque<Long> pending = new ArrayDeque<>();
    Set<Long> visited = new LinkedHashSet<>();
    pending.add(sourceRepositoryId);
    while (!pending.isEmpty()) {
      long repositoryId = pending.removeFirst();
      if (!visited.add(repositoryId)) continue;
      scans.findRepositoryConfig(repositoryId)
          .filter(RepositoryScanConfig::enabled)
          .filter(config -> appliesToSource(config, source.type()))
          .ifPresent(configs::add);
      repositories.listGroupsContaining(repositoryId).stream()
          .map(RepositoryRecord::id)
          .filter(java.util.Objects::nonNull)
          .forEach(pending::addLast);
    }
    return List.copyOf(configs);
  }

  public Optional<RepositoryScanConfig> effectiveConfig(
      long sourceRepositoryId, long profileId) {
    return effectiveConfigsForSource(sourceRepositoryId).stream()
        .filter(config -> config.profileId() == profileId)
        .findFirst();
  }

  public boolean appliesToSource(
      RepositoryScanConfig config, long sourceRepositoryId) {
    return repositories.findById(sourceRepositoryId)
        .map(RepositoryRecord::type)
        .map(type -> appliesToSource(config, type))
        .orElse(false);
  }

  public List<Long> sourceRepositoryIds(long contextRepositoryId) {
    RepositoryRecord context = repositories.findById(contextRepositoryId).orElse(null);
    if (context == null) return List.of();
    Set<Long> sources = new LinkedHashSet<>();
    collectSources(context, sources, new LinkedHashSet<>());
    return List.copyOf(sources);
  }

  private void collectSources(
      RepositoryRecord repository, Set<Long> sources, Set<Long> visited) {
    if (repository.id() == null || !visited.add(repository.id())) return;
    if (repository.type() != RepositoryType.GROUP) {
      sources.add(repository.id());
      return;
    }
    for (RepositoryRecord member : repositories.listMembers(repository.id())) {
      collectSources(member, sources, visited);
    }
  }

  static boolean appliesToSource(
      RepositoryScanConfig config, RepositoryType sourceType) {
    return switch (sourceType) {
      case HOSTED -> config.scanHostedContent();
      case PROXY -> config.scanProxyContent();
      case GROUP -> false;
    };
  }
}
