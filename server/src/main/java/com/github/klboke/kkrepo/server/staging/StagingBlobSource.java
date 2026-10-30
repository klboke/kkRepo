package com.github.klboke.kkrepo.server.staging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Opens the staged blob so a format adapter can replay its normal publication path. */
public interface StagingBlobSource {
  InputStream open();

  /** Materializes the blob into a temporary file owned by the caller. */
  default Path materialize(String suffix) {
    try {
      Path file = com.github.klboke.kkrepo.server.blob.TempBlobFiles.createTempFile(
          null, "staging-replay-", suffix == null ? ".tmp" : suffix);
      try (InputStream input = open()) {
        Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
      }
      return file;
    } catch (IOException error) {
      throw new IllegalStateException("Unable to materialize staged blob", error);
    }
  }
}
