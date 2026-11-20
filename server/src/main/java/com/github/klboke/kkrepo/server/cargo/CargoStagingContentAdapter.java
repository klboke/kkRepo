package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.protocol.cargo.CargoCrateName;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.protocol.cargo.CargoPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Cargo crate publication. */
@Component
final class CargoStagingContentAdapter implements StagingContentAdapter {
  private static final String PUBLISH_PATH = "api/v1/crates/new";

  private static final CargoPathParser PARSER = new CargoPathParser();

  private final RawHostedService hosted;
  private final CargoHostedService cargoHosted;
  private final ObjectMapper objectMapper;
  private final StagingDao staging;

  CargoStagingContentAdapter(
      RawHostedService hosted,
      CargoHostedService cargoHosted,
      ObjectMapper objectMapper,
      StagingDao staging) {
    this.hosted = hosted;
    this.cargoHosted = cargoHosted;
    this.objectMapper = objectMapper;
    this.staging = staging;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.CARGO;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    // Sparse-index clients ask for /crates/{name}/{version}/download while staged crates are
    // stored under the canonical archive name, so both spellings resolve to the same item.
    CargoPath parsed = PARSER.parse(path);
    if (parsed.kind() == CargoPath.Kind.DOWNLOAD
        && parsed.crateName() != null && parsed.version() != null) {
      return Optional.of(CargoAssetWriter.cratePath(parsed.crateName(), parsed.version()));
    }
    return Optional.of(path);
  }

  /**
   * Serves the sparse index file from live versions plus staged publications, so a Cargo client can
   * resolve a crate version that is still staged.
   */
  @Override
  public Optional<MavenResponse> generatedMetadata(
      RepositoryRuntime runtime, long sessionId, String rawPath, boolean headOnly) {
    CargoPath path = PARSER.parse(rawPath);
    if (path.kind() != CargoPath.Kind.INDEX || path.crateName() == null) {
      return Optional.empty();
    }
    Map<String, Map<String, Object>> byVersion = new LinkedHashMap<>();
    for (Map<String, Object> entry : cargoHosted.indexEntries(runtime, path.crateName())) {
      Object version = entry.get("vers");
      if (version != null) byVersion.put(String.valueOf(version), entry);
    }
    String crateKey = CargoCrateName.parse(path.crateName()).lowerDashUnderscoreKey();
    for (StagingItemRecord item : staging.listItems(sessionId)) {
      if (item.repositoryId() != runtime.id() || item.format() != RepositoryFormat.CARGO) {
        continue;
      }
      CargoPath staged = PARSER.parse(item.logicalPath());
      if (staged.kind() != CargoPath.Kind.DOWNLOAD || staged.crateName() == null
          || !crateKey.equals(
              CargoCrateName.parse(staged.crateName()).lowerDashUnderscoreKey())) {
        continue;
      }
      if (item.attributes().get("indexEntry") instanceof Map<?, ?> raw) {
        Map<String, Object> entry = new LinkedHashMap<>();
        raw.forEach((key, value) -> entry.put(String.valueOf(key), value));
        Object version = entry.get("vers");
        if (version != null) byVersion.put(String.valueOf(version), entry);
      }
    }
    if (byVersion.isEmpty()) {
      return Optional.empty();
    }
    StringBuilder body = new StringBuilder();
    for (Map<String, Object> entry : byVersion.values()) {
      try {
        body.append(objectMapper.writeValueAsString(entry)).append('\n');
      } catch (IOException error) {
        throw new UncheckedIOException("Unable to serialize Cargo index entry", error);
      }
    }
    return Optional.of(CargoResponses.text(body.toString(), null, null, headOnly));
  }

  @Override
  public boolean supportsPublication(String rawPath) {
    return PUBLISH_PATH.equals(normalizePath(rawPath).orElse(""));
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "cargo-staging-", ".publish");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      CargoPackageMetadata metadata;
      try (CargoPublishPayload payload = CargoPublishPayload.read(
          objectMapper, Files.newInputStream(temp))) {
        metadata = payload.metadata();
      }
      String path = CargoAssetWriter.cratePath(metadata.name(), metadata.version());
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, path, temp, "application/octet-stream", Map.of(), actor, ip);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("cargoPublishPath", PUBLISH_PATH);
      attributes.put("indexEntry", metadata.indexEntry(blob.sha256(), false));
      return new StagedUpload(blob, path, "application/octet-stream", attributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Invalid Cargo publish body", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    try (InputStream body = request.getInputStream()) {
      return Optional.of(stage(
          runtime, requestedPath, body, request.getContentType(), actor, ip));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed("Cargo staging does not support deletions");
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "Cargo staging does not support deletions";
    if (live != null) return "Cargo crate version already exists: " + item.logicalPath();
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    try (InputStream body = source.open()) {
      cargoHosted.publish(runtime, body, actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
