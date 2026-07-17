package com.github.klboke.kkrepo.server.blob;

import com.github.klboke.kkrepo.core.BlobFileRegion;
import com.github.klboke.kkrepo.core.BlobStorage;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TempBlobFiles {
  private static final Logger log = LoggerFactory.getLogger(TempBlobFiles.class);
  static final String TOMCAT_SENDFILE_SUPPORTED_ATTR = "org.apache.tomcat.sendfile.support";
  static final String TOMCAT_SENDFILE_FILENAME_ATTR = "org.apache.tomcat.sendfile.filename";
  static final String TOMCAT_SENDFILE_FILE_START_ATTR = "org.apache.tomcat.sendfile.start";
  static final String TOMCAT_SENDFILE_FILE_END_ATTR = "org.apache.tomcat.sendfile.end";
  static final String CLIENT_ABORT_HANDLED_ATTR = TempBlobFiles.class.getName() + ".CLIENT_ABORT_HANDLED";
  static final String RESPONSE_BYTES_WRITTEN_ATTR = TempBlobFiles.class.getName() + ".RESPONSE_BYTES_WRITTEN";
  static final String RESPONSE_COPY_STARTED_AT_NANOS_ATTR =
      TempBlobFiles.class.getName() + ".RESPONSE_COPY_STARTED_AT_NANOS";
  static final int DEFAULT_TRANSFER_BUFFER_SIZE = 1024 * 1024;
  private static final int MIN_TRANSFER_BUFFER_SIZE = 8 * 1024;
  private static final int MAX_TRANSFER_BUFFER_SIZE = 16 * 1024 * 1024;
  private static volatile int responseBufferSize = DEFAULT_TRANSFER_BUFFER_SIZE;

  private TempBlobFiles() {
  }

  public static int responseBufferSize() {
    return responseBufferSize;
  }

  public static void configureResponseBufferSize(int bytes) {
    responseBufferSize = Math.max(MIN_TRANSFER_BUFFER_SIZE, Math.min(MAX_TRANSFER_BUFFER_SIZE, bytes));
  }

  public static Path createTempFile(BlobStorage storage, String prefix, String suffix) throws IOException {
    if (storage != null) {
      var stagingDirectory = storage.stagingDirectory();
      if (stagingDirectory.isPresent()) {
        Path staging = stagingDirectory.get();
        Files.createDirectories(staging);
        return Files.createTempFile(staging, prefix, suffix);
      }
    }
    return Files.createTempFile(prefix, suffix);
  }

  public static void copyResponse(InputStream in, OutputStream out) throws IOException {
    copyResponse(in, out, null);
  }

  public static void copyResponse(InputStream in, OutputStream out, HttpServletRequest request) throws IOException {
    if (in == null) {
      return;
    }
    long bytesWritten = 0;
    markResponseCopyStarted(request);
    try (InputStream src = in) {
      if (src instanceof BlobFileRegion fileRegion) {
        CountingOutputStream countingOut = new CountingOutputStream(out);
        try {
          fileRegion.transferFileRegionTo(countingOut);
        } catch (IOException e) {
          markBytesWritten(request, countingOut.bytesWritten());
          if (handleClientAbort(request, e)) {
            return;
          }
          throw e;
        } catch (RuntimeException e) {
          markBytesWritten(request, countingOut.bytesWritten());
          if (handleClientAbort(request, e)) {
            return;
          }
          throw e;
        }
        return;
      }
      byte[] buffer = new byte[responseBufferSize()];
      int read;
      while ((read = src.read(buffer)) != -1) {
        try {
          out.write(buffer, 0, read);
          bytesWritten += read;
        } catch (IOException e) {
          markBytesWritten(request, bytesWritten);
          if (handleClientAbort(request, e)) {
            return;
          }
          throw e;
        } catch (RuntimeException e) {
          markBytesWritten(request, bytesWritten);
          if (handleClientAbort(request, e)) {
            return;
          }
          throw e;
        }
      }
    }
  }

  static boolean isClientAbort(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      String className = current.getClass().getName();
      String simpleName = current.getClass().getSimpleName();
      if ("org.apache.catalina.connector.ClientAbortException".equals(className)
          || "org.apache.coyote.CloseNowException".equals(className)
          || "org.eclipse.jetty.io.EofException".equals(className)
          || "ClientAbortException".equals(simpleName)) {
        return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("broken pipe")
            || normalized.contains("connection reset by peer")
            || normalized.equals("connection reset")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  static boolean hasHandledClientAbort(HttpServletRequest request) {
    return request != null && Boolean.TRUE.equals(request.getAttribute(CLIENT_ABORT_HANDLED_ATTR));
  }

  static boolean handleClientAbort(HttpServletRequest request, Throwable exception) {
    if (!isClientAbort(exception)) {
      return false;
    }
    logClientAbort(request, exception);
    return true;
  }

  static void logClientAbort(HttpServletRequest request, Throwable exception) {
    if (request != null) {
      if (Boolean.TRUE.equals(request.getAttribute(CLIENT_ABORT_HANDLED_ATTR))) {
        return;
      }
      request.setAttribute(CLIENT_ABORT_HANDLED_ATTR, Boolean.TRUE);
    }
    if (!log.isInfoEnabled()) {
      return;
    }
    log.info(
        "Client aborted repository response: method={} uri={} remoteAddr={} forwardedFor={} requestId={} userAgent={} range={} ifRange={} envoyTimeoutMs={} bytesWritten={} elapsedMs={} exception={}: {}",
        requestValue(request, HttpServletRequest::getMethod),
        requestUri(request),
        requestValue(request, HttpServletRequest::getRemoteAddr),
        requestHeader(request, "X-Forwarded-For"),
        requestHeader(request, "X-Request-Id"),
        requestHeader(request, "User-Agent"),
        requestHeader(request, "Range"),
        requestHeader(request, "If-Range"),
        requestHeader(request, "X-Envoy-Expected-Rq-Timeout-Ms"),
        requestAttribute(request, RESPONSE_BYTES_WRITTEN_ATTR),
        elapsedMillis(request),
        exception.getClass().getSimpleName(),
        safe(exception.getMessage()));
  }

  private static void markResponseCopyStarted(HttpServletRequest request) {
    if (request != null && request.getAttribute(RESPONSE_COPY_STARTED_AT_NANOS_ATTR) == null) {
      request.setAttribute(RESPONSE_COPY_STARTED_AT_NANOS_ATTR, System.nanoTime());
    }
  }

  private static void markBytesWritten(HttpServletRequest request, long bytesWritten) {
    if (request != null) {
      request.setAttribute(RESPONSE_BYTES_WRITTEN_ATTR, bytesWritten);
    }
  }

  private static String requestUri(HttpServletRequest request) {
    if (request == null) {
      return "-";
    }
    Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    String rawUri = errorUri instanceof String value ? value : request.getRequestURI();
    String uri = safe(rawUri);
    String rawQuery = request.getQueryString();
    String query = swiftIdentifiersUri(rawUri) && rawQuery != null
        ? "url=<redacted>"
        : safe(rawQuery);
    return "-".equals(query) ? uri : uri + "?" + query;
  }

  private static boolean swiftIdentifiersUri(String uri) {
    if (uri == null) {
      return false;
    }
    int repositorySegment = uri.lastIndexOf("/repository/");
    if (repositorySegment < 0) {
      return false;
    }
    String repositoryPath = uri.substring(repositorySegment + "/repository/".length());
    int pathSeparator = repositoryPath.indexOf('/');
    return pathSeparator > 0 && "identifiers".equals(repositoryPath.substring(pathSeparator + 1));
  }

  private static String requestHeader(HttpServletRequest request, String name) {
    if (request == null) {
      return "-";
    }
    return safe(request.getHeader(name));
  }

  private static String requestAttribute(HttpServletRequest request, String name) {
    if (request == null) {
      return "-";
    }
    Object value = request.getAttribute(name);
    return value == null ? "-" : safe(value.toString());
  }

  private static String elapsedMillis(HttpServletRequest request) {
    if (request == null) {
      return "-";
    }
    Object value = request.getAttribute(RESPONSE_COPY_STARTED_AT_NANOS_ATTR);
    if (!(value instanceof Long startedAtNanos)) {
      return "-";
    }
    return Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
  }

  private static String requestValue(HttpServletRequest request, RequestValue value) {
    if (request == null) {
      return "-";
    }
    return safe(value.get(request));
  }

  private static String safe(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    String sanitized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
  }

  @FunctionalInterface
  private interface RequestValue {
    String get(HttpServletRequest request);
  }

  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private long bytesWritten;

    private CountingOutputStream(OutputStream delegate) {
      this.delegate = delegate;
    }

    long bytesWritten() {
      return bytesWritten;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      bytesWritten++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      bytesWritten += len;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }
  }

  public static boolean tryUseTomcatSendfile(HttpServletRequest request, InputStream in) {
    if (request == null || in == null || !(in instanceof BlobFileRegion fileRegion)
        || !Boolean.TRUE.equals(request.getAttribute(TOMCAT_SENDFILE_SUPPORTED_ATTR))) {
      return false;
    }
    long position = fileRegion.position();
    long count = fileRegion.count();
    if (position < 0 || count <= 0 || Long.MAX_VALUE - position < count || fileRegion.path() == null) {
      return false;
    }
    request.setAttribute(TOMCAT_SENDFILE_FILENAME_ATTR, fileRegion.path().toAbsolutePath().toString());
    request.setAttribute(TOMCAT_SENDFILE_FILE_START_ATTR, position);
    request.setAttribute(TOMCAT_SENDFILE_FILE_END_ATTR, position + count);
    try {
      in.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to close file blob stream after enabling sendfile", e);
    }
    return true;
  }

  public static InputStream openDeleteOnClose(Path path) {
    if (path == null) {
      return InputStream.nullInputStream();
    }
    try {
      return new DeleteOnCloseInputStream(Files.newInputStream(path), path);
    } catch (IOException e) {
      deleteQuietly(path);
      throw new UncheckedIOException("Failed to open temporary blob response file: " + path, e);
    }
  }

  public static void deleteQuietly(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private static final class DeleteOnCloseInputStream extends FilterInputStream {
    private final Path path;
    private boolean closed;

    private DeleteOnCloseInputStream(InputStream delegate, Path path) {
      super(delegate);
      this.path = path;
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      IOException closeError = null;
      try {
        super.close();
      } catch (IOException e) {
        closeError = e;
      } finally {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          if (closeError == null) closeError = e;
          else closeError.addSuppressed(e);
        }
      }
      if (closeError != null) throw closeError;
    }
  }
}
