package com.github.klboke.kkrepo.storage.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.springframework.stereotype.Component;

@Component
public class FileBlobStorePathValidator {
  public void validate(FileBlobStoreConfig config, Collection<FileBlobStoreConfig> existingStores) {
    validateUniquePath(config, existingStores);
    validateWritableDirectory(config.root());
  }

  public void validateWritableDirectory(Path root) {
    try {
      if (Files.exists(root) && !Files.isDirectory(root)) {
        throw new IllegalArgumentException("File blob store path is not a directory: " + root);
      }
      Files.createDirectories(root);
      if (!Files.isWritable(root)) {
        throw new IllegalArgumentException("File blob store path is not writable: " + root);
      }
    } catch (IOException e) {
      String detail = e.getMessage();
      String suffix = detail == null || detail.isBlank() ? "" : " (" + detail + ")";
      throw new IllegalArgumentException(
          "File blob store path cannot be created or written: " + root + suffix,
          e);
    }
  }

  private void validateUniquePath(FileBlobStoreConfig config, Collection<FileBlobStoreConfig> existingStores) {
    for (FileBlobStoreConfig existing : existingStores) {
      if (existing.id() == config.id()) {
        continue;
      }
      Path existingRoot = existing.root();
      Path candidateRoot = config.root();
      if (candidateRoot.equals(existingRoot)
          || candidateRoot.startsWith(existingRoot)
          || existingRoot.startsWith(candidateRoot)) {
        throw new IllegalArgumentException(
            "File blob store path overlaps existing blob store '" + existing.name() + "': " + existingRoot);
      }
    }
  }
}
