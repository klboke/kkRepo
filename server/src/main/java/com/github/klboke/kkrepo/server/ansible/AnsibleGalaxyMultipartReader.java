package com.github.klboke.kkrepo.server.ansible;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Bounded multipart reader for Galaxy collection publication.
 *
 * <p>Ansible 2.9 emits {@code Content-Disposition: file} for the archive part instead of the
 * RFC-standard {@code form-data}. Servlet multipart parsers discard that legacy part before a
 * controller can inspect it. The application therefore resolves multipart requests lazily and
 * this reader spools the raw request to a bounded temporary file, accepting both disposition
 * forms without buffering collection bytes in the JVM heap.</p>
 */
@Component
public final class AnsibleGalaxyMultipartReader {
  private static final int MAX_PARTS = 16;
  private static final int MAX_HEADER_BYTES = 16 * 1024;
  private static final int MAX_SHA256_FIELD_BYTES = 128;
  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  private final long maxArtifactBytes;
  private final long maxBase64ArtifactBytes;
  private final long maxMultipartBytes;

  @Autowired
  public AnsibleGalaxyMultipartReader(
      @Value("${kkrepo.ansible.archive.max-compressed-bytes:1073741824}")
      long maxArtifactBytes,
      @Value("${kkrepo.ansible.multipart.max-overhead-bytes:1048576}")
      long maxMultipartOverheadBytes) {
    this.maxArtifactBytes = Math.max(1L, maxArtifactBytes);
    this.maxBase64ArtifactBytes = maxBase64BodyBytes(this.maxArtifactBytes);
    long overhead = Math.max(1L, maxMultipartOverheadBytes);
    long largestArtifactBody = Math.max(this.maxArtifactBytes, this.maxBase64ArtifactBytes);
    this.maxMultipartBytes = largestArtifactBody > Long.MAX_VALUE - overhead
        ? Long.MAX_VALUE
        : largestArtifactBody + overhead;
  }

  public Upload read(HttpServletRequest request) {
    String boundary = boundary(request == null ? null : request.getContentType());
    long contentLength = request.getContentLengthLong();
    if (contentLength > maxMultipartBytes) {
      throw new AnsibleGalaxyExceptions.ContentTooLarge(
          "Ansible publish multipart body exceeds the configured limit");
    }

    Path spool = null;
    try {
      spool = Files.createTempFile("kkrepo-ansible-multipart-", ".tmp");
      copyBounded(request.getInputStream(), spool);
      ParsedUpload parsed = parse(spool, boundary);
      Path retained = spool;
      spool = null;
      return new Upload(
          retained,
          parsed.fileOffset(),
          parsed.fileLength(),
          parsed.decodedFileLength(),
          parsed.filename(),
          parsed.sha256(),
          parsed.base64Encoded());
    } catch (AnsibleGalaxyExceptions.GalaxyException e) {
      delete(spool);
      throw e;
    } catch (IOException | RuntimeException e) {
      delete(spool);
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Unable to parse Ansible publish multipart body", e);
    }
  }

  private void copyBounded(InputStream input, Path target) throws IOException {
    long total = 0;
    try (InputStream source = input; var output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[COPY_BUFFER_BYTES];
      for (int read; (read = source.read(buffer)) >= 0;) {
        if (read == 0) continue;
        total += read;
        if (total > maxMultipartBytes) {
          throw new AnsibleGalaxyExceptions.ContentTooLarge(
              "Ansible publish multipart body exceeds the configured limit");
        }
        output.write(buffer, 0, read);
      }
    }
    if (total == 0) {
      throw new AnsibleGalaxyExceptions.BadRequest("Ansible publish multipart body is empty");
    }
  }

