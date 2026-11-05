package com.github.klboke.kkrepo.server.gitlfs;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Git LFS object publication. */
@Component
final class GitLfsStagingContentAdapter implements StagingContentAdapter {
  private final GitLfsHostedService gitLfsHosted;
  private final AssetDao assets;

  GitLfsStagingContentAdapter(GitLfsHostedService gitLfsHosted, AssetDao assets) {
    this.gitLfsHosted = gitLfsHosted;
    this.assets = assets;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.GITLFS;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (!path.startsWith("lfs/") || path.length() <= 4 || path.contains("..")) {
      return Optional.empty();
    }
    return Optional.of(path);
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    throw new MavenExceptions.BadRequestException(
        "Git LFS staging is driven through the Batch upload actions");
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed("Git LFS staging does not support deletions");
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "Git LFS staging does not support deletions";
    Object oid = item.attributes().get("gitLfsOid");
    if (oid != null && assets.findAssetByPath(repository.id(), String.valueOf(oid)).isPresent()) {
      return "Git LFS object already exists: " + oid;
    }
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    Object oid = item.attributes().get("gitLfsOid");
    if (oid == null) {
      throw new MavenExceptions.BadRequestException("Staged Git LFS item has no object id");
    }
    try (InputStream body = source.open()) {
      gitLfsHosted.restore(
          runtime, String.valueOf(oid), item.size() == null ? 0 : item.size(), body, actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
