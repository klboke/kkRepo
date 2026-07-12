package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComposerArchiveInspectorTest {
  @TempDir
  Path tempDir;

  @Test
  void readsComposerJsonFromSingleTopLevelDirectory() throws Exception {
    Path archive = zipEntries(
        "package/", null,
        "package/composer.json", """
            {"name":"company/example","version":"1.2.3","require":{"php":">=8.2"}}
            """,
        "package/src/Example.php", "<?php");
    ComposerArchiveInspector.Inspected inspected =
        new ComposerArchiveInspector(new ObjectMapper()).inspect(archive, "example.zip", null, null);

    assertEquals("company/example", inspected.name());
    assertEquals("1.2.3", inspected.version());
    assertEquals("company/example", inspected.metadata().get("name"));
  }

  @Test
  void requiresExplicitVersionWhenArchiveOmitsIt() throws Exception {
    Path archive = zip("composer.json", "{\"name\":\"company/example\"}");
    ComposerArchiveInspector inspector = new ComposerArchiveInspector(new ObjectMapper());

    assertThrows(ComposerExceptions.BadRequestException.class,
        () -> inspector.inspect(archive, "example.zip", null, null));
    assertEquals("2.0.0", inspector.inspect(archive, "example.zip", null, "2.0.0").version());
  }

  @Test
  void rejectsTraversalEntries() throws Exception {
    Path archive = zip("../composer.json", "{\"name\":\"company/example\",\"version\":\"1.0.0\"}");
    assertThrows(ComposerExceptions.BadRequestException.class,
        () -> new ComposerArchiveInspector(new ObjectMapper()).inspect(archive, "example.zip", null, null));
  }

  @Test
  void rejectsFilesOutsideComposerJsonTopLevelDirectory() throws Exception {
    Path archive = zipEntries(
        "package/composer.json", "{\"name\":\"company/example\",\"version\":\"1.0.0\"}",
        "other/readme.md", "unexpected");

    assertThrows(ComposerExceptions.BadRequestException.class,
        () -> new ComposerArchiveInspector(new ObjectMapper()).inspect(archive, "example.zip", null, null));
  }

  @Test
  void rejectsUppercasePublishedPackageName() throws Exception {
    Path archive = zip("composer.json", "{\"name\":\"Company/Example\",\"version\":\"1.0.0\"}");

    assertThrows(ComposerExceptions.BadRequestException.class,
        () -> new ComposerArchiveInspector(new ObjectMapper()).inspect(archive, "example.zip", null, null));
  }

  private Path zip(String entryName, String body) throws IOException {
    return zipEntries(entryName, body);
  }

  private Path zipEntries(String... entries) throws IOException {
    Path archive = tempDir.resolve("fixture.zip");
    try (OutputStream out = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(out)) {
      for (int i = 0; i < entries.length; i += 2) {
        zip.putNextEntry(new ZipEntry(entries[i]));
        if (entries[i + 1] != null) zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return archive;
  }
}
