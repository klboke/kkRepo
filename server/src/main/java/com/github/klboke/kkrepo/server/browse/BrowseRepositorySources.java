package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryFormat;
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

  static List<RepositoryRecord> sources(
      RepositoryRecord visibleRepository,
      RepositoryDao repositoryDao) {
    if (visibleRepository.type() != RepositoryType.GROUP) {
      return List.of(visibleRepository);
    }
    if (!supportsNestedGroups(visibleRepository.format())) {
      return repositoryDao.listMembers(visibleRepository.id());
    }
    List<RepositoryRecord> sources = new ArrayList<>();
    collect(visibleRepository, repositoryDao, new LinkedHashSet<>(), sources);
    return List.copyOf(sources);
  }

  static List<RepositoryRecord> swiftSources(
      RepositoryRecord visibleRepository,
      RepositoryDao repositoryDao) {
    return sources(visibleRepository, repositoryDao);
  }

  static List<RepositoryRecord> ansibleSources(
      RepositoryRecord visibleRepository,
      RepositoryDao repositoryDao) {
    return swiftSources(visibleRepository, repositoryDao);
  }

  static List<RepositoryRecord> condaSources(
      RepositoryRecord visibleRepository,
      RepositoryDao repositoryDao) {
    return swiftSources(visibleRepository, repositoryDao);
  }

  private static boolean supportsNestedGroups(RepositoryFormat format) {
    return format == RepositoryFormat.PUB
        || format == RepositoryFormat.COMPOSER
        || format == RepositoryFormat.HELM
        || format == RepositoryFormat.TERRAFORM
        || format == RepositoryFormat.SWIFT
        || format == RepositoryFormat.ANSIBLEGALAXY
        || format == RepositoryFormat.CONDA;
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
