package com.github.klboke.kkrepo.server.pub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class PubArchiveInspector {
  private static final int MAX_PUBSPEC_BYTES = 1024 * 1024;
  private static final int MAX_PUBSPEC_ALIASES = 50;

  private PubArchiveInspector() {
  }

  static PubPackageMetadata inspect(Path archive) {
    try (InputStream raw = Files.newInputStream(archive);
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      String rootPubspec = null;
      String firstLevelPubspec = null;
      int firstLevelPubspecs = 0;
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        String name = normalizeEntryName(entry.getName());
        if (name.isEmpty() && !entry.isFile()) {
          continue;
        }
        validateEntry(name);
        if (!entry.isFile()) {
          continue;
        }
        if (isRootPubspec(name)) {
          if (rootPubspec != null) {
            throw new PubExceptions.BadRequestException("Pub archive contains multiple pubspec.yaml files");
          }
          rootPubspec = readSmallText(tar);
        } else if (isFirstLevelPubspec(name)) {
          firstLevelPubspecs++;
          if (firstLevelPubspec == null) {
            firstLevelPubspec = readSmallText(tar);
          }
        }
      }
      String pubspec = rootPubspec;
      if (pubspec == null && firstLevelPubspec != null) {
        if (firstLevelPubspecs > 1) {
          throw new PubExceptions.BadRequestException("Pub archive contains multiple pubspec.yaml files");
        }
        pubspec = firstLevelPubspec;
      }
      if (pubspec == null) {
        throw new PubExceptions.BadRequestException("Pub archive must contain pubspec.yaml");
      }
      return PubPackageMetadata.fromPubspec(parsePubspec(pubspec));
    } catch (PubExceptions.BadRequestException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new PubExceptions.BadRequestException("Invalid Pub archive", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> parsePubspec(String text) {
    Object loaded = loadYaml().load(text == null ? "" : text);
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new PubExceptions.BadRequestException("pubspec.yaml must be a YAML mapping");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> {
      if (key != null) {
        result.put(String.valueOf(key), normalizeValue(value));
      }
    });
    return result;
  }

  private static Yaml loadYaml() {
    LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(MAX_PUBSPEC_BYTES);
    options.setMaxAliasesForCollections(MAX_PUBSPEC_ALIASES);
    return new Yaml(new SafeConstructor(options));
  }

  private static Object normalizeValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> nested = new LinkedHashMap<>();
      map.forEach((key, child) -> {
        if (key != null) {
          nested.put(String.valueOf(key), normalizeValue(child));
        }
      });
      return nested;
    }
    if (value instanceof Iterable<?> iterable) {
      java.util.List<Object> list = new java.util.ArrayList<>();
      for (Object item : iterable) {
        list.add(normalizeValue(item));
      }
      return list;
    }
    return value;
  }

  private static String readSmallText(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    int n;
    while ((n = in.read(buffer)) > 0) {
      total += n;
      if (total > MAX_PUBSPEC_BYTES) {
        throw new PubExceptions.BadRequestException("pubspec.yaml is too large");
      }
      out.write(buffer, 0, n);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static boolean isRootPubspec(String entryName) {
    return entryName.equals("pubspec.yaml");
  }

  private static boolean isFirstLevelPubspec(String entryName) {
    return entryName.endsWith("/pubspec.yaml")
        && entryName.indexOf('/') == entryName.lastIndexOf('/');
  }

  private static String normalizeEntryName(String value) {
    String name = value == null ? "" : value.replace('\\', '/');
    while (name.startsWith("./")) {
      name = name.substring(2);
    }
    return name;
  }

  private static void validateEntry(String name) {
    if (name.isBlank()
        || name.startsWith("/")
        || name.contains("\0")) {
      throw new PubExceptions.BadRequestException("Pub archive contains an unsafe path: " + name);
    }
    for (String segment : name.split("/", -1)) {
      if (segment.equals("..")) {
        throw new PubExceptions.BadRequestException("Pub archive contains an unsafe path: " + name);
      }
    }
  }
}
