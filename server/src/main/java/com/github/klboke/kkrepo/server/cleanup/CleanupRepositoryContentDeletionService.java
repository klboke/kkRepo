package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.browse.BrowseContentDeleteController;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService.CleanupDeleteSubject;
import com.github.klboke.kkrepo.server.docker.DockerManifestStore;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import java.util.List;
import org.springframework.stereotype.Service;

/** Dispatches cleanup subjects through the same application-level protocol deletion adapters. */
@Service
public class CleanupRepositoryContentDeletionService
    implements RepositoryContentDeletionService {
  private final BrowseContentDeleteController browseDeletion;
  private final RepositoryRuntimeRegistry runtimes;
  private final DockerManifestStore dockerManifests;
  private final NpmHostedService npmHosted;

  public CleanupRepositoryContentDeletionService(
      BrowseContentDeleteController browseDeletion,
      RepositoryRuntimeRegistry runtimes,
      DockerManifestStore dockerManifests,
      NpmHostedService npmHosted) {
    this.browseDeletion = browseDeletion;
    this.runtimes = runtimes;
    this.dockerManifests = dockerManifests;
    this.npmHosted = npmHosted;
  }

  @Override
  public int deleteForCleanup(
      String repository,
      String subjectKind,
      long subjectId,
      String path,
      String actorId) {
    RepositoryRuntime runtime = runtimes.resolve(repository)
        .orElseThrow(() -> new CleanupValidationException(
            "cleanup repository runtime was not found: " + repository));
    if (runtime.format() == RepositoryFormat.NPM
        && runtime.type() == RepositoryType.HOSTED
        && "COMPONENT".equals(subjectKind)) {
      return npmHosted.deleteTarballForCleanup(runtime, path, actorId);
    }
    if (runtime.format() != RepositoryFormat.DOCKER) {
      return browseDeletion.deleteForCleanup(
          repository, subjectKind, subjectId, path, actorId);
    }
    if (!"DOCKER_MANIFEST".equals(subjectKind) || path == null) {
      throw new CleanupValidationException("invalid Docker cleanup subject");
    }
    int separator = path.lastIndexOf('@');
    if (separator <= 0 || separator == path.length() - 1) {
      throw new CleanupValidationException("invalid Docker cleanup reference");
    }
    return dockerManifests.deleteReference(
        runtime, path.substring(0, separator), path.substring(separator + 1));
  }

  @Override
  public List<Integer> deleteBatchForCleanup(
      String repository, List<CleanupDeleteSubject> subjects, String actorId) {
    if (subjects == null || subjects.isEmpty()) return List.of();
    RepositoryRuntime runtime = runtimes.resolve(repository)
        .orElseThrow(() -> new CleanupValidationException(
            "cleanup repository runtime was not found: " + repository));
    if (runtime.format() == RepositoryFormat.NPM
        && runtime.type() == RepositoryType.HOSTED
        && subjects.stream().allMatch(subject -> "COMPONENT".equals(subject.subjectKind()))) {
      return npmHosted.deleteTarballsForCleanup(
          runtime, subjects.stream().map(CleanupDeleteSubject::path).toList(), actorId);
    }
    return RepositoryContentDeletionService.super.deleteBatchForCleanup(
        repository, subjects, actorId);
  }
}
