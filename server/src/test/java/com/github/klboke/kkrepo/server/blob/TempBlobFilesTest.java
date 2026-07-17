package com.github.klboke.kkrepo.server.blob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.klboke.kkrepo.core.BlobFileRegion;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class TempBlobFilesTest {
  @AfterEach
  void resetBufferSize() {
    TempBlobFiles.configureResponseBufferSize(TempBlobFiles.DEFAULT_TRANSFER_BUFFER_SIZE);
  }

  @Test
  void transferBufferDefaultsToOneMegabyteAndIsConfigurableWithinBounds() {
    assertEquals(1024 * 1024, TempBlobFiles.responseBufferSize());

    TempBlobFiles.configureResponseBufferSize(2 * 1024 * 1024);
    assertEquals(2 * 1024 * 1024, TempBlobFiles.responseBufferSize());

    TempBlobFiles.configureResponseBufferSize(1);
    assertEquals(8 * 1024, TempBlobFiles.responseBufferSize());

    TempBlobFiles.configureResponseBufferSize(32 * 1024 * 1024);
    assertEquals(16 * 1024 * 1024, TempBlobFiles.responseBufferSize());
  }

  @Test
  void createTempFileRecreatesMissingStagingDirectory(@TempDir Path tempDir) throws Exception {
    Path stagingDirectory = tempDir.resolve("blob-root").resolve(".tmp");

    Path tempFile = TempBlobFiles.createTempFile(
        new StagingOnlyBlobStorage(stagingDirectory),
        "kkrepo-test-",
        ".blob");

    assertTrue(Files.isRegularFile(tempFile));
    assertTrue(tempFile.startsWith(stagingDirectory));
  }

  @Test
  void copyResponseUsesFileRegionTransferFastPath() throws Exception {
    TransferBody body = new TransferBody("fast-path");
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    TempBlobFiles.copyResponse(body, out);

    assertTrue(body.transferred);
    assertTrue(body.closed);
    assertArrayEquals("fast-path".getBytes(StandardCharsets.UTF_8), out.toByteArray());
  }

  @Test
  void copyResponseIgnoresClientAbortDuringBufferedWrite() {
    InputStream body = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8));

    assertDoesNotThrow(() -> TempBlobFiles.copyResponse(body, new FailingOutputStream("Broken pipe")));
  }

  @Test
  void copyResponseLogsClientAbortWithRequestContext() {
    Map<String, Object> attributes = new HashMap<>();
    HttpServletRequest request = requestWithAttributes(attributes);
    InputStream body = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8));

    assertDoesNotThrow(() -> TempBlobFiles.copyResponse(body, new FailingOutputStream("Broken pipe"), request));
    assertTrue(TempBlobFiles.hasHandledClientAbort(request));
  }

  @Test
  void clientAbortLogRedactsSwiftIdentifiersUrlQuery() {
    Map<String, Object> attributes = new HashMap<>();
    String sensitiveQuery =
        "url=https%3A%2F%2Ftoken%40github.com%2Fprivate-owner%2Fprivate-package.git%3Faccess_token%3Dsecret";
    HttpServletRequest request = requestWithAttributes(
        attributes,
        "/repository/swift-group/identifiers",
        sensitiveQuery);
    ListAppender<ILoggingEvent> appender = attachAppender();

    try {
      TempBlobFiles.logClientAbort(request, new IOException("Broken pipe"));
    } finally {
      detachAppender(appender);
    }

    assertEquals(1, appender.list.size());
    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("uri=/repository/swift-group/identifiers?url=<redacted>"));
    assertFalse(message.contains("private-owner"));
    assertFalse(message.contains("private-package"));
    assertFalse(message.contains("access_token"));
    assertFalse(message.contains("secret"));
  }

  @Test
  void copyResponseRecordsBytesWrittenBeforeClientAbort() {
    TempBlobFiles.configureResponseBufferSize(8 * 1024);
    Map<String, Object> attributes = new HashMap<>();
    HttpServletRequest request = requestWithAttributes(attributes);
    byte[] payload = new byte[9 * 1024];
    InputStream body = new ByteArrayInputStream(payload);

    assertDoesNotThrow(() -> TempBlobFiles.copyResponse(
        body,
        new FailingAfterWritesOutputStream(1, "Connection reset by peer"),
        request));

    assertEquals(8L * 1024, request.getAttribute(TempBlobFiles.RESPONSE_BYTES_WRITTEN_ATTR));
    assertTrue(request.getAttribute(TempBlobFiles.RESPONSE_COPY_STARTED_AT_NANOS_ATTR) instanceof Long);
  }

  @Test
  void copyResponseRecordsBytesWrittenBeforeUncheckedClientAbort() {
    TempBlobFiles.configureResponseBufferSize(8 * 1024);
    Map<String, Object> attributes = new HashMap<>();
    HttpServletRequest request = requestWithAttributes(attributes);
    byte[] payload = new byte[9 * 1024];
    InputStream body = new ByteArrayInputStream(payload);

    assertDoesNotThrow(() -> TempBlobFiles.copyResponse(
        body,
        new RuntimeFailingAfterWritesOutputStream(1),
        request));

    assertEquals(8L * 1024, request.getAttribute(TempBlobFiles.RESPONSE_BYTES_WRITTEN_ATTR));
    assertTrue(TempBlobFiles.hasHandledClientAbort(request));
  }

  @Test
  void copyResponseRecordsBytesWrittenBeforeUncheckedFileRegionClientAbort() {
    Map<String, Object> attributes = new HashMap<>();
    HttpServletRequest request = requestWithAttributes(attributes);
    TransferBody body = new TransferBody("payload");
    body.runtimeFailureAfterBytes = 4;
    body.runtimeFailure = new IllegalStateException(
        "ServletOutputStream failed", new IOException("Broken pipe"));

    assertDoesNotThrow(() -> TempBlobFiles.copyResponse(body, new ByteArrayOutputStream(), request));

    assertEquals(4L, request.getAttribute(TempBlobFiles.RESPONSE_BYTES_WRITTEN_ATTR));
    assertTrue(TempBlobFiles.hasHandledClientAbort(request));
  }

  @Test
  void recognizesSpringAsyncClientAbortWrapper() {
    IOException brokenPipe = new IOException("Broken pipe");
    AsyncRequestNotUsableException exception =
        new AsyncRequestNotUsableException("ServletOutputStream failed to write", brokenPipe);

    assertTrue(TempBlobFiles.isClientAbort(exception));
  }

  @Test
  void copyResponseIgnoresClientAbortDuringFileRegionTransfer() {
    TransferBody body = new TransferBody("payload");
    body.writeFailure = new IOException("Connection reset by peer");

    assertDoesNotThrow(() -> TempBlobFiles.copyResponse(body, new ByteArrayOutputStream()));
    assertTrue(body.closed);
  }

  @Test
  void copyResponsePropagatesNonClientAbortFileRegionFailures() {
    TransferBody body = new TransferBody("payload");
    body.writeFailure = new IOException("file read failed");

    IOException thrown = assertThrows(IOException.class,
        () -> TempBlobFiles.copyResponse(body, new ByteArrayOutputStream()));

    assertEquals("file read failed", thrown.getMessage());
  }

  @Test
  void copyResponsePropagatesNonClientAbortWriteFailures() {
    InputStream body = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8));

    IOException thrown = assertThrows(IOException.class,
        () -> TempBlobFiles.copyResponse(body, new FailingOutputStream("disk failed")));

    assertEquals("disk failed", thrown.getMessage());
  }

  @Test
  void copyResponsePropagatesReadFailures() {
    InputStream body = new InputStream() {
      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        throw new IOException("blob read failed");
      }

      @Override
      public int read() throws IOException {
        throw new IOException("blob read failed");
      }
    };

    IOException thrown = assertThrows(IOException.class,
        () -> TempBlobFiles.copyResponse(body, new ByteArrayOutputStream()));

    assertEquals("blob read failed", thrown.getMessage());
  }

  @Test
  void tomcatSendfileAttributesUseExclusiveEndAndCloseBody() {
    Map<String, Object> attributes = new HashMap<>();
    HttpServletRequest request = requestWithAttributes(attributes);
    request.setAttribute(TempBlobFiles.TOMCAT_SENDFILE_SUPPORTED_ATTR, Boolean.TRUE);
    Path path = Path.of("/blob/root/object.bin");
    TransferBody body = new TransferBody("0123456789", path, 2, 5);

    assertTrue(TempBlobFiles.tryUseTomcatSendfile(request, body));

    assertTrue(body.closed);
    assertEquals(path.toAbsolutePath().toString(),
        request.getAttribute(TempBlobFiles.TOMCAT_SENDFILE_FILENAME_ATTR));
    assertEquals(2L, request.getAttribute(TempBlobFiles.TOMCAT_SENDFILE_FILE_START_ATTR));
    assertEquals(7L, request.getAttribute(TempBlobFiles.TOMCAT_SENDFILE_FILE_END_ATTR));
  }

  private static HttpServletRequest requestWithAttributes(Map<String, Object> attributes) {
    return requestWithAttributes(attributes, "/repository/pypi/packages/example.whl", null);
  }

  private static HttpServletRequest requestWithAttributes(
      Map<String, Object> attributes,
      String requestUri,
      String queryString) {
    return (HttpServletRequest) Proxy.newProxyInstance(
        TempBlobFilesTest.class.getClassLoader(),
        new Class<?>[]{HttpServletRequest.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getAttribute" -> attributes.get((String) args[0]);
          case "setAttribute" -> {
            attributes.put((String) args[0], args[1]);
            yield null;
          }
          case "getMethod" -> "GET";
          case "getRequestURI" -> requestUri;
          case "getQueryString" -> queryString;
          case "getRemoteAddr" -> "127.0.0.1";
          case "getHeader" -> switch ((String) args[0]) {
            case "User-Agent" -> "uv/0.11.19";
            case "X-Forwarded-For" -> "10.0.0.1";
            case "X-Request-Id" -> "request-1";
            case "Range" -> "bytes=3-";
            case "If-Range" -> "\"etag\"";
            case "X-Envoy-Expected-Rq-Timeout-Ms" -> "15000";
            default -> null;
          };
          default -> defaultValue(method.getReturnType());
        });
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(TempBlobFiles.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(TempBlobFiles.class);
    logger.detachAppender(appender);
  }

  private static Object defaultValue(Class<?> returnType) {
    if (returnType == boolean.class) return false;
    if (returnType == byte.class) return (byte) 0;
    if (returnType == short.class) return (short) 0;
    if (returnType == int.class) return 0;
    if (returnType == long.class) return 0L;
    if (returnType == float.class) return 0F;
    if (returnType == double.class) return 0D;
    if (returnType == char.class) return '\0';
    return null;
  }

  private static final class TransferBody extends InputStream implements BlobFileRegion {
    private final byte[] payload;
    private final Path path;
    private final long position;
    private final long count;
    private boolean transferred;
    private boolean closed;
    private IOException writeFailure;
    private int runtimeFailureAfterBytes = -1;
    private RuntimeException runtimeFailure;

    private TransferBody(String payload) {
      this(payload, Path.of("/unused"), 0, payload.getBytes(StandardCharsets.UTF_8).length);
    }

    private TransferBody(String payload, Path path, long position, long count) {
      this.payload = payload.getBytes(StandardCharsets.UTF_8);
      this.path = path;
      this.position = position;
      this.count = count;
    }

    @Override
    public Path path() {
      return path;
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public long count() {
      return count;
    }

    @Override
    public void transferFileRegionTo(OutputStream out) throws IOException {
      transferred = true;
      if (writeFailure != null) {
        throw writeFailure;
      }
      if (runtimeFailure != null) {
        int bytesBeforeFailure = Math.max(0, Math.min(runtimeFailureAfterBytes, payload.length));
        out.write(payload, 0, bytesBeforeFailure);
        throw runtimeFailure;
      }
      out.write(payload);
    }

    @Override
    public int read() {
      throw new AssertionError("copyResponse should use transferTo for BlobFileRegion");
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class FailingOutputStream extends OutputStream {
    private final String message;

    private FailingOutputStream(String message) {
      this.message = message;
    }

    @Override
    public void write(int b) throws IOException {
      throw new IOException(message);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      throw new IOException(message);
    }
  }

  private static final class FailingAfterWritesOutputStream extends OutputStream {
    private final int successfulWrites;
    private final String message;
    private int writes;

    private FailingAfterWritesOutputStream(int successfulWrites, String message) {
      this.successfulWrites = successfulWrites;
      this.message = message;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (writes++ >= successfulWrites) {
        throw new IOException(message);
      }
    }
  }

  private static final class RuntimeFailingAfterWritesOutputStream extends OutputStream {
    private final int successfulWrites;
    private int writes;

    private RuntimeFailingAfterWritesOutputStream(int successfulWrites) {
      this.successfulWrites = successfulWrites;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (writes++ >= successfulWrites) {
        throw new IllegalStateException(
            "ServletOutputStream failed to write", new IOException("Broken pipe"));
      }
    }
  }

  private static final class StagingOnlyBlobStorage implements BlobStorage {
    private final Path stagingDirectory;

    private StagingOnlyBlobStorage(Path stagingDirectory) {
      this.stagingDirectory = stagingDirectory;
    }

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Path> stagingDirectory() {
      return Optional.of(stagingDirectory);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }
}
