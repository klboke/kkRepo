package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPublishMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmVersions;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import de.marhali.json5.Json5Element;
import de.marhali.json5.config.Json5Options;
import de.marhali.json5.exception.Json5Exception;
import de.marhali.json5.stream.Json5Lexer;
import de.marhali.json5.stream.Json5Parser;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class OhpmArchiveUpload {
  private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
  private static final Json5Options JSON5_OPTIONS = Json5Options.builder().build();
  private static final ObjectMapper JSON = new ObjectMapper();
  private final long maxFileBytes;
  private final Path directory;

  OhpmArchiveUpload(long maxFileBytes, Path directory) {
    if (maxFileBytes < 1 || maxFileBytes > Long.MAX_VALUE / 8) {
      throw new IllegalArgumentException("Invalid OHPM upload size limit");
    }
    this.maxFileBytes = maxFileBytes;
    this.directory = directory;
  }

  OhpmPublishParser.Upload parse(InputStream harInput, InputStream hspInput, String tag) throws IOException {
    List<Path> staged = new ArrayList<>();
    try {
      var har = stage(harInput, staged);
      var hsp = hspInput == null ? null : stage(hspInput, staged);
      return publication(har, hsp, tag);
    } catch (IOException | RuntimeException error) {
      staged.forEach(TempBlobFiles::deleteQuietly);
      throw error;
    }
  }

  OhpmPublishParser.Upload parseBundle(InputStream input, String tag) throws IOException {
    List<Path> staged = new ArrayList<>();
    try {
      var attachments = new OhpmStreamParser(maxFileBytes, directory).extractHsp(
          OhpmStreamParser.bounded(input, maxFileBytes * 2 + 1024 * 1024), staged);
      return publication(attachments.har(), attachments.hsp(), tag);
    } catch (IOException | RuntimeException error) {
      staged.forEach(TempBlobFiles::deleteQuietly);
      throw error;
    }
  }

  private OhpmPublishParser.Upload publication(OhpmPublishParser.Attachment har,
      OhpmPublishParser.Attachment hsp, String tag) throws IOException {
    ObjectNode manifest = manifest(har.file());
    try {
      OhpmPackageId packageId = OhpmPackageId.parse(manifest.path("name").textValue());
      String version = manifest.path("version").textValue();
      if (!OhpmVersions.isValid(version)) throw invalid("Invalid OHPM version");
      boolean interfaceHar = "InterfaceHar".equals(manifest.path("packageType").textValue());
      if (interfaceHar != (hsp != null)) {
        throw invalid("InterfaceHar requires an HSP file; ordinary HAR must not include one");
      }
      ObjectNode dist = manifest.putObject("dist");
      dist.put("integrity", integrity(har));
      if (hsp != null) {
        if (manifest.has("hspType") && !"bundle_app".equals(manifest.path("hspType").textValue())) {
          throw invalid("Unsupported HSP type");
        }
        dist.put("integrity_hsp", integrity(hsp));
        manifest.put("hspType", "bundle_app");
      }
      manifest.put("_id", packageId.id() + "@" + version);
      manifest.put("_ohpmVersion", "6.0.1");
      ObjectNode root = JSON.createObjectNode().put("name", packageId.id()).put("_id", packageId.id());
      for (String field : List.of("description", "license", "author", "homepage", "repository", "keywords")) {
        if (manifest.has(field)) root.set(field, manifest.path(field).deepCopy());
      }
      root.putObject("versions").set(version, manifest);
      root.putObject("dist-tags").put(tag == null || tag.isBlank() ? "latest" : tag, version);
      OhpmPublishMetadata metadata = OhpmPublishMetadata.parse(packageId, root);
      return new OhpmPublishParser.Upload(metadata, har, hsp);
    } catch (IllegalArgumentException error) {
      throw invalid("Invalid OHPM archive metadata");
    }
  }

  private OhpmPublishParser.Attachment stage(InputStream input, List<Path> staged) throws IOException {
    Path file = directory == null ? Files.createTempFile("kkrepo-ohpm-", ".upload")
        : Files.createTempFile(directory, "kkrepo-ohpm-", ".upload");
    staged.add(file);
    long size;
    try (var output = Files.newOutputStream(file)) {
      size = OhpmStreamParser.bounded(input, maxFileBytes).transferTo(output);
    }
    if (size == 0) throw invalid("Empty OHPM archive");
    return new OhpmPublishParser.Attachment(file, size);
  }

  private static String integrity(OhpmPublishParser.Attachment attachment) throws IOException {
    try (var input = Files.newInputStream(attachment.file())) {
      return OhpmIntegrity.compute(input).value();
    }
  }

  private ObjectNode manifest(Path file) throws IOException {
    ObjectNode manifest = null;
    try (var compressed = Files.newInputStream(file);
         var gzip = GzipCompressorInputStream.builder().setInputStream(compressed)
             .setDecompressConcatenated(true).get();
         var expanded = OhpmStreamParser.bounded(gzip, maxFileBytes * 4 + 1024 * 1024);
         var input = new PushbackInputStream(expanded, 16 * 1024);
         var archive = new TarArchiveInputStream(input, 512) {
           private int headers;

           @Override
           protected byte[] readRecord() throws IOException {
             byte[] record = super.readRecord();
             if (record != null && !isEOFRecord(record)) {
               if (++headers > 100_000) throw invalid("HAR contains too many archive entries");
               TarArchiveEntry header = new TarArchiveEntry(record);
               if (!header.isCheckSumOK() || header.isSparse()) throw invalid("Invalid HAR tar header");
               boolean extended = header.isPaxHeader() || header.isGlobalPaxHeader()
                   || header.isGNULongNameEntry() || header.isGNULongLinkEntry();
               if (header.getSize() < 0 || header.getSize() > (extended ? 16 * 1024 : maxFileBytes * 4)) {
                 throw new OhpmException(413, "HAR entry exceeds upload limits");
               }
               if (header.isPaxHeader() || header.isGlobalPaxHeader()) {
                 byte[] attributes = input.readNBytes((int) header.getSize());
                 if (attributes.length != header.getSize()) throw invalid("Truncated HAR PAX header");
                 String text = new String(attributes, StandardCharsets.UTF_8);
                 if (text.contains(" GNU.sparse.") || text.contains(" SCHILY.realsize=")) {
                   throw invalid("Sparse HAR entries are not supported");
                 }
                 input.unread(attributes);
               }
             }
             return record;
           }
         }) {
      TarArchiveEntry entry;
      while ((entry = archive.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory() && (name.equals(".") || name.equals("./"))) {
          if (entry.getSize() != 0) throw invalid("Invalid HAR directory entry");
          continue;
        }
        OhpmStreamParser.safePath(name);
        if (entry.isSparse() || entry.isLink() || entry.isSymbolicLink()
            || !(entry.isFile() || entry.isDirectory()) || !archive.canReadEntryData(entry)
            || entry.isDirectory() && entry.getSize() != 0) {
          throw invalid("HAR must contain regular files and directories");
        }
        if (name.startsWith("./")) name = name.substring(2);
        if ("package/oh-package.json5".equals(name) && entry.isFile()) {
          if (manifest != null) throw invalid("HAR contains duplicate manifests");
          if (entry.getSize() > MAX_MANIFEST_BYTES) throw new OhpmException(413, "OHPM manifest is too large");
          manifest = parseManifest(archive.readNBytes(MAX_MANIFEST_BYTES + 1));
        }
      }
      byte[] padding = new byte[8192];
      for (int count; (count = input.read(padding)) != -1;) {
        for (int index = 0; index < count; index++) {
          if (padding[index] != 0) throw invalid("Trailing data after HAR archive");
        }
      }
    } catch (IOException | IllegalArgumentException error) {
      throw invalid("Invalid HAR gzip/tar archive");
    }
    if (manifest == null) throw invalid("HAR is missing package/oh-package.json5");
    return manifest;
  }

  static ObjectNode parseManifest(byte[] bytes) throws IOException {
    if (bytes.length > MAX_MANIFEST_BYTES) throw new OhpmException(413, "OHPM manifest is too large");
    String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    var lexer = new Json5Lexer(new StringReader(text), JSON5_OPTIONS) {
      private int depth;
      private int values;

      @Override
      public Json5Element nextValue() {
        if (++depth > 64 || ++values > 100_000) throw invalid("OHPM manifest exceeds complexity limits");
        try {
          return super.nextValue();
        } finally {
          depth--;
        }
      }
    };
    try {
      Json5Element parsed = Json5Parser.parse(lexer);
      if (parsed == null || lexer.nextClean() != 0) throw invalid("Invalid OHPM manifest");
      var tree = toJson(parsed);
      if (!(tree instanceof ObjectNode object)) throw invalid("OHPM manifest must be an object");
      return object;
    } catch (Json5Exception error) {
      throw invalid("Invalid OHPM JSON5 manifest");
    }
  }

  private static JsonNode toJson(Json5Element element) {
    if (element.isJson5Null()) return JSON.getNodeFactory().nullNode();
    if (element.isJson5Object()) {
      ObjectNode object = JSON.createObjectNode();
      element.getAsJson5Object().entrySet().forEach(entry -> object.set(entry.getKey(), toJson(entry.getValue())));
      return object;
    }
    if (element.isJson5Array()) {
      var array = JSON.createArrayNode();
      element.getAsJson5Array().forEach(value -> array.add(toJson(value)));
      return array;
    }
    var primitive = element.getAsJson5Primitive();
    if (primitive.isBoolean()) return JSON.getNodeFactory().booleanNode(primitive.getAsBoolean());
    if (primitive.isNumber()) return JSON.valueToTree(primitive.getAsNumber());
    return JSON.getNodeFactory().textNode(primitive.getAsString());
  }

  private static OhpmException invalid(String message) {
    return new OhpmException(400, message);
  }
}
