package com.github.klboke.kkrepo.server.proxy;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.pypi.PypiResponse;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Optional cold artifact delivery. A consumer drives upstream reads (bounded memory/backpressure).
 * Only complete validated files are offered to the existing protocol cache writer. Staging and
 * bounded best-effort work are disposable; shared Blob/asset rows remain the only cache truth.
 */
@Service
public class ProgressiveProxyService {
  public static final String RANGE_HANDLED = ProgressiveProxyService.class.getName() + ".rangeHandled";
  private static final Logger log = LoggerFactory.getLogger(ProgressiveProxyService.class);
  private static final String RANGE_ALLOWED = ProgressiveProxyService.class.getName() + ".rangeAllowed";
  private static final String HOOKS = ProgressiveProxyService.class.getName() + ".hooks";
  private static final int BUFFER_SIZE = 64 * 1024;
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final BlobStorageRegistry storage;
  private final ArtifactDownloadPolicy policy;
  private final Semaphore slots;
  private final AtomicLong stagedBytes = new AtomicLong();
  private final long maxStagedBytes;
  private final ThreadPoolExecutor publisher;

  public ProgressiveProxyService(RepositoryDao repositories, AssetDao assets,
      BlobStorageRegistry storage, ArtifactDownloadPolicy policy,
      @Value("${kkrepo.proxy.streaming.max-concurrent:16}") int maxConcurrent,
      @Value("${kkrepo.proxy.streaming.max-staged-bytes:21474836480}") long maxStagedBytes) {
    this.repositories = repositories;
    this.assets = assets;
    this.storage = storage;
    this.policy = policy;
    this.slots = new Semaphore(Math.max(1, maxConcurrent));
    this.maxStagedBytes = Math.max(0, maxStagedBytes);
    this.publisher = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(Math.max(1, maxConcurrent)),
        Thread.ofPlatform().daemon().name("proxy-cache-publish-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
    this.publisher.allowCoreThreadTimeOut(true);
  }

  @FunctionalInterface public interface Validator {
    void validate(Path file) throws IOException;
  }

  public static MavenResponse fetchMaven(ProgressiveProxyService service, HttpRemoteFetcher fetcher,
      HttpRemoteFetcher.Request request, String path, RepositoryRuntime runtime, boolean eligible,
      String expectedSha256, Validator validator, HttpRemoteFetcher.ResultHandler<MavenResponse> buffered)
      throws IOException {
    if (service == null || !eligible) return fetcher.fetchWithBodyRetry(request, path, buffered);
    return service.fetch(fetcher::fetch,
        (req, handler) -> fetcher.fetchWithBodyRetry(req, path, handler),
        request, path, runtime, expectedSha256, validator, buffered,
        transfer -> {
          MavenResponse response = MavenResponse.ok(transfer.body(), transfer.length(),
              transfer.contentType(), transfer.headers().get("ETag"), transfer.lastModified()).withStatus(transfer.status());
          transfer.headers().forEach(response::withHeader);
          response.withInternalAttribute(HOOKS, transfer.hooks());
          return response;
        }, response -> { if (response.hasBody()) response.body().close(); });
  }

  public static PypiResponse fetchPypi(ProgressiveProxyService service, HttpRemoteFetcher fetcher,
      HttpRemoteFetcher.Request request, String path, RepositoryRuntime runtime, boolean eligible,
      String expectedSha256, Validator validator, HttpRemoteFetcher.ResultHandler<PypiResponse> buffered)
      throws IOException {
    if (service == null || !eligible) return fetcher.fetchWithBodyRetry(request, path, buffered);
    return service.fetch(fetcher::fetch,
        (req, handler) -> fetcher.fetchWithBodyRetry(req, path, handler),
        request, path, runtime, expectedSha256, validator, buffered,
        transfer -> {
          PypiResponse response = PypiResponse.ok(transfer.body(), transfer.length(),
              transfer.contentType(), transfer.headers().get("ETag"), transfer.lastModified()).withStatus(transfer.status());
          transfer.headers().forEach(response::withHeader);
          return response;
        }, response -> { if (response.hasBody()) response.body().close(); });
  }

  public static com.github.klboke.kkrepo.server.docker.DockerResponse fetchDocker(
      ProgressiveProxyService service, RemoteFetch remote, HttpRemoteFetcher.Request request,
      String path, RepositoryRuntime runtime, boolean eligible, String digest,
      HttpRemoteFetcher.ResultHandler<com.github.klboke.kkrepo.server.docker.DockerResponse> buffered)
      throws IOException {
    BufferedFetch<com.github.klboke.kkrepo.server.docker.DockerResponse> fallback = (req, handler) -> {
      try (var result = remote.fetch(req)) { return handler.handle(result); }
    };
    if (service == null || !eligible) return fallback.fetch(request, buffered);
    return service.fetch(remote, fallback, request, path, runtime, digest, null, buffered, transfer -> {
      var response = com.github.klboke.kkrepo.server.docker.DockerResponse.body(
          transfer.status(), transfer::body, transfer.length(), transfer.contentType(), transfer.lastModified());
      transfer.headers().forEach(response::withHeader);
      return response.withHeader("Docker-Content-Digest", "sha256:" + digest);
    }, response -> { if (response.body() != null) response.body().close(); });
  }

  @FunctionalInterface private interface Discard<T> { void close(T response) throws IOException; }
  public record Transfer(int status, InputStream body, long length, String contentType,
      Instant lastModified, Map<String, String> headers, CompletionHooks hooks) {}

  public static final class CompletionHooks {
    final java.util.List<Validator> validators = new java.util.ArrayList<>();
    final java.util.List<Runnable> afterCache = new java.util.ArrayList<>();
  }

  public static boolean onCached(MavenResponse response, Validator validator, Runnable afterCache) {
    if (!(response.internalAttribute(HOOKS) instanceof CompletionHooks hooks)) return false;
    if (validator != null) hooks.validators.add(validator);
    if (afterCache != null) hooks.afterCache.add(afterCache);
    return true;
  }

  @FunctionalInterface public interface RemoteFetch {
    HttpRemoteFetcher.Result fetch(HttpRemoteFetcher.Request request) throws IOException;
  }
  @FunctionalInterface private interface BufferedFetch<T> {
    T fetch(HttpRemoteFetcher.Request request, HttpRemoteFetcher.ResultHandler<T> handler) throws IOException;
  }

  private <T> T fetch(RemoteFetch fetcher, BufferedFetch<T> fallback,
      HttpRemoteFetcher.Request request, String path,
      RepositoryRuntime runtime, String expectedSha256, Validator validator,
      HttpRemoteFetcher.ResultHandler<T> buffered, Function<Transfer, T> responseFactory,
      Discard<T> discard) throws IOException {
    HttpServletRequest client = currentRequest();
    if (!runtime.streamingEnabled() || !runtime.isProxy() || request.headOnly()
        || !matchesClientPath(runtime, path, client)
        || client == null || !"GET".equals(client.getMethod())
        || Boolean.TRUE.equals(client.getAttribute(ArtifactDownloadPolicy.INTERNAL_SCANNER_REQUEST_ATTRIBUTE))
        || !policy.allowsProgressiveRead(runtime.id())) {
      return fallback.fetch(request, buffered);
    }
    String range = client.getHeader("Range");
    if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) {
      return fallback.fetch(request, buffered);
    }
    Instant revision = repositories.findUpdatedAt(runtime.id()).orElse(null);
    RepositoryRecord snapshot = repositories.findById(runtime.id()).orElse(null);
    if (snapshot == null || revision == null || !snapshot.online()
        || !(snapshot.attributes().get("proxy") instanceof Map<?, ?> proxy)
        || !Boolean.TRUE.equals(proxy.get("streamingEnabled"))
        || !Objects.equals(snapshot.proxyRemoteUrl(), runtime.proxyRemoteUrl())
        || !Objects.equals(snapshot.blobStoreId(), runtime.blobStoreId())
        // An incompatible/stale asset still needs the established synchronous replacement path.
        || assets.findAssetByPath(runtime.id(), path).isPresent()) {
      return fallback.fetch(request, buffered);
    }
    String clientIp = ProxyRequestAudit.currentClientIp();
    CompletionHooks hooks = new CompletionHooks();
    HttpRemoteFetcher.Request outbound = range == null ? request
        : request.withByteRange(range, client.getHeader("If-Range"));
    try (HttpRemoteFetcher.Result result = fetcher.fetch(outbound)) {
      if (range != null && result.status() == 416 && result.header("Content-Range") != null
          && result.header("Content-Range").matches("bytes \\*/[0-9]+")) {
        client.setAttribute(RANGE_HANDLED, true);
        return responseFactory.apply(new Transfer(416, InputStream.nullInputStream(), 0,
            null, null, Map.of("Content-Range", result.header("Content-Range")), hooks));
      }
      boolean partial = result.status() == 206;
      // Unknown length, encoded bodies and unsupported responses retain the established path.
      if ((result.status() != 200 && !partial) || result.contentLength() <= 0
          || ((runtime.format() == com.github.klboke.kkrepo.core.RepositoryFormat.NPM
                  || runtime.format() == com.github.klboke.kkrepo.core.RepositoryFormat.MAVEN2)
              && result.contentType() != null && result.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/html"))
          || (result.header("Content-Encoding") != null
              && !"identity".equalsIgnoreCase(result.header("Content-Encoding")))) {
        result.close();
        return fallback.fetch(request, buffered);
      }
      if (partial && (range == null || !validContentRange(range, result))) {
        throw new IOException("Invalid upstream byte range response");
      }
      long reservation = partial ? 0 : result.contentLength();
      // Docker's writer spools the blob and its stream upload can spool it once more in storage.
      int copies = runtime.format() == com.github.klboke.kkrepo.core.RepositoryFormat.DOCKER ? 3 : 2;
      if (reservation > maxStagedBytes / copies || !slots.tryAcquire()) {
        result.close();
        return fallback.fetch(request, buffered);
      }
      reservation *= copies;
      if (stagedBytes.addAndGet(reservation) > maxStagedBytes) {
        stagedBytes.addAndGet(-reservation);
        slots.release();
        result.close();
        return fallback.fetch(request, buffered);
      }
      Reservation permit = new Reservation(reservation);
      Path file = null;
      InputStream transferred = null;
      try {
        if (!partial) {
          BlobStorage blobStorage = storage.forBlobStoreId(runtime.blobStoreId());
          file = TempBlobFiles.createTempFile(blobStorage, "kkrepo-progressive-", ".tmp");
        }
        Path completedFile = file;
        Runnable complete = partial ? permit::close : () -> {
          try {
            publisher.execute(() -> {
              try (ProxyCachePublication ignored = new ProxyCachePublication(
                  repositories, assets, snapshot, revision, path, clientIp);
                  InputStream input = Files.newInputStream(completedFile);
                  HttpRemoteFetcher.Result staged = new HttpRemoteFetcher.Result(200, result.headers(), input)) {
                T cached = buffered.handle(staged);
                discard.close(cached);
                ignored.close();
                hooks.afterCache.forEach(Runnable::run);
                assets.findAssetByPath(runtime.id(), path)
                    .ifPresent(asset -> assets.touchLastDownloaded(asset.id(), Instant.now()));
              } catch (ProxyCachePublication.Superseded ignored) {
                log.debug("Progressive cache fill superseded: repository={} path={}", runtime.name(), path);
              } catch (Exception error) {
                // Upstream URLs can contain credentials; only record the error category.
                log.warn("Progressive cache fill failed: repository={} path={} error={}",
                    runtime.name(), path, error.getClass().getSimpleName());
              } finally {
                TempBlobFiles.deleteQuietly(completedFile);
                permit.close();
              }
            });
          } catch (RuntimeException rejected) {
            TempBlobFiles.deleteQuietly(completedFile);
            permit.close();
            log.warn("Progressive cache publisher unavailable: repository={}", runtime.name());
          }
        };
        transferred = result.detachBody();
        InputStream body = new ProgressiveBody(transferred, result.contentLength(), file,
            partial ? null : expectedSha256, partial ? null : candidate -> {
              if (validator != null) validator.validate(candidate);
              for (Validator check : hooks.validators) check.validate(candidate);
            }, complete, permit);
        transferred = body;
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (String name : new String[] {"ETag", "Content-Range", "Content-Disposition"}) {
          if (result.header(name) != null) headers.put(name, result.header(name));
        }
        headers.put("Accept-Ranges", "bytes");
        client.setAttribute(RANGE_HANDLED, true);
        return responseFactory.apply(new Transfer(result.status(), body, result.contentLength(),
            result.contentType(), result.lastModified(), Map.copyOf(headers), hooks));
      } catch (IOException | RuntimeException error) {
        if (transferred != null) {
          try { transferred.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
        }
        TempBlobFiles.deleteQuietly(file);
        permit.close();
        throw error;
      }
    }
  }

  static boolean validContentRange(String requested, HttpRemoteFetcher.Result result) {
    String value = result.header("Content-Range");
    if (value == null) return false;
    var match = java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(value);
    if (!match.matches()) return false;
    try {
      long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
      long total = Long.parseLong(match.group(3));
      if (start > end || end >= total || end - start + 1 != result.contentLength()) return false;
      String[] spec = requested.substring(6).split("-", -1);
      if (spec[0].isEmpty()) {
        long suffix = Long.parseLong(spec[1]);
        return suffix > 0 && start == Math.max(0, total - suffix) && end == total - 1;
      }
      return start == Long.parseLong(spec[0])
          && end == (spec[1].isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(spec[1])));
    } catch (RuntimeException invalid) { return false; }
  }

  /** Enables local Range handling for an opted-in artifact, including cache hits and groups. */
  public static void enableRanges(RepositoryRuntime runtime, String path) {
    HttpServletRequest request = currentRequest();
    if (runtime.streamingEnabled() && request != null && matchesClientPath(runtime, path, request)) {
      request.setAttribute(RANGE_ALLOWED, true);
    }
  }

  public static boolean rangeAllowed(HttpServletRequest request) {
    return request != null && Boolean.TRUE.equals(request.getAttribute(RANGE_ALLOWED));
  }

  public static boolean rangeHandled(HttpServletRequest request) {
    return request != null && Boolean.TRUE.equals(request.getAttribute(RANGE_HANDLED));
  }

  private static boolean matchesClientPath(RepositoryRuntime runtime, String path, HttpServletRequest request) {
    if (request == null) return true;
    // These adapters enter artifact-only methods after resolving their public download routes;
    // the durable asset path intentionally differs from the Cargo/Pub client URL.
    if (runtime.format() == com.github.klboke.kkrepo.core.RepositoryFormat.DOCKER
        || runtime.format() == com.github.klboke.kkrepo.core.RepositoryFormat.CARGO
        || runtime.format() == com.github.klboke.kkrepo.core.RepositoryFormat.PUB) return true;
    // Internal metadata/projection reads must finish caching before their caller resumes.
    String uri = org.springframework.web.util.UriUtils.decode(request.getRequestURI(), java.nio.charset.StandardCharsets.UTF_8);
    return uri.endsWith("/" + path);
  }

  private static HttpServletRequest currentRequest() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
        ? attributes.getRequest() : null;
  }

