package com.github.klboke.kkrepo.server.openvsx;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BoundedZipInputStreamTest {
  @Test
  void rejectsArchivesThatInflateBeyondTheBudget() throws Exception {
    byte[] archive = zip("extension/package.json", "0123456789".getBytes(
        java.nio.charset.StandardCharsets.UTF_8));

    try (BoundedZipInputStream zip = new BoundedZipInputStream(
        new ByteArrayInputStream(archive), 4)) {
      assertThrows(IOException.class, () -> {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          zip.readNBytes(1024);
        }
      });
    }
  }

  private static byte[] zip(String name, byte[] content) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(content);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }
}
