package com.github.klboke.kkrepo.server.conda;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.protocol.conda.CondaMediaTypes;
import com.github.klboke.kkrepo.protocol.conda.CondaPackageIdentifiers;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.protocol.conda.CondaVersions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Parses upstream repodata and deterministically renders Conda channel metadata. */
@Component
final class CondaMetadataCodec {
  private static final Pattern MD5 = Pattern.compile("[0-9a-fA-F]{32}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
  private static final int MAX_PACKAGE_RECORDS = 1_000_000;
  private static final int MAX_RECORD_JSON_BYTES = 256 * 1024;
  private static final int MAX_DEPENDENCIES = 10_000;
  private static final int MAX_PACKAGE_BASE_URL_LENGTH = 2048;
  private static final long MAX_IN_MEMORY_GROUP_METADATA_BYTES = 16L * 1024 * 1024;
  private static final int METADATA_OUTPUT_BUFFER_BYTES = 64 * 1024;
  private static final List<String> CHANNELDATA_FIELDS = List.of(
      "summary", "description", "home", "license", "doc_url", "dev_url", "source_url",
      "icon_url", "icon_hash", "tags", "run_exports");

  private final ObjectMapper mapper;
  private final int bzip2BlockSize;
  private final int zstdLevel;

  @Autowired
  CondaMetadataCodec(
      ObjectMapper mapper,
      @Value("${kkrepo.conda.metadata.bzip2-block-size:9}") int bzip2BlockSize,
      @Value("${kkrepo.conda.metadata.zstd-level:3}") int zstdLevel) {
    this.mapper = mapper;
    this.bzip2BlockSize = Math.max(1, Math.min(9, bzip2BlockSize));
    this.zstdLevel = Math.max(-5, Math.min(22, zstdLevel));
  }

  CondaMetadataCodec(ObjectMapper mapper) {
    this(mapper, 9, 3);
  }

  /**
   * Opens a JSON view over an upstream repodata representation.
   *
   * <p>Group repositories prefer the upstream zstd representation so object storage persists and
   * transfers roughly one tenth of the bytes of the pretty-printed JSON index. The returned
   * stream owns {@code input}; construction failures close it before propagating the error.
   */
  InputStream decodeRepodata(InputStream input, CondaPath.Encoding encoding) throws IOException {
    try {
      return switch (encoding) {
        case JSON -> input;
        case BZIP2 -> new BZip2CompressorInputStream(input, true);
        case ZSTD -> new ZstdInputStream(input);
        default -> throw new IOException("Unsupported Conda repodata encoding: " + encoding);
      };
    } catch (IOException | RuntimeException error) {
      try {
        input.close();
      } catch (IOException ignored) {
      }
      throw error;
    }
  }

  ProxyInventory parseRepodata(
      byte[] bytes, long repositoryId, String channel, String subdir, Instant indexedAt) {
    return parseRepodata(
        new ByteArrayInputStream(bytes), sha256(bytes), repositoryId, channel, subdir, indexedAt);
  }

  ProxyInventory parseRepodata(
      InputStream input,
      String metadataSha256,
      long repositoryId,
      String channel,
      String subdir,
      Instant indexedAt) {
    try (ProxyInventoryFile inventory = parseRepodataFile(
        input, metadataSha256, repositoryId, channel, subdir, indexedAt)) {
      ArrayList<CondaRegistryDao.PackageRecord> records = new ArrayList<>();
      inventory.records().visit(records::add);
      return new ProxyInventory(
          List.copyOf(records), inventory.metadataSha256(), inventory.packageBaseUrl());
    }
  }

  /**
   * Parses a potentially very large upstream index into a replayable temporary spool.
   *
   * <p>Only one package record is retained while parsing or replaying. This keeps an upstream
   * metadata refresh bounded even for channels with hundreds of thousands of records.
   */
  ProxyInventoryFile parseRepodataFile(
      InputStream input,
      String metadataSha256,
      long repositoryId,
      String channel,
      String subdir,
      Instant indexedAt) {
    Path spool = null;
    try {
      spool = Files.createTempFile("kkrepo-conda-inventory-", ".json");
      String packageBaseUrl = null;
      int[] recordCount = {0};
      TreeSet<String> removed = new TreeSet<>();
      try (JsonParser parser = mapper.getFactory().createParser(input);
           OutputStream output = Files.newOutputStream(
               spool, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
           JsonGenerator writer = mapper.getFactory().createGenerator(output)) {
        writer.writeStartArray();
        JsonToken first = parser.nextToken();
        if (first == null) {
          writer.writeEndArray();
        } else if (first != JsonToken.START_OBJECT) {
          throw upstream("Conda repodata must be an object");
        } else {
          HashSet<String> rootFields = new HashSet<>();
          while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
              throw upstream("Conda repodata contains an invalid root field");
            }
            String field = parser.currentName();
            if (!rootFields.add(field)) {
              throw upstream("Conda repodata contains a duplicate root field");
            }
            JsonToken value = parser.nextToken();
            if ("packages".equals(field)) {
              parsePackageMap(
                  parser, value, repositoryId, channel, subdir, "tar.bz2", indexedAt,
                  record -> writeSpoolRecord(writer, record), recordCount);
            } else if ("packages.conda".equals(field)) {
              parsePackageMap(
                  parser, value, repositoryId, channel, subdir, "conda", indexedAt,
                  record -> writeSpoolRecord(writer, record), recordCount);
            } else if ("info".equals(field)) {
              JsonNode rawInfo = mapper.readTree(parser);
              ObjectNode root = mapper.createObjectNode();
              root.set("info", rawInfo);
              packageBaseUrl = packageBaseUrl(root);
            } else if ("removed".equals(field)) {
              parseRemoved(parser, value, removed);
            } else {
              parser.skipChildren();
            }
          }
          if (parser.nextToken() != null) {
            throw upstream("Conda repodata contains trailing content");
          }
          writer.writeEndArray();
        }
      }
      Path inventoryPath = spool;
      CondaRegistryDao.PackageRecordSource records = visitor -> visitSpool(
          inventoryPath, repositoryId, channel, subdir, indexedAt, visitor);
      return new ProxyInventoryFile(
          records, metadataSha256, packageBaseUrl, List.copyOf(removed), recordCount[0], spool);
    } catch (MavenExceptions.BadUpstreamException e) {
      delete(spool);
      throw e;
    } catch (IOException | RuntimeException e) {
      delete(spool);
      throw upstream("Invalid Conda upstream repodata", e);
    }
  }

  Rendered renderRepodata(
      String subdir,
      List<CondaRegistryDao.PackageRecord> records,
      List<CondaRegistryDao.Tombstone> tombstones,
      CondaPath.Encoding encoding) {
    RecordSource source = (archiveFormat, visitor) -> {
      ArrayList<CondaRegistryDao.PackageRecord> sorted = new ArrayList<>(safe(records));
      sorted.sort(Comparator.comparing(CondaRegistryDao.PackageRecord::filename));
      for (CondaRegistryDao.PackageRecord record : sorted) {
        if (archiveFormat.equals(record.archiveFormat())) {
          visitor.accept(record);
        }
      }
    };
    try (RenderedFile file = renderRepodataFile(subdir, source, tombstones, encoding)) {
      return new Rendered(Files.readAllBytes(file.path()), file.contentType(), file.etag());
    } catch (IOException e) {
      throw new IllegalStateException("Failed reading rendered Conda metadata", e);
    }
  }

