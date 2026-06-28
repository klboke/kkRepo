package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.cargo.CargoCrateName;
import com.github.klboke.kkrepo.protocol.cargo.CargoVersions;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CargoPublishPayload implements AutoCloseable {
  private static final int MAX_METADATA_BYTES = 1024 * 1024;
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  private final CargoPackageMetadata metadata;
  private final Path crateFile;
  private final long crateLength;

  private CargoPublishPayload(CargoPackageMetadata metadata, Path crateFile, long crateLength) {
    this.metadata = metadata;
    this.crateFile = crateFile;
    this.crateLength = crateLength;
  }

  static CargoPublishPayload read(ObjectMapper objectMapper, InputStream body) {
    Path tmp = null;
    try {
      long jsonLength = readU32Le(body);
      if (jsonLength <= 0 || jsonLength > MAX_METADATA_BYTES) {
        throw new CargoExceptions.BadRequestException("Cargo publish metadata length is invalid");
      }
      byte[] metadataBytes = body.readNBytes((int) jsonLength);
      if (metadataBytes.length != jsonLength) {
        throw new EOFException("Cargo publish metadata ended early");
      }
      Map<String, Object> json = objectMapper.readValue(metadataBytes, JSON_MAP);
      CargoPackageMetadata metadata = CargoPackageMetadata.fromPublishJson(json);
      long crateLength = readU32Le(body);
      if (crateLength <= 0) {
        throw new CargoExceptions.BadRequestException("Cargo publish crate length is invalid");
      }
      tmp = Files.createTempFile("kkrepo-cargo-", ".crate");
      try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
        copyExact(body, out, crateLength);
      }
      CargoCrateInspector.Manifest manifest = CargoCrateInspector.inspect(tmp);
      if (!metadata.name().equals(manifest.name()) || !metadata.version().equals(manifest.version())) {
        throw new CargoExceptions.BadRequestException(
            "Cargo.toml package identity does not match publish metadata");
      }
      return new CargoPublishPayload(metadata, tmp, crateLength);
    } catch (CargoExceptions.BadRequestException e) {
      TempBlobFiles.deleteQuietly(tmp);
      throw e;
    } catch (IOException e) {
      TempBlobFiles.deleteQuietly(tmp);
      throw new CargoExceptions.BadRequestException("Invalid Cargo publish body", e);
    }
  }

  CargoPackageMetadata metadata() {
    return metadata;
  }

  Path crateFile() {
    return crateFile;
  }

  long crateLength() {
    return crateLength;
  }

  @Override
  public void close() {
    TempBlobFiles.deleteQuietly(crateFile);
  }

  private static long readU32Le(InputStream in) throws IOException {
    int b0 = in.read();
    int b1 = in.read();
    int b2 = in.read();
    int b3 = in.read();
    if ((b0 | b1 | b2 | b3) < 0) {
      throw new EOFException("Cargo publish length prefix ended early");
    }
    return ((long) b0 & 0xff)
        | (((long) b1 & 0xff) << 8)
        | (((long) b2 & 0xff) << 16)
        | (((long) b3 & 0xff) << 24);
  }

  private static void copyExact(InputStream in, OutputStream out, long length) throws IOException {
    byte[] buffer = new byte[TempBlobFiles.responseBufferSize()];
    long remaining = length;
    while (remaining > 0) {
      int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) {
        throw new EOFException("Cargo publish crate payload ended early");
      }
      out.write(buffer, 0, read);
      remaining -= read;
    }
  }

  record CargoPackageMetadata(
      String name,
      String normalizedName,
      String version,
      String versionKey,
      String description,
      Map<String, Object> publishJson) {

    static CargoPackageMetadata fromPublishJson(Map<String, Object> json) {
      String name = text(json.get("name"));
      String version = CargoVersions.requireVersion(text(json.get("vers")));
      CargoCrateName crateName = CargoCrateName.parse(name);
      return new CargoPackageMetadata(
          crateName.value(),
          crateName.lowerDashUnderscoreKey(),
          version,
          CargoVersions.uniquenessKey(version),
          text(json.get("description")),
          new LinkedHashMap<>(json));
    }

    static CargoPackageMetadata fromManifest(CargoCrateInspector.Manifest manifest) {
      return fromPublishJson(manifest.publishJson());
    }

    Map<String, Object> indexEntry(String checksum, boolean yanked) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", name);
      entry.put("vers", version);
      entry.put("deps", indexDependencies(publishJson.get("deps")));
      entry.put("cksum", checksum);
      entry.put("features", mapOrEmpty(publishJson.get("features")));
      entry.put("yanked", yanked);
      entry.put("links", publishJson.containsKey("links") ? publishJson.get("links") : null);
      Object features2 = publishJson.get("features2");
      if (features2 instanceof Map<?, ?>) {
        entry.put("features2", features2);
        entry.put("v", 2);
      } else {
        entry.put("v", 0);
      }
      Object rustVersion = publishJson.get("rust_version");
      if (rustVersion != null) {
        entry.put("rust_version", rustVersion);
      }
      return entry;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> indexDependencies(Object raw) {
      if (!(raw instanceof List<?> deps)) {
        return List.of();
      }
      List<Object> result = new ArrayList<>(deps.size());
      for (Object item : deps) {
        if (!(item instanceof Map<?, ?> dep)) {
          continue;
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        String originalName = text(dep.get("name"));
        String explicit = text(dep.get("explicit_name_in_toml"));
        mapped.put("name", explicit == null ? originalName : explicit);
        mapped.put("req", text(dep.get("version_req")));
        mapped.put("features", defaultValue(dep, "features", List.of()));
        mapped.put("optional", defaultValue(dep, "optional", false));
        mapped.put("default_features", defaultValue(dep, "default_features", true));
        mapped.put("target", dep.get("target"));
        mapped.put("kind", defaultValue(dep, "kind", "normal"));
        mapped.put("registry", dep.get("registry"));
        mapped.put("package", explicit == null ? null : originalName);
        result.add(mapped);
      }
      return result;
    }

    private static Object defaultValue(Map<?, ?> map, String key, Object fallback) {
      Object value = map.get(key);
      return value == null ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object value) {
      return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
      if (value == null) {
        return null;
      }
      String text = String.valueOf(value).trim();
      return text.isBlank() ? null : text;
    }
  }
}
