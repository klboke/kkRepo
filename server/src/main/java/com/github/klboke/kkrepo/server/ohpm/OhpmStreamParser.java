package com.github.klboke.kkrepo.server.ohpm;

import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPublishMetadata;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import jakarta.servlet.http.Part;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class OhpmStreamParser {
  private static final long MAX_METADATA_BYTES = 8 * 1024 * 1024;
  private static final long MAX_ARCHIVE_OVERHEAD = 1024 * 1024;
  private final long maxFileBytes;
  private final Path directory;
  private final OhpmPublishParser metadataParser;

  OhpmStreamParser(long maxFileBytes, Path directory) {
    this.metadataParser = new OhpmPublishParser(maxFileBytes, directory);
    this.maxFileBytes = maxFileBytes;
    this.directory = directory;
  }

  OhpmPublishParser.Upload parse(OhpmPackageId packageId, Collection<Part> parts) throws IOException {
    List<Path> staged = new ArrayList<>();
    try {
      Map<String, Part> fields = fields(parts);
      Part metadataPart = fields.get("metadata");
      if (metadataPart.getSize() > MAX_METADATA_BYTES) throw tooLarge();
      OhpmPublishMetadata metadata;
      try (InputStream input = bounded(metadataPart.getInputStream(), MAX_METADATA_BYTES)) {
        metadata = metadataParser.metadata(packageId, input);
      }
      Part content = fields.get("pkg_stream");
      long limit = metadata.isHsp() ? maxFileBytes * 2 + MAX_ARCHIVE_OVERHEAD : maxFileBytes;
      if (content.getSize() < 1 || content.getSize() > limit) throw tooLarge();
      try (InputStream input = bounded(content.getInputStream(), limit)) {
        if (!metadata.isHsp()) {
          var har = stage(input, staged);
          return new OhpmPublishParser.Upload(metadata, OhpmPublishParser.verified(har, metadata.harIntegrity()), null);
        }
        return hsp(metadata, input, staged);
      }
    } catch (IOException | RuntimeException error) {
      staged.forEach(TempBlobFiles::deleteQuietly);
      throw error;
    } finally {
      if (parts != null) {
        for (Part part : parts) {
          if (part != null) {
            try {
              part.delete();
            } catch (IOException ignored) {
            }
          }
        }
      }
    }
  }

  private OhpmPublishParser.Upload hsp(OhpmPublishMetadata metadata, InputStream input, List<Path> staged)
      throws IOException {
    OhpmPublishParser.Attachment har = null;
    OhpmPublishParser.Attachment hsp = null;
    try (InputStream expanded = expanded(input);
         PushbackInputStream tarInput = new PushbackInputStream(expanded, 16 * 1024);
         TarArchiveInputStream archive = new TarArchiveInputStream(tarInput, 512) {
           private int headers;

           @Override
           protected byte[] readRecord() throws IOException {
             byte[] record = super.readRecord();
             if (record != null && !isEOFRecord(record)) {
               if (++headers > 32) throw invalid("HSP archive contains too many entries");
               TarArchiveEntry header = new TarArchiveEntry(record);
               if (!header.isCheckSumOK()) throw invalid("Invalid HSP tar header checksum");
               if (header.isSparse()) throw invalid("HSP sparse archive entries are not supported");
               boolean extended = header.isPaxHeader() || header.isGlobalPaxHeader()
                   || header.isGNULongNameEntry() || header.isGNULongLinkEntry();
               if (header.getSize() < 0) throw invalid("Invalid HSP tar entry length");
               if (header.getSize() > (extended ? 16 * 1024 : maxFileBytes)) throw tooLarge();
               if (header.isPaxHeader() || header.isGlobalPaxHeader()) {
                 byte[] attributes = tarInput.readNBytes((int) header.getSize());
                 if (attributes.length != header.getSize()) throw invalid("Truncated HSP PAX header");
                 String text = new String(attributes, StandardCharsets.UTF_8);
                 if (text.contains(" GNU.sparse.") || text.contains(" SCHILY.realsize=")) {
                   throw invalid("HSP sparse archive entries are not supported");
                 }
                 tarInput.unread(attributes);
               }
             }
             return record;
           }
         }) {
      TarArchiveEntry entry;
      while ((entry = next(archive)) != null) {
        if (entry.isDirectory()) {
          if (entry.getSize() != 0) throw invalid("Invalid HSP directory entry");
          if (!entry.getName().equals(".") && !entry.getName().equals("./")) safePath(entry.getName());
          continue;
        }
        safePath(entry.getName());
        if (!entry.isFile() || entry.isSparse() || entry.isLink() || entry.isSymbolicLink()
            || !archive.canReadEntryData(entry)) {
          throw invalid("HSP archive must contain regular HAR and HSP files");
        }
        if (entry.getSize() < 1 || entry.getSize() > maxFileBytes) throw tooLarge();
        if (entry.getName().endsWith(".har") && har == null) {
          har = stage(archive, staged);
        } else if (entry.getName().endsWith(".hsp") && hsp == null) {
          hsp = stage(archive, staged);
        } else {
          throw invalid("Unexpected or duplicate HSP archive file");
        }
      }
      byte[] padding = new byte[8192];
      for (int count; (count = tarInput.read(padding)) != -1;) {
        for (int index = 0; index < count; index++) {
          if (padding[index] != 0) throw invalid("Trailing data after HSP archive");
        }
      }
    } catch (IllegalArgumentException error) {
      throw invalid("Invalid HSP archive");
    }
    return new OhpmPublishParser.Upload(metadata,
        OhpmPublishParser.verified(har, metadata.harIntegrity()),
        OhpmPublishParser.verified(hsp, metadata.hspIntegrity()));
  }

  private InputStream expanded(InputStream input) throws IOException {
    try {
      InputStream gzip = GzipCompressorInputStream.builder().setInputStream(input)
          .setDecompressConcatenated(true).get();
      return bounded(new FilterInputStream(gzip) {
        @Override
        public int read() throws IOException {
          try {
            return in.read();
          } catch (IOException error) {
            throw invalid("Invalid or interrupted HSP gzip stream");
          }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
          try {
            return in.read(bytes, offset, length);
          } catch (IOException error) {
            throw invalid("Invalid or interrupted HSP gzip stream");
          }
        }
      }, maxFileBytes * 2 + MAX_ARCHIVE_OVERHEAD);
    } catch (IOException error) {
      throw invalid("Invalid HSP gzip stream");
    }
  }

  private static TarArchiveEntry next(TarArchiveInputStream archive) throws IOException {
    try {
      return archive.getNextEntry();
    } catch (IOException error) {
      throw invalid("Invalid or interrupted HSP tar stream");
    }
  }

  private OhpmPublishParser.Attachment stage(InputStream input, List<Path> files) throws IOException {
    Path file = directory == null ? Files.createTempFile("kkrepo-ohpm-", ".upload")
        : Files.createTempFile(directory, "kkrepo-ohpm-", ".upload");
    files.add(file);
    long total = 0;
    try (var output = Files.newOutputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int count; (count = read(input, buffer)) != -1;) {
        if (count > maxFileBytes - total) throw tooLarge();
        total += count;
        output.write(buffer, 0, count);
      }
    }
    if (total == 0) throw invalid("Empty OHPM attachment");
    return new OhpmPublishParser.Attachment(file, total);
  }

  private static int read(InputStream input, byte[] buffer) throws IOException {
    try {
      return input.read(buffer);
    } catch (IOException error) {
      if (input instanceof TarArchiveInputStream) throw invalid("Invalid or interrupted HSP tar content");
      throw error;
    }
  }

  private static Map<String, Part> fields(Collection<Part> parts) {
    if (parts == null || parts.size() != 2) throw invalid("OHPM stream requires metadata and pkg_stream");
    Map<String, Part> fields = new HashMap<>();
    for (Part part : parts) {
      if (part == null || !List.of("metadata", "pkg_stream").contains(part.getName())
          || fields.putIfAbsent(part.getName(), part) != null) {
        throw invalid("Unexpected or duplicate OHPM multipart field");
      }
      if (part.getHeader("Content-Transfer-Encoding") != null) {
        throw invalid("OHPM multipart fields must contain raw bytes");
      }
    }
    return fields;
  }

  private static void safePath(String name) {
    if (name.startsWith("./")) name = name.substring(2);
    if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
    if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")
        || name.chars().anyMatch(value -> value < 32 || value == 127)) throw invalid("Unsafe HSP archive path");
    for (String segment : name.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw invalid("Unsafe HSP archive path");
    }
  }

  private static InputStream bounded(InputStream input, long limit) {
    return new FilterInputStream(input) {
      private long remaining = limit;

      @Override
      public int read() throws IOException {
        int value = in.read();
        if (value >= 0 && --remaining < 0) throw tooLarge();
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
        if (count > 0) remaining -= count;
        if (remaining < 0) throw tooLarge();
        return count;
      }

      @Override
      public long skip(long count) throws IOException {
        long skipped = 0;
        byte[] buffer = new byte[8192];
        while (skipped < count) {
          int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
          if (read < 0) break;
          skipped += read;
        }
        return skipped;
      }
    };
  }

  private static OhpmException invalid(String message) { return new OhpmException(400, message); }
  private static OhpmException tooLarge() { return new OhpmException(413, "OHPM stream exceeds upload limits"); }
}
