package com.github.klboke.kkrepo.server.ansible;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyNames;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyVersions;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, non-executing validator for untrusted Ansible collection tarballs. */
@Component
final class AnsibleCollectionArchiveInspector {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
  private static final Pattern REQUIRES_ANSIBLE = Pattern.compile(
      "(?m)^\\s*requires_ansible\\s*:\\s*['\"]?([^'\"#\\r\\n]+?)['\"]?\\s*(?:#.*)?$");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
  private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
  private static final int MAX_FILES_BYTES = 16 * 1024 * 1024;
  private static final int MAX_RUNTIME_BYTES = 1024 * 1024;
  private static final int MAX_PROTOCOL_METADATA_BYTES = 64 * 1024;
  private static final long RATIO_FLOOR = 1024L * 1024;

  private final ObjectMapper objectMapper;
  private final long maxCompressedBytes;
  private final long maxExpandedBytes;
  private final long maxEntryBytes;
  private final int maxEntries;
  private final long maxCompressionRatio;
  private final long maxInspectionNanos;
  private final Semaphore inspectionPermits;
  private final long permitWaitMillis;

  @Autowired
  AnsibleCollectionArchiveInspector(
      ObjectMapper objectMapper,
      @Value("${kkrepo.ansible.archive.max-compressed-bytes:1073741824}") long maxCompressedBytes,
      @Value("${kkrepo.ansible.archive.max-expanded-bytes:2147483648}") long maxExpandedBytes,
      @Value("${kkrepo.ansible.archive.max-entry-bytes:1073741824}") long maxEntryBytes,
      @Value("${kkrepo.ansible.archive.max-entries:100000}") int maxEntries,
      @Value("${kkrepo.ansible.archive.max-compression-ratio:200}") long maxCompressionRatio,
      @Value("${kkrepo.ansible.archive.max-inspection-seconds:120}") long maxInspectionSeconds,
      @Value("${kkrepo.ansible.archive.max-concurrent-inspections:4}") int maxConcurrentInspections,
      @Value("${kkrepo.ansible.archive.inspection-permit-wait-ms:5000}") long permitWaitMillis) {
    this.objectMapper = objectMapper;
    this.maxCompressedBytes = Math.max(1L, maxCompressedBytes);
    this.maxExpandedBytes = Math.max(1L, maxExpandedBytes);
    this.maxEntryBytes = Math.max(1L, Math.min(maxEntryBytes, this.maxExpandedBytes));
    this.maxEntries = Math.max(1, maxEntries);
    this.maxCompressionRatio = Math.max(1L, maxCompressionRatio);
    this.maxInspectionNanos = Duration.ofSeconds(Math.max(1L, maxInspectionSeconds)).toNanos();
    this.inspectionPermits = new Semaphore(Math.max(1, maxConcurrentInspections), true);
    this.permitWaitMillis = Math.max(1L, permitWaitMillis);
  }

  AnsibleCollectionArchiveInspector(ObjectMapper objectMapper, long compressed, long expanded, int entries) {
    this(objectMapper, compressed, expanded, expanded, entries, 200, 120, 4, 5000);
  }

  InspectedCollection inspect(InputStream body) {
    boolean permit = acquireInspectionPermit();
    Path buffered = null;
    try {
      buffered = Files.createTempFile("kkrepo-ansible-", ".tar.gz");
      MessageDigest digest = sha256();
      long compressed = copyBounded(body, buffered, digest);
      ensureGzipMagic(buffered);
      ArchiveEntries archive = inspectTar(buffered, compressed);
      InspectedCollection result = validateMetadata(
          buffered, compressed, HexFormat.of().formatHex(digest.digest()), archive);
      buffered = null;
      return result;
    } catch (AnsibleGalaxyExceptions.GalaxyException e) {
      delete(buffered);
      throw e;
    } catch (IOException | RuntimeException e) {
      delete(buffered);
      throw new AnsibleGalaxyExceptions.BadRequest("Unable to inspect Ansible collection archive", e);
    } finally {
      if (permit) inspectionPermits.release();
    }
  }

