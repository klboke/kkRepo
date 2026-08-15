package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.alpine.AlpineService;
import com.github.klboke.kkrepo.server.browse.BrowseContentDeleteController;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService.CleanupDeleteSubject;
import com.github.klboke.kkrepo.server.conan.ConanService;
import com.github.klboke.kkrepo.server.docker.DockerManifestStore;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Dispatches cleanup subjects through the same application-level protocol deletion adapters. */
@Service
public class CleanupRepositoryContentDeletionService
    implements RepositoryContentDeletionService {
  private final BrowseContentDeleteController browseDeletion;
  private final RepositoryRuntimeRegistry runtimes;
  private final DockerManifestStore dockerManifests;
  private final NpmHostedService npmHosted;
  private final AptService aptService;
  private ConanService conanService;
  private AlpineService alpineService;

  public CleanupRepositoryContentDeletionService(
      BrowseContentDeleteController browseDeletion,
      RepositoryRuntimeRegistry runtimes,
      DockerManifestStore dockerManifests,
      NpmHostedService npmHosted,
      AptService aptService) {
    this.browseDeletion = browseDeletion;
    this.runtimes = runtimes;
    this.dockerManifests = dockerManifests;
    this.npmHosted = npmHosted;
    this.aptService = aptService;
  }

  @Autowired(required = false)
  void setConanService(ConanService conanService) {
    this.conanService = conanService;
  }

  @Autowired(required = false)
  void setAlpineService(AlpineService alpineService) {
    this.alpineService = alpineService;
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
    if (runtime.format() == RepositoryFormat.CONAN
        && conanService != null
        && "COMPONENT".equals(subjectKind)) {
      return conanService.deleteComponentForCleanup(runtime, subjectId, actorId);
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
    if (runtime.format() == RepositoryFormat.APT
        && runtime.type() == RepositoryType.HOSTED
        && subjects.stream().allMatch(subject -> "COMPONENT".equals(subject.subjectKind()))) {
      return aptService.deleteComponentsForCleanup(
          runtime,
          subjects.stream().map(CleanupDeleteSubject::subjectId).toList(),
          "cleanup policy delete by " + actorId);
    }
    if (runtime.format() == RepositoryFormat.ALPINE
        && alpineService != null
        && runtime.type() == RepositoryType.HOSTED
        && subjects.stream().allMatch(subject -> "COMPONENT".equals(subject.subjectKind()))) {
      return alpineService.deleteComponentsForCleanup(
          runtime,
          subjects.stream().map(CleanupDeleteSubject::subjectId).toList(),
          "cleanup policy delete by " + actorId);
    }
    if (runtime.format() == RepositoryFormat.CONAN
        && conanService != null
        && subjects.stream().allMatch(subject -> "COMPONENT".equals(subject.subjectKind()))) {
      return subjects.stream()
          .map(subject -> conanService.deleteComponentForCleanup(
              runtime, subject.subjectId(), actorId))
          .toList();
    }
    return RepositoryContentDeletionService.super.deleteBatchForCleanup(
        repository, subjects, actorId);
  }
}