  private final class Reservation implements AutoCloseable {
    private final long bytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    Reservation(long bytes) { this.bytes = bytes; }
    @Override public void close() {
      if (closed.compareAndSet(false, true)) { stagedBytes.addAndGet(-bytes); slots.release(); }
    }
  }

  /** Withholds the final block until EOF, size and protocol validation have succeeded. */
  static final class ProgressiveBody extends InputStream {
    private final InputStream upstream;
    private final Path file;
    private final OutputStream stage;
    private final String expectedSha256;
    private final Validator validator;
    private final Runnable complete;
    private final AutoCloseable permit;
    private final MessageDigest sha256;
    private final byte[] block = new byte[BUFFER_SIZE];
    private long remaining;
    private int position, available;
    private boolean finished, closed;

    ProgressiveBody(InputStream upstream, long length, Path file, String expectedSha256,
        Validator validator, Runnable complete, AutoCloseable permit) throws IOException {
      this.upstream = upstream; this.remaining = length; this.file = file;
      this.stage = file == null ? OutputStream.nullOutputStream() : Files.newOutputStream(file);
      this.expectedSha256 = expectedSha256; this.validator = validator;
      this.complete = complete; this.permit = permit;
      try { this.sha256 = MessageDigest.getInstance("SHA-256"); }
      catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) return 0;
      if (closed) throw new IOException("Progressive response is closed");
      try {
        if (position == available) {
          if (finished) return -1;
          int read = upstream.read(block, 0, (int) Math.min(block.length, remaining));
          if (read < 0) throw new IOException("Incomplete upstream response");
          if (read == 0) return 0;
          sha256.update(block, 0, read);
          stage.write(block, 0, read);
          remaining -= read;
          position = 0; available = read;
          if (remaining == 0) {
            if (upstream.read() != -1) throw new IOException("Upstream response exceeds declared length");
            stage.close();
            if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.equalsIgnoreCase(HexFormat.of().formatHex(sha256.digest()))) {
              throw new IOException("Upstream SHA-256 mismatch");
            }
            if (validator != null) validator.validate(file);
            upstream.close();
            finished = true;
            complete.run();
          }
        }
        int copied = Math.min(length, available - position);
        System.arraycopy(block, position, bytes, offset, copied);
        position += copied;
        return copied;
      } catch (IOException | RuntimeException error) {
        close();
        throw error;
      }
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      try { upstream.close(); } finally {
        try { stage.close(); } finally {
          if (!finished) {
            TempBlobFiles.deleteQuietly(file);
            try { permit.close(); } catch (Exception ignored) { }
          }
        }
      }
    }
  }

  @PreDestroy void stop() { publisher.shutdown(); }
}
