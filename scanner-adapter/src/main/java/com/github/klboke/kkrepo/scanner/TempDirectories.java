package com.github.klboke.kkrepo.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;

final class TempDirectories {
  private TempDirectories() {}

  static void deleteRecursively(Path directory) {
    if (directory == null || !Files.exists(directory)) return;
    try {
      Files.walkFileTree(directory, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
            throws IOException {
          Files.deleteIfExists(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException failure)
            throws IOException {
          Files.deleteIfExists(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ignored) {
      // Temp cleanup is best-effort; the container's ephemeral filesystem is the final boundary.
    }
  }
}
