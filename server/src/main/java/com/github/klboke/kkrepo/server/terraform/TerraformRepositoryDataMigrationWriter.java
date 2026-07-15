package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Replays Nexus Terraform archives through the normal validation and atomic publication path. */
@Component
public class TerraformRepositoryDataMigrationWriter {
  private static final Pattern NEXUS_PROVIDER_ARCHIVE = Pattern.compile(
      "^v1/providers/([^/]+)/([^/]+)/([^/]+)/download/([^/]+)/([^/]+)/([^/]+\\.zip)$",
      Pattern.CASE_INSENSITIVE);

  private final TerraformService service;
  private final TerraformAssetSupport assets;
  private final TerraformRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final TerraformPathParser paths = new TerraformPathParser();

  TerraformRepositoryDataMigrationWriter(
      TerraformService service,
      TerraformAssetSupport assets,
      TerraformRegistryDao registry,
      RepositoryRuntimeRegistry runtimes) {
    this.service = service;
    this.assets = assets;
    this.registry = registry;
    this.runtimes = runtimes;
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      String responseContentType,
      boolean validateSize) {
    if (repository.format() != RepositoryFormat.TERRAFORM) {
      throw new IllegalArgumentException("Terraform migration writer requires a Terraform repository");
    }
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "Terraform migration target repository is unavailable: " + repository.name()));
    SourceTarget target = target(source.sourcePath());
    Optional<AssetRecord> existing = assets.find(runtime, target.assetPath());
    if (existing.isPresent() && target.module()) {
      return verifiedExisting(source, existing.get(), validateSize);
    }
    if (existing.isPresent() && !target.module()
        && registry.listProviderPlatforms(
                runtime.id(), target.path().namespace(), target.path().name(), target.path().version()).stream()
            .anyMatch(platform -> platform.os().equals(target.path().os())
                && platform.arch().equals(target.path().arch())
                && platform.assetPath().equals(target.assetPath()))) {
      return verifiedExisting(source, existing.get(), validateSize);
    }

    Path buffered = null;
    try {
      buffered = Files.createTempFile("kkrepo-terraform-migration-", target.module() ? ".archive" : ".zip");
      long size = Files.copy(body, buffered, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      if (validateSize && source.size() != null && source.size() >= 0 && source.size() != size) {
        throw new IllegalStateException("Terraform migration size mismatch for " + source.sourcePath()
            + ": expected " + source.size() + ", actual " + size);
      }
      try (InputStream replay = Files.newInputStream(buffered)) {
        service.putForMigration(
            runtime,
            target.path(),
            replay,
            firstNonBlank(responseContentType, source.contentType()),
            target.module() ? null : "attachment; filename=\"" + target.filename() + "\"",
            firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
            source.sourceCreatedByIp());
      }
      AssetRecord stored = assets.find(runtime, target.assetPath())
          .orElseThrow(() -> new IllegalStateException(
              "Terraform migration did not publish " + target.assetPath()));
      return verifiedExisting(source, stored, validateSize);
    } catch (IOException e) {
      throw new IllegalStateException("Failed buffering Terraform migration asset " + source.sourcePath(), e);
    } finally {
      if (buffered != null) {
        try {
          Files.deleteIfExists(buffered);
        } catch (IOException ignored) {
        }
      }
    }
  }

  public static boolean isMigratableTerraformPath(String rawPath) {
    try {
      String path = normalize(rawPath);
      TerraformPath parsed = new TerraformPathParser().parse(path);
      return parsed.kind() == TerraformPath.Kind.MODULE_ARCHIVE
          || NEXUS_PROVIDER_ARCHIVE.matcher(path).matches();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private MigratedAsset verifiedExisting(
      RepositoryDataMigrationAssetRecord source, AssetRecord asset, boolean validateSize) {
    AssetBlobRecord blob = assets.blob(asset);
    if (blob == null) {
      throw new IllegalStateException("Migrated Terraform asset has no blob: " + asset.path());
    }
    if (validateSize && source.size() != null && source.size() >= 0 && source.size() != blob.size()) {
      throw new IllegalStateException("Terraform migration size mismatch for " + source.sourcePath()
          + ": expected " + source.size() + ", actual " + blob.size());
    }
    return new MigratedAsset(asset.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private SourceTarget target(String rawPath) {
    String normalized = normalize(rawPath);
    TerraformPath module = paths.parse(normalized);
    if (module.kind() == TerraformPath.Kind.MODULE_ARCHIVE) {
      return new SourceTarget(module, normalized, module.filename(), true);
    }
    Matcher provider = NEXUS_PROVIDER_ARCHIVE.matcher(normalized);
    if (!provider.matches()) {
      throw new IllegalArgumentException("Unsupported Nexus Terraform migration asset: " + rawPath);
    }
    String uploadPath = "v1/providers/" + provider.group(1) + "/" + provider.group(2) + "/"
        + provider.group(3) + "/download/" + provider.group(4) + "/" + provider.group(5);
    TerraformPath parsed = paths.parse(uploadPath);
    if (parsed.kind() != TerraformPath.Kind.PROVIDER_DOWNLOAD) {
      throw new IllegalArgumentException("Invalid Nexus Terraform provider asset: " + rawPath);
    }
    TerraformPathParser.requireFilename(provider.group(6));
    String targetPath = "v1/providers/" + parsed.namespace() + "/" + parsed.name() + "/"
        + parsed.version() + "/package/" + parsed.os() + "/" + provider.group(6);
    return new SourceTarget(parsed, targetPath, provider.group(6), false);
  }

  private static String normalize(String path) {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) return first;
    return second == null || second.isBlank() ? "application/octet-stream" : second;
  }

  public record MigratedAsset(
      Long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) {
  }

  private record SourceTarget(
      TerraformPath path, String assetPath, String filename, boolean module) {
  }
}
