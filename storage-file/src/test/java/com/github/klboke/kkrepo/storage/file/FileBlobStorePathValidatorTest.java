package com.github.klboke.kkrepo.storage.file;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStorePathValidatorTest {
  @TempDir
  Path tempDir;

  @Test
  void explainsWhenBlobStoreDirectoryCannotBeCreated() throws IOException {
    Path blockingFile = tempDir.resolve("blocking-file");
    Files.writeString(blockingFile, "not a directory");
    Path blobStorePath = blockingFile.resolve("default");

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new FileBlobStorePathValidator().validateWritableDirectory(blobStorePath));

    assertTrue(error.getMessage().contains("cannot be created or written"));
    assertTrue(error.getMessage().contains(blobStorePath.toString()));
    assertInstanceOf(IOException.class, error.getCause());
  }
}
