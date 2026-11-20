package com.github.klboke.kkrepo.server.openvsx;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxProtocol;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/** Blob/asset plumbing for Open VSX hosted and proxy repositories. */
@Component
final class OpenVsxAssetSupport {
  private static final int MAX_EXTRACTED_BYTES = 64 * 1024 * 1024;
  private static final int MAX_ARCHIVE_ENTRIES = 100_000;
  private static final int MAX_LISTED_CHILDREN = 10_000;
  private static final long MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;

  private final AssetDao assets;
  private final RawHostedService hosted;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  OpenVsxAssetSupport(
      AssetDao assets,
      RawHostedService hosted,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.assets = assets;
    this.hosted = hosted;
    this.objectMapper = objectMapper;
  }

  boolean storeHosted(
      RepositoryRuntime runtime,
      OpenVsxVsixInspector.Inspected inspected,
      Path file,
      String path,
      String sha1,
      String sha256,
      long size,
      String actor,
      String ip) {
    ComponentRecord component = new OpenVsxComponentFactory().component(
        runtime, inspected, path, sha1, sha256, size, java.time.Instant.now());
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("openvsxSha1", sha1);
    attributes.put("openvsxSha256", sha256);
    attributes.put("openvsxSize", size);
    return hosted.putInternalWithComponentFileAtBrowsePathIfAbsent(
        runtime, path, file, OpenVsxProtocol.VSIX_MEDIA_TYPE, attributes, actor, ip,
        component, path);
  }

  MavenResponse serve(RepositoryRuntime runtime, String path, boolean headOnly) {
    return hosted.getInternal(runtime, path, headOnly);
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  void delete(RepositoryRuntime runtime, String path) {
    hosted.deleteInternal(runtime, path);
  }

  /**
   * Serves a package member from the immutable VSIX. The archive is opened lazily, read with a
   * bounded buffer, and never extracted to durable storage.
   */
  MavenResponse extract(
      RepositoryRuntime runtime, String path, String memberPath, boolean headOnly) {
    String member = memberPath == null ? "" : memberPath;
    while (member.startsWith("/")) member = member.substring(1);
    if (member.isEmpty() || member.endsWith("/") || member.equals("extension")) {
      return list(runtime, path, member, headOnly);
    }
    needArchive(runtime, path);
    MavenResponse archive = hosted.getInternal(runtime, path, false);
    try (InputStream body = archive.body()) {
      if (body == null) throw new MavenExceptions.MavenNotFoundException(path);
      try (ZipInputStream zip = new BoundedZipInputStream(body, MAX_ARCHIVE_BYTES)) {
        ZipEntry entry;
        int entries = 0;
        long totalBytes = 0;
        while ((entry = zip.getNextEntry()) != null) {
          if (++entries > MAX_ARCHIVE_ENTRIES) {
            throw new MavenExceptions.BadRequestException("VSIX contains too many entries");
          }
          if (entry.getSize() > 0) {
            if (entry.getSize() > MAX_ARCHIVE_BYTES - totalBytes) {
              throw new MavenExceptions.BadRequestException("VSIX payload is too large");
            }
            totalBytes += entry.getSize();
          }
          if (!entry.getName().equals(member)) continue;
          byte[] bytes = readBounded(zip, MAX_EXTRACTED_BYTES);
          if (headOnly) {
            return MavenResponse.noBody(200, bytes.length, contentType(member),
                sha1(bytes), null);
          }
          return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
              contentType(member), sha1(bytes), null);
        }
      }
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unreadable VSIX member", error);
    }
    throw new MavenExceptions.MavenNotFoundException(member);
  }

  private MavenResponse list(
      RepositoryRuntime runtime, String path, String directory, boolean headOnly) {
    needArchive(runtime, path);
    MavenResponse archive = hosted.getInternal(runtime, path, false);
    String prefix = directory.isEmpty() ? "" : directory.endsWith("/") ? directory : directory + "/";
    List<String> children = new ArrayList<>();
    try (InputStream body = archive.body()) {
      if (body == null) throw new MavenExceptions.MavenNotFoundException(path);
      try (ZipInputStream zip = new BoundedZipInputStream(body, MAX_ARCHIVE_BYTES)) {
        ZipEntry entry;
        int entries = 0;
        long totalBytes = 0;
        while ((entry = zip.getNextEntry()) != null) {
          if (++entries > MAX_ARCHIVE_ENTRIES) {
            throw new MavenExceptions.BadRequestException("VSIX contains too many entries");
          }
          if (entry.getSize() > 0) {
            if (entry.getSize() > MAX_ARCHIVE_BYTES - totalBytes) {
              throw new MavenExceptions.BadRequestException("VSIX payload is too large");
            }
            totalBytes += entry.getSize();
          }
          String name = entry.getName();
          if (!name.startsWith(prefix)) continue;
          String rest = name.substring(prefix.length());
          if (rest.isEmpty()) continue;
          int slash = rest.indexOf('/');
          String child = slash < 0 ? rest : rest.substring(0, slash);
          if (!children.contains(child)) {
            children.add(child);
            if (children.size() > MAX_LISTED_CHILDREN) {
              throw new MavenExceptions.BadRequestException("VSIX directory has too many entries");
            }
          }
        }
      }
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unreadable VSIX archive", error);
    }
    List<String> urls = children.stream()
        .map(child -> directory.isEmpty() ? child : directory + "/" + child)
        .toList();
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(urls);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, "application/json", null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json",
        null, null);
  }

  private void needArchive(RepositoryRuntime runtime, String path) {
    if (find(runtime, path).isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      total += read;
      if (total > limit) {
        throw new MavenExceptions.BadRequestException("VSIX member is too large");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static String contentType(String path) {
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".md") || lower.endsWith(".txt")) return "text/markdown";
    if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".svg")) return "image/svg+xml";
    if (lower.endsWith(".vsixmanifest")) return "text/xml";
    if (lower.endsWith(".vsix")) return OpenVsxProtocol.VSIX_MEDIA_TYPE;
    return "application/octet-stream";
  }

  private static String sha1(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