  private boolean acquireInspectionPermit() {
    try {
      if (inspectionPermits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS)) return true;
      throw new AnsibleGalaxyExceptions.ServiceUnavailable(
          "Ansible archive inspection capacity is busy; retry the request");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AnsibleGalaxyExceptions.ServiceUnavailable(
          "Interrupted while waiting for Ansible archive inspection capacity");
    }
  }

  private long copyBounded(InputStream body, Path target, MessageDigest digest) throws IOException {
    if (body == null) throw new AnsibleGalaxyExceptions.BadRequest("Collection artifact is required");
    long total = 0;
    try (var output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = body.read(buffer)) >= 0;) {
        if (read == 0) continue;
        total += read;
        if (total > maxCompressedBytes) {
          throw new AnsibleGalaxyExceptions.ContentTooLarge(
              "Ansible collection artifact exceeds the upload limit");
        }
        digest.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    if (total == 0) throw new AnsibleGalaxyExceptions.BadRequest("Collection artifact is empty");
    return total;
  }

  private static void ensureGzipMagic(Path file) throws IOException {
    try (InputStream input = Files.newInputStream(file)) {
      if (input.read() != 0x1f || input.read() != 0x8b) {
        throw new AnsibleGalaxyExceptions.BadRequest(
            "Collection artifact must be a gzip-compressed tar archive");
      }
    }
  }

  private ArchiveEntries inspectTar(Path file, long compressed) throws IOException {
    long started = System.nanoTime();
    long expanded = 0;
    int count = 0;
    Set<String> names = new HashSet<>();
    Set<String> folded = new HashSet<>();
    Map<String, String> fileHashes = new LinkedHashMap<>();
    Map<String, String> symbolicLinks = new LinkedHashMap<>();
    Set<String> directories = new HashSet<>();
    byte[] manifest = null;
    byte[] files = null;
    byte[] runtime = null;
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
         GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw, false);
         TarArchiveInputStream tar = new TarArchiveInputStream(gzip, StandardCharsets.UTF_8.name())) {
      for (TarArchiveEntry entry; (entry = tar.getNextTarEntry()) != null;) {
        ensureWithinTime(started);
        if (++count > maxEntries) {
          throw new AnsibleGalaxyExceptions.ContentTooLarge(
              "Ansible collection archive contains too many entries");
        }
        String name = safeName(entry);
        if (!names.add(name) || !folded.add(name.toLowerCase(Locale.ROOT))) {
          throw new AnsibleGalaxyExceptions.BadRequest(
              "Ansible collection archive contains duplicate or case-conflicting entries");
        }
        if (entry.isDirectory()) {
          directories.add(name);
          continue;
        }
        if (entry.isSymbolicLink()) {
          symbolicLinks.put(name, safeSymbolicLinkTarget(name, entry.getLinkName()));
          continue;
        }
        if (!entry.isFile() || entry.isLink() || entry.isCharacterDevice()
            || entry.isBlockDevice() || entry.isFIFO()) {
          throw new AnsibleGalaxyExceptions.BadRequest(
              "Ansible collection archive contains a prohibited entry type");
        }
        if (entry.getSize() < 0 || entry.getSize() > maxEntryBytes) {
          throw new AnsibleGalaxyExceptions.ContentTooLarge(
              "Ansible collection archive entry exceeds the safe limit");
        }
        MessageDigest entryDigest = sha256();
        int captureLimit = switch (name) {
          case "MANIFEST.json" -> MAX_MANIFEST_BYTES;
          case "FILES.json" -> MAX_FILES_BYTES;
          case "meta/runtime.yml" -> MAX_RUNTIME_BYTES;
          default -> 0;
        };
        ByteArrayOutputStream captured = captureLimit == 0 ? null : new ByteArrayOutputStream();
        long entryBytes = 0;
        byte[] buffer = new byte[32 * 1024];
        for (int read; (read = tar.read(buffer)) >= 0;) {
          if (read == 0) continue;
          expanded += read;
          entryBytes += read;
          if (entryBytes > maxEntryBytes || expanded > maxExpandedBytes) {
            throw new AnsibleGalaxyExceptions.ContentTooLarge(
                "Ansible collection archive expands beyond the safe limit");
          }
          if (expanded > RATIO_FLOOR && exceedsRatio(expanded, compressed)) {
            throw new AnsibleGalaxyExceptions.BadRequest(
                "Ansible collection archive compression ratio exceeds the safe limit");
          }
          ensureWithinTime(started);
          entryDigest.update(buffer, 0, read);
          if (captured != null) {
            if (captured.size() + read > captureLimit) {
              throw new AnsibleGalaxyExceptions.ContentTooLarge(name + " exceeds the safe limit");
            }
            captured.write(buffer, 0, read);
          }
        }
        fileHashes.put(name, HexFormat.of().formatHex(entryDigest.digest()));
        if (captured != null) {
          if (name.equals("MANIFEST.json")) manifest = captured.toByteArray();
          else if (name.equals("FILES.json")) files = captured.toByteArray();
          else runtime = captured.toByteArray();
        }
      }
    }
    if (manifest == null || files == null) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Collection archive must contain root MANIFEST.json and FILES.json");
    }
    resolveSymbolicLinkHashes(fileHashes, symbolicLinks, directories);
    return new ArchiveEntries(manifest, files, runtime, Map.copyOf(fileHashes), Set.copyOf(directories));
  }

  /**
   * Collection builds preserve symbolic links and describe them as files in FILES.json, using the
   * target file's checksum. Resolve only links that remain inside the archive and ultimately point
   * to a regular file; hard links, dangling links, directory links and cycles remain prohibited.
   */
  private static void resolveSymbolicLinkHashes(
      Map<String, String> fileHashes,
      Map<String, String> symbolicLinks,
      Set<String> directories) {
    for (String name : symbolicLinks.keySet()) {
      String hash = resolveSymbolicLinkHash(
          name, fileHashes, symbolicLinks, directories, new HashSet<>());
      fileHashes.put(name, hash);
    }
  }

  private static String resolveSymbolicLinkHash(
      String name,
      Map<String, String> fileHashes,
      Map<String, String> symbolicLinks,
      Set<String> directories,
      Set<String> resolving) {
    String direct = fileHashes.get(name);
    if (direct != null) return direct;
    if (!resolving.add(name)) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible collection archive contains a symbolic-link cycle");
    }
    String target = symbolicLinks.get(name);
    if (target == null || directories.contains(target)) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible collection archive contains a dangling or directory symbolic link");
    }
    String hash = resolveSymbolicLinkHash(
        target, fileHashes, symbolicLinks, directories, resolving);
    resolving.remove(name);
    return hash;
  }

  private static String safeSymbolicLinkTarget(String entryName, String rawTarget) {
    if (rawTarget == null || rawTarget.isBlank() || rawTarget.length() > 1024
        || rawTarget.indexOf('\0') >= 0 || rawTarget.indexOf('\\') >= 0
        || rawTarget.startsWith("/") || rawTarget.startsWith("//")
        || rawTarget.matches("^[A-Za-z]:.*") || containsControl(rawTarget)) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible collection archive contains an unsafe symbolic link");
    }
    List<String> segments = new ArrayList<>();
    int slash = entryName.lastIndexOf('/');
    if (slash >= 0) {
      for (String segment : entryName.substring(0, slash).split("/")) {
        segments.add(segment);
      }
    }
    for (String segment : rawTarget.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment)) continue;
      if ("..".equals(segment)) {
        if (segments.isEmpty()) {
          throw new AnsibleGalaxyExceptions.BadRequest(
              "Ansible collection archive symbolic link escapes the archive");
        }
        segments.remove(segments.size() - 1);
      } else {
        segments.add(segment);
      }
    }
    String resolved = String.join("/", segments);
    if (resolved.isEmpty() || resolved.length() > 1024) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible collection archive contains an unsafe symbolic link");
    }
    return safeManifestName(resolved);
  }

  @SuppressWarnings("unchecked")
  private InspectedCollection validateMetadata(
      Path file, long size, String artifactSha256, ArchiveEntries archive) throws IOException {
    Map<String, Object> manifest = objectMapper.readValue(archive.manifestBytes(), MAP);
    Map<String, Object> files = objectMapper.readValue(archive.filesBytes(), MAP);
    Map<String, Object> collectionInfo = requiredMap(manifest, "collection_info");
    Map<String, Object> fileManifest = requiredMap(manifest, "file_manifest_file");
    if (!"FILES.json".equals(string(fileManifest.get("name")))
        || !"file".equals(string(fileManifest.get("ftype")))
        || !"sha256".equalsIgnoreCase(string(fileManifest.get("chksum_type")))) {
      throw new AnsibleGalaxyExceptions.BadRequest("MANIFEST.json must reference FILES.json with SHA-256");
    }
    String expectedFilesHash = string(fileManifest.get("chksum_sha256"));
    String actualFilesHash = HexFormat.of().formatHex(sha256().digest(archive.filesBytes()));
    if (!validSha(expectedFilesHash) || !actualFilesHash.equalsIgnoreCase(expectedFilesHash)) {
      throw new AnsibleGalaxyExceptions.BadRequest("FILES.json checksum does not match MANIFEST.json");
    }

    String namespace = AnsibleGalaxyNames.requireNamespace(requiredString(collectionInfo, "namespace"));
    String name = AnsibleGalaxyNames.requireCollection(requiredString(collectionInfo, "name"));
    String version = AnsibleGalaxyVersions.require(requiredString(collectionInfo, "version"));
    validateFileInventory(files, archive);

    Map<String, Object> dependencies = stringMap(collectionInfo.get("dependencies"), "dependencies");
    Map<String, Object> metadata = protocolMetadata(collectionInfo);
    String requiresAnsible = parseRequiresAnsible(archive.runtimeBytes());
    String filename = AnsibleGalaxyPathParser.canonicalFilename(namespace, name, version);
    return new InspectedCollection(
        file, size, artifactSha256, filename, namespace, name, version,
        metadata, Map.copyOf(dependencies), requiresAnsible);
  }

  private Map<String, Object> protocolMetadata(Map<String, Object> collectionInfo)
      throws IOException {
    Map<String, Object> projected = new LinkedHashMap<>();
    copyMetadataValue(collectionInfo, projected, "authors", 64, 512);
    copyMetadataValue(collectionInfo, projected, "license", 64, 128);
    copyMetadataValue(collectionInfo, projected, "tags", 256, 128);
    copyMetadataValue(collectionInfo, projected, "description", 1, 16 * 1024);
    copyMetadataValue(collectionInfo, projected, "repository", 1, 4096);
    copyMetadataValue(collectionInfo, projected, "documentation", 1, 4096);
    copyMetadataValue(collectionInfo, projected, "homepage", 1, 4096);
    copyMetadataValue(collectionInfo, projected, "issues", 1, 4096);
    if (objectMapper.writeValueAsBytes(projected).length > MAX_PROTOCOL_METADATA_BYTES) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Collection protocol metadata exceeds the safe limit");
    }
    return immutableMap(projected);
  }

  private static void copyMetadataValue(
      Map<String, Object> source,
      Map<String, Object> target,
      String key,
      int maxItems,
      int maxLength) {
    Object raw = source.get(key);
    if (raw == null) return;
    if (raw instanceof String text) {
      target.put(key, boundedMetadataText(key, text, maxLength));
      return;
    }
    if (!(raw instanceof Collection<?> values) || values.size() > maxItems) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Collection metadata " + key + " exceeds the safe limit");
    }
    List<String> copy = new ArrayList<>(values.size());
    for (Object value : values) {
      if (!(value instanceof String text)) {
        throw new AnsibleGalaxyExceptions.BadRequest(
            "Collection metadata " + key + " contains an invalid value");
      }
      copy.add(boundedMetadataText(key, text, maxLength));
    }
    target.put(key, List.copyOf(copy));
  }

  private static String boundedMetadataText(String key, String value, int maxLength) {
    if (value.length() > maxLength || value.indexOf('\0') >= 0
        || value.chars().anyMatch(ch -> ch < 0x20 && ch != '\r' && ch != '\n' && ch != '\t')) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Collection metadata " + key + " contains an invalid value");
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private void validateFileInventory(Map<String, Object> files, ArchiveEntries archive) {
    Object rawEntries = files.get("files");
    if (!(rawEntries instanceof List<?> entries) || entries.size() > maxEntries) {
      throw new AnsibleGalaxyExceptions.BadRequest("FILES.json files must be a bounded array");
    }
    Set<String> declaredFiles = new HashSet<>();
    Set<String> declaredDirectories = new HashSet<>();
    for (Object raw : entries) {
      if (!(raw instanceof Map<?, ?> entry)) {
        throw new AnsibleGalaxyExceptions.BadRequest("FILES.json contains an invalid entry");
      }
      String name = safeManifestName(string(entry.get("name")));
      String type = string(entry.get("ftype"));
      if ("dir".equals(type)) {
        if (!declaredDirectories.add(name)) {
          throw new AnsibleGalaxyExceptions.BadRequest("FILES.json contains a duplicate directory");
        }
        if (!".".equals(name) && !archive.directories().contains(name)) {
          throw new AnsibleGalaxyExceptions.BadRequest("FILES.json references a missing directory: " + name);
        }
      } else if ("file".equals(type)) {
        if (!declaredFiles.add(name)) {
          throw new AnsibleGalaxyExceptions.BadRequest("FILES.json contains a duplicate file");
        }
        String expected = string(entry.get("chksum_sha256"));
        String actual = archive.fileHashes().get(name);
        if (!"sha256".equalsIgnoreCase(string(entry.get("chksum_type")))
            || !validSha(expected) || actual == null || !actual.equalsIgnoreCase(expected)) {
          throw new AnsibleGalaxyExceptions.BadRequest("Collection file checksum mismatch: " + name);
        }
      } else {
        throw new AnsibleGalaxyExceptions.BadRequest("FILES.json contains an unsupported entry type");
      }
    }
    Set<String> actual = new HashSet<>(archive.fileHashes().keySet());
    actual.remove("MANIFEST.json");
    actual.remove("FILES.json");
    if (!actual.equals(declaredFiles)) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "FILES.json does not exactly describe the collection files");
    }
  }

  private static Map<String, Object> requiredMap(Map<String, Object> map, String name) {
    Object value = map.get(name);
    if (!(value instanceof Map<?, ?> raw)) {
      throw new AnsibleGalaxyExceptions.BadRequest("MANIFEST.json requires " + name);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private static Map<String, Object> stringMap(Object value, String label) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> raw) || raw.size() > 256) {
      throw new AnsibleGalaxyExceptions.BadRequest(label + " must be a bounded object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      String key = string(entry.getKey());
      String item = string(entry.getValue());
      if (key == null || key.isBlank() || key.length() > 129 || item == null || item.length() > 512) {
        throw new AnsibleGalaxyExceptions.BadRequest(label + " contains an invalid value");
      }
      result.put(key, item);
    }
    return result;
  }

  private static String requiredString(Map<String, Object> map, String name) {
    String value = string(map.get(name));
    if (value == null || value.isBlank() || value.length() > 255) {
      throw new AnsibleGalaxyExceptions.BadRequest("collection_info requires " + name);
    }
    return value;
  }

  private static String parseRequiresAnsible(byte[] runtime) {
    if (runtime == null || runtime.length == 0) return null;
    String text = new String(runtime, StandardCharsets.UTF_8);
    Matcher matcher = REQUIRES_ANSIBLE.matcher(text);
    if (!matcher.find()) return null;
    String value = matcher.group(1).trim();
    if (value.isEmpty() || value.length() > 255 || containsControl(value)) {
      throw new AnsibleGalaxyExceptions.BadRequest("meta/runtime.yml has invalid requires_ansible");
    }
    return value;
  }

  private static String safeName(TarArchiveEntry entry) {
    String raw = entry.getName();
    if (raw == null || raw.isBlank() || raw.length() > 1024 || raw.indexOf('\0') >= 0
        || raw.indexOf('\\') >= 0 || raw.startsWith("/") || raw.startsWith("//")
        || raw.matches("^[A-Za-z]:.*")) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible collection archive contains an unsafe path");
    }
    String name = raw;
    while (name.startsWith("./")) name = name.substring(2);
    while (name.endsWith("/") && name.length() > 1) name = name.substring(0, name.length() - 1);
    safeManifestName(name);
    return name;
  }

  private static String safeManifestName(String name) {
    if (name == null || name.isBlank() || name.length() > 1024 || name.indexOf('\\') >= 0
        || name.startsWith("/") || name.indexOf('\0') >= 0) {
      throw new AnsibleGalaxyExceptions.BadRequest("Collection metadata contains an unsafe path");
    }
    if (!".".equals(name)) {
      for (String segment : name.split("/", -1)) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
          throw new AnsibleGalaxyExceptions.BadRequest("Collection metadata contains path traversal");
        }
      }
    }
    return name;
  }

  private boolean exceedsRatio(long expanded, long compressed) {
    long base = Math.max(1L, compressed);
    long whole = expanded / base;
    return whole > maxCompressionRatio
        || (whole == maxCompressionRatio && expanded % base != 0);
  }

  private void ensureWithinTime(long started) {
    if (System.nanoTime() - started > maxInspectionNanos) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible collection archive inspection exceeded the time limit");
    }
  }

  private static boolean validSha(String value) {
    return value != null && SHA256.matcher(value).matches();
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f);
  }

  private static String string(Object value) {
    return value instanceof String text ? text : value == null ? null : String.valueOf(value);
  }

  private static Map<String, Object> immutableMap(Map<String, Object> value) {
    return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static void delete(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }

  record InspectedCollection(
      Path file,
      long size,
      String sha256,
      String filename,
      String namespace,
      String name,
      String version,
      Map<String, Object> metadata,
      Map<String, Object> dependencies,
      String requiresAnsible) {
  }

  private record ArchiveEntries(
      byte[] manifestBytes,
      byte[] filesBytes,
      byte[] runtimeBytes,
      Map<String, String> fileHashes,
      Set<String> directories) {
  }
}
