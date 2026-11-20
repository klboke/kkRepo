package com.github.klboke.kkrepo.server.goartifact;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.goartifact.GoVersions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Staged Go module archive publication replayed through the normal Go writer. */
@Component
final class GoStagingContentAdapter extends PathReplayStagingAdapter {
  private final GoHostedService goHosted;
  private final StagingDao staging;

  GoStagingContentAdapter(
      RawHostedService hosted, GoHostedService goHosted, StagingDao staging) {
    super(hosted);
    this.goHosted = goHosted;
    this.staging = staging;
  }

  /**
   * Serves {@code /@v/list} from the live version set plus staged publications and deletions so
   * Go clients can resolve a staged release before promotion.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    GoPath path;
    try {
      path = GoPath.parse(rawPath);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (path.kind() != GoAssetKind.LIST) {
      return Optional.empty();
    }
    Set<String> versions = new LinkedHashSet<>();
    try {
      versions.addAll(goHosted.listVersions(runtime, path));
    } catch (MavenExceptions.MavenNotFoundException ignored) {
      // A module with no live release can still gain its first versions through staging.
    }
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.GO) {
        continue;
      }
      GoPath staged;
      try {
        staged = GoPath.parse(item.logicalPath());
      } catch (IllegalArgumentException invalid) {
        continue;
      }
      if (!path.module().equals(staged.module()) || staged.version() == null) {
        continue;
      }
      if (StagingDao.ITEM_DELETE.equals(item.action())) {
        versions.remove(staged.version());
      } else {
        versions.add(staged.version());
      }
    }
    List<String> candidates = versions.stream().filter(GoVersions::isCanonical).toList();
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(GoResponses.text(
        String.join("\n", GoVersions.listVersions(candidates)), null, headOnly));
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.GO;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    return Optional.of(path);
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> goHosted.publish(
        runtime, item.logicalPath(), body, actor, ip));
  }
}