  /**
   * Copies a database-backed record stream into a replayable file before compression starts.
   *
   * <p>Rendering visits the source once for each archive format. Snapshotting keeps those JDBC
   * cursor transactions limited to row decoding and sequential disk writes; bzip2/zstd CPU work
   * no longer holds a pooled database connection.
   */
  RecordSourceFile snapshotRecordSource(String subdir, RecordSource source) {
    Path spool = null;
    try {
      spool = Files.createTempFile("kkrepo-conda-render-source-", ".json");
      try (OutputStream output = Files.newOutputStream(
               spool, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
           JsonGenerator writer = mapper.getFactory().createGenerator(output)) {
        writer.writeStartArray();
        source.visit("tar.bz2", record -> writeSpoolRecord(writer, record));
        source.visit("conda", record -> writeSpoolRecord(writer, record));
        writer.writeEndArray();
      }
      Path snapshot = spool;
      RecordSource replay = (archiveFormat, visitor) ->
          visitRenderSpool(snapshot, subdir, archiveFormat, visitor);
      return new RecordSourceFile(replay, spool);
    } catch (IOException | RuntimeException error) {
      delete(spool);
      throw new IllegalStateException("Failed snapshotting Conda metadata records", error);
    }
  }

  /**
   * Streams deterministic JSON directly through the selected compressor into a temporary file.
   * Only one package record is materialized at a time, avoiding simultaneous full JSON and
   * compressed byte arrays for large channels.
   */
  RenderedFile renderRepodataFile(
      String subdir,
      RecordSource source,
      List<CondaRegistryDao.Tombstone> tombstones,
      CondaPath.Encoding encoding) {
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-conda-metadata-", suffix(encoding));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (OutputStream raw = Files.newOutputStream(
               file, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
           DigestOutputStream digested = new DigestOutputStream(raw, digest);
           OutputStream encoded = encodedOutput(digested, encoding);
           JsonGenerator generator = mapper.getFactory().createGenerator(encoded)) {
        generator.setCodec(mapper);
        HashSet<String> active = new HashSet<>();
        generator.writeStartObject();
        generator.writeObjectFieldStart("info");
        generator.writeStringField("subdir", subdir);
        generator.writeNumberField("repodata_version", 1);
        generator.writeEndObject();
        writePackageCollection(generator, "packages", "tar.bz2", source, active);
        writePackageCollection(generator, "packages.conda", "conda", source, active);
        generator.writeArrayFieldStart("removed");
        TreeSet<String> removed = new TreeSet<>();
        for (CondaRegistryDao.Tombstone tombstone : safe(tombstones)) {
          if (!active.contains(tombstone.filename())) {
            removed.add(tombstone.filename());
          }
        }
        for (String filename : removed) {
          generator.writeString(filename);
        }
        generator.writeEndArray();
        generator.writeNumberField("repodata_version", 1);
        generator.writeEndObject();
      }
      return new RenderedFile(
          file, mediaType(encoding), HexFormat.of().formatHex(digest.digest()), Files.size(file));
    } catch (MavenExceptions.MavenNotFoundException e) {
      delete(file);
      throw e;
    } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
      delete(file);
      throw new IllegalStateException("Failed rendering Conda metadata", e);
    }
  }

  /**
   * Bounded group merge used by the cold path. Metadata below the in-memory ceiling follows the
   * Nexus algorithm (parse member trees once, combine package nodes, serialize once, then compress
   * in bulk). Larger inputs are validated once and copied into disk-backed collection spools in
   * contiguous raw ranges so memory remains bounded without re-serializing every package record.
   */
  RenderedFile renderMergedRepodataFile(
      String subdir,
      List<MergeSource> sources,
      List<CondaRegistryDao.Tombstone> tombstones,
      CondaPath.Encoding encoding) {
    Path file = null;
    try {
      List<MaterializedMergeSource> materialized = materializeMergeSources(sources);
      if (materialized.stream().noneMatch(source -> source.raw() != null)) {
        return renderMaterializedMergedRepodata(subdir, materialized, tombstones, encoding);
      }
      return renderSpooledMergedRepodata(subdir, materialized, tombstones, encoding);
    } catch (MavenExceptions.MavenNotFoundException e) {
      delete(file);
      throw e;
    } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
      delete(file);
      throw new IllegalStateException("Failed merging Conda group metadata", e);
    }
  }

  Rendered renderChanneldata(List<CondaRegistryDao.PackageRecord> records) {
    ArrayList<CondaRegistryDao.PackageRecord> sorted = new ArrayList<>(safe(records));
    sorted.sort(Comparator.comparing(CondaRegistryDao.PackageRecord::name)
        .thenComparing(CondaRegistryDao.PackageRecord::subdir)
        .thenComparing(CondaRegistryDao.PackageRecord::filename));
    try (RenderedFile file = renderChanneldataFile(visitor -> {
      for (CondaRegistryDao.PackageRecord record : sorted) visitor.accept(record);
    })) {
      return new Rendered(Files.readAllBytes(file.path()), file.contentType(), file.etag());
    } catch (IOException e) {
      throw new IllegalStateException("Failed reading rendered Conda channeldata", e);
    }
  }

  /** Streams name-ordered records into channeldata while retaining one package aggregate. */
  RenderedFile renderChanneldataFile(ChannelRecordSource source) {
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-conda-channeldata-", ".json");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (OutputStream raw = Files.newOutputStream(
               file, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
           DigestOutputStream digested = new DigestOutputStream(raw, digest);
           JsonGenerator generator = mapper.getFactory().createGenerator(digested)) {
        generator.setCodec(mapper);
        ChanneldataAccumulator accumulator = new ChanneldataAccumulator(generator);
        generator.writeStartObject();
        generator.writeNumberField("channeldata_version", 1);
        generator.writeObjectFieldStart("packages");
        source.visit(accumulator::accept);
        accumulator.finishPackages();
        generator.writeEndObject();
        generator.writeArrayFieldStart("subdirs");
        for (String subdir : accumulator.subdirs()) generator.writeString(subdir);
        generator.writeEndArray();
        generator.writeEndObject();
      }
      return new RenderedFile(
          file, CondaMediaTypes.JSON, HexFormat.of().formatHex(digest.digest()), Files.size(file));
    } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
      delete(file);
      throw new IllegalStateException("Failed rendering Conda channeldata", e);
    }
  }

  Rendered emptyNotices() {
    return encode(json(Map.of("notices", List.of())), CondaPath.Encoding.JSON);
  }

  Rendered sanitizeJson(byte[] bytes) {
    try (RenderedFile file = sanitizeJsonFile(new ByteArrayInputStream(bytes))) {
      return new Rendered(Files.readAllBytes(file.path()), file.contentType(), file.etag());
    } catch (IOException e) {
      throw upstream("Invalid Conda upstream metadata", e);
    }
  }

  /** Streams arbitrary Conda JSON while removing remote-location fields at every depth. */
  RenderedFile sanitizeJsonFile(InputStream input) {
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-conda-sanitized-", ".json");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (JsonParser parser = mapper.getFactory().createParser(input);
           OutputStream raw = Files.newOutputStream(
               file, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
           DigestOutputStream digested = new DigestOutputStream(raw, digest);
           JsonGenerator generator = mapper.getFactory().createGenerator(digested)) {
        JsonToken token = parser.nextToken();
        if (token == null) throw upstream("Conda upstream metadata is empty");
        while (token != null) {
          if (token == JsonToken.FIELD_NAME
              && ("base_url".equals(parser.currentName())
                  || "download_url".equals(parser.currentName()))) {
            JsonToken value = parser.nextToken();
            if (value == null) throw upstream("Invalid Conda upstream metadata");
            parser.skipChildren();
          } else {
            generator.copyCurrentEvent(parser);
          }
          token = parser.nextToken();
        }
      }
      return new RenderedFile(
          file, CondaMediaTypes.JSON, HexFormat.of().formatHex(digest.digest()), Files.size(file));
    } catch (MavenExceptions.BadUpstreamException e) {
      delete(file);
      throw e;
    } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
      delete(file);
      throw upstream("Invalid Conda upstream metadata", e);
    }
  }

  private void parsePackageMap(
      JsonParser parser,
      JsonToken token,
      long repositoryId,
      String channel,
      String subdir,
      String archiveFormat,
      Instant indexedAt,
      RecordVisitor output,
      int[] recordCount) {
    if (token == JsonToken.VALUE_NULL) return;
    if (token != JsonToken.START_OBJECT) {
      throw upstream("Conda repodata package collection must be an object");
    }
    try {
      HashSet<String> filenames = new HashSet<>();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) {
          throw upstream("Conda repodata contains an invalid package entry");
        }
        String filename = parser.currentName();
        if (!filenames.add(filename)) {
          throw upstream("Conda repodata contains a duplicate package filename");
        }
        if (parser.nextToken() == null) {
          throw upstream("Conda repodata contains an incomplete package entry");
        }
        JsonNode raw = mapper.readTree(parser);
        if (recordCount[0] >= MAX_PACKAGE_RECORDS) {
          throw upstream("Conda repodata contains too many package records");
        }
        output.accept(parseRecord(
            filename, raw, repositoryId, channel, subdir, archiveFormat,
            indexedAt));
        recordCount[0]++;
      }
    } catch (IOException e) {
      throw upstream("Invalid Conda upstream repodata package collection", e);
    }
  }

  private static void parseRemoved(
      JsonParser parser, JsonToken token, Set<String> removed) throws IOException {
    if (token == JsonToken.VALUE_NULL) return;
    if (token != JsonToken.START_ARRAY) {
      throw upstream("Conda repodata removed collection must be an array");
    }
    for (JsonToken entry = parser.nextToken(); entry != JsonToken.END_ARRAY;
         entry = parser.nextToken()) {
      if (entry == null || entry != JsonToken.VALUE_STRING) {
        throw upstream("Conda repodata contains an invalid removed package");
      }
      String filename = parser.getText();
      if (!CondaPathParser.isPackage(filename)) {
        throw upstream("Conda repodata contains an invalid removed package");
      }
      removed.add(filename);
    }
  }

  private CondaRegistryDao.PackageRecord parseRecord(
      String filename,
      JsonNode raw,
      long repositoryId,
      String channel,
      String subdir,
      String archiveFormat,
      Instant indexedAt) {
    if (!CondaPathParser.isPackage(filename) || !raw.isObject()) {
      throw upstream("Conda repodata contains an invalid package entry");
    }
    if (archiveFormat.equals("conda") != filename.endsWith(".conda")) {
      throw upstream("Conda repodata package is in the wrong archive collection");
    }
    ObjectNode value = (ObjectNode) raw;
    removeRemoteLocations(value);
    String name = text(value, "name");
    String version = text(value, "version");
    String build = text(value, "build");
    if (!CondaPackageIdentifiers.isUpstreamName(name)) {
      throw upstream("Conda repodata package has an invalid name");
    }
    if (!CondaPackageIdentifiers.isBuild(build)) {
      throw upstream("Conda repodata package has an invalid build");
    }
    try {
      CondaVersions.require(version);
    } catch (IllegalArgumentException e) {
      throw upstream("Conda repodata package has an invalid version", e);
    }
    long buildNumber = integer(value, "build_number", true);
    long size = integer(value, "size", true);
    if (size == 0) throw upstream("Conda repodata package has an invalid size");
    String suffix = archiveFormat.equals("conda") ? ".conda" : ".tar.bz2";
    if (!filename.equals(name + "-" + version + "-" + build + suffix)) {
      throw upstream("Conda repodata filename does not match its package coordinate");
    }
    String md5 = checksum(value, "md5", MD5);
    String sha256 = checksum(value, "sha256", SHA256);
    if (md5 == null && sha256 == null) {
      throw upstream("Conda repodata package is missing both sha256 and md5");
    }
    String declaredSubdir = optionalText(value, "subdir");
    if (value.has("subdir") && declaredSubdir == null) {
      throw upstream("Conda repodata package has an invalid subdir");
    }
    if (declaredSubdir != null && !declaredSubdir.equals(subdir)) {
      throw upstream("Conda repodata package subdir does not match its index");
    }
    value.put("subdir", subdir);
    if (md5 != null) value.put("md5", md5);
    if (sha256 != null) value.put("sha256", sha256);
    value.put("size", size);
    validateStringArray(value, "depends");
    validateStringArray(value, "constrains");
    validateLegacyFeatureField(value, "track_features");
    validateLegacyFeatureField(value, "features");
    @SuppressWarnings("unchecked")
    Map<String, Object> converted = mapper.convertValue(value, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) sanitizedValue(converted);
    String recordFingerprint;
    try {
      byte[] canonical = mapper.writeValueAsBytes(metadata);
      if (canonical.length > MAX_RECORD_JSON_BYTES) {
        throw upstream("Conda repodata package record is too large");
      }
      recordFingerprint = sha256(canonical);
    } catch (IOException e) {
      throw upstream("Conda repodata package record is invalid", e);
    }
    Instant when = indexedAt == null ? Instant.now() : indexedAt;
    return new CondaRegistryDao.PackageRecord(
        null, repositoryId, channel, subdir, filename, name, version, build, buildNumber,
        archiveFormat, metadata, recordFingerprint, md5, sha256, size, null, null,
        CondaRegistryDao.SOURCE_PROXY, 0, when, when);
  }

  String fingerprint(Map<String, Object> metadata) {
    try {
      return sha256(mapper.writeValueAsBytes(sanitizedValue(
          metadata == null ? Map.of() : metadata)));
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid Conda package record metadata", e);
    }
  }

  private Map<String, Object> publicRecord(CondaRegistryDao.PackageRecord record) {
    TreeMap<String, Object> metadata = new TreeMap<>();
    Object safe = sanitizedValue(record.metadata() == null ? Map.of() : record.metadata());
    if (safe instanceof Map<?, ?> values) {
      values.forEach((key, value) -> metadata.put(String.valueOf(key), value));
    }
    metadata.put("name", record.name());
    metadata.put("version", record.version());
    metadata.put("build", record.build());
    metadata.put("build_number", record.buildNumber());
    metadata.put("subdir", record.subdir());
    metadata.put("size", record.size());
    if (record.md5() == null) metadata.remove("md5");
    else metadata.put("md5", record.md5());
    if (record.sha256() == null) metadata.remove("sha256");
    else metadata.put("sha256", record.sha256());
    return java.util.Collections.unmodifiableMap(metadata);
  }

  private static Object sanitizedValue(Object value) {
    if (value instanceof Map<?, ?> raw) {
      TreeMap<String, Object> copy = new TreeMap<>();
      raw.forEach((key, nested) -> {
        String name = String.valueOf(key);
        if (!"base_url".equals(name) && !"download_url".equals(name)) {
          copy.put(name, sanitizedValue(nested));
        }
      });
      return copy;
    }
    if (value instanceof List<?> raw) {
      return raw.stream().map(CondaMetadataCodec::sanitizedValue).toList();
    }
    return value;
  }

  private byte[] json(Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new IllegalStateException("Failed encoding Conda metadata", e);
    }
  }

  private Rendered encode(byte[] json, CondaPath.Encoding encoding) {
    try {
      byte[] body = switch (encoding) {
        case JSON, NONE -> json;
        case BZIP2 -> bzip2(json);
        case ZSTD -> zstd(json);
        case MSGPACK_ZSTD -> throw new MavenExceptions.MavenNotFoundException(
            "Conda sharded repodata is not available");
      };
      String type = switch (encoding) {
        case BZIP2 -> CondaMediaTypes.BZIP2;
        case ZSTD, MSGPACK_ZSTD -> CondaMediaTypes.ZSTD;
        default -> CondaMediaTypes.JSON;
      };
      return new Rendered(body, type, sha256(body));
    } catch (IOException e) {
      throw new IllegalStateException("Failed compressing Conda metadata", e);
    }
  }

  private byte[] bzip2(byte[] input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (BZip2CompressorOutputStream compressed =
             new BZip2CompressorOutputStream(output, bzip2BlockSize)) {
      compressed.write(input);
    }
    return output.toByteArray();
  }

  private byte[] zstd(byte[] input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZstdOutputStream compressed = new ZstdOutputStream(output, zstdLevel)) {
      compressed.write(input);
    }
    return output.toByteArray();
  }

  private void writePackageCollection(
      JsonGenerator generator,
      String field,
      String archiveFormat,
      RecordSource source,
      Set<String> active) throws IOException {
    generator.writeObjectFieldStart(field);
    source.visit(archiveFormat, record -> {
      active.add(record.filename());
      generator.writeFieldName(record.filename());
      generator.writeObject(publicRecord(record));
    });
    generator.writeEndObject();
  }

  private RenderedFile renderSpooledMergedRepodata(
      String subdir,
      List<MaterializedMergeSource> sources,
      List<CondaRegistryDao.Tombstone> tombstones,
      CondaPath.Encoding encoding) throws IOException, NoSuchAlgorithmException {
    Path file = null;
    try (StreamingMergeSpool spool = spoolMergedPackageCollections(sources)) {
      for (CondaRegistryDao.Tombstone tombstone : safe(tombstones)) {
        spool.removed().add(tombstone.filename());
      }
      file = Files.createTempFile("kkrepo-conda-group-metadata-", suffix(encoding));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (OutputStream raw = Files.newOutputStream(
               file, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
           DigestOutputStream digested = new DigestOutputStream(raw, digest);
           OutputStream encoded = encodedOutput(digested, encoding)) {
        writeUtf8(encoded, "{\"info\":{\"subdir\":");
        encoded.write(mapper.writeValueAsBytes(subdir));
        writeUtf8(encoded, ",\"repodata_version\":1},\"packages\":");
        Files.copy(spool.packages(), encoded);
        writeUtf8(encoded, ",\"packages.conda\":");
        Files.copy(spool.condaPackages(), encoded);
        writeUtf8(encoded, ",\"removed\":[");
        boolean first = true;
        for (String filename : spool.removed()) {
          if (spool.packagesVisible().contains(filename)
              || spool.condaPackagesVisible().contains(filename)) {
            continue;
          }
          if (!first) encoded.write(',');
          encoded.write(mapper.writeValueAsBytes(filename));
          first = false;
        }
        writeUtf8(encoded, "],\"repodata_version\":1}");
      }
      return new RenderedFile(
          file, mediaType(encoding), HexFormat.of().formatHex(digest.digest()), Files.size(file));
    } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
      delete(file);
      throw e;
    }
  }

  private StreamingMergeSpool spoolMergedPackageCollections(
      List<MaterializedMergeSource> sources) throws IOException {
    Path packages = null;
    Path condaPackages = null;
    HashSet<String> packagesVisible = new HashSet<>();
    HashSet<String> condaPackagesVisible = new HashSet<>();
    TreeSet<String> removed = new TreeSet<>();
    try {
      packages = Files.createTempFile("kkrepo-conda-group-packages-", ".json");
      condaPackages = Files.createTempFile("kkrepo-conda-group-packages-conda-", ".json");
      try (JsonObjectSpoolWriter packagesWriter = new JsonObjectSpoolWriter(packages);
           JsonObjectSpoolWriter condaPackagesWriter =
               new JsonObjectSpoolWriter(condaPackages)) {
        for (MaterializedMergeSource source : safe(sources)) {
          if (source.records() != null) {
            spoolRecordCollection(
                source.records(), "tar.bz2", packagesVisible, packagesWriter);
            spoolRecordCollection(
                source.records(), "conda", condaPackagesVisible, condaPackagesWriter);
          } else {
            try (InputStream input = source.raw().open()) {
              spoolRawPackageCollections(
                  input,
                  packagesVisible,
                  condaPackagesVisible,
                  removed,
                  packagesWriter,
                  condaPackagesWriter);
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      delete(packages);
      delete(condaPackages);
      throw e;
    }
    return new StreamingMergeSpool(
        packages, condaPackages, packagesVisible, condaPackagesVisible, removed);
  }

  private void spoolRecordCollection(
      RecordSource records,
      String archiveFormat,
      Set<String> visible,
      JsonObjectSpoolWriter writer) throws IOException {
    records.visit(archiveFormat, record -> {
      if (!visible.add(record.filename())) return;
      writer.write(record.filename(), mapper.writeValueAsBytes(publicRecord(record)));
    });
  }

  private RenderedFile renderMaterializedMergedRepodata(
      String subdir,
      List<MaterializedMergeSource> sources,
      List<CondaRegistryDao.Tombstone> tombstones,
      CondaPath.Encoding encoding) throws IOException {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode info = mapper.createObjectNode();
    info.put("subdir", subdir);
    info.put("repodata_version", 1);
    root.set("info", info);

    HashSet<String> active = new HashSet<>();
    ObjectNode packages = mergeMaterializedPackageCollection(
        "packages", "tar.bz2", sources, active);
    ObjectNode condaPackages = mergeMaterializedPackageCollection(
        "packages.conda", "conda", sources, active);
    root.set("packages", packages);
    root.set("packages.conda", condaPackages);

    TreeSet<String> removed = new TreeSet<>();
    for (MaterializedMergeSource source : safe(sources)) {
      if (source.tree() != null) collectTreeRemoved(source.tree(), removed);
    }
    for (CondaRegistryDao.Tombstone tombstone : safe(tombstones)) {
      removed.add(tombstone.filename());
    }
    root.set("removed", mapper.valueToTree(
        removed.stream().filter(filename -> !active.contains(filename)).toList()));
    root.put("repodata_version", 1);

    Rendered rendered = encode(mapper.writeValueAsBytes(root), encoding);
    Path file = Files.createTempFile("kkrepo-conda-group-metadata-", suffix(encoding));
    try {
      Files.write(
          file,
          rendered.body(),
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      return new RenderedFile(
          file, rendered.contentType(), rendered.etag(), rendered.body().length);
    } catch (IOException | RuntimeException e) {
      delete(file);
      throw e;
    }
  }

  private ObjectNode mergeMaterializedPackageCollection(
      String field,
      String archiveFormat,
      List<MaterializedMergeSource> sources,
      Set<String> active) throws IOException {
    ObjectNode merged = mapper.createObjectNode();
    for (MaterializedMergeSource source : safe(sources)) {
      if (source.records() != null) {
        source.records().visit(archiveFormat, record -> {
          if (merged.has(record.filename())) return;
          active.add(record.filename());
          merged.set(record.filename(), mapper.valueToTree(publicRecord(record)));
        });
        continue;
      }
      JsonNode collection = source.tree().get(field);
      if (collection == null || collection.isNull()) continue;
      if (!collection.isObject()) {
        throw upstream("Conda repodata package collection must be an object");
      }
      int records = 0;
      java.util.Iterator<Map.Entry<String, JsonNode>> fields = collection.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String filename = entry.getKey();
        JsonNode record = entry.getValue();
        if (++records > MAX_PACKAGE_RECORDS
            || !CondaPathParser.isPackage(filename)
            || ("conda".equals(archiveFormat) != filename.endsWith(".conda"))
            || record == null
            || !record.isObject()) {
          throw upstream("Conda repodata contains an invalid package entry");
        }
        if (merged.has(filename)) continue;
        active.add(filename);
        merged.set(filename, record);
      }
    }
    return merged;
  }

  private void collectTreeRemoved(JsonNode root, Set<String> removed) {
    JsonNode values = root.get("removed");
    if (values == null || values.isNull()) return;
    if (!values.isArray()) {
      throw upstream("Conda repodata removed collection must be an array");
    }
    for (JsonNode entry : values) {
      if (!entry.isTextual() || !CondaPathParser.isPackage(entry.textValue())) {
        throw upstream("Conda repodata contains an invalid removed package");
      }
      removed.add(entry.textValue());
    }
  }

  private List<MaterializedMergeSource> materializeMergeSources(List<MergeSource> sources)
      throws IOException {
    long totalRawBytes = 0;
    for (MergeSource source : safe(sources)) {
      if (source.raw() == null) continue;
      if (source.rawSize() < 0
          || totalRawBytes > MAX_IN_MEMORY_GROUP_METADATA_BYTES - source.rawSize()) {
        totalRawBytes = MAX_IN_MEMORY_GROUP_METADATA_BYTES + 1;
        break;
      }
      totalRawBytes += source.rawSize();
    }
    boolean materializeRaw = totalRawBytes <= MAX_IN_MEMORY_GROUP_METADATA_BYTES;
    ArrayList<MaterializedMergeSource> result = new ArrayList<>();
    for (MergeSource source : safe(sources)) {
      if (source.records() != null) {
        result.add(new MaterializedMergeSource(source.records(), null, null));
      } else if (materializeRaw) {
        try (InputStream input = source.raw().open()) {
          JsonNode tree = mapper.readTree(input);
          if (tree == null || !tree.isObject()) {
            throw upstream("Conda repodata must be an object");
          }
          removeRemoteLocations(tree);
          result.add(new MaterializedMergeSource(null, null, tree));
        }
      } else {
        result.add(new MaterializedMergeSource(null, source.raw(), null));
      }
    }
    return List.copyOf(result);
  }

  private void spoolRawPackageCollections(
      InputStream input,
      Set<String> packagesVisible,
      Set<String> condaPackagesVisible,
      Set<String> removed,
      JsonObjectSpoolWriter packagesWriter,
      JsonObjectSpoolWriter condaPackagesWriter) throws IOException {
    Path raw = Files.createTempFile("kkrepo-conda-group-raw-", ".json");
    try {
      try (OutputStream output = new BufferedOutputStream(
          Files.newOutputStream(
              raw, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
          METADATA_OUTPUT_BUFFER_BYTES)) {
        input.transferTo(output);
      }
      try (JsonParser parser = mapper.getFactory().createParser(Files.newInputStream(raw));
           FileChannel channel = FileChannel.open(raw, StandardOpenOption.READ)) {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
          throw upstream("Conda repodata must be an object");
        }
        HashSet<String> rootFields = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          if (parser.currentToken() != JsonToken.FIELD_NAME) {
            throw upstream("Conda repodata contains an invalid root field");
          }
          String field = parser.currentName();
          if (!rootFields.add(field)) {
            throw upstream("Conda repodata contains a duplicate root field");
          }
          JsonToken value = parser.nextToken();
          if ("packages".equals(field)) {
            spoolRawPackageCollection(
                parser, channel, value, "tar.bz2", packagesVisible, packagesWriter);
          } else if ("packages.conda".equals(field)) {
            spoolRawPackageCollection(
                parser, channel, value, "conda", condaPackagesVisible, condaPackagesWriter);
          } else if ("removed".equals(field)) {
            parseRemoved(parser, value, removed);
          } else {
            parser.skipChildren();
          }
        }
        if (parser.nextToken() != null) {
          throw upstream("Conda repodata contains trailing content");
        }
      }
    } finally {
      delete(raw);
    }
  }

  private void spoolRawPackageCollection(
      JsonParser parser,
      FileChannel channel,
      JsonToken value,
      String archiveFormat,
      Set<String> visible,
      JsonObjectSpoolWriter writer) throws IOException {
    if (value == JsonToken.VALUE_NULL) return;
    if (value != JsonToken.START_OBJECT) {
      throw upstream("Conda repodata package collection must be an object");
    }
    HashSet<String> memberFilenames = new HashSet<>();
    int records = 0;
    long rawRangeStart = -1;
    long rawRangeEnd = -1;
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken() != JsonToken.FIELD_NAME) {
        throw upstream("Conda repodata contains an invalid package entry");
      }
      long entryStart = parser.currentTokenLocation().getByteOffset();
      String filename = parser.currentName();
      if (!memberFilenames.add(filename)) {
        throw upstream("Conda repodata contains a duplicate package filename");
      }
      if (++records > MAX_PACKAGE_RECORDS
          || !CondaPathParser.isPackage(filename)
          || ("conda".equals(archiveFormat) != filename.endsWith(".conda"))) {
        throw upstream("Conda repodata contains an invalid package entry");
      }
      JsonToken record = parser.nextToken();
      if (record != JsonToken.START_OBJECT) {
        throw upstream("Conda repodata contains an invalid package entry");
      }
      RawRecordSpan span = scanRawRecord(parser);
      boolean selected = visible.add(filename);
      if (!selected || span.hasRemoteLocation()) {
        if (rawRangeStart >= 0) {
          writer.writeRawEntries(channel, rawRangeStart, rawRangeEnd);
          rawRangeStart = -1;
          rawRangeEnd = -1;
        }
        if (selected) writer.write(filename, sanitizeRawRecord(channel, span));
      } else {
        if (rawRangeStart < 0) rawRangeStart = entryStart;
        rawRangeEnd = span.end();
      }
    }
    if (rawRangeStart >= 0) {
      writer.writeRawEntries(channel, rawRangeStart, rawRangeEnd);
    }
  }

  private RawRecordSpan scanRawRecord(JsonParser parser) throws IOException {
    long start = parser.currentTokenLocation().getByteOffset();
    int depth = 1;
    boolean hasRemoteLocation = false;
    while (depth > 0) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        throw upstream("Conda repodata contains an incomplete package value");
      }
      if (token == JsonToken.FIELD_NAME) {
        String field = parser.currentName();
        hasRemoteLocation |= "base_url".equals(field) || "download_url".equals(field);
      } else if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
        depth++;
      } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
        depth--;
      }
    }
    long end = parser.currentLocation().getByteOffset();
    if (start < 0 || end <= start) {
      throw upstream("Conda repodata package offsets are invalid");
    }
    return new RawRecordSpan(start, end, hasRemoteLocation);
  }

  private byte[] sanitizeRawRecord(
      FileChannel channel, RawRecordSpan span) throws IOException {
    long length = span.end() - span.start();
    if (length > MAX_RECORD_JSON_BYTES) {
      throw upstream("Conda repodata package record is too large");
    }
    byte[] bytes = readRange(channel, span.start(), length);
    JsonNode record = mapper.readTree(bytes);
    if (record == null || !record.isObject()) {
      throw upstream("Conda repodata contains an invalid package entry");
    }
    removeRemoteLocations(record);
    return mapper.writeValueAsBytes(record);
  }

  private static byte[] readRange(
      FileChannel channel, long start, long length) throws IOException {
    if (length < 0 || length > Integer.MAX_VALUE) {
      throw upstream("Conda repodata package record is too large");
    }
    byte[] value = new byte[(int) length];
    ByteBuffer buffer = ByteBuffer.wrap(value);
    channel.position(start);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        throw upstream("Conda repodata package record is truncated");
      }
    }
    return value;
  }

  OutputStream encodedOutput(OutputStream output, CondaPath.Encoding encoding)
      throws IOException {
    return switch (encoding) {
      case JSON, NONE -> new BufferedOutputStream(output, METADATA_OUTPUT_BUFFER_BYTES);
      case BZIP2 -> new BZip2CompressorOutputStream(
          new BufferedOutputStream(output, METADATA_OUTPUT_BUFFER_BYTES), bzip2BlockSize);
      case ZSTD -> new ZstdOutputStream(
          new BufferedOutputStream(output, METADATA_OUTPUT_BUFFER_BYTES), zstdLevel);
      case MSGPACK_ZSTD -> throw new MavenExceptions.MavenNotFoundException(
          "Conda sharded repodata is not available");
    };
  }

  private static void writeUtf8(OutputStream output, String value) throws IOException {
    output.write(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String mediaType(CondaPath.Encoding encoding) {
    return switch (encoding) {
      case BZIP2 -> CondaMediaTypes.BZIP2;
      case ZSTD, MSGPACK_ZSTD -> CondaMediaTypes.ZSTD;
      default -> CondaMediaTypes.JSON;
    };
  }

  private static String suffix(CondaPath.Encoding encoding) {
    return switch (encoding) {
      case BZIP2 -> ".json.bz2";
      case ZSTD, MSGPACK_ZSTD -> ".json.zst";
      default -> ".json";
    };
  }

  private static void removeRemoteLocations(JsonNode node) {
    if (node instanceof ObjectNode object) {
      object.remove(List.of("base_url", "download_url"));
      object.elements().forEachRemaining(CondaMetadataCodec::removeRemoteLocations);
    } else if (node.isArray()) {
      node.elements().forEachRemaining(CondaMetadataCodec::removeRemoteLocations);
    }
  }

  private void visitSpool(
      Path spool,
      long repositoryId,
      String channel,
      String subdir,
      Instant indexedAt,
      java.util.function.Consumer<CondaRegistryDao.PackageRecord> visitor) {
    try (InputStream input = Files.newInputStream(spool);
         JsonParser parser = mapper.getFactory().createParser(input)) {
      if (parser.nextToken() != JsonToken.START_ARRAY) {
        throw new IllegalStateException("Conda proxy inventory spool is invalid");
      }
      for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY;
           token = parser.nextToken()) {
        if (token == null || token != JsonToken.START_OBJECT) {
          throw new IllegalStateException("Conda proxy inventory spool is incomplete");
        }
        SpoolRecord record = readSpoolRecord(parser);
        visitor.accept(record.toPackageRecord(repositoryId, channel, subdir, indexedAt));
      }
      if (parser.nextToken() != null) {
        throw new IllegalStateException("Conda proxy inventory spool has trailing content");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed reading Conda proxy inventory spool", e);
    }
  }

  private void visitRenderSpool(
      Path spool,
      String subdir,
      String archiveFormat,
      RecordVisitor visitor) throws IOException {
    try (InputStream input = Files.newInputStream(spool);
         JsonParser parser = mapper.getFactory().createParser(input)) {
      if (parser.nextToken() != JsonToken.START_ARRAY) {
        throw new IOException("Conda render source spool is invalid");
      }
      for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY;
           token = parser.nextToken()) {
        if (token == null || token != JsonToken.START_OBJECT) {
          throw new IOException("Conda render source spool is incomplete");
        }
        SpoolRecord record = readSpoolRecord(parser);
        if (archiveFormat.equals(record.archiveFormat())) {
          visitor.accept(record.toPackageRecord(0, "", subdir, Instant.EPOCH));
        }
      }
      if (parser.nextToken() != null) {
        throw new IOException("Conda render source spool has trailing content");
      }
    }
  }

  /**
   * Writes the private spool schema explicitly so GraalVM native images do not need Jackson
   * reflection metadata for {@link SpoolRecord}. The metadata value itself is limited to JSON
   * maps, lists, and scalars by the Conda record parser.
   */
  private void writeSpoolRecord(
      JsonGenerator writer, CondaRegistryDao.PackageRecord record) throws IOException {
    if (record == null) throw new IOException("Conda spool record is missing");
    writer.writeStartObject();
    writer.writeStringField("filename", record.filename());
    writer.writeStringField("name", record.name());
    writer.writeStringField("version", record.version());
    writer.writeStringField("build", record.build());
    writer.writeNumberField("buildNumber", record.buildNumber());
    writer.writeStringField("archiveFormat", record.archiveFormat());
    writer.writeObjectField("metadata", record.metadata() == null ? Map.of() : record.metadata());
    writer.writeStringField("recordSha256", record.recordSha256());
    writeNullableStringField(writer, "md5", record.md5());
    writeNullableStringField(writer, "sha256", record.sha256());
    writer.writeNumberField("size", record.size());
    writer.writeEndObject();
  }

  /** Reads the private spool schema without reflective POJO or record deserialization. */
  @SuppressWarnings("unchecked")
  private SpoolRecord readSpoolRecord(JsonParser parser) throws IOException {
    String filename = null;
    String name = null;
    String version = null;
    String build = null;
    long buildNumber = 0;
    String archiveFormat = null;
    Map<String, Object> metadata = null;
    String recordSha256 = null;
    String md5 = null;
    String sha256 = null;
    long size = 0;
    int seen = 0;

    JsonToken fieldToken;
    while ((fieldToken = parser.nextToken()) != JsonToken.END_OBJECT) {
      if (fieldToken == null) throw new IOException("Conda spool record is incomplete");
      String field = parser.currentName();
      JsonToken value = parser.nextToken();
      if (value == null) throw new IOException("Conda spool record is incomplete");
      int bit = switch (field) {
        case "filename" -> 1;
        case "name" -> 1 << 1;
        case "version" -> 1 << 2;
        case "build" -> 1 << 3;
        case "buildNumber" -> 1 << 4;
        case "archiveFormat" -> 1 << 5;
        case "metadata" -> 1 << 6;
        case "recordSha256" -> 1 << 7;
        case "md5" -> 1 << 8;
        case "sha256" -> 1 << 9;
        case "size" -> 1 << 10;
        default -> throw new IOException("Conda spool record contains an unknown field");
      };
      if ((seen & bit) != 0) throw new IOException("Conda spool record contains a duplicate field");
      seen |= bit;

      switch (field) {
        case "filename" -> filename = spoolText(parser, value, field, false);
        case "name" -> name = spoolText(parser, value, field, false);
        case "version" -> version = spoolText(parser, value, field, false);
        case "build" -> build = spoolText(parser, value, field, false);
        case "buildNumber" -> buildNumber = spoolLong(parser, value, field);
        case "archiveFormat" -> archiveFormat = spoolText(parser, value, field, false);
        case "metadata" -> {
          if (value != JsonToken.START_OBJECT) {
            throw new IOException("Conda spool record has invalid metadata");
          }
          metadata = mapper.readValue(parser, Map.class);
        }
        case "recordSha256" -> recordSha256 = spoolText(parser, value, field, false);
        case "md5" -> md5 = spoolText(parser, value, field, true);
        case "sha256" -> sha256 = spoolText(parser, value, field, true);
        case "size" -> size = spoolLong(parser, value, field);
      }
    }
    if (seen != (1 << 11) - 1) throw new IOException("Conda spool record is incomplete");
    return new SpoolRecord(
        filename, name, version, build, buildNumber, archiveFormat, metadata, recordSha256,
        md5, sha256, size);
  }

  private static void writeNullableStringField(
      JsonGenerator writer, String field, String value) throws IOException {
    if (value == null) writer.writeNullField(field);
    else writer.writeStringField(field, value);
  }

  private static String spoolText(
      JsonParser parser, JsonToken token, String field, boolean nullable) throws IOException {
    if (token == JsonToken.VALUE_NULL && nullable) return null;
    if (token != JsonToken.VALUE_STRING || parser.getText().isEmpty()) {
      throw new IOException("Conda spool record has invalid " + field);
    }
    return parser.getText();
  }

  private static long spoolLong(JsonParser parser, JsonToken token, String field)
      throws IOException {
    if (token != JsonToken.VALUE_NUMBER_INT) {
      throw new IOException("Conda spool record has invalid " + field);
    }
    long value = parser.getLongValue();
    if (value < 0) throw new IOException("Conda spool record has invalid " + field);
    return value;
  }

  private static void delete(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }

  private static String packageBaseUrl(ObjectNode root) {
    JsonNode rawInfo = root.get("info");
    if (rawInfo == null || rawInfo.isNull()) return null;
    if (!(rawInfo instanceof ObjectNode info)) {
      throw upstream("Conda repodata info must be an object");
    }
    JsonNode raw = info.get("base_url");
    if (raw == null || raw.isNull()) return null;
    if (!raw.isTextual()) {
      throw upstream("Conda repodata info.base_url must be a URL string");
    }
    String value = raw.textValue();
    if (value.isBlank()
        || value.length() > MAX_PACKAGE_BASE_URL_LENGTH
        || value.indexOf('\\') >= 0
        || value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
      throw upstream("Conda repodata info.base_url is invalid");
    }
    try {
      URI uri = URI.create(value);
      if (uri.isOpaque() || uri.getRawQuery() != null || uri.getRawFragment() != null
          || uri.getRawUserInfo() != null) {
        throw upstream("Conda repodata info.base_url is invalid");
      }
      if (uri.isAbsolute()
          && (!("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()))
              || uri.getHost() == null)) {
        throw upstream("Conda repodata info.base_url must use HTTP or HTTPS");
      }
      if (uri.getRawAuthority() != null && uri.getHost() == null) {
        throw upstream("Conda repodata info.base_url has an invalid authority");
      }
      return value;
    } catch (IllegalArgumentException e) {
      throw upstream("Conda repodata info.base_url is invalid", e);
    }
  }

  private static String text(ObjectNode value, String field) {
    String result = optionalText(value, field);
    if (result == null || result.isBlank() || result.length() > 255
        || result.indexOf('/') >= 0 || result.indexOf('\\') >= 0
        || result.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
      throw upstream("Conda repodata package has an invalid " + field);
    }
    return result;
  }

  private static String optionalText(ObjectNode value, String field) {
    JsonNode raw = value.get(field);
    return raw != null && raw.isTextual() ? raw.textValue() : null;
  }

  private static long integer(ObjectNode value, String field, boolean required) {
    JsonNode raw = value.get(field);
    if (raw == null || raw.isNull()) {
      if (required) throw upstream("Conda repodata package is missing " + field);
      return 0;
    }
    if (!raw.canConvertToLong() || !raw.isIntegralNumber() || raw.longValue() < 0) {
      throw upstream("Conda repodata package has an invalid " + field);
    }
    return raw.longValue();
  }

  private static void validateStringArray(ObjectNode value, String field) {
    JsonNode raw = value.get(field);
    if (raw == null || raw.isNull()) return;
    if (!raw.isArray() || raw.size() > MAX_DEPENDENCIES) {
      throw upstream("Conda repodata package has an invalid " + field);
    }
    for (JsonNode item : raw) {
      if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 4096
          || item.textValue().chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
        throw upstream("Conda repodata package has an invalid " + field);
      }
    }
  }

  private static void validateLegacyFeatureField(ObjectNode value, String field) {
    JsonNode raw = value.get(field);
    if (raw == null || raw.isNull()) return;
    if (raw.isTextual()) {
      String text = raw.textValue();
      if (text.length() <= 4096
          && text.chars().noneMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
        return;
      }
    } else if (raw.isArray() && raw.size() <= MAX_DEPENDENCIES) {
      boolean valid = true;
      for (JsonNode item : raw) {
        if (!item.isTextual() || item.textValue().isBlank()
            || item.textValue().length() > 4096
            || item.textValue().chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
          valid = false;
          break;
        }
      }
      if (valid) return;
    }
    throw upstream("Conda repodata package has an invalid " + field);
  }

  private static String checksum(ObjectNode value, String field, Pattern pattern) {
    String raw = optionalText(value, field);
    if (raw == null || raw.isBlank()) return null;
    if (!pattern.matcher(raw).matches()) {
      throw upstream("Conda repodata package has an invalid " + field);
    }
    return raw.toLowerCase(Locale.ROOT);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MavenExceptions.BadUpstreamException upstream(String message) {
    return new MavenExceptions.BadUpstreamException(message);
  }

  private static MavenExceptions.BadUpstreamException upstream(String message, Throwable cause) {
    return new MavenExceptions.BadUpstreamException(message, cause);
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  record ProxyInventory(
      List<CondaRegistryDao.PackageRecord> records,
      String metadataSha256,
      String packageBaseUrl) { }

  record ProxyInventoryFile(
      CondaRegistryDao.PackageRecordSource records,
      String metadataSha256,
      String packageBaseUrl,
      List<String> removed,
      int recordCount,
      Path path) implements AutoCloseable {
    @Override
    public void close() {
      delete(path);
    }
  }

  record RecordSourceFile(RecordSource records, Path path) implements AutoCloseable {
    @Override
    public void close() {
      delete(path);
    }
  }

  record Rendered(byte[] body, String contentType, String etag) { }

  @FunctionalInterface
  interface RecordSource {
    void visit(String archiveFormat, RecordVisitor visitor) throws IOException;
  }

  @FunctionalInterface
  interface RecordVisitor {
    void accept(CondaRegistryDao.PackageRecord record) throws IOException;
  }

  @FunctionalInterface
  interface ChannelRecordSource {
    void visit(RecordVisitor visitor) throws IOException;
  }

  @FunctionalInterface
  interface RawRepodataSource {
    InputStream open() throws IOException;
  }

  record MergeSource(RecordSource records, RawRepodataSource raw, long rawSize) {
    static MergeSource records(RecordSource records) {
      return new MergeSource(java.util.Objects.requireNonNull(records), null, 0);
    }

    static MergeSource raw(long rawSize, RawRepodataSource raw) {
      return new MergeSource(null, java.util.Objects.requireNonNull(raw), rawSize);
    }
  }

  private record MaterializedMergeSource(
      RecordSource records, RawRepodataSource raw, JsonNode tree) { }

  private record RawRecordSpan(long start, long end, boolean hasRemoteLocation) { }

  private final class JsonObjectSpoolWriter implements AutoCloseable {
    private final OutputStream output;
    private final byte[] transferBuffer = new byte[METADATA_OUTPUT_BUFFER_BYTES];
    private boolean first = true;

    private JsonObjectSpoolWriter(Path path) throws IOException {
      this.output = new BufferedOutputStream(
          Files.newOutputStream(
              path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
          METADATA_OUTPUT_BUFFER_BYTES);
      output.write('{');
    }

    private void write(String filename, byte[] value) throws IOException {
      writePrefix(filename);
      output.write(value);
    }

    private void writeRawEntries(FileChannel channel, long start, long end) throws IOException {
      if (start < 0 || end <= start) {
        throw upstream("Conda repodata package offsets are invalid");
      }
      if (!first) output.write(',');
      first = false;
      channel.position(start);
      long remaining = end - start;
      ByteBuffer buffer = ByteBuffer.wrap(transferBuffer);
      boolean inString = false;
      boolean escaped = false;
      while (remaining > 0) {
        buffer.clear();
        buffer.limit((int) Math.min(buffer.capacity(), remaining));
        int read = channel.read(buffer);
        if (read < 0) {
          throw upstream("Conda repodata package record is truncated");
        }
        if (read == 0) continue;
        int compacted = 0;
        for (int index = 0; index < read; index++) {
          byte value = transferBuffer[index];
          if (inString) {
            transferBuffer[compacted++] = value;
            if (escaped) {
              escaped = false;
            } else if (value == '\\') {
              escaped = true;
            } else if (value == '"') {
              inString = false;
            }
          } else if (value == '"') {
            inString = true;
            transferBuffer[compacted++] = value;
          } else if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
            transferBuffer[compacted++] = value;
          }
        }
        if (compacted > 0) output.write(transferBuffer, 0, compacted);
        remaining -= read;
      }
    }

    private void writePrefix(String filename) throws IOException {
      if (!first) output.write(',');
      output.write(mapper.writeValueAsBytes(filename));
      output.write(':');
      first = false;
    }

    @Override
    public void close() throws IOException {
      try {
        output.write('}');
      } finally {
        output.close();
      }
    }
  }

  private record StreamingMergeSpool(
      Path packages,
      Path condaPackages,
      Set<String> packagesVisible,
      Set<String> condaPackagesVisible,
      TreeSet<String> removed) implements AutoCloseable {
    @Override
    public void close() {
      delete(packages);
      delete(condaPackages);
    }
  }

  record RenderedFile(Path path, String contentType, String etag, long size)
      implements AutoCloseable {
    @Override
    public void close() {
      delete(path);
    }
  }

  private record SpoolRecord(
      String filename,
      String name,
      String version,
      String build,
      long buildNumber,
      String archiveFormat,
      Map<String, Object> metadata,
      String recordSha256,
      String md5,
      String sha256,
      long size) {
    private CondaRegistryDao.PackageRecord toPackageRecord(
        long repositoryId, String channel, String subdir, Instant indexedAt) {
      Instant when = indexedAt == null ? Instant.now() : indexedAt;
      return new CondaRegistryDao.PackageRecord(
          null,
          repositoryId,
          channel,
          subdir,
          filename,
          name,
          version,
          build,
          buildNumber,
          archiveFormat,
          metadata,
          recordSha256,
          md5,
          sha256,
          size,
          null,
          null,
          CondaRegistryDao.SOURCE_PROXY,
          0,
          when,
          when);
    }
  }

  private static final class ChanneldataAccumulator {
    private final JsonGenerator generator;
    private final TreeSet<String> subdirs = new TreeSet<>();
    private ChannelPackage current;

    private ChanneldataAccumulator(JsonGenerator generator) {
      this.generator = generator;
    }

    private void accept(CondaRegistryDao.PackageRecord record) throws IOException {
      if (record == null) return;
      subdirs.add(record.subdir());
      if (current == null) {
        current = new ChannelPackage(record.name());
      } else if (!current.name.equals(record.name())) {
        if (current.name.compareTo(record.name()) > 0) {
          throw new IllegalStateException("Conda channel records are not ordered by name");
        }
        writeCurrent();
        current = new ChannelPackage(record.name());
      }
      current.accept(record);
    }

    private void finishPackages() throws IOException {
      writeCurrent();
    }

    private void writeCurrent() throws IOException {
      if (current == null) return;
      generator.writeFieldName(current.name);
      generator.writeObject(current.toMap());
      current = null;
    }

    private Set<String> subdirs() {
      return subdirs;
    }
  }

  private static final class ChannelPackage {
    private final String name;
    private final Set<String> subdirs = new TreeSet<>();
    private CondaRegistryDao.PackageRecord latest;

    private ChannelPackage(String name) {
      this.name = name;
    }

    private void accept(CondaRegistryDao.PackageRecord record) {
      subdirs.add(record.subdir());
      if (latest == null || compare(record, latest) > 0) latest = record;
    }

    private Map<String, Object> toMap() {
      LinkedHashMap<String, Object> value = new LinkedHashMap<>();
      value.put("name", name);
      value.put("subdirs", List.copyOf(subdirs));
      if (latest != null) {
        value.put("version", latest.version());
        value.put("reference_package", latest.filename());
        for (String field : CHANNELDATA_FIELDS) {
          Object raw = latest.metadata().get(field);
          if (raw != null) value.put(field, raw);
        }
      }
      return java.util.Collections.unmodifiableMap(value);
    }

    private static int compare(
        CondaRegistryDao.PackageRecord left, CondaRegistryDao.PackageRecord right) {
      int version = CondaVersions.compare(left.version(), right.version());
      if (version != 0) return version;
      int buildNumber = Long.compare(left.buildNumber(), right.buildNumber());
      if (buildNumber != 0) return buildNumber;
      int build = left.build().compareTo(right.build());
      if (build != 0) return build;
      Object leftTimestamp = left.metadata().get("timestamp");
      Object rightTimestamp = right.metadata().get("timestamp");
      if (leftTimestamp instanceof Number l && rightTimestamp instanceof Number r) {
        return Long.compare(l.longValue(), r.longValue());
      }
      return left.filename().compareTo(right.filename());
    }
  }
}