  private ParsedUpload parse(Path spool, String boundary) throws IOException {
    byte[] opening = ("--" + boundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    byte[] nextBoundary = ("\r\n--" + boundary + "\r\n")
        .getBytes(StandardCharsets.ISO_8859_1);
    byte[] finalBoundary = ("\r\n--" + boundary + "--")
        .getBytes(StandardCharsets.ISO_8859_1);
    FilePart file = null;
    String sha256 = null;
    int partCount = 0;

    try (RandomAccessFile input = new RandomAccessFile(spool.toFile(), "r")) {
      requireBytes(input, opening);
      boolean finished = false;
      while (!finished) {
        if (++partCount > MAX_PARTS) {
          throw badRequest("Ansible publish multipart body contains too many parts");
        }
        PartHeaders headers = readHeaders(input);
        long bodyOffset = input.getFilePointer();
        BoundaryHit hit = findBoundary(input, nextBoundary, finalBoundary);
        long bodyLength = hit.offset() - bodyOffset;
        if (bodyLength < 0) {
          throw badRequest("Ansible publish multipart body is malformed");
        }

        ContentDisposition disposition = contentDisposition(headers.contentDisposition());
        String type = disposition.getType().toLowerCase(Locale.ROOT);
        if (!"form-data".equals(type) && !"file".equals(type)) {
          throw badRequest("Unsupported Ansible publish multipart disposition");
        }
        String name = disposition.getName();
        String filename = disposition.getFilename();
        boolean filePart = filename != null && !filename.isBlank()
            && ("file".equals(name) || "file".equals(type));
        if (filePart) {
          if (file != null) {
            throw badRequest("Ansible publish requires exactly one file part");
          }
          if (bodyLength == 0) {
            throw badRequest("Ansible publish collection artifact is empty");
          }
          String transferEncoding = headers.contentTransferEncoding();
          boolean base64Encoded = transferEncoding != null
              && "base64".equalsIgnoreCase(transferEncoding.trim());
          if (transferEncoding != null && !base64Encoded) {
            throw badRequest("Unsupported Ansible publish Content-Transfer-Encoding");
          }
          if ((!base64Encoded && bodyLength > maxArtifactBytes)
              || (base64Encoded && bodyLength > maxBase64ArtifactBytes)) {
            throw new AnsibleGalaxyExceptions.ContentTooLarge(
                "Ansible collection artifact exceeds the upload limit");
          }
          long decodedLength = base64Encoded
              ? decodedBase64Length(input, bodyOffset, bodyLength)
              : bodyLength;
          if (decodedLength == 0) {
            throw badRequest("Ansible publish collection artifact is empty");
          }
          if (decodedLength > maxArtifactBytes) {
            throw new AnsibleGalaxyExceptions.ContentTooLarge(
                "Ansible collection artifact exceeds the upload limit");
          }
          file = new FilePart(
              bodyOffset, bodyLength, decodedLength, filename, base64Encoded);
        } else if ("sha256".equals(name) && filename == null) {
          if (sha256 != null) {
            throw badRequest("Ansible publish requires exactly one sha256 field");
          }
          if (bodyLength > MAX_SHA256_FIELD_BYTES) {
            throw badRequest("Ansible publish sha256 field is too large");
          }
          sha256 = readUtf8(input, bodyOffset, (int) bodyLength).trim();
        }

        finished = hit.finalBoundary();
        input.seek(hit.afterOffset());
      }
      requireEnd(input);
    }

    if (file == null || sha256 == null || sha256.isBlank()) {
      throw badRequest("Ansible publish requires file and sha256 multipart fields");
    }
    return new ParsedUpload(
        file.offset(),
        file.length(),
        file.decodedLength(),
        file.filename(),
        sha256,
        file.base64Encoded());
  }

  private static PartHeaders readHeaders(RandomAccessFile input) throws IOException {
    int consumed = 0;
    String contentDisposition = null;
    String contentTransferEncoding = null;
    while (true) {
      HeaderLine line = readCrlfLine(input, MAX_HEADER_BYTES - consumed);
      consumed += line.bytes();
      if (line.value().isEmpty()) break;
      int colon = line.value().indexOf(':');
      if (colon <= 0) {
        throw badRequest("Ansible publish multipart header is malformed");
      }
      String name = line.value().substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.value().substring(colon + 1).trim();
      if ("content-disposition".equals(name)) {
        if (contentDisposition != null) {
          throw badRequest("Duplicate Content-Disposition multipart header");
        }
        contentDisposition = value;
      } else if ("content-transfer-encoding".equals(name)) {
        if (contentTransferEncoding != null) {
          throw badRequest("Duplicate Content-Transfer-Encoding multipart header");
        }
        contentTransferEncoding = value;
      }
    }
    if (contentDisposition == null || contentDisposition.isBlank()) {
      throw badRequest("Ansible publish multipart part is missing Content-Disposition");
    }
    return new PartHeaders(contentDisposition, contentTransferEncoding);
  }

  private static long decodedBase64Length(RandomAccessFile input, long offset, long length)
      throws IOException {
    input.seek(offset);
    long remaining = length;
    long characters = 0;
    int padding = 0;
    boolean sawPadding = false;
    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    while (remaining > 0) {
      int requested = (int) Math.min(buffer.length, remaining);
      int read = input.read(buffer, 0, requested);
      if (read < 0) throw badRequest("Ansible publish base64 artifact is truncated");
      remaining -= read;
      for (int i = 0; i < read; i++) {
        int value = buffer[i] & 0xff;
        if (value == '\r' || value == '\n' || value == ' ' || value == '\t') continue;
        if (value == '=') {
          sawPadding = true;
          if (++padding > 2) throw badRequest("Ansible publish base64 artifact is malformed");
        } else if (isBase64Alphabet(value)) {
          if (sawPadding) throw badRequest("Ansible publish base64 artifact is malformed");
        } else {
          throw badRequest("Ansible publish base64 artifact is malformed");
        }
        characters++;
      }
    }
    if (characters == 0 || characters % 4 != 0) {
      throw badRequest("Ansible publish base64 artifact is malformed");
    }
    return Math.subtractExact(Math.multiplyExact(characters / 4, 3), padding);
  }

  private static boolean isBase64Alphabet(int value) {
    return value >= 'A' && value <= 'Z'
        || value >= 'a' && value <= 'z'
        || value >= '0' && value <= '9'
        || value == '+' || value == '/';
  }

  private static long maxBase64BodyBytes(long decodedBytes) {
    long groups = decodedBytes / 3 + (decodedBytes % 3 == 0 ? 0 : 1);
    if (groups > Long.MAX_VALUE / 4) return Long.MAX_VALUE;
    long characters = groups * 4;
    long lines = characters / 76 + (characters % 76 == 0 ? 0 : 1);
    if (lines > (Long.MAX_VALUE - characters) / 2) return Long.MAX_VALUE;
    return characters + lines * 2;
  }

  private static HeaderLine readCrlfLine(RandomAccessFile input, int remaining)
      throws IOException {
    if (remaining <= 0) {
      throw badRequest("Ansible publish multipart headers are too large");
    }
    byte[] line = new byte[Math.min(remaining, MAX_HEADER_BYTES)];
    int count = 0;
    boolean carriageReturn = false;
    while (count < line.length) {
      int next = input.read();
      if (next < 0) {
        throw badRequest("Ansible publish multipart headers are truncated");
      }
      if (carriageReturn) {
        if (next != '\n') {
          throw badRequest("Ansible publish multipart headers require CRLF line endings");
        }
        return new HeaderLine(
            new String(line, 0, count - 1, StandardCharsets.ISO_8859_1), count + 1);
      }
      line[count++] = (byte) next;
      carriageReturn = next == '\r';
      if (next == '\n') {
        throw badRequest("Ansible publish multipart headers require CRLF line endings");
      }
    }
    throw badRequest("Ansible publish multipart headers are too large");
  }

  private static ContentDisposition contentDisposition(String value) {
    try {
      return ContentDisposition.parse(value);
    } catch (IllegalArgumentException e) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible publish Content-Disposition is malformed", e);
    }
  }

