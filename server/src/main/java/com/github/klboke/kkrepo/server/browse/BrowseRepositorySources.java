package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BrowseRepositorySources {
  private BrowseRepositorySources() {
  }

  static List<RepositoryRecord> swiftSources(
      RepositoryRecord visibleRepository,
      RepositoryDao repositoryDao) {
    if (visibleRepository.type() != RepositoryType.GROUP) {
      return List.of(visibleRepository);
    }
    List<RepositoryRecord> sources = new ArrayList<>();
    collect(visibleRepository, repositoryDao, new LinkedHashSet<>(), sources);
    return List.copyOf(sources);
  }

  static List<RepositoryRecord> ansibleSources(
      RepositoryRecord visibleRepository,
      RepositoryDao repositoryDao) {
    return swiftSources(visibleRepository, repositoryDao);
  }

  private static void collect(
      RepositoryRecord repository,
      RepositoryDao repositoryDao,
      Set<Long> visited,
      List<RepositoryRecord> sources) {
    if (repository.id() == null || !visited.add(repository.id())) {
      return;
    }
    if (repository.type() != RepositoryType.GROUP) {
      sources.add(repository);
      return;
    }
    for (RepositoryRecord member : repositoryDao.listMembers(repository.id())) {
      collect(member, repositoryDao, visited, sources);
    }
  }
}
