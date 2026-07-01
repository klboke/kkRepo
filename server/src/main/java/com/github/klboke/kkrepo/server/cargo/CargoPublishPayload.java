package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
}