  private static BoundaryHit findBoundary(
      RandomAccessFile input, byte[] nextBoundary, byte[] finalBoundary) throws IOException {
    int[] nextFailure = failureTable(nextBoundary);
    int[] finalFailure = failureTable(finalBoundary);
    int nextMatch = 0;
    int finalMatch = 0;
    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    long chunkOffset = input.getFilePointer();
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (read == 0) continue;
      for (int i = 0; i < read; i++) {
        byte value = buffer[i];
        nextMatch = advance(nextBoundary, nextFailure, nextMatch, value);
        finalMatch = advance(finalBoundary, finalFailure, finalMatch, value);
        long end = chunkOffset + i + 1L;
        if (nextMatch == nextBoundary.length) {
          long offset = end - nextBoundary.length;
          return new BoundaryHit(offset, end, false);
        }
        if (finalMatch == finalBoundary.length) {
          long offset = end - finalBoundary.length;
          return new BoundaryHit(offset, end, true);
        }
      }
      chunkOffset += read;
    }
    throw badRequest("Ansible publish multipart closing boundary is missing");
  }

  private static int advance(byte[] pattern, int[] failure, int matched, byte value) {
    while (matched > 0 && pattern[matched] != value) {
      matched = failure[matched - 1];
    }
    if (pattern[matched] == value) matched++;
    return matched;
  }

  private static int[] failureTable(byte[] pattern) {
    int[] failure = new int[pattern.length];
    for (int i = 1, matched = 0; i < pattern.length; i++) {
      while (matched > 0 && pattern[i] != pattern[matched]) {
        matched = failure[matched - 1];
      }
      if (pattern[i] == pattern[matched]) matched++;
      failure[i] = matched;
    }
    return failure;
  }

  private static void requireBytes(RandomAccessFile input, byte[] expected) throws IOException {
    byte[] actual = new byte[expected.length];
    try {
      input.readFully(actual);
    } catch (IOException e) {
      throw badRequest("Ansible publish multipart opening boundary is malformed");
    }
    if (!java.util.Arrays.equals(actual, expected)) {
      throw badRequest("Ansible publish multipart opening boundary is malformed");
    }
  }

  private static String readUtf8(RandomAccessFile input, long offset, int length)
      throws IOException {
    byte[] value = new byte[length];
    input.seek(offset);
    input.readFully(value);
    return new String(value, StandardCharsets.UTF_8);
  }

  private static void requireEnd(RandomAccessFile input) throws IOException {
    long remaining = input.length() - input.getFilePointer();
    if (remaining == 0) return;
    if (remaining == 2 && input.read() == '\r' && input.read() == '\n') return;
    throw badRequest("Ansible publish multipart epilogue is malformed");
  }

  private static String boundary(String contentType) {
    try {
      MediaType mediaType = MediaType.parseMediaType(contentType == null ? "" : contentType);
      if (!MediaType.MULTIPART_FORM_DATA.isCompatibleWith(mediaType)) {
        throw badRequest("Ansible collection publish requires multipart/form-data");
      }
      String boundary = unquoteBoundary(mediaType.getParameter("boundary"));
      if (!validBoundary(boundary)) {
        throw badRequest("Ansible publish multipart boundary is invalid");
      }
      return boundary;
    } catch (AnsibleGalaxyExceptions.GalaxyException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      throw new AnsibleGalaxyExceptions.BadRequest(
          "Ansible publish multipart Content-Type is malformed", e);
    }
  }

  private static boolean validBoundary(String boundary) {
    if (boundary == null || boundary.isEmpty() || boundary.length() > 70
        || boundary.charAt(boundary.length() - 1) == ' ') {
      return false;
    }
    for (int i = 0; i < boundary.length(); i++) {
      char c = boundary.charAt(i);
      boolean valid = Character.isLetterOrDigit(c)
          || "'()+_,-./:=? ".indexOf(c) >= 0;
      if (!valid || c > 0x7f) return false;
    }
    return true;
  }

  private static String unquoteBoundary(String boundary) {
    if (boundary == null || boundary.length() < 2
        || boundary.charAt(0) != '"' || boundary.charAt(boundary.length() - 1) != '"') {
      return boundary;
    }
    StringBuilder value = new StringBuilder(boundary.length() - 2);
    boolean escaped = false;
    for (int i = 1; i < boundary.length() - 1; i++) {
      char c = boundary.charAt(i);
      if (escaped) {
        value.append(c);
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else {
        value.append(c);
      }
    }
    return escaped ? null : value.toString();
  }

  private static AnsibleGalaxyExceptions.BadRequest badRequest(String detail) {
    return new AnsibleGalaxyExceptions.BadRequest(detail);
  }

  private static void delete(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // The system temporary directory cleanup remains a final safety net.
    }
  }

  /** A bounded view of the archive bytes retained only for the publication call. */
  public static final class Upload implements AutoCloseable {
    private final Path spool;
    private final long offset;
    private final long length;
    private final long decodedLength;
    private final String filename;
    private final String sha256;
    private final boolean base64Encoded;
    private boolean closed;

    private Upload(
        Path spool,
        long offset,
        long length,
        long decodedLength,
        String filename,
        String sha256,
        boolean base64Encoded) {
      this.spool = spool;
      this.offset = offset;
      this.length = length;
      this.decodedLength = decodedLength;
      this.filename = filename;
      this.sha256 = sha256;
      this.base64Encoded = base64Encoded;
    }

    public InputStream openStream() throws IOException {
      if (closed) throw new IOException("Ansible multipart upload is closed");
      FileChannel channel = FileChannel.open(spool, StandardOpenOption.READ);
      channel.position(offset);
      InputStream range = new RangeInputStream(channel, length);
      return base64Encoded ? Base64.getMimeDecoder().wrap(range) : range;
    }

    public String filename() {
      return filename;
    }

    public String sha256() {
      return sha256;
    }

    public long size() {
      return decodedLength;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      delete(spool);
    }
  }

  private static final class RangeInputStream extends InputStream {
    private final FileChannel channel;
    private long remaining;

    private RangeInputStream(FileChannel channel, long remaining) {
      this.channel = channel;
      this.remaining = remaining;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) return 0;
      if (remaining == 0) return -1;
      int requested = (int) Math.min(length, remaining);
      int read = channel.read(ByteBuffer.wrap(bytes, offset, requested));
      if (read < 0) throw new IOException("Ansible multipart artifact is truncated");
      remaining -= read;
      return read;
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }

  private record PartHeaders(String contentDisposition, String contentTransferEncoding) {}

  private record HeaderLine(String value, int bytes) {}

  private record BoundaryHit(long offset, long afterOffset, boolean finalBoundary) {}

  private record FilePart(
      long offset,
      long length,
      long decodedLength,
      String filename,
      boolean base64Encoded) {}

  private record ParsedUpload(
      long fileOffset,
      long fileLength,
      long decodedFileLength,
      String filename,
      String sha256,
      boolean base64Encoded) {}
}
