package com.github.klboke.kkrepo.server.gitlfs;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;

@Component
public class GitLfsMigrationWriter {
  private final GitLfsHostedService service;
  private final RepositoryRuntimeRegistry repositories;

  public GitLfsMigrationWriter(GitLfsHostedService service, RepositoryRuntimeRegistry repositories) {
    this.service = service;
    this.repositories = repositories;
  }

  public MigratedAsset write(RepositoryRecord repository, RepositoryDataMigrationAssetRecord source, InputStream body) {
    try (body) {
      String path = source.sourcePath();
      String oid = path != null && path.startsWith("/") ? path.substring(1) : path;
      if (!GitLfsProtocol.validOid(oid) || source.size() == null || source.size() < 0) {
        throw new IllegalArgumentException("Unsupported Nexus Git LFS object path or size");
      }
      var runtime = repositories.resolveById(repository.id()).orElseThrow();
      var asset = service.restore(runtime, oid, source.size(), body,
          source.sourceCreatedBy() == null ? "nexus-migration" : source.sourceCreatedBy(), source.sourceCreatedByIp());
      var blob = service.blob(asset);
      return new MigratedAsset(asset.componentId(), asset.id(), blob.id(), blob.objectKey());
    } catch (IOException error) {
      throw new java.io.UncheckedIOException("Unable to read Git LFS migration content", error);
    }
  }

  public record MigratedAsset(Long componentId, long assetId, long assetBlobId, String objectKey) {}
}
