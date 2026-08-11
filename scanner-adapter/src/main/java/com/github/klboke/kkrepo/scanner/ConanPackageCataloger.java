package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.springframework.stereotype.Component;

/** Stages a guarded Conan package archive and its canonical conaninfo sidecar for Syft. */
@Component
final class ConanPackageCataloger {
  private static final int MAX_CONANINFO_LINES = 16_384;

  Prepared prepare(
      Path archive,
      Path conanInfo,
      ResourceLimits limits,
      Path workspace,
      ScanDeadline deadline) {
    Path root = workspace.resolve("conan-root");
    try {
      Files.createDirectories(root);
      extractRegularFiles(archive, root, limits, deadline);
      Path canonical = root.resolve("conaninfo.txt");
      if (Files.exists(canonical)) {
        throw rejected(
            "CONAN_INFO_DUPLICATE",
            "Conan package archive must not contain the external conaninfo.txt sidecar");
      }
      validateConanInfo(conanInfo);
      Files.copy(conanInfo, canonical, StandardCopyOption.COPY_ATTRIBUTES);
      deadline.check();
      return new Prepared(root, Files.size(conanInfo));
    } catch (ScannerRequestException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new ScannerRequestException(
          "CONAN_STAGE_IO", "Unable to stage Conan scanner input", 503, true, failure);
    }
  }

  private static void extractRegularFiles(
      Path archive,
      Path root,
      ResourceLimits limits,
      ScanDeadline deadline) throws IOException {
    try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(archive))) {
      String compressor;
      try {
        compressor = CompressorStreamFactory.detect(input);
      } catch (org.apache.commons.compress.compressors.CompressorException failure) {
        throw rejected("CONAN_ARCHIVE_INVALID", "Conan package compressor is unsupported");
      }
      try (InputStream decoded = CompressorStreamFactory.getSingleton()
               .createCompressorInputStream(compressor, input, false);
           TarArchiveInputStream tar = new TarArchiveInputStream(decoded)) {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        Set<Path> targets = new HashSet<>();
        for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
          deadline.check();
          Path target = root.resolve(entry.getName()).normalize();
          if (!target.startsWith(root) || target.equals(root)) {
            throw rejected("CONAN_ARCHIVE_INVALID", "Conan package path escapes its root");
          }
          if (!targets.add(target)) {
            throw rejected("CONAN_ARCHIVE_INVALID", "Conan package contains duplicate paths");
          }
          if (entry.isDirectory()) {
            Files.createDirectories(target);
            continue;
          }
          // Links have already been range-checked by ArchiveGuard. Do not materialize them in the
          // scanner workspace, where they could otherwise change later filesystem traversal.
          if (entry.isSymbolicLink() || entry.isLink()) continue;
          if (!entry.isFile()) {
            throw rejected("CONAN_ARCHIVE_INVALID", "Conan package contains a special file");
          }
          if (entry.getSize() < 0 || entry.getSize() > limits.maxSingleFileBytes()) {
            throw rejected("CONAN_ARCHIVE_LIMIT", "Conan package file exceeds its limit");
          }
          Path parent = target.getParent();
          if (parent != null) Files.createDirectories(parent);
          long remaining = entry.getSize();
          try (var output = Files.newOutputStream(target)) {
            while (remaining > 0) {
              deadline.check();
              int count = tar.read(buffer, 0, (int) Math.min(buffer.length, remaining));
              if (count < 0) {
                throw rejected("CONAN_ARCHIVE_INVALID", "Conan package file is truncated");
              }
              if (count == 0) continue;
              output.write(buffer, 0, count);
              remaining -= count;
              written = Math.addExact(written, count);
              if (written > limits.maxUncompressedBytes()) {
                throw rejected("CONAN_ARCHIVE_LIMIT", "Conan package expansion exceeds its limit");
              }
            }
          }
        }
      } catch (org.apache.commons.compress.compressors.CompressorException failure) {
        throw rejected("CONAN_ARCHIVE_INVALID", "Conan package compressor is unsupported");
      } catch (ArithmeticException overflow) {
        throw rejected("CONAN_ARCHIVE_LIMIT", "Conan package expansion exceeds its limit");
      }
    }
  }

  private static void validateConanInfo(Path conanInfo) throws IOException {
    byte[] bytes = Files.readAllBytes(conanInfo);
    String value;
    try {
      value = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw rejected("CONAN_INFO_INVALID", "conaninfo.txt must be valid UTF-8");
    }
    if (value.indexOf('\0') >= 0 || value.lines().limit(MAX_CONANINFO_LINES + 1L).count()
        > MAX_CONANINFO_LINES) {
      throw rejected("CONAN_INFO_INVALID", "conaninfo.txt is malformed or exceeds its limit");
    }
  }

  private static ScannerRequestException rejected(String code, String message) {
    return new ScannerRequestException(code, message, 422, false);
  }

  record Prepared(Path scanRoot, long conanInfoBytes) {}
}
